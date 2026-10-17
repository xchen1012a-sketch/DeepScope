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

import { payloadText, type EventEntry } from '@/api/advisor';
import { thinkLine, toolFromEvent, type ToolCall } from '../conversationModel';
import { toolPresentation } from '../toolPresentation';
import { normalizeLegacyDeliverables } from './legacyDeliverable';

/**
 * 留下但不画的记录：路由结果留在会话记录里，供这一轮实际跑哪个智能体使用，不再画成提示；
 * 用量只出现在输入区的统计条与上下文环；任务拆解与子智能体事实分别进子智能体视图与记录。
 */
export const HIDDEN_KINDS: ReadonlySet<string> = new Set([
  'route',
  'agent_route',
  'usage',
  'decompose',
  'subagent_facts',
]);

/**
 * 内部汇报类工具（ReportActions）在执行时已经通过 CUSTOM advisor_event 产出了对应的
 * 独立时间线事件与过程卡片，不再作为普通 tool_call 重复渲染一行。
 */
export const HIDDEN_TOOL_NAMES: ReadonlySet<string> = new Set([
  'report_route',
  'report_skill_load',
  'report_decompose',
  'report_dispatch',
  'report_self_task',
  'report_facts',
  'report_subagent_settled',
  'report_deliverable',
  'report_question',
  'report_proposal',
  'report_note',
  'report_error',
  'task_dispatch',
]);

/** 留在过程组外面的对话内容，顺序不变：访客消息、错误、追问、提案。 */
export const OUTSIDE_KINDS: ReadonlySet<string> = new Set([
  'proposal',
  'proposal_resolved',
  'error',
  'question',
]);

/**
 * 会出一张时间线卡片的块（设计 §5：访客消息、思考、工具调用、技能加载、派发、追问、
 * 提案、错误、子智能体落定）。主对话画不出来的块没有卡片，避免点了没处去。
 */
export const TIMELINE_KINDS: ReadonlySet<string> = new Set([
  'user_message',
  'thinking',
  'advisor_message',
  'tool_call',
  'compaction',
  'load_skill',
  'dispatch',
  'self_task',
  'subagent_settled',
  'advisor_note',
  'question',
  'proposal',
  'proposal_resolved',
  'error',
]);

export type BlockStatus = 'running' | 'done' | 'failed';

/**
 * 主对话里的一个可定位的块，时间线卡片也由它派生。
 *
 * `anchor` 与 `index` 按块在会话里的**位置**编号，不跟着事件 `seq` 走：实时条目的
 * `seq` 是远大于数据库 seq 的合成值，一轮落库后换成数据库 seq；位置不变，锚点就不变，
 * 时间线点卡片才不会在换 id 之后静默找不到目标。
 */
export interface ConversationBlock {
  anchor: string;
  partId?: string;
  /** 用户可见的块序号，从 1 开始按渲染顺序递增。 */
  index: number;
  /** 事件行 seq；实时条目是合成 seq，回放后是数据库 seq。 */
  seq: number;
  turn: number;
  kind: string;
  status: BlockStatus;
  createdAt: number;
  /** 一行摘要：时间线卡片与过程行都用它。 */
  summary: string;
  event?: EventEntry;
  text?: string;
  thinkingText?: string;
  tool?: ToolCall;
}

export interface TurnProjection {
  turn: number;
  user: ConversationBlock | null;
  process: ConversationBlock[];
  ordered?: ConversationBlock[];
  finalText: ConversationBlock | null;
  finalBlocks?: ConversationBlock[];
  durationMs?: number | null;
  durationKind?: 'process' | 'total';
  completionNotice?: 'iteration_limit';
  outside: ConversationBlock[];
  collapsedByDefault: boolean;
  toolCount: number;
  earlierReplyCount: number;
  subagentCount: number;
}

/** 时间线卡片：一个可见块一张，倒序排列。 */
export interface TimelineCardView {
  anchor: string;
  index: number;
  seq: number;
  kind: string;
  status: BlockStatus;
  summary: string;
  createdAt: number;
}

/** 滚动位置在这个范围内就算用户正看着最上面。 */
export const TIMELINE_TOP_THRESHOLD = 8;

/**
 * 倒序卡片列表的跟随规则：用户正看着最上面时，新卡出现在顶部，视图贴住顶部；
 * 已经向下翻旧卡时，把新卡撑开的高度补进 scrollTop，正在看的卡片不动位置。
 */
