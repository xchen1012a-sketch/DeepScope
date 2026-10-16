/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSessionSubagentsPage, getSessionTimelinePage } from '@/api/advisor';
import {
  nextTimelineScrollTop,
  TIMELINE_TOP_THRESHOLD,
  timelineCards,
  type TimelineCardView,
  type TurnProjection,
} from './model/blocks';
import type { SubagentRecord } from './useAdvisorConversation';
import { SubagentCard } from './components/SubagentCard';
import { SubagentView } from './components/SubagentView';
import { TimelineCard } from './components/TimelineCard';

export const DEFAULT_PROCESS_PAGE_SIZE = 10;

/** 滚动距离底部小于该阈值时自动触发下一页请求。 */
const SCROLL_LOAD_MORE_THRESHOLD = 80;
const SUBAGENT_REFRESH_INTERVAL = 2_000;

function isNearBottom(node: HTMLElement): boolean {
  return node.scrollHeight - node.scrollTop - node.clientHeight <= SCROLL_LOAD_MORE_THRESHOLD;
}

/**
 * 过程侧边栏整页视图：轨迹（支持滚动触底按页向后端请求时间线卡片，点卡片回到主对话）与
 * 子智能体（支持滚动触底按页向后端请求子智能体列表，点开是该子智能体自己的只读对话）。
 */
export function ProcessPanel({
  pane,
  turns,
  subagents,
  onLocate,
  sessionId,
  pageSize = DEFAULT_PROCESS_PAGE_SIZE,
}: {
  pane: 'trajectory' | 'subagents';
  turns: TurnProjection[];
  subagents: SubagentRecord[];
  onLocate: (anchor: string) => void;
  sessionId?: string;
  pageSize?: number;
}) {
  if (pane === 'subagents') {
    return <SubagentPane sessionId={sessionId} subagents={subagents} pageSize={pageSize} />;
  }
  return (
    <TimelinePane
      sessionId={sessionId}
      turns={turns}
      onLocate={onLocate}
      pageSize={pageSize}
    />
  );
}

