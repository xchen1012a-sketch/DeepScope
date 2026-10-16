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

import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import * as Popover from '@radix-ui/react-popover';
import {
  AlertCircle,
  ArrowUp,
  Check,
  Clock,
  CornerDownRight,
  Loader2,
  MoreHorizontal,
  Sparkles,
  Square,
  Terminal,
  Trash2,
  X,
  Zap,
} from 'lucide-react';
import type { QueuedMessage } from '../useAdvisorConversation';
import { Button } from '@/components/ui/button';
import { formatTokenCount } from '@/lib/format';
import { api, ApiError, apiFetch } from '@/lib/apiClient';
import { Textarea } from '@/components/ui/input';
import { advisorKeys, fetchCommands, type SlashCommand, type UploadedAttachment } from '@/api/advisor';
import { SlashCommandMenu } from './SlashCommandMenu';
import {
  DEFAULT_CONTEXT_WINDOW,
  cacheHit,
  contextBreakdown,
  contextRemaining,
  contextWindowOf,
  formatRunDuration,
  inferContextWindow,
  type UsageSample,
} from '../conversationModel';
import { AttachmentStrip, ComposerToolbar } from './ComposerToolbar';
import { ContextRing } from './ContextRing';
import { ModelPicker } from './ModelPicker';
import type { ReasoningEffort } from '../aguiClient';
import { AgentPicker, type AgentPickerProps } from './AgentPicker';
import { cn } from '@/lib/utils';
import { useAccountIdentity } from '@/lib/accountIdentity';