export function nextTimelineScrollTop(
  atTop: boolean,
  previousScrollHeight: number,
  nextScrollHeight: number,
  scrollTop: number,
): number {
  if (atTop) return 0;
  return Math.max(0, scrollTop + (nextScrollHeight - previousScrollHeight));
}

/** 还没有编号的块；编号与锚点在整条会话上按渲染顺序统一分配。 */
type DraftBlock = Omit<ConversationBlock, 'anchor' | 'index'>;

function draft(event: EventEntry, kind: string, extra: Partial<DraftBlock> = {}): DraftBlock {
  return {
    seq: event.seq,
    turn: event.turn,
    kind,
    status: 'done',
    createdAt: event.createdAt ?? 0,
    summary: '',
    event,
    ...extra,
  };
}

function summaryOf(event: EventEntry): string {
  const payload = event.payload;
  return (
    payloadText(payload, 'text') ||
    payloadText(payload, 'title') ||
    payloadText(payload, 'summary') ||
    payloadText(payload, 'skillName') ||
    payloadText(payload, 'name') ||
    payloadText(payload, 'reason')
  );
}

interface DraftTurn {
  turn: number;
  user: DraftBlock | null;
  process: DraftBlock[];
  ordered?: DraftBlock[];
  finalText: DraftBlock | null;
  finalBlocks?: DraftBlock[];
  durationMs?: number | null;
  durationKind?: 'process' | 'total';
  completionNotice?: 'iteration_limit';
  outside: DraftBlock[];
  collapsedByDefault: boolean;
  toolCount: number;
  earlierReplyCount: number;
  subagentCount: number;
}

/**
 * 把一条会话收成紧凑回合。进行中的回合不收起。
 * 结束后，若最后一条助手正文后面没有工具调用，这段正文留在过程组外，其余默认收起。
 */
