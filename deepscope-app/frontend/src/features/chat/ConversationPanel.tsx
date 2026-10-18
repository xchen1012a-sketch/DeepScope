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

import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTabDraft } from '@/hooks/useTabDraft';
import { Archive, MessageSquare } from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  advisorKeys,
  executeCommand,
  payloadList,
  type AttachmentSummary,
  type UploadedAttachment,
} from '@/api/advisor';
import { resolveProposal, listAvailableAgents, agentKeys } from '@/api/agent';
import { useAccountPreferences } from '@/features/account/useAccount';
import {
  endRun,
  useAdvisorConversation,
  groupSubagents,
  matchProposalResolutions,
  usageFrom,
  type ConversationEvent,
} from './useAdvisorConversation';
import {
  getStoredReasoningEffortPreference,
  getStoredThinkingPreference,
  setStoredReasoningEffortPreference,
  setStoredThinkingPreference,
} from './conversationModel';
import type { ReasoningEffort } from './aguiClient';
import { captureConversationAnchor, restoreConversationAnchor, type ConversationAnchor } from './conversationScroll';
import { Markdown } from './components/Markdown';
import { CopyTextButton } from './components/CopyTextButton';
import { ErrorCard } from './components/processCards';
import { ProposalCard, ProposalResolvedCard } from './components/gateCards';
import { ToolConfirmationBar } from './components/ToolConfirmationBar';
import { BlockFrame, TurnGroup } from './components/TurnGroup';
import { ThinkingLine } from './components/ThinkingLine';
import { ProcessStateIcon } from './components/ProcessStateIcon';
import { ProcessStatusLabel } from './components/ProcessStatusLabel';
import { ToolLine } from './components/ToolLine';
import { ComposerDock } from './components/ComposerDock';
import { QuestionCard } from './components/QuestionCard';
import { Button } from '@/components/ui/button';
import { VisitorAttachmentChips, useComposerAttachments } from './components/ComposerToolbar';
import { ProcessDrawerTrigger, ProcessSidePanel } from './components/ProcessDrawer';
import { KIND_ICON } from './components/kindIcons';
import { buildBlocks, findBlock, type ConversationBlock } from './model/blocks';
import { questionErrorKey } from './model/questions';

/** 把后端原始错误文案映射为本地化友好提示：超时与并发冲突两类最常见。 */
function friendlyRunError(message: string, t: (key: string) => string): string {
  if (message.includes('resource_command_unavailable')) return t('chat.composer.slashMenu.unavailable');
  if (message.includes('resource_command_not_bound')) return t('chat.composer.slashMenu.notBound');
  if (message.includes('resource_command_ambiguous')) return t('chat.composer.slashMenu.ambiguous');
  if (message.includes('resource_catalog_unavailable')) return t('chat.composer.slashMenu.loadFailed');
  if (message.includes('AGUI_STREAM_INTERRUPTED')) return t('chat.errorDisconnected');
  if (/timed out/i.test(message) || /TIMEOUT_ERROR/.test(message)) return t('chat.errorTimeout');
  if (/already has an active run/i.test(message) || /AGUI_INTERRUPT_CONTRACT_ERROR/.test(message)) return t('chat.errorBusy');
  return message;
}