export function ComposerDock({
  draft,
  setDraft,
  running,
  canSend,
  onSend,
  onStop,
  queuedMessages,
  onInterruptAndSend,
  onCancelQueuedMessage,
  turns,
  samples,
  toolCallCount,
  thinking,
  onToggleThinking,
  reasoningEffort,
  onSelectReasoningEffort,
  attachments,
  uploading,
  uploadError,
  fileInputRef,
  onPickFiles,
  onFilesSelected,
  onRemoveAttachment,
  onPaste,
  onDrop,
  sessionId,
  onCompacted,
  onClearSession,
  selectedModelEndpointId,
  onSelectModel,
  variant = 'conversation',
  agentPicker,
  inputReplacement,
}: {
  variant?: 'welcome' | 'conversation';
  agentPicker?: AgentPickerProps;
  /** Replace ordinary input while retaining the shared queue and its cancellation controls. */
  inputReplacement?: React.ReactNode;
  draft: string;
  setDraft: (value: string) => void;
  running: boolean;
  canSend: boolean;
  onSend: () => void;
  onStop: () => void;
  queuedMessages?: QueuedMessage[];
  onInterruptAndSend?: (queuedId?: string) => void;
  onCancelQueuedMessage?: (queuedId: string) => void;
  turns: number;
  samples: UsageSample[];
  /** 本会话已发生的工具调用数；只用于上下文面板的工具行估算。 */
  toolCallCount: number;
  thinking: boolean;
  onToggleThinking: () => void;
  /** 推理程度档位；仅思考开启时可选。 */
  reasoningEffort?: ReasoningEffort;
  onSelectReasoningEffort?: (tier: ReasoningEffort) => void;
  attachments: UploadedAttachment[];
  uploading: boolean;
  uploadError: string | null;
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onPickFiles: () => void;
  onFilesSelected: (files: FileList) => void;
  onRemoveAttachment: (id: string) => void;
  onPaste: (event: React.ClipboardEvent) => void;
  onDrop: (event: React.DragEvent) => void;
  /** 当前会话；一键压缩按它定位后端状态。 */
  sessionId?: string;
  /** 压缩成功后回调：刷新时间线让圆环按压缩后的占用回弹。 */
  onCompacted?: () => void;
  /** 清空会话上下文回调：跳转到新对话页面。 */
  onClearSession?: () => void;
  selectedModelEndpointId?: string | null;
  onSelectModel?: (endpointId: string | null) => void;
}) {
  const { t } = useTranslation();
  const identity = useAccountIdentity();
  const changingModel = useIsMutating({ mutationKey: advisorKeys.modelActivation }) > 0;
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const isInputReplaced = Boolean(inputReplacement);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    const maxHeight = 200;
    const nextHeight = Math.min(Math.max(el.scrollHeight, variant === 'welcome' ? 72 : 36), maxHeight);
    el.style.height = `${nextHeight}px`;
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [draft, variant, isInputReplaced]);

  useEffect(() => {
    const input = textareaRef.current;
    if (!input) return;
    const containInputWheel = (event: WheelEvent) => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.deltaY === 0) return;
      const atTop = input.scrollTop <= 0;
      const atBottom = input.scrollTop + input.clientHeight >= input.scrollHeight - 1;
      if ((event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom)) {
        event.preventDefault();
      }
    };
    // React delegates wheel events passively; only the textarea needs to contain boundary scrolling.
    input.addEventListener('wheel', containInputWheel, { passive: false });
    return () => input.removeEventListener('wheel', containInputWheel);
  }, [isInputReplaced]);

  const activeModelQuery = useQuery({
    queryKey: sessionId ? ['advisor', 'session-model', sessionId] : ['advisor', 'active-model'],
    queryFn: () =>
      api.get<{ endpointId?: string; provider?: string; modelName?: string; contextWindow?: number }>(
        sessionId ? `/api/advisor/sessions/${encodeURIComponent(sessionId)}/model` : '/api/advisor/models/active',
      ),
    retry: false,
  });
  const activeModel = activeModelQuery.data;
  const [usageOpen, setUsageOpen] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const [contextPinned, setContextPinned] = useState(false);
  const [openQueueMoreId, setOpenQueueMoreId] = useState<string | null>(null);
  const hoverCloseTimerRef = useRef<number | null>(null);

  const clearHoverCloseTimer = () => {
    if (hoverCloseTimerRef.current !== null) {
      window.clearTimeout(hoverCloseTimerRef.current);
      hoverCloseTimerRef.current = null;
    }
  };

  useEffect(() => () => clearHoverCloseTimer(), []);

  // 一键压缩三态：就绪 → 进行（置灰旋转，禁止重复点击）→ 完成（绿色对勾短暂停留后恢复就绪）。
  // 后端状态持久化在智能体状态存储里，压缩期间刷新页面不会丢结果，前端只需按请求结果渲染。
  const [compactState, setCompactState] = useState<'ready' | 'running' | 'done'>('ready');
  const [compactNotice, setCompactNotice] = useState<string | null>(null);
  const compactTimersRef = useRef<number[]>([]);
  const noticeTimerRef = useRef<number | null>(null);

  const clearNoticeTimer = () => {
    if (noticeTimerRef.current !== null) {
      window.clearTimeout(noticeTimerRef.current);
      noticeTimerRef.current = null;
    }
  };

  const showCompactNotice = (message: string) => {
    clearNoticeTimer();
    setCompactNotice(message);
    noticeTimerRef.current = window.setTimeout(() => {
      setCompactNotice(null);
      noticeTimerRef.current = null;
    }, 2500);
  };

  useEffect(() => () => {
    clearNoticeTimer();
    compactTimersRef.current.forEach((timer) => window.clearTimeout(timer));
  }, []);

  const handleCompact = async () => {
    if (compactState === 'running') return;
    if (!sessionId) {
      showCompactNotice(t('chat.composer.compactReason.too_short'));
      return;
    }
    clearNoticeTimer();
    setCompactState('running');
    setCompactNotice(null);
    try {
      const result = await apiFetch<{
        outcome: string;
        reason?: string;
        inputTokens?: number;
        keptTokens?: number;
      }>(`/api/advisor/sessions/${sessionId}:compact`, { method: 'POST' });
      if (result.outcome === 'compacted') {
        setCompactState('done');
        onCompacted?.();
        compactTimersRef.current.push(
          window.setTimeout(() => setCompactState('ready'), 1600),
        );
      } else {
        setCompactState('ready');
        showCompactNotice(t(`chat.composer.compactReason.${result.reason ?? 'unavailable'}`));
      }
    } catch (error) {
      setCompactState('ready');
      let reason = 'unavailable';
      if (error instanceof ApiError && error.status === 409) {
        try {
          const parsed = JSON.parse(error.body);
          if (parsed && typeof parsed.reason === 'string' && parsed.reason) {
            reason = parsed.reason;
          } else {
            reason = 'already_compacting';
          }
        } catch {
          reason = 'already_compacting';
        }
      }
      showCompactNotice(t(`chat.composer.compactReason.${reason}`));
    }
  };

  // 受控斜杠命令：/ 呼出自动补全浮层，支持实时搜索与全键盘导航
  const [slashDismissed, setSlashDismissed] = useState(false);
  const [slashHighlightIndex, setSlashHighlightIndex] = useState(0);
  const slashMatch = useMemo(() => /(?:^|\s)\/([a-zA-Z0-9_-]*)$/.exec(draft), [draft]);
  const slashActive = slashMatch !== null;
  const slashQuery = slashMatch ? slashMatch[1] : '';
  const commandsQuery = useQuery({
    queryKey: [...advisorKeys.commands, identity?.userId],
    queryFn: fetchCommands,
    enabled: slashActive && !slashDismissed,
    staleTime: 0,
    retry: false,
  });

  const defaultSlashCommands = useMemo<SlashCommand[]>(
    () => [
      {
        commandId: 'cmd-builtin-compact',
        code: 'compact',
        name: t('chat.composer.compactTitle'),
        description: t('chat.composer.compactDescription'),
        origin: 'builtin',
        targetType: 'action',
        targetId: 'compact',
        paramsJson: '{}',
        sortOrder: 10,
      },
      {
        commandId: 'cmd-builtin-clear',
        code: 'clear',
        name: t('chat.composer.clearTitle', { defaultValue: '清空会话上下文' }),
        description: t('chat.composer.clearDescription', { defaultValue: '重置当前会话历史，开始全新对话' }),
        origin: 'builtin',
        targetType: 'action',
        targetId: 'clear',
        paramsJson: '{}',
        sortOrder: 20,
      },
    ],
    [t],
  );

  const availableCommands =
    commandsQuery.data && commandsQuery.data.length > 0
      ? commandsQuery.data
      : defaultSlashCommands;

  const slashStartIndex = useMemo(() => {
    if (!slashMatch) return -1;
    const matchStr = slashMatch[0];
    const offset = matchStr.startsWith('/') ? 0 : 1;
    return slashMatch.index + offset;
  }, [slashMatch]);

  const filteredCommands = useMemo(() => {
    if (!slashActive) return [];
    if (!slashQuery) return availableCommands;
    const lower = slashQuery.toLowerCase();
    return availableCommands.filter(
      (c) =>
        c.code.toLowerCase().includes(lower) ||
        c.name.toLowerCase().includes(lower) ||
        c.description.toLowerCase().includes(lower),
    );
  }, [slashActive, slashQuery, availableCommands]);

  useEffect(() => {
    setSlashHighlightIndex(0);
  }, [slashQuery]);

  const handleSelectSlashCommand = (cmd: SlashCommand) => {
    const prefix = draft.slice(0, slashStartIndex);
    const suffix = cmd.targetType === 'action' ? '' : ' ';
    const newDraft = `${prefix}/${cmd.code}${suffix}`;
    setDraft(newDraft);
    setSlashDismissed(true);
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
    });
  };

  const handleSendAction = () => {
    const text = draft.trim();
    if (!text && attachments.length === 0) return;
    if (text === '/clear') {
      if (running) { showCompactNotice(t('chat.errorBusy')); return; }
      setDraft('');
      setSlashDismissed(true);
      onClearSession?.();
      return;
    }
    if (text === '/compact' || text === '/compress') {
      if (running) { showCompactNotice(t('chat.errorBusy')); return; }
      setDraft('');
      setSlashDismissed(true);
      void handleCompact();
      return;
    }
    if (canSend && !changingModel) {
      onSend();
    }
  };

  const handleContextMouseEnter = () => {
    clearHoverCloseTimer();
    setContextOpen(true);
  };

  const handleContextMouseLeave = () => {
    if (contextPinned) return;
    clearHoverCloseTimer();
    hoverCloseTimerRef.current = window.setTimeout(() => {
      setContextOpen(false);
      hoverCloseTimerRef.current = null;
    }, 150);
  };

  // 当前实际在用的模型名：模型目录优先；目录拿不到（如无设置权限）时依次使用
  // 活跃模型公共接口与最近一次采样回报的名字——运行数据里带着真话，不能因为没权限就显示「未设置模型」。
  let sampleModelName: string | undefined;
  for (let index = samples.length - 1; index >= 0; index -= 1) {
    const name = samples[index]?.modelName;
    if (name) {
      sampleModelName = name;
      break;
    }
  }
  const hit = cacheHit(samples);
  const latest = samples.length > 0 ? samples[samples.length - 1] : undefined;
  const currentModelName = activeModel?.modelName ?? sampleModelName;
  const sampleWindow = sampleModelName === currentModelName ? contextWindowOf(samples) : undefined;
  const capacity =
    activeModel?.contextWindow ||
    sampleWindow ||
    (currentModelName ? inferContextWindow(currentModelName) : DEFAULT_CONTEXT_WINDOW);
  const capacityKnown = capacity !== undefined && capacity > 0;
  const context = contextRemaining(latest?.projectedTokens, capacity);
  const breakdown = contextBreakdown(latest?.projectedTokens, toolCallCount) ?? {
    systemPromptTokens: 0,
    toolTokens: 0,
    messageTokens: 0,
  };
  const seconds = samples.reduce((sum, sample) => sum + (sample.timeSeconds ?? 0), 0);
  const duration = seconds > 0 ? formatRunDuration(seconds * 1000) : null;
  // 没有采样的回合不参与平均：只把真有采样的样本计入。
  const sampled = samples.filter((sample) => sample.firstTokenMs !== undefined);
  const firstToken =
    sampled.length > 0
      ? Math.round(sampled.reduce((sum, sample) => sum + (sample.firstTokenMs ?? 0), 0) / sampled.length)
      : null;
  const rates = samples.filter((sample) => sample.outputTokensPerSecond !== undefined);
  const tokensPerSecond =
    rates.length > 0
      ? rates.reduce((sum, sample) => sum + (sample.outputTokensPerSecond ?? 0), 0) / rates.length
      : null;
  const stats = [
    t('chat.composer.turns', { count: turns }),
    t('chat.composer.steps', { count: samples.length }),
    duration ? t('chat.composer.ran', { duration }) : null,
    firstToken !== null ? t('chat.composer.firstToken', { milliseconds: firstToken }) : null,
    tokensPerSecond !== null
      ? t('chat.composer.tokensPerSecond', { rate: tokensPerSecond.toFixed(1) })
      : null,
  ].filter((part): part is string => part !== null);

  const contextLabel = capacityKnown
    ? t('chat.composer.contextRingPercent', { percent: context.remainingPercent })
    : t('chat.composer.contextWindowPending');
  const barColor =
    context.remainingPercent <= 15
      ? 'bg-rose-500'
      : context.remainingPercent <= 30
        ? 'bg-amber-500'
        : 'bg-indigo-500';

  return (
    <div className={cn('shrink-0 bg-white', variant === 'welcome' ? 'p-0' : 'px-4 pb-3 pt-2 sm:px-10')}>
      <div className="mx-auto max-w-3xl">
        {queuedMessages && queuedMessages.length > 0 && (
          <div
            data-testid="composer-queue-tab"
            className="relative flex flex-col"
          >
            {queuedMessages.map((item, index) => {
              const previewText =
                item.text.trim() ||
                item.options?.attachments?.map((a) => a.name).join('、') ||
                t('chat.composer.emptyMessage', { defaultValue: '无文本' });
              const isLast = index === queuedMessages.length - 1;
              const depthFromBottom = queuedMessages.length - 1 - index;
              const horizontalInset = Math.min(depthFromBottom * 6 + 10, 24);
              const isMoreOpen = openQueueMoreId === item.id;

              return (
                <div
                  key={item.id}
                  data-testid={`composer-queue-item-${index}`}
                  style={{
                    marginLeft: `${horizontalInset}px`,
                    marginRight: `${horizontalInset}px`,
                    marginBottom: isLast ? '-10px' : '-8px',
                    zIndex: index + 1,
                  }}
                  className={`relative flex items-center justify-between rounded-t-2xl border border-b-0 border-slate-200/90 px-3.5 pt-2 pb-3.5 text-xs text-slate-700 shadow-xs transition-all ${
                    isLast ? 'bg-slate-50/95' : 'bg-slate-100/90'
                  }`}
                >
                  {/* Left side: Queue icon + preview text */}
                  <div className="flex min-w-0 flex-1 items-center gap-2">
                    <span className="flex items-center gap-1.5 font-medium text-slate-700">
                      <svg
                        className="h-3.5 w-3.5 text-slate-500 shrink-0"
                        viewBox="0 0 16 16"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.6"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                      >
                        <path d="M5 4h5M5 8h7M5 12h4" />
                        <path d="M3.5 2.5H2v11h1.5" />
                      </svg>
                      <span className="sr-only">
                        {t('chat.composer.queueBadge', { defaultValue: '排队中' })}
                      </span>
                    </span>
                    <span className="truncate text-xs font-normal text-slate-700 max-w-[280px] sm:max-w-md">
                      {previewText}
                    </span>
                  </div>

                  {/* Right side: Steer (↪ 引导), Trash (🗑), More (···) */}
                  <div className="flex shrink-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() => onInterruptAndSend?.(item.id)}
                      disabled={isInputReplaced}
                      className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200/70 hover:text-slate-900 cursor-pointer transition-colors disabled:cursor-not-allowed disabled:opacity-40"
                      title={t('chat.composer.interruptAndSendHint', { defaultValue: '打断当前执行并立即发送此消息' })}
                    >
                      <CornerDownRight className="h-3.5 w-3.5 text-slate-500" />
                      <span>{t('chat.composer.steer', { defaultValue: '引导' })}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => onCancelQueuedMessage?.(item.id)}
                      className="flex h-6 w-6 items-center justify-center rounded-md text-slate-400 hover:bg-slate-200/70 hover:text-slate-600 cursor-pointer transition-colors"
                      title={t('chat.composer.cancelQueue', { defaultValue: '取消排队' })}
                      aria-label={t('chat.composer.cancelQueue', { defaultValue: '取消排队' })}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>

                    <Popover.Root
                      open={isMoreOpen}
                      onOpenChange={(open) => setOpenQueueMoreId(open ? item.id : null)}
                    >
                      <Popover.Trigger asChild>
                        <button
                          type="button"
                          className="flex h-6 w-6 items-center justify-center rounded-md text-slate-400 hover:bg-slate-200/70 hover:text-slate-600 cursor-pointer transition-colors"
                          title={t('chat.composer.queueMore', { defaultValue: '更多操作' })}
                          aria-label={t('chat.composer.queueMore', { defaultValue: '更多操作' })}
                        >
                          <MoreHorizontal className="h-3.5 w-3.5" />
                        </button>
                      </Popover.Trigger>
                      <Popover.Portal>
                        <Popover.Content
                          side="top"
                          align="end"
                          sideOffset={6}
                          className="z-[70] w-64 max-w-[calc(100vw-32px)] rounded-xl border border-slate-200 bg-white p-3 text-xs text-slate-700 shadow-md animate-in fade-in zoom-in-95"
                        >
                          <div className="font-semibold text-slate-800 pb-1.5 border-b border-slate-100 flex items-center justify-between">
                            <span>{t('chat.composer.queueContent', { defaultValue: '排队内容' })} (#{index + 1})</span>
                            <span className="text-[11px] text-slate-400 font-normal">{queuedMessages.length} {t('chat.composer.queueBadge', { defaultValue: '排队中' })}</span>
                          </div>
                          <div className="py-2 max-h-36 overflow-y-auto whitespace-pre-wrap break-words text-slate-600 leading-relaxed font-mono text-[11.5px] bg-slate-50 rounded-lg p-2 my-1">
                            {previewText}
                          </div>
                          <div className="pt-1.5 flex flex-col gap-1 border-t border-slate-100">
                            <button
                              type="button"
                              onClick={() => {
                                onInterruptAndSend?.(item.id);
                                setOpenQueueMoreId(null);
                              }}
                              disabled={isInputReplaced}
                              className="flex items-center gap-1.5 w-full text-left px-2 py-1.5 rounded-md hover:bg-slate-100 text-indigo-600 font-medium cursor-pointer disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              <CornerDownRight className="h-3.5 w-3.5" />
                              <span>{t('chat.composer.interruptAndSend', { defaultValue: '立即打断并发送' })}</span>
                            </button>
                            <button
                              type="button"
                              onClick={() => {
                                onCancelQueuedMessage?.(item.id);
                                setOpenQueueMoreId(null);
                              }}
                              className="flex items-center gap-1.5 w-full text-left px-2 py-1.5 rounded-md hover:bg-rose-50 text-rose-600 font-medium cursor-pointer"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                              <span>{t('chat.composer.cancelQueue', { defaultValue: '取消排队' })}</span>
                            </button>
                          </div>
                          <Popover.Arrow className="fill-white" />
                        </Popover.Content>
                      </Popover.Portal>
                    </Popover.Root>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {isInputReplaced ? inputReplacement : <>
        <div
          onDragOver={(event) => event.preventDefault()}
          onDrop={onDrop}
          className="relative z-10 rounded-[20px] border border-slate-200/80 bg-white px-3.5 py-2 shadow-sm"
        >
          {compactNotice && !slashActive && !contextOpen && (
            <div
              role="status"
              data-testid="compact-floating-notice"
              className="pointer-events-none absolute -top-8 left-3 z-30 flex items-center gap-1.5 rounded-lg border border-amber-200/90 bg-amber-50 px-2.5 py-1 text-[11.5px] font-medium text-amber-800 shadow-sm animate-in fade-in slide-in-from-bottom-1"
            >
              <AlertCircle className="h-3.5 w-3.5 shrink-0 text-amber-600" />
              <span>{compactNotice}</span>
            </div>
          )}
          {compactState === 'running' && !slashActive && !contextOpen && (
            <div
              role="status"
              data-testid="compact-floating-running"
              className="pointer-events-none absolute -top-8 left-3 z-30 flex items-center gap-1.5 rounded-lg border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11.5px] font-medium text-indigo-800 shadow-sm animate-in fade-in slide-in-from-bottom-1"
            >
              <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-indigo-600" />
              <span>{t('chat.composer.compactRunning')}</span>
            </div>
          )}
          {compactState === 'done' && !slashActive && !contextOpen && (
            <div
              role="status"
              data-testid="compact-floating-done"
              className="pointer-events-none absolute -top-8 left-3 z-30 flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-[11.5px] font-medium text-emerald-800 shadow-sm animate-in fade-in slide-in-from-bottom-1"
            >
              <Check className="h-3.5 w-3.5 shrink-0 text-emerald-600" />
              <span>{t('chat.composer.compactDone')}</span>
            </div>
          )}
          {slashActive && !slashDismissed && (
            <SlashCommandMenu
              commands={filteredCommands}
              highlightIndex={slashHighlightIndex}
              onSelect={handleSelectSlashCommand}
              onHighlight={setSlashHighlightIndex}
            />
          )}
          <AttachmentStrip
            attachments={attachments}
            uploading={uploading}
            uploadError={uploadError}
            onRemove={onRemoveAttachment}
          />
          <Textarea
            ref={textareaRef}
            value={draft}
            onPaste={onPaste}
            onDragOver={(event) => event.preventDefault()}
            onDrop={onDrop}
            onChange={(event) => {
              setDraft(event.target.value);
              setSlashDismissed(false);
            }}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return;
              if (slashActive && !slashDismissed) {
                if (event.key === 'Escape') {
                  event.preventDefault();
                  setSlashDismissed(true);
                  return;
                }
                if (filteredCommands.length > 0) {
                  if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    setSlashHighlightIndex((prev) => (prev + 1) % filteredCommands.length);
                    return;
                  }
                  if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    setSlashHighlightIndex((prev) => (prev - 1 + filteredCommands.length) % filteredCommands.length);
                    return;
                  }
                  if (
                    (event.key === 'Enter' || event.key === 'Tab') &&
                    !event.shiftKey &&
                    !event.nativeEvent.isComposing
                  ) {
                    event.preventDefault();
                    const targetCmd = filteredCommands[slashHighlightIndex] ?? filteredCommands[0];
                    if (targetCmd) {
                      handleSelectSlashCommand(targetCmd);
                    }
                    return;
                  }
                }
              }

              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                handleSendAction();
              }
            }}
            placeholder={t('chat.collaborate')}
            className={cn('max-h-[200px] resize-none overscroll-y-contain border-0 px-1 py-1 text-[15px] leading-7 shadow-none focus:shadow-none focus:ring-0 focus-visible:ring-0', variant === 'welcome' ? 'min-h-[72px]' : 'min-h-[36px]')}
            rows={variant === 'welcome' ? 2 : 1}
          />
          <div className="flex flex-wrap items-center justify-between gap-2 pt-0.5">
            <ComposerToolbar
              fileInputRef={fileInputRef}
              onPickFiles={onPickFiles}
              onFilesSelected={onFilesSelected}
              disabled={uploading || changingModel}
            />
            <div className="flex min-w-0 max-w-full flex-wrap items-center justify-end gap-1 sm:gap-2">
              {agentPicker ? <AgentPicker {...agentPicker} /> : null}
              <ModelPicker
                sessionId={sessionId}
                selectedEndpointId={selectedModelEndpointId}
                onSelectModel={onSelectModel}
                thinking={thinking}
                onToggleThinking={onToggleThinking}
                reasoningEffort={reasoningEffort}
                onSelectReasoningEffort={onSelectReasoningEffort}
                fallbackModelName={currentModelName}
                disabled={false}
              />
              <Popover.Root
                open={contextOpen}
                onOpenChange={(nextOpen) => {
                  setContextOpen(nextOpen);
                  if (!nextOpen) setContextPinned(false);
                }}
              >
                <Popover.Trigger asChild>
                  <button
                    type="button"
                    data-testid="context-ring-trigger"
                    aria-label={contextLabel}
                    onMouseEnter={handleContextMouseEnter}
                    onMouseLeave={handleContextMouseLeave}
                    onClick={(event) => {
                      event.preventDefault();
                      clearHoverCloseTimer();
                      const nextPinned = !contextPinned;
                      setContextPinned(nextPinned);
                      setContextOpen(nextPinned);
                    }}
                    className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <ContextRing remainingPercent={context.remainingPercent} capacityKnown={capacityKnown} />
                  </button>
                </Popover.Trigger>
                <Popover.Portal>
                  <Popover.Content
                    side="top"
                    align="end"
                    sideOffset={10}
                    collisionPadding={12}
                    aria-label={contextLabel}
                    onMouseEnter={handleContextMouseEnter}
                    onMouseLeave={handleContextMouseLeave}
                    onOpenAutoFocus={(event) => {
                      if (!contextPinned) event.preventDefault();
                    }}
                    className="z-[60] w-72 max-w-[calc(100vw-24px)] rounded-xl border border-border bg-white p-4 text-[12.5px] text-slate-600 shadow-lg"
                  >
                    {capacityKnown ? (
                      <>
                    <div className="flex items-center justify-between gap-2 font-medium text-slate-800">
                      <span>
                        {t('chat.composer.contextRemainingTitle', {
                          remainingPercent: context.remainingPercent,
                          usedPercent: context.percent,
                        })}
                      </span>
                    </div>
                    <div className="mt-1 text-[12px] text-slate-500">
                      {t('chat.composer.contextRatio', {
                        used: formatTokenCount(context.used),
                        capacity: formatTokenCount(context.capacity),
                      })}
                    </div>
                    <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-200">
                      <div className={`h-full transition-all ${barColor}`} style={{ width: `${Math.min(100, context.percent)}%` }} />
                    </div>
                    <dl className="mt-2.5 space-y-1" data-testid="context-breakdown">
                      <BreakdownRow label={t('chat.composer.contextSystem')} value={breakdown.systemPromptTokens} />
                      <BreakdownRow label={t('chat.composer.contextTools')} value={breakdown.toolTokens} />
                      <BreakdownRow label={t('chat.composer.contextMessages')} value={breakdown.messageTokens} />
                    </dl>
                    {context.remainingPercent <= 20 ? (
                      <p className="mt-2 rounded-md bg-amber-50 px-2 py-1 text-[11.5px] text-amber-700">
                        {t('chat.composer.contextCompactionHint')}
                      </p>
                    ) : null}
                    <p className="mt-2 text-[11.5px] text-slate-400">{t('chat.composer.contextEstimate')}</p>
                    <CompactCard
                      state={compactState}
                      notice={compactNotice}
                      disabled={running}
                      onCompact={() => void handleCompact()}
                    />
                      </>
                    ) : <p>{t('chat.composer.contextWindowPending')}</p>}
                    <Popover.Arrow className="fill-white" />
                  </Popover.Content>
                </Popover.Portal>
              </Popover.Root>
              {running ? (
                draft.trim().length > 0 || attachments.length > 0 ? (
                  <Button
                    size="icon"
                    className="h-9 w-9 shrink-0 rounded-full bg-indigo-600 hover:bg-indigo-700"
                    onClick={handleSendAction}
                    disabled={!canSend || changingModel}
                    title={t('chat.composer.queueSendBtn')}
                    aria-label={t('chat.composer.queueSendBtn')}
                  >
                    <ArrowUp className="h-4 w-4" />
                  </Button>
                ) : (
                  <Button
                    size="icon"
                    variant="outline"
                    className="h-9 w-9 shrink-0 rounded-full"
                    onClick={onStop}
                    title={t('chat.composer.stop')}
                    aria-label={t('chat.composer.stop')}
                  >
                    <Square className="h-3.5 w-3.5" />
                  </Button>
                )
              ) : (
                <Button
                  size="icon"
                  className="h-9 w-9 shrink-0 rounded-full"
                  onClick={handleSendAction}
                  disabled={!canSend || changingModel}
                  title={t('chat.send')}
                  aria-label={t('chat.send')}
                >
                  <ArrowUp className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>
        </div>
        <div className="mt-2 flex flex-wrap items-center justify-between gap-2 px-1 text-[11.5px] text-slate-400">
          <span>{t('home.sendHint')}</span>
          <Popover.Root open={usageOpen} onOpenChange={setUsageOpen}>
            <Popover.Trigger asChild>
              <button type="button" className="rounded px-1 py-0.5 hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                {t('chat.composer.usageDetails')}
              </button>
            </Popover.Trigger>
            <Popover.Portal>
              <Popover.Content side="top" align="end" sideOffset={10} collisionPadding={12} aria-label={t('chat.composer.usageDetails')} className="z-[60] w-80 max-w-[calc(100vw-24px)] rounded-xl border border-border bg-white p-4 text-[12px] text-slate-500 shadow-lg">
                <div className="mb-3 flex items-center justify-between gap-2">
                  <span className="font-medium text-slate-800">{t('chat.composer.usageDetails')}</span>
                  <Popover.Close aria-label={t('common:dialogClose')} className="rounded p-1 hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><X className="h-3.5 w-3.5" /></Popover.Close>
                </div>
                <p>{stats.join(' · ')}</p>
                {hit ? (
                  <div className="mt-2">
                    <p>{t('chat.composer.cacheHit', { percent: hit.percent })}</p>
                    <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
                      <CacheCell label={t('chat.composer.uncached')} value={hit.uncachedInput} />
                      <CacheCell label={t('chat.composer.cacheRead')} value={hit.cacheRead} />
                      <CacheCell label={t('chat.composer.cacheWrite')} value={hit.cacheWrite} />
                      <CacheCell label={t('chat.composer.output')} value={hit.output} />
                    </div>
                  </div>
                ) : null}
                <Popover.Arrow className="fill-white" />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        </div>
        </>}
      </div>
    </div>
  );
}

function CacheCell({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="text-slate-400">{label}</div>
      <div className="font-medium text-slate-700">{value}</div>
    </div>
  );
}

/** 上下文面板的一行估算；三行都是估算值。 */
function BreakdownRow({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-slate-500">{label}</dt>
      <dd className="font-mono text-[12px] text-slate-700">{formatTokenCount(value)}</dd>
    </div>
  );
}

/**
 * 一键压缩操作：极简命令超链接形式（如 compact 指令），就绪态仅呈现压缩指令；
 * 进行态置灰旋转并禁止重复点击；完成态短暂显示绿色对勾后恢复就绪。
 * 冲突与短会话提示以原因码走词典，提示展示约 2.5 秒后自动消失。
 */
function CompactCard({
  state,
  notice,
  disabled,
  onCompact,
}: {
  state: 'ready' | 'running' | 'done';
  notice: string | null;
  disabled: boolean;
  onCompact: () => void;
}) {
  const { t } = useTranslation();
  const running = state === 'running';
  const done = state === 'done';
  return (
    <div className="mt-3 border-t border-slate-100 pt-2.5">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="compact-card"
          aria-label={t('chat.composer.compactTitle')}
          title={t('chat.composer.compactTitle')}
          disabled={running || done || disabled}
          onClick={onCompact}
          className={`group inline-flex items-center gap-1.5 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
            running
              ? 'cursor-wait opacity-70'
              : done
                ? 'text-emerald-700'
                : disabled
                  ? 'cursor-not-allowed opacity-50'
                  : 'text-indigo-600 hover:text-indigo-700'
          }`}
        >
          {done ? (
            <Check className="h-3.5 w-3.5 shrink-0 text-emerald-600" aria-hidden />
          ) : running ? (
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-slate-400" aria-hidden />
          ) : (
            <Terminal className="h-3.5 w-3.5 shrink-0 text-indigo-500 group-hover:text-indigo-700" aria-hidden />
          )}
          <span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[11.5px] font-semibold text-indigo-600 underline underline-offset-2 decoration-indigo-300 group-hover:bg-indigo-100 group-hover:text-indigo-700 group-hover:decoration-indigo-600">
            compact
          </span>
          {done ? (
            <span className="text-[12px] font-medium text-emerald-700">
              {t('chat.composer.compactDone')}
            </span>
          ) : running ? (
            <span className="text-[12px] font-medium text-slate-600">
              {t('chat.composer.compactRunning')}
            </span>
          ) : null}
        </button>
      </div>
      {notice && !running && !done ? (
        <p className="mt-1.5 rounded-md bg-amber-50 px-2 py-1 text-[11.5px] text-amber-700 transition-opacity" role="status">
          {notice}
        </p>
      ) : null}
    </div>
  );
}