/** A versioned snapshot owns its order; aggregate rows remain compatibility data. */
function partsTurn(events: EventEntry[], open: boolean): DraftTurn | null {
  const snapshots = events.filter(event => event.kind === 'advisor_message' && event.payload.partsVersion === 1 && Array.isArray(event.payload.parts));
  if (snapshots.length === 0) return null;
  const ordered: DraftBlock[] = [];
  const finalPartIds = new Set<string>();
  let durationMs: number | null = null;
  let durationKind: 'process' | 'total' = 'process';
  let completionNotice: 'iteration_limit' | undefined;
  const hasPresentation = snapshots.some(snapshot => snapshot.payload.presentationVersion !== undefined || snapshot.payload.presentation !== undefined);
  let confirmed = false;
  const represented = new Set<number>();
  for (const snapshot of snapshots) {
    const rawPresentation = snapshot.payload.presentation;
    if (snapshot.payload.presentationVersion === 1 && rawPresentation && typeof rawPresentation === 'object') {
      const meta = rawPresentation as Record<string, unknown>;
      const rawParts = (snapshot.payload.parts as unknown[]).filter((part): part is Record<string, unknown> => part !== null && typeof part === 'object' && !Array.isArray(part));
      const ids = meta.finalMessageIds;
      const availableMessages = new Set(rawParts.filter(part => part && part.kind === 'text' && part.runId === meta.runId && typeof part.text === 'string' && part.text.trim()).map(part => part.messageId));
      const start = meta.runStartedAt; const final = meta.finalStartedAt; const end = meta.runEndedAt;
      const limited = meta.outcome === 'limited' && meta.terminationReason === 'MAX_ITERATIONS';
      const valid = (meta.outcome === 'completed' || limited) && typeof meta.runId === 'string'
        && Array.isArray(ids) && ids.length > 0 && new Set(ids).size === ids.length
        && ids.every(id => typeof id === 'string' && availableMessages.has(id))
        && [start, end, ...(limited && final === undefined ? [] : [final])].every(value => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)
        && (end as number) >= (start as number)
        && (final === undefined && limited || (final as number) >= (start as number) && (end as number) >= (final as number));
      if (valid) {
        confirmed = true;
        const finalMessages = new Set(ids as string[]);
        for (const part of rawParts) if (part.kind === 'text' && part.runId === meta.runId && finalMessages.has(String(part.messageId)) && typeof part.id === 'string') finalPartIds.add(part.id);
        durationMs = ((final ?? end) as number) - (start as number);
        durationKind = final === undefined ? 'total' : 'process';
        completionNotice = limited ? 'iteration_limit' : undefined;
      }
    }

    for (const raw of snapshot.payload.parts as unknown[]) {
      if (!raw || typeof raw !== 'object') continue;
      const part = raw as Record<string, unknown>;
      if (typeof part.id !== 'string' || typeof part.kind !== 'string') continue;
      const status: BlockStatus = part.status === 'failed' ? 'failed' : open && part.status === 'running' ? 'running' : 'done';
      const extra = { partId: part.id, status };
      if ((part.kind === 'text' || part.kind === 'reasoning') && typeof part.text === 'string') {
        if (!part.text.trim()) continue;
        ordered.push(draft(snapshot, part.kind === 'text' ? 'advisor_message' : 'thinking', {
          ...extra, ...(part.kind === 'text' ? { text: part.text, summary: part.text } : { thinkingText: part.text, summary: thinkLine(part.text, status === 'running') }),
        }));
      } else if (part.kind === 'tool' && part.tool && typeof part.tool === 'object') {
        const event = { ...snapshot, kind: 'tool_call', payload: part.tool as Record<string, unknown> };
        const parsed = toolFromEvent(event);
        const tool = status === 'failed' && parsed.status === 'running' ? { ...parsed, status: 'failed' as const } : parsed;
        if (tool.name === 'task_dispatch') {
          const target = toolPresentation(tool.name, tool.args).target;
          const summary = target || summaryOf(event) || '已派发子任务';
          if (!ordered.some(b => b.kind === 'dispatch' && b.summary === summary)) {
            ordered.push(draft(event, 'dispatch', { ...extra, summary, status: tool.status }));
          }
          continue;
        }
        if (!HIDDEN_TOOL_NAMES.has(tool.name)) ordered.push(draft(event, 'tool_call', { ...extra, tool, status: tool.status, summary: tool.name }));
      } else if (part.kind === 'event' && part.event && typeof part.event === 'object') {
        const recorded = part.event as Record<string, unknown>;
        if (typeof recorded.seq === 'number') represented.add(recorded.seq);
        if (typeof recorded.kind !== 'string' || HIDDEN_KINDS.has(recorded.kind) || recorded.kind === 'user_message') continue;
        const original = events.find(event => event.seq === recorded.seq);
        const event = { ...snapshot, ...original, seq: typeof recorded.seq === 'number' ? recorded.seq : snapshot.seq,
          kind: recorded.kind, payload: (recorded.payload ?? {}) as Record<string, unknown> };
        ordered.push(draft(event, event.kind, { ...extra, summary: summaryOf(event), ...(event.kind === 'advisor_message' ? { text: payloadText(event.payload, 'text') } : {}) }));
      }
    }
  }
  // Legacy actionable cards are never hidden by a new snapshot that did not capture them.
  for (const event of events) {
    if (OUTSIDE_KINDS.has(event.kind) && !represented.has(event.seq)) ordered.push(draft(event, event.kind, { summary: summaryOf(event) }));
    if (event.payload.legacyDeliverable && !ordered.some(block => block.text === payloadText(event.payload, 'text'))) {
      ordered.push(draft(event, 'advisor_message', { partId: `legacy-deliverable-${event.seq}`, text: payloadText(event.payload, 'text'), summary: summaryOf(event) }));
    }
  }
  const user = events.find(event => event.kind === 'user_message');
  const texts = ordered.filter(block => block.kind === 'advisor_message');
  const finalBlocks = confirmed
    ? ordered.filter(block => block.kind === 'advisor_message' && block.partId && finalPartIds.has(block.partId))
    : !hasPresentation && !open && ordered[ordered.length - 1]?.kind === 'advisor_message' && !ordered.some(block => block.status === 'failed') ? [ordered[ordered.length - 1]] : [];
  const finalText = finalBlocks[0] ?? null;
  const process = ordered.filter(block => !finalBlocks.includes(block) && !OUTSIDE_KINDS.has(block.kind));
  return {
    turn: events[0]?.turn ?? 0, user: user ? draft(user, 'user_message', { text: payloadText(user.payload, 'text') }) : null,
    ordered, process, finalBlocks, durationMs, durationKind, completionNotice,
    finalText, outside: ordered.filter(block => OUTSIDE_KINDS.has(block.kind)), collapsedByDefault: Boolean(finalText && process.length > 0 && (confirmed || !open)),
    toolCount: ordered.filter(block => block.kind === 'tool_call').length,
    earlierReplyCount: Math.max(0, texts.length - finalBlocks.length), subagentCount: ordered.filter(block => block.kind === 'dispatch').length,
  };
}