function mergeTimelineItems(
  existing: TimelineCardView[],
  incoming: TimelineCardView[],
  prepend = false,
): TimelineCardView[] {
  const ordered = prepend ? [...incoming, ...existing] : [...existing, ...incoming];
  const seen = new Set<string>();
  const out: TimelineCardView[] = [];
  for (const item of ordered) {
    const key = `${item.seq}:${item.kind}:${item.anchor}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

function mergeSubagentItems(
  existing: SubagentRecord[],
  incoming: SubagentRecord[],
  prepend = false,
): SubagentRecord[] {
  const ordered = prepend ? [...incoming, ...existing] : [...existing, ...incoming];
  const byId = new Map<string, SubagentRecord>();
  for (const item of ordered) {
    const normalized = { ...item, facts: Array.isArray(item.facts) ? item.facts : [], steps: Array.isArray(item.steps) ? item.steps : [] };
    const previous = byId.get(item.id);
    byId.set(item.id, previous ? mergeSubagentRecord(normalized, previous) : normalized);
  }
  return [...byId.values()];
}

function subagentStepKey(step: SubagentRecord['steps'][number]): string {
  return `${step.kind}:${step.tool?.id ? `id:${step.tool.id}` : `order:${step.order}`}`;
}

/** Lifecycle freshness and process completeness are independent. */
function mergeSubagentRecord(remote: SubagentRecord, local: SubagentRecord): SubagentRecord {
  const localTerminal = local.status !== 'running';
  const remoteTerminal = remote.status !== 'running';
  const owner = localTerminal !== remoteTerminal ? localTerminal ? local : remote
    : (remote.endedAt ?? 0) > (local.endedAt ?? 0) ? remote : local;
  const byStep = new Map([...remote.steps, ...local.steps].map(step => [subagentStepKey(step), step]));
  const steps = [...byStep.values()].sort((left, right) => left.order - right.order).map(step => {
    if (!step.tool) {
      const candidates = [...remote.steps, ...local.steps].filter(candidate => subagentStepKey(candidate) === subagentStepKey(step));
      return candidates.reduce((current, candidate) => (candidate.text?.length ?? 0) > (current.text?.length ?? 0) ? candidate : current, step);
    }
    const candidates = [...remote.steps, ...local.steps].filter(candidate => subagentStepKey(candidate) === subagentStepKey(step));
    const best = candidates.reduce((current, candidate) => {
      if (!current.tool || !candidate.tool) return current;
      if ((current.tool.status === 'running') !== (candidate.tool.status === 'running')) return current.tool.status === 'running' ? candidate : current;
      if ((candidate.tool.endedAt ?? 0) > (current.tool.endedAt ?? 0)) return candidate;
      return current;
    }, step);
    return { ...best, order: step.order };
  });
  const other = owner === local ? remote : local;
  const facts = [...new Map([...other.facts, ...owner.facts].map(fact => [fact.id || JSON.stringify([fact.fact, fact.source, fact.conclusion]), fact])).values()];
  return { ...other, ...owner, startedAt: remote.startedAt ?? local.startedAt,
    steps, facts, factCount: facts.length,
    summary: owner.summary || other.summary };
}

function TimelinePane({
  sessionId,
  turns,
  onLocate,
  pageSize,
}: {
  sessionId?: string;
  turns: TurnProjection[];
  onLocate: (anchor: string) => void;
  pageSize: number;
}) {
  const { t } = useTranslation();
  const localCards = useMemo(() => timelineCards(turns), [turns]);
  const [page, setPage] = useState(1);
  const [remoteItems, setRemoteItems] = useState<TimelineCardView[]>([]);
  const [remoteHasMore, setRemoteHasMore] = useState(false);
  const [remoteLoaded, setRemoteLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const atTopRef = useRef(true);
  const heightRef = useRef(0);

  useEffect(() => {
    setPage(1);
    setRemoteItems([]);
    setRemoteHasMore(false);
    setRemoteLoaded(false);
    loadingRef.current = false;
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    loadingRef.current = true;
    setLoading(true);
    getSessionTimelinePage<TimelineCardView>(sessionId, page, pageSize)
      .then((res) => {
        if (cancelled || !res || !Array.isArray(res.items)) return;
        setRemoteItems((prev) =>
          page === 1 ? mergeTimelineItems([], res.items) : mergeTimelineItems(prev, res.items),
        );
        setRemoteHasMore(Boolean(res.hasMore));
        setRemoteLoaded(true);
      })
      .catch(() => {
        // 网络异常时优雅降级到本地切片
      })
      .finally(() => {
        if (!cancelled) {
          loadingRef.current = false;
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, page, pageSize]);

  // 会话有新事件落库时刷新第 1 页并拼到顶部，保留已向下滚动加载的历史页。
  useEffect(() => {
    if (!sessionId || !remoteLoaded || localCards.length === 0) return;
    let cancelled = false;
    getSessionTimelinePage<TimelineCardView>(sessionId, 1, pageSize)
      .then((res) => {
        if (cancelled || !res || !Array.isArray(res.items)) return;
        setRemoteItems((prev) => mergeTimelineItems(prev, res.items, true));
        if (page === 1) {
          setRemoteHasMore(Boolean(res.hasMore));
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [sessionId, pageSize, localCards.length, remoteLoaded, page]);

  const { cards, hasMore } = useMemo(() => {
    const localByAnchor = new Map(localCards.map(item => [item.anchor, item]));
    const localBySeqAndKind = new Map<string, TimelineCardView | null>();
    for (const item of localCards) {
      const key = `${item.seq}:${item.kind}`;
      localBySeqAndKind.set(key, localBySeqAndKind.has(key) ? null : item);
    }

    if (sessionId && remoteLoaded && (remoteItems.length > 0 || localCards.length === 0)) {
      const aligned = remoteItems.map((card) => {
        const matched = localByAnchor.get(card.anchor) ?? localBySeqAndKind.get(`${card.seq}:${card.kind}`);
        return matched ? { ...card, anchor: matched.anchor } : card;
      });
      const remoteAnchors = new Set(aligned.map((c) => c.anchor));
      const liveOnly = localCards.filter(
        (c) => (c.status === 'running' || c.seq >= 1_000_000) && !remoteAnchors.has(c.anchor),
      );
      return {
        cards: liveOnly.length > 0 ? [...liveOnly, ...aligned] : aligned,
        hasMore: remoteHasMore,
      };
    }

    const visibleCount = page * pageSize;
    return {
      cards: localCards.slice(0, visibleCount),
      hasMore: visibleCount < localCards.length,
    };
  }, [sessionId, remoteLoaded, remoteItems, remoteHasMore, localCards, page, pageSize]);

  const loadMore = useCallback(() => {
    if (!hasMore || loadingRef.current) return;
    loadingRef.current = true;
    setPage((prev) => prev + 1);
  }, [hasMore]);

  useEffect(() => {
    if (!hasMore || typeof IntersectionObserver === 'undefined') return;
    const root = scrollRef.current;
    const sentinel = sentinelRef.current;
    if (!root || !sentinel) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          loadMore();
        }
      },
      { root, rootMargin: '0px 0px 80px 0px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, loadMore, cards.length]);

  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (!node) return;
    node.scrollTop = nextTimelineScrollTop(
      atTopRef.current,
      heightRef.current,
      node.scrollHeight,
      node.scrollTop,
    );
    heightRef.current = node.scrollHeight;
  }, [cards]);

  return (
    <div
      ref={scrollRef}
      data-timeline-scroll
      onScroll={(event) => {
        const node = event.currentTarget;
        atTopRef.current = node.scrollTop <= TIMELINE_TOP_THRESHOLD;
        if (isNearBottom(node)) {
          loadMore();
        }
      }}
      className="min-h-0 flex-1 overflow-auto bg-muted px-4 py-4 sm:px-6"
    >
      <div className="mx-auto max-w-3xl">
        {cards.length === 0 ? (
          <p className="px-1 py-6 text-center text-[13px] leading-relaxed text-slate-400">
            {loading ? t('chat.process.loading') : t('chat.process.empty')}
          </p>
        ) : (
          <div className="process-pane-enter space-y-3">
            {cards.map((card) => (
              <TimelineCard key={card.anchor} card={card} onLocate={onLocate} />
            ))}
            {hasMore || loading ? (
              <div
                ref={sentinelRef}
                data-timeline-sentinel
                className="flex items-center justify-center gap-1.5 py-2 text-[12px] text-slate-400"
              >
                {loading ? (
                  <>
                    <Loader2 className="h-3.5 w-3.5 animate-spin text-indigo-500" />
                    <span>{t('chat.process.loading')}</span>
                  </>
                ) : null}
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}

function SubagentPane({
  sessionId,
  subagents,
  pageSize,
}: {
  sessionId?: string;
  subagents: SubagentRecord[];
  pageSize: number;
}) {
  const { t } = useTranslation();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [remoteItems, setRemoteItems] = useState<SubagentRecord[]>([]);
  const [remoteHasMore, setRemoteHasMore] = useState(false);
  const [remoteLoaded, setRemoteLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<number | null>(null);
  const queryClient = useQueryClient();

  // 最新派发在最上面。
  const localOrdered = useMemo(() => [...subagents].reverse(), [subagents]);

  // Live events remain authoritative; polling only fills persisted progress for the open task.
  const progressKey = ['session-subagent-progress', sessionId];
  const selectedLocal = localOrdered.find(item => item.id === selectedId);
  const selectedRemote = remoteItems.find(item => item.id === selectedId);
  const selectedSnapshot = selectedLocal && selectedRemote ? mergeSubagentRecord(selectedRemote, selectedLocal) : selectedLocal ?? selectedRemote;
  const progressQuery = useQuery({
    queryKey: progressKey,
    enabled: query => {
      const latest = query.state.data?.find(item => item.id === selectedId);
      return Boolean(sessionId && selectedId && selectedSnapshot?.status === 'running' && (!latest || latest.status === 'running'));
    },
    queryFn: async () => {
      const selectedIndex = remoteItems.findIndex(item => item.id === selectedId);
      const selectedPage = selectedIndex < 0 ? 1 : Math.floor(selectedIndex / pageSize) + 1;
      const response = await getSessionSubagentsPage<SubagentRecord>(sessionId!, selectedPage, pageSize);
      const previous = queryClient.getQueryData<SubagentRecord[]>(progressKey) ?? [];
      return mergeSubagentItems(previous, response.items);
    },
    refetchInterval: query => {
      const latest = query.state.data?.find(item => item.id === selectedId);
      return latest && latest.status !== 'running' ? false : SUBAGENT_REFRESH_INTERVAL;
    },
    retry: false,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    setPage(1);
    setRemoteItems([]);
    setRemoteHasMore(false);
    setRemoteLoaded(false);
    setSelectedId(null);
    loadingRef.current = false;
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    loadingRef.current = true;
    setLoading(true);
    getSessionSubagentsPage<SubagentRecord>(sessionId, page, pageSize)
      .then((res) => {
        if (cancelled || !res || !Array.isArray(res.items)) return;
        setRemoteItems((prev) =>
          page === 1 ? mergeSubagentItems([], res.items) : mergeSubagentItems(prev, res.items),
        );
        setRemoteHasMore(Boolean(res.hasMore));
        setRemoteLoaded(true);
      })
      .catch(() => {
        // 网络异常时优雅降级到本地切片
      })
      .finally(() => {
        if (!cancelled) {
          loadingRef.current = false;
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, page, pageSize]);

  // 新增派发时刷新第 1 页并拼到顶部，保留已向下滚动加载的历史页。
  useEffect(() => {
    if (!sessionId || !remoteLoaded || localOrdered.length === 0) return;
    let cancelled = false;
    getSessionSubagentsPage<SubagentRecord>(sessionId, 1, pageSize)
      .then((res) => {
        if (cancelled || !res || !Array.isArray(res.items)) return;
        setRemoteItems((prev) => mergeSubagentItems(prev, res.items, true));
        if (page === 1) {
          setRemoteHasMore(Boolean(res.hasMore));
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [sessionId, pageSize, localOrdered.length, remoteLoaded, page]);

  const { items, hasMore } = useMemo(() => {
    const localById = new Map<string, SubagentRecord>();
    for (const item of localOrdered) {
      localById.set(item.id, item);
    }

    if (sessionId && remoteLoaded && (remoteItems.length > 0 || localOrdered.length === 0)) {
      const refreshed = mergeSubagentItems(remoteItems, progressQuery.data ?? []);
      const mergedItems = refreshed.map((remote) => {
        const local = localById.get(remote.id);
        if (local) return mergeSubagentRecord(remote, local);
        return remote;
      });
      const remoteIds = new Set(mergedItems.map((item) => item.id));
      const liveOnly = localOrdered.filter(
        (item) => item.status === 'running' && !remoteIds.has(item.id),
      );
      return {
        items: liveOnly.length > 0 ? [...liveOnly, ...mergedItems] : mergedItems,
        hasMore: remoteHasMore,
      };
    }

    const visibleCount = page * pageSize;
    return {
      items: localOrdered.slice(0, visibleCount),
      hasMore: visibleCount < localOrdered.length,
    };
  }, [sessionId, remoteLoaded, remoteItems, remoteHasMore, localOrdered, page, pageSize, progressQuery.data]);

  const loadMore = useCallback(() => {
    if (!hasMore || loadingRef.current || selectedId !== null) return;
    loadingRef.current = true;
    setPage((prev) => prev + 1);
  }, [hasMore, selectedId]);

  useEffect(() => {
    if (!hasMore || selectedId !== null || typeof IntersectionObserver === 'undefined') return;
    const root = scrollRef.current;
    const sentinel = sentinelRef.current;
    if (!root || !sentinel) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          loadMore();
        }
      },
      { root, rootMargin: '0px 0px 80px 0px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, selectedId, loadMore, items.length]);

  const selected = useMemo(
    () =>
      items.find((agent) => agent.id === selectedId) ??
      localOrdered.find((agent) => agent.id === selectedId) ??
      null,
    [items, localOrdered, selectedId],
  );

  useLayoutEffect(() => {
    if (selectedId !== null || restoreRef.current === null) return;
    const node = scrollRef.current;
    if (node) node.scrollTop = restoreRef.current;
    restoreRef.current = null;
  }, [selectedId]);

  return (
    <div
      ref={scrollRef}
      data-subagent-scroll
      onScroll={(event) => {
        if (selectedId === null && isNearBottom(event.currentTarget)) {
          loadMore();
        }
      }}
      className="min-h-0 flex-1 overflow-auto bg-muted px-4 py-4 sm:px-6"
    >
      <div className="mx-auto max-w-3xl">
        {selected ? (
          <div className="process-pane-enter">
            <SubagentView record={selected} onBack={() => setSelectedId(null)} />
          </div>
        ) : items.length === 0 ? (
          <p className="px-1 py-6 text-center text-[13px] leading-relaxed text-slate-400">
            {loading ? t('chat.process.loading') : t('chat.process.noSubagents')}
          </p>
        ) : (
          <div className="process-pane-enter space-y-3">
            {items.map((agent) => (
              <SubagentCard
                key={agent.id}
                record={agent}
                onOpen={(id) => {
                  restoreRef.current = scrollRef.current?.scrollTop ?? null;
                  setSelectedId(id);
                }}
              />
            ))}
            {hasMore || loading ? (
              <div
                ref={sentinelRef}
                data-subagent-sentinel
                className="flex items-center justify-center gap-1.5 py-2 text-[12px] text-slate-400"
              >
                {loading ? (
                  <>
                    <Loader2 className="h-3.5 w-3.5 animate-spin text-indigo-500" />
                    <span>{t('chat.process.loading')}</span>
                  </>
                ) : null}
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}