/** 访客消息：右对齐的浅底圆角气泡，不放用户名（DeepSeek 风格的左右分栏）。 */
function VisitorBubble({ text, attachments, createdAt }: { text: string; attachments?: AttachmentSummary[]; createdAt?: number }) {
  const autoAttachmentNames =
    attachments && attachments.length > 0 ? attachments.map((item) => item.name).join('、') : '';
  const hasImageAttachment = Boolean(attachments?.some((item) => item.kind === 'image'));
  const visibleText = hasImageAttachment && text.trim() === autoAttachmentNames ? '' : text;

  return (
    <div className="group flex flex-col items-end py-1.5">
      <div className="max-w-[90%] break-words rounded-2xl rounded-br-md bg-indigo-50 px-4 py-3 sm:max-w-[85%]">
        <VisitorAttachmentChips attachments={attachments} />
        {visibleText ? (
          <div className="whitespace-pre-wrap text-[15px] leading-7 text-slate-900">{visibleText}</div>
        ) : null}
      </div>
      <div className="flex min-h-8 items-center gap-2 text-xs text-slate-400 opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 [@media(hover:none)]:opacity-100">
        {createdAt ? <time dateTime={new Date(createdAt).toISOString()}>{new Intl.DateTimeFormat(undefined, { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(createdAt)}</time> : null}
        <CopyTextButton text={text} />
      </div>
    </div>
  );
}

/** 参谋回复：左对齐、无气泡底色，Markdown 正文直接铺开。 */
function AdvisorBubble({ text, internalIds }: { text: string; internalIds?: readonly string[] }) {
  return (
    <div className="flex justify-start py-1.5 text-[15px] leading-7 text-slate-800">
      <div className="min-w-0 max-w-full">
        <Markdown text={text} internalIds={internalIds} />
      </div>
    </div>
  );
}

/** 提案挂起门的共享上下文：裁决回调与进行中标记。 */
interface ProposalGate {
  resolutionOf: (seq: number) => ProposalResolutionMeta | undefined;
  resolving: boolean;
  onResolve: (seq: number, approved: boolean, content?: string) => void;
}

interface ProposalResolutionMeta {
  approved: boolean;
  name: string;
  file?: string;
}

/** 过程组里与过程组外的过程性块：技能加载、派发、自办、落定、说明、追问。 */
const GENERIC_BLOCK_KINDS = new Set([
  'load_skill',
  'dispatch',
  'self_task',
  'subagent_settled',
  'advisor_note',
  'question',
]);

/** 自动上下文压缩状态行沿用当前过程的单一律动焦点。 */
function CompactionBlockLine({ block }: { block: ConversationBlock }) {
  const { t } = useTranslation();
  const isRunning = block.status === 'running';
  return (
    <div
      className={
        isRunning
          ? 'flex items-center gap-2 rounded-lg border border-amber-200/80 bg-amber-50/70 px-3 py-1.5 text-[12.5px] text-amber-800'
          : 'flex items-center gap-2 rounded-lg border border-slate-200/80 bg-slate-50/60 px-3 py-1.5 text-[12.5px] text-slate-600'
      }
    >
      {isRunning ? (
        <ProcessStateIcon state="running" />
      ) : (
        <Archive className="h-3.5 w-3.5 shrink-0 text-slate-400" />
      )}
      <ProcessStatusLabel active={isRunning}>
        {isRunning ? t('chat.compaction.running') : t('chat.compaction.done')}
      </ProcessStatusLabel>
      {!isRunning && block.summary ? (
        <span className="min-w-0 truncate font-mono text-[12px] text-slate-400">{block.summary}</span>
      ) : null}
    </div>
  );
}

/** 首 Token 到达前的即时等待指示行。 */
function WaitingLine() {
  const { t } = useTranslation();
  return (
    <div data-anchor="live-waiting" className="flex items-center gap-2 px-1 py-1.5 text-[13px] text-slate-500">
      <ProcessStateIcon state="running" />
      <ProcessStatusLabel active>{t('chat.activity.waiting')}</ProcessStatusLabel>
    </div>
  );
}

/**
 * 过程性块在主对话里的一行呈现：图标 + 可见序号 + 类型标题 + 一行摘要。
 * 未知 kind 不画（读侧对未知 kind 的既有处置就是忽略），避免露出 i18n 键名。
 */
function ProcessBlockLine({ block }: { block: ConversationBlock }) {
  const { t } = useTranslation();
  const Icon = KIND_ICON[block.kind] ?? MessageSquare;
  return (
    <div className="px-1 py-2">
      <div className="flex items-start gap-2 text-[13px]">
        <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" />
        <span className="shrink-0 font-medium text-slate-600">{t(block.kind === 'dispatch' ? 'chat.activity.dispatched' : block.kind === 'subagent_settled' ? 'chat.activity.settled' : `chat.timelineKind.${block.kind}`)}</span>
        {block.summary ? (
          <span className="line-clamp-2 min-w-0 flex-1 whitespace-pre-wrap text-slate-500">{block.summary}</span>
        ) : null}
      </div>
    </div>
  );
}

export function EventItem({
  event,
  proposalGate,
}: {
  event: ConversationEvent;
  proposalGate: ProposalGate;
}) {
  const { payload, kind } = event;
  switch (kind) {
    case 'proposal':
      return (
        <ProposalCard
          event={event}
          resolution={proposalGate.resolutionOf(event.seq)}
          resolving={proposalGate.resolving}
          onResolve={(approved, content) => proposalGate.onResolve(event.seq, approved, content)}
        />
      );
    case 'proposal_resolved':
      return <ProposalResolvedCard payload={payload} />;
    case 'error':
      return <ErrorCard text={String(payload.text ?? '')} />;
    default:
      return null;
  }
}

function ReplacementAgentPicker({ onSelect, disabled }: { onSelect: (id: string) => void; disabled: boolean }) {
  const { t } = useTranslation();
  const agents = useQuery({ queryKey: [...agentKeys.agents, 'available'], queryFn: listAvailableAgents, retry: false });
  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild>
        <button type="button" disabled={disabled} className="text-indigo-600">{t('chat.chooseAgent')}</button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content sideOffset={8} collisionPadding={12} className="z-50 max-h-64 min-w-48 overflow-auto rounded-xl border border-slate-200 bg-white p-1.5 text-sm shadow-lg">
          {agents.isPending ? <p className="p-2 text-slate-500">{t('shell.loading')}</p> : null}
          {agents.isError ? <button type="button" className="p-2 text-indigo-600" onClick={() => void agents.refetch()}>{t('chat.loadAgentsAgain')}</button> : null}
          {agents.data?.length === 0 ? <p className="p-2 text-slate-500">{t('chat.noReplacementAgent')}</p> : null}
          {agents.data?.map(agent => (
            <DropdownMenu.Item key={agent.agentId} onSelect={() => onSelect(agent.agentId)} className="cursor-default rounded-lg px-3 py-2 outline-none data-[highlighted]:bg-slate-100">{agent.name}</DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

/**
 * 右侧对话区：按 seq 线性回放时间线（消息与过程卡片交错），叠加实时思考卡片与
 * 流式气泡；composer 在运行中禁用、在追问待答时引导补充信息。宽屏下可展开过程侧边栏。
 */
export function ConversationPanel({
  sessionId,
  initialQuestion,
  initialThinking,
  initialReasoningEffort,
  initialAttachments,
  onQuestionConsumed,
}: {
  sessionId: string;
  initialQuestion?: string;
  initialThinking?: boolean;
  initialReasoningEffort?: ReasoningEffort;
  initialAttachments?: UploadedAttachment[];
  onQuestionConsumed?: () => void;
}) {
  const { t } = useTranslation();
  const changingModel = useIsMutating({ mutationKey: advisorKeys.modelActivation }) > 0;
  const queryClient = useQueryClient();
  const {
    session,
    events,
    streamingText,
    thinking,
    hasOrderedParts,
    running,
    queuedMessages,
    runError,
    agentUnavailable,
    runAgentId,
    retry,
    awaitingVisitor,
    pendingQuestion,
    pendingQuestionReply,
    answerQuestion,
    continueQuestion,
    questionBusy,
    questionError,
    pendingProposal,
    pendingInterrupts,
    resolveInterrupts,
    send,
    interruptAndSend,
    cancelQueuedMessage,
    stop,
  } = useAdvisorConversation(sessionId);
  const [draft, setDraft] = useTabDraft(`chat:${sessionId}`, '');
  const [agentOverride, setAgentOverride] = useState<string | null | undefined>();
  const selectedAgentId = agentOverride !== undefined ? agentOverride : session?.agentId ?? null;
  const preferences = useAccountPreferences();
  const [thinkingOverride, setDeepThinking] = useState<boolean | undefined>(initialThinking);
  const deepThinking = thinkingOverride ?? getStoredThinkingPreference();
  const showThinkingProcess = preferences.data?.thinkEnabled ?? false;
  const [effortOverride, setEffort] = useState<ReasoningEffort | undefined>(initialReasoningEffort);
  const reasoningEffort = effortOverride ?? preferences.data?.reasoningEffort ?? getStoredReasoningEffortPreference() ?? 'high';

  const handleToggleThinking = () => {
    const next = !deepThinking;
    setDeepThinking(next);
    setStoredThinkingPreference(next);
    if (!next) {
      handleSelectEffort('low');
    }
  };

  const handleSelectEffort = (tier: ReasoningEffort) => {
    setEffort(tier);
    setStoredReasoningEffortPreference(tier);
    // 账号偏好是跨设备的增强持久化；保存失败不打断本地体验。
    preferences.save({ reasoningEffort: tier });
  };
  const {
    attachments,
    uploading,
    uploadError,
    fileInputRef,
    openFilePicker,
    addFiles,
    removeAttachment,
    clearAttachments,
    handlePaste,
    handleDrop,
  } = useComposerAttachments();
  const [highlight, setHighlight] = useState<string | null>(null);
  const [forcedOpen, setForcedOpen] = useState<number[]>([]);
  const [processOpen, setProcessOpen] = useState(false);
  const [selectedAnchor, setSelectedAnchor] = useState<string | null>(null);
  const detailTrigger = useRef<HTMLElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const followRef = useRef(true);
  const readingAnchor = useRef<ConversationAnchor | null>(null);
  const startedRef = useRef(false);
  const [commandError, setCommandError] = useState<string | null>(null);

  const handleClearContext = async () => {
    if (!sessionId) return;
    if (running) { setCommandError(t('chat.errorBusy')); return; }
    setCommandError(null);
    try {
      const result = await executeCommand('clear', sessionId);
      if (result.outcome !== 'cleared') throw new Error('Clear was not confirmed');
      endRun(sessionId);
      queryClient.setQueryData(advisorKeys.session(sessionId), (old: unknown) =>
        old && typeof old === 'object' ? { ...old, entries: [], deliverables: [] } : old,
      );
      await queryClient.invalidateQueries({ queryKey: advisorKeys.session(sessionId) });
    } catch {
      setCommandError(t('chat.clearFailed'));
    }
  };

  const proposalResolutions = useMemo(() => matchProposalResolutions(events), [events]);
  const routeByTurn = useMemo(() => new Map(events.filter(event => event.kind === 'agent_route').map(event => [event.turn, event.payload])), [events]);
  const resolveMutation = useMutation({
    mutationFn: ({ seq, approved, content }: { seq: number; approved: boolean; content?: string }) =>
      resolveProposal(sessionId, seq, { approved, ...(approved && content !== undefined ? { content } : {}) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: advisorKeys.session(sessionId) }),
  });

  const proposalGate: ProposalGate = {
    resolutionOf: (seq) => proposalResolutions.get(seq),
    resolving: resolveMutation.isPending,
    onResolve: (seq, approved, content) => resolveMutation.mutate({ seq, approved, content }),
  };

  useEffect(() => {
    const hasInitial = Boolean(initialQuestion) || Boolean(initialAttachments && initialAttachments.length > 0);
    if (!session || !hasInitial || startedRef.current) return;
    startedRef.current = true;
    onQuestionConsumed?.();
    void send(initialQuestion ?? '', {
      thinking: Boolean(initialThinking),
      reasoningEffort: initialReasoningEffort,
      attachments: initialAttachments,
      selectedAgentId: session.agentId ?? null,
      onAccepted: () => setDraft(current => current === (initialQuestion ?? '') ? '' : current),
    });
  }, [session, initialQuestion, initialThinking, initialReasoningEffort, initialAttachments, send, onQuestionConsumed]);

  const restoreReadingPosition = () => {
    const node = scrollRef.current;
    if (!node || highlight) return;
    if (followRef.current) node.scrollTop = node.scrollHeight;
    else restoreConversationAnchor(node, readingAnchor.current);
    readingAnchor.current = captureConversationAnchor(node);
  };

  useLayoutEffect(restoreReadingPosition, [events, streamingText, thinking.status, thinking.text, running, highlight]);

  const hasDraft = draft.trim().length > 0 || attachments.length > 0;
  const canSend = hasDraft && !uploading && !changingModel && !(pendingQuestion && (running || questionBusy));

  const submit = () => {
    if (!canSend) return;
    const text = draft.trim();
    if (text === '/clear') {
      setDraft('');
      clearAttachments();
      void handleClearContext();
      return;
    }
    if (text === '/compact' || text === '/compress') {
      setDraft('');
      clearAttachments();
      return;
    }
    if (pendingQuestion && !running) {
      setCommandError(null);
      void answerQuestion(pendingQuestion, { selectedOptionIds: [], customText: text || attachments.map(item => item.name).join('、') }, deepThinking, attachments, selectedAgentId, reasoningEffort)
        .then(() => { setDraft(current => current.trim() === text ? '' : current); clearAttachments(); })
        .catch(error => setCommandError(t(questionErrorKey(error))));
      return;
    }
    const currentAttachments = attachments;
    followRef.current = true;
    void send(draft, { thinking: deepThinking, reasoningEffort, attachments: currentAttachments, selectedAgentId,
      onAccepted: () => { setDraft(current => current === draft ? '' : current); currentAttachments.forEach(item => removeAttachment(item.id)); },
    });
  };

  const turns = useMemo(() => {
    const projected = buildBlocks(events, running);
    if (showThinkingProcess) return projected;
    return projected.map(turn => ({
      ...turn,
      process: turn.process.filter(block => block.kind !== 'thinking'),
      ordered: turn.ordered?.filter(block => block.kind !== 'thinking'),
    }));
  }, [events, running, showThinkingProcess]);
  const subagents = useMemo(() => groupSubagents(events), [events]);
  const internalReferenceIds = useMemo(() => events.flatMap(event => [
    ...payloadList<{ id?: string }>(event.payload, 'facts'),
    ...payloadList<{ id?: string }>(event.payload, 'evidence'),
  ]).map(item => item.id).filter((id): id is string => typeof id === 'string' && /^[FE]\d+$/.test(id)), [events]);
  const samples = useMemo(() => {
    const usageEvents = events.filter((event) => event.kind === 'usage');
    const usageSamples = usageEvents.map(usageFrom);
    // 压缩条目比最后一次采样更新时，用压缩后的保留量覆盖投影值，让圆环即时回弹；
    // 该条目已持久化在时间线里，刷新后依然正确。
    const lastUsageSeq = usageEvents.length > 0 ? usageEvents[usageEvents.length - 1].seq : -1;
    const compactionEvents = events
      .filter((event) => event.kind === 'compaction' && event.seq > lastUsageSeq);
    const compaction = compactionEvents.length > 0 ? compactionEvents[compactionEvents.length - 1] : undefined;
    const keptTokens = compaction && typeof compaction.payload.keptTokens === 'number'
      ? (compaction.payload.keptTokens as number)
      : undefined;
    if (keptTokens === undefined || usageSamples.length === 0) return usageSamples;
    const last = usageSamples[usageSamples.length - 1];
    return [...usageSamples.slice(0, -1), { ...last, projectedTokens: keptTokens }];
  }, [events]);

  // 定位按块锚点走：锚点由块在会话里的位置决定，实时合成 seq 换成数据库 seq 也不变。
  const locate = (anchor: string) => {
    const found = findBlock(turns, anchor);
    if (found) setForcedOpen((current) => (current.includes(found.turn.turn) ? current : [...current, found.turn.turn]));
    setHighlight(anchor);
    setProcessOpen(false);
    window.setTimeout(() => setHighlight(null), 1500);
  };

  useEffect(() => {
    if (!highlight) return;
    const frame = window.requestAnimationFrame(() => {
      const root = scrollRef.current;
      const target = root?.querySelector<HTMLElement>(`[data-anchor="${CSS.escape(highlight)}"]`);
      if (!root || !target) return;
      root.scrollTo({ top: target.offsetTop - root.offsetTop - 24, behavior: 'smooth' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [highlight]);

  const inspect = (block: ConversationBlock, trigger: HTMLButtonElement) => {
    detailTrigger.current = trigger;
    setSelectedAnchor(block.anchor);
    setProcessOpen(true);
  };
  const selectedBlock = selectedAnchor ? findBlock(turns, selectedAnchor)?.block : undefined;
  const renderBlock = (block: ConversationBlock) => {
    switch (block.kind) {
      case 'user_message': {
        const attached = block.event ? payloadList<AttachmentSummary>(block.event.payload, 'attachments') : undefined;
        const reply = block.event?.payload.questionReply;
        const questionText = reply && typeof reply === 'object' && 'questionText' in reply && typeof reply.questionText === 'string' ? reply.questionText : '';
        return <div className="space-y-1">
          {questionText ? <p className="text-right text-xs text-slate-500">{questionText}</p> : null}
          <VisitorBubble text={block.text ?? ''} attachments={attached} createdAt={block.createdAt} />
        </div>;
      }
      case 'question':
        if (block.event?.seq === pendingQuestion?.seq) return <p className="px-2 text-xs text-slate-500">{t('chat.timelineKind.question')}</p>;
        return <ProcessBlockLine block={block} />;
      case 'thinking':
        return <ThinkingLine text={block.thinkingText ?? ''} streaming={block.status === 'running'} />;
      case 'tool_call':
        return <ToolLine block={block} onInspect={inspect} />;
      case 'compaction':
        return <CompactionBlockLine block={block} />;
      case 'advisor_message':
        return <AdvisorBubble text={block.text ?? ''} internalIds={internalReferenceIds} />;
      case 'proposal':
      case 'proposal_resolved':
      case 'error':
        return block.event ? <EventItem event={block.event} proposalGate={proposalGate} /> : null;
      default:
        return GENERIC_BLOCK_KINDS.has(block.kind) ? <ProcessBlockLine block={block} /> : null;
    }
  };

  return (
    <div className="flex h-full min-h-0 w-full overflow-hidden">
      <div className="flex min-w-0 min-h-0 flex-1 flex-col overflow-hidden">
        <header
          className={cn(
            'flex min-h-16 shrink-0 items-center justify-between gap-3 bg-white pl-14 lg:pl-6 transition-all duration-200',
            processOpen ? 'pr-6' : 'pr-24 sm:pr-28',
          )}
        >
          <h1 className="min-w-0 truncate text-[14px] font-medium text-slate-900">
            {session?.title || t('chat.untitled')}
          </h1>
          <ProcessDrawerTrigger
            triggerRef={triggerRef}
            open={processOpen}
            onClick={() => {
              setProcessOpen((prev) => !prev);
              if (processOpen) setSelectedAnchor(null);
            }}
          />
        </header>

        <div
          ref={scrollRef}
          data-conversation-scroll
          onClickCapture={(event) => {
            const button = (event.target as HTMLElement).closest('button');
            const root = scrollRef.current;
            if (!button || !root) return;
            const before = button.getBoundingClientRect().top;
            window.requestAnimationFrame(() => {
              if (!button.isConnected) return;
              root.scrollTop += button.getBoundingClientRect().top - before;
              readingAnchor.current = captureConversationAnchor(root);
              followRef.current = root.scrollHeight - root.scrollTop - root.clientHeight < 100;
            });
          }}
          onScroll={(event) => {
            const node = event.currentTarget;
            followRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 100;
            readingAnchor.current = captureConversationAnchor(node);
          }}
          className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden bg-white px-4 pb-8 pt-6 sm:px-10"
        >
          <div className="mx-auto max-w-3xl space-y-10">
            {events.length === 0 && !streamingText && !running ? (
              <p className="pt-16 text-center text-[13px] text-slate-400">{t('chat.emptyHint')}</p>
            ) : null}

            {turns.length === 0 && running && !pendingProposal && !awaitingVisitor && (!showThinkingProcess || thinking.status === 'idle') && !streamingText ? (
              <WaitingLine />
            ) : null}

            {turns.map((turn, index) => {
              const last = index === turns.length - 1;
              const hasActiveProcess = turn.process.some(
                (block) => block.status === 'running' || block.tool?.status === 'running',
              );
              return (
                <div key={turn.turn} className="space-y-6">
                  {turn.user ? (
                    <BlockFrame block={turn.user} highlight={highlight}>
                      {renderBlock(turn.user)}
                    </BlockFrame>
                  ) : null}
                  <TurnGroup
                    turn={turn}
                    forcedOpen={forcedOpen.includes(turn.turn)}
                    highlight={highlight}
                    activityEnabled={last && running && !pendingProposal && !awaitingVisitor && (hasOrderedParts || thinking.status !== 'thinking')}
                    renderBlock={renderBlock}
                  />
                  {last && running && !pendingProposal && !awaitingVisitor && (!showThinkingProcess || thinking.status === 'idle') && !streamingText && !hasActiveProcess && !turn.finalText ? (
                    <WaitingLine />
                  ) : null}
                  {showThinkingProcess && !hasOrderedParts && last && running && thinking.status !== 'idle' ? (
                    <div data-anchor="live-thinking">
                      <ThinkingLine text={thinking.text} streaming={thinking.status === 'thinking' && !pendingProposal && !awaitingVisitor} />
                    </div>
                  ) : null}
                  {!hasOrderedParts && last && streamingText ? (
                    <div data-anchor="live-text">
                      <AdvisorBubble text={streamingText} internalIds={internalReferenceIds} />
                    </div>
                  ) : null}
                  {(turn.ordered ? [] : turn.outside).map((block) => (
                    <BlockFrame key={block.anchor} block={block} highlight={highlight}>
                      {renderBlock(block)}
                    </BlockFrame>
                  ))}
                </div>
              );
            })}

            {agentUnavailable ? (
              <div className="space-y-3 rounded-xl bg-slate-50 p-4 text-sm">
                <p>{t('chat.agentUnavailable')}</p>
                <div className="flex gap-4">
                  <button type="button" disabled={running} className="text-indigo-600" onClick={() => void retry(true)}>{t('chat.continueBasic')}</button>
                  <ReplacementAgentPicker disabled={running} onSelect={id => void retry(false, id)} />
                </div>
              </div>
            ) : null}
            {runError ? <div className="space-y-2"><ErrorCard text={friendlyRunError(runError, t)} /><button type="button" disabled={running} className="text-sm text-indigo-600" onClick={() => void retry(false, agentOverride)}>{t('chat.retryMessage')}</button></div> : null}
            {commandError ? <ErrorCard text={commandError} /> : null}
          </div>
        </div>

        {pendingQuestionReply && !pendingQuestion && !runError && !agentUnavailable ? <div className="flex shrink-0 items-center justify-between gap-3 px-4 pb-2">
          <p className="text-sm text-slate-500">{t('questionCard.savedAnswer')}</p>
          <Button size="sm" disabled={running || changingModel} onClick={() => {
            setCommandError(null);
            void continueQuestion(deepThinking, selectedAgentId, reasoningEffort).catch(() => setCommandError(t('questionCard.continueError')));
          }}>{t('questionCard.continue')}</Button>
        </div> : null}
        {pendingInterrupts.length ? (
          <ToolConfirmationBar
            interrupts={pendingInterrupts}
            busy={running}
            onResolve={(responses) => void resolveInterrupts(responses)}
          />
        ) : null}
        <ComposerDock
          inputReplacement={pendingQuestion ? <QuestionCard key={`${sessionId}:${pendingQuestion.questionId}`} question={pendingQuestion}
              busy={running || questionBusy || changingModel || pendingInterrupts.length > 0}
              onStop={running ? stop : undefined}
              error={questionError ? t(questionErrorKey(questionError)) : undefined}
              onAnswer={answer => answerQuestion(pendingQuestion, answer, deepThinking, undefined, selectedAgentId, reasoningEffort)} /> : undefined}
          agentPicker={{ value: selectedAgentId, onChange: setAgentOverride, running: running && agentOverride !== undefined,
            activeAgentId: runAgentId ?? session?.agentId ?? null,
            activeAgentName: (runAgentId === session?.agentId || !runAgentId) ? session?.agentName : undefined }}
          draft={draft}
          setDraft={setDraft}
          running={running}
          canSend={canSend}
          onSend={submit}
          onStop={stop}
          queuedMessages={queuedMessages}
          onInterruptAndSend={interruptAndSend}
          onCancelQueuedMessage={cancelQueuedMessage}
          turns={turns.length}
          samples={samples}
          toolCallCount={events.filter((event) => event.kind === 'tool_call').length}
          thinking={deepThinking}
          onToggleThinking={handleToggleThinking}
          reasoningEffort={reasoningEffort}
          onSelectReasoningEffort={handleSelectEffort}
          attachments={attachments}
          uploading={uploading}
          uploadError={uploadError}
          fileInputRef={fileInputRef}
          onPickFiles={openFilePicker}
          onFilesSelected={(files) => void addFiles(files)}
          onRemoveAttachment={removeAttachment}
          onPaste={handlePaste}
          onDrop={handleDrop}
          sessionId={sessionId}
          onCompacted={() => queryClient.invalidateQueries({ queryKey: advisorKeys.session(sessionId) })}
          onClearSession={handleClearContext}
        />
      </div>

      {processOpen ? (
        <ProcessSidePanel
          sessionId={sessionId}
          selectedBlock={selectedBlock}
          onBack={() => setSelectedAnchor(null)}
          onClose={() => {
            setProcessOpen(false);
            setSelectedAnchor(null);
          }}
          onCloseAutoFocus={(event) => {
            if (detailTrigger.current?.isConnected) {
              event.preventDefault();
              detailTrigger.current.focus();
            } else if (triggerRef.current?.isConnected) {
              triggerRef.current.focus();
            }
            detailTrigger.current = null;
          }}
          turns={turns}
          subagents={subagents}
          onLocate={locate}
        />
      ) : null}
    </div>
  );
}