function buildTurn(events: EventEntry[], open: boolean): DraftTurn {
  events = normalizeLegacyDeliverables(events);
  const parts = partsTurn(events, open);
  if (parts) return parts;
  const userEvent = events.find((event) => event.kind === 'user_message') ?? null;
  const process: DraftBlock[] = [];
  const outside: DraftBlock[] = [];
  let finalCandidate: DraftBlock | null = null;
  let toolCount = 0;
  let subagentCount = 0;
  let earlierReplyCount = 0;

  for (const event of events) {
    if (HIDDEN_KINDS.has(event.kind) || event.kind === 'user_message') continue;
    if (OUTSIDE_KINDS.has(event.kind)) {
      outside.push(
        draft(event, event.kind, { status: event.kind === 'error' ? 'failed' : 'done', summary: summaryOf(event) }),
      );
      continue;
    }
    if (event.kind === 'tool_call') {
      const tool = toolFromEvent(event);
      if (tool.name === 'task_dispatch') {
        subagentCount += 1;
        const target = toolPresentation(tool.name, tool.args).target;
        const summary = target || summaryOf(event) || '已派发子任务';
        if (!process.some(b => b.kind === 'dispatch' && b.summary === summary)) {
          process.push(draft(event, 'dispatch', { summary, status: tool.status }));
        }
        continue;
      }
      if (HIDDEN_TOOL_NAMES.has(tool.name)) continue;
      toolCount += 1;
      process.push(draft(event, 'tool_call', { tool, status: tool.status, summary: tool.name }));
      continue;
    }
    if (event.kind === 'dispatch') {
      subagentCount += 1;
      process.push(draft(event, 'dispatch', { summary: summaryOf(event) }));
      continue;
    }
    if (event.kind === 'advisor_message') {
      const reasoning = payloadText(event.payload, 'reasoning');
      if (reasoning) {
        process.push(
          draft(event, 'thinking', {
            thinkingText: reasoning,
            summary: thinkLine(reasoning, open),
            status: open ? 'running' : 'done',
          }),
        );
      }
      const text = payloadText(event.payload, 'text');
      if (text) {
        if (finalCandidate) {
          earlierReplyCount += 1;
          process.push({ ...finalCandidate, status: 'done' });
        }
        finalCandidate = draft(event, 'advisor_message', {
          text,
          summary: text,
          status: open ? 'running' : 'done',
        });
      }
      continue;
    }
    if (event.kind === 'compaction') {
      const status = payloadText(event.payload, 'status') === 'running' ? 'running' : 'done';
      const inputTokens = typeof event.payload.inputTokens === 'number' ? event.payload.inputTokens : undefined;
      const keptTokens = typeof event.payload.keptTokens === 'number' ? event.payload.keptTokens : undefined;
      const summary =
        inputTokens !== undefined && keptTokens !== undefined
          ? `${inputTokens} → ${keptTokens} tokens`
          : inputTokens !== undefined
            ? `${inputTokens} tokens`
            : summaryOf(event);
      process.push(draft(event, 'compaction', { status, summary }));
      continue;
    }
    process.push(draft(event, event.kind, { summary: summaryOf(event) }));
  }

  const lastToolAfterReply =
    finalCandidate !== null && process.some((item) => item.kind === 'tool_call' && item.seq > finalCandidate.seq);
  const finalText = !open && finalCandidate && !lastToolAfterReply ? finalCandidate : null;
  if (finalCandidate && finalText === null) process.push(finalCandidate);

  return {
    turn: events[0]?.turn ?? 0,
    user: userEvent ? draft(userEvent, 'user_message', { text: payloadText(userEvent.payload, 'text'), summary: payloadText(userEvent.payload, 'text') }) : null,
    process,
    finalText,
    outside,
    collapsedByDefault: !open && finalText !== null,
    toolCount,
    earlierReplyCount,
    subagentCount,
  };
}

/**
 * 一份块模型：主对话、时间线、子智能体三处都从这里派生。
 * 进行中的最后一回合不收起；块的锚点按位置编号，事件 seq 换代不影响定位。
 */
export function buildBlocks(events: EventEntry[], running: boolean): TurnProjection[] {
  const grouped = new Map<number, EventEntry[]>();
  for (const event of [...events].sort((left, right) => left.seq - right.seq)) {
    const bucket = grouped.get(event.turn) ?? [];
    bucket.push(event);
    grouped.set(event.turn, bucket);
  }
  const numbers = [...grouped.keys()].sort((left, right) => left - right);
  let counter = 0;
  const number = (block: DraftBlock): ConversationBlock => {
    counter += 1;
    return { ...block, index: counter, anchor: block.partId ? `flow-${encodeURIComponent(block.partId)}` : `flow-${counter}` };
  };
  return numbers.map((turn, position) => {
    const built = buildTurn(grouped.get(turn) ?? [], running && position === numbers.length - 1);
    // 属性求值按书写顺序进行：访客块 → 过程块 → 最终正文 → 过程组外的块。
    const user = built.user ? number(built.user) : null;
    if (built.ordered) {
      const ordered = built.ordered.map(number);
      const processIds = new Set(built.process.map(item => item.partId));
      const finalIds = new Set(built.finalBlocks?.map(item => item.partId));
      return { ...built, user, ordered,
        process: ordered.filter(block => processIds.has(block.partId)),
        finalText: built.finalText ? ordered.find(block => block.partId === built.finalText?.partId) ?? null : null,
        finalBlocks: ordered.filter(block => finalIds.has(block.partId)),
        outside: ordered.filter(block => OUTSIDE_KINDS.has(block.kind)),
      };
    }
    const process = built.process.map(number);
    const finalText = built.finalText ? number(built.finalText) : null;
    const outside = built.outside.map(number);
    return {
      turn: built.turn,
      user,
      process,
      finalText,
      outside,
      collapsedByDefault: built.collapsedByDefault,
      toolCount: built.toolCount,
      earlierReplyCount: built.earlierReplyCount,
      subagentCount: built.subagentCount,
    };
  });
}

/** 一个回合里主对话真正画出来的块，按渲染顺序。 */
export function renderedBlocks(turn: TurnProjection): ConversationBlock[] {
  return (turn.ordered ? [turn.user, ...turn.ordered] : [turn.user, ...turn.process, turn.finalText, ...turn.outside]).filter(
    (block): block is ConversationBlock => block !== null,
  );
}

/** 按锚点找到目标块与它所在的回合；锚点成了位置的函数，找不到只可能是这条记录没了。 */
export function findBlock(
  turns: TurnProjection[],
  anchor: string,
): { turn: TurnProjection; block: ConversationBlock } | null {
  for (const turn of turns) {
    const block = renderedBlocks(turn).find((item) => item.anchor === anchor);
    if (block) return { turn, block };
  }
  return null;
}

/** 时间线卡片：主对话画得出来的块倒序排列，最新的在最上面。 */
export function timelineCards(turns: TurnProjection[]): TimelineCardView[] {
  const cards: TimelineCardView[] = [];
  for (const turn of turns) {
    for (const block of renderedBlocks(turn)) {
      if (!TIMELINE_KINDS.has(block.kind)) continue;
      cards.push({
        anchor: block.anchor,
        index: block.index,
        seq: block.seq,
        kind: block.kind,
        status: block.status,
        summary: block.summary,
        createdAt: block.createdAt,
      });
    }
  }
  return cards.reverse();
}

/**
 * 子智能体自己的视图也用同一份回合形状：过程步骤按顺序进过程组，总结作为最终正文。
 * 复用 `TurnGroup` 渲染，交互与主对话一致。
 */
export function buildSubagentTurn(source: {
  steps: Array<{ kind: string; text?: string; tool?: ToolCall }>;
  summary: string;
}): TurnProjection {
  let counter = 0;
  const number = (block: DraftBlock): ConversationBlock => {
    counter += 1;
    return { ...block, index: counter, anchor: block.partId ? `flow-${encodeURIComponent(block.partId)}` : `flow-${counter}` };
  };
  const process = source.steps.flatMap((step): ConversationBlock[] => {
    if (step.kind === 'thinking') {
      const text = step.text ?? '';
      if (!text) return [];
      return [
        number({
          seq: 0,
          turn: 0,
          kind: 'thinking',
          status: 'done',
          createdAt: 0,
          summary: thinkLine(text, false),
          thinkingText: text,
        }),
      ];
    }
    if (step.kind === 'tool_call' && step.tool) {
      return [
        number({
          seq: 0,
          turn: 0,
          kind: 'tool_call',
          status: step.tool.status,
          createdAt: 0,
          summary: step.tool.name,
          tool: step.tool,
        }),
      ];
    }
    return [];
  });
  const summary = source.summary.trim();
  const finalText = summary
    ? number({
        seq: 0,
        turn: 0,
        kind: 'advisor_message',
        status: 'done',
        createdAt: 0,
        summary,
        text: summary,
      })
    : null;
  return {
    turn: 0,
    user: null,
    process,
    finalText,
    outside: [],
    collapsedByDefault: finalText !== null,
    toolCount: process.filter((block) => block.kind === 'tool_call').length,
    earlierReplyCount: 0,
    subagentCount: 0,
  };
}
