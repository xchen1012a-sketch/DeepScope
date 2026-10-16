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

import { useCallback, useEffect, useMemo, useRef, useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  advisorKeys,
  cacheAttachmentPreview,
  getSessionDetail,
  interruptSession,
  payloadList,
  payloadText,
  postSessionMessage,
  type AttachmentSummary,
  type EventEntry,
  type FactItem,
  type PostMessageInput,
  type SubagentRunInput,
  type ToolCallInput,
  type UploadedAttachment,
  type UsageInput,
  type AguiResumeInput,
  type AguiInterrupt,
  type PendingQuestion,
  type QuestionAnswer,
  type QuestionReplyInput,
  type SessionDetail,
} from '@/api/advisor';
import { resolvePendingQuestion, questionPrompt, unfinishedQuestionReply } from './model/questions';
import { routeSession } from '@/api/agent';
import { updatePresentationParts, settlePresentationParts } from './model/presentationParts';
import type { ConversationPart, RunPresentation } from '@/api/advisor';
import { runAdvisorStream, randomId, type AguiStreamSignal, type ReasoningEffort } from './aguiClient';
import { useSessionList } from './useSessionList';
import { foldToolCall, foldUsage, type UsageSample } from './conversationModel';
import {
  applySubagentSignal,
  EMPTY_SUBAGENT_ACCUMULATOR,
  registerSubagentDispatch,
  subagentStepsFromReplay,
  toSubagentRuns,
  type SubagentAccumulator,
  type SubagentStep,
} from './subagentSteps';

export type ConversationEvent = EventEntry;

export interface SendOptions {
  /** Clear the input only after persistence or acceptance into the active run's queue. */
  onAccepted?: () => void;
  questionReply?: QuestionReplyInput;
  /** Release the answer form after persistence, while the model continues in this run. */
  onQuestionSaved?: () => void;
  resume?: AguiResumeInput[];
  resumeAgentId?: string;
  /** 是否开启深度思考（默认关闭）。 */
  thinking?: boolean;
  /** 推理程度档位（仅深度思考开启时传给后端）。 */
  reasoningEffort?: ReasoningEffort;
  /** 本轮附带的已上传附件。 */
  attachments?: UploadedAttachment[];
  allowBasic?: boolean;
  selectedAgentId?: string | null;
  retryEntry?: ConversationEvent;
}

/**
 * 组装发给智能体的完整输入：访客正文 + 附件解析内容（提取文本、压缩包目录或图片工作区路径）。
 */
export function buildAgentPrompt(text: string, attachments?: UploadedAttachment[]): string {
  const trimmed = text.trim();
  const blocks = (attachments ?? [])
    .map((item) => item.extractedText?.trim() || `[附件: ${item.name}]`)
    .filter(Boolean);
  if (blocks.length === 0) return trimmed;
  if (!trimmed) return blocks.join('\n\n');
  return `${trimmed}\n\n${blocks.join('\n\n')}`;
}

/** 提取用于落库与时间线回放展示的附件摘要。 */
export function toAttachmentSummaries(attachments?: UploadedAttachment[]): AttachmentSummary[] | undefined {
  if (!attachments || attachments.length === 0) return undefined;
  return attachments.map(({ id, name, size, kind, mimeType, dataUrl }) => {
    if (dataUrl) cacheAttachmentPreview(id, dataUrl);
    return { id, name, size, kind, mimeType };
  });
}

export interface RouteSnapshot {
  mode: string;
  skillName: string;
}

/**
 * 把 CUSTOM advisor_event 的 value（payload 平铺 + kind/seq/turn）归一成时间线条目。
 * 没有 kind 的 value 不是过程事件，返回 null；没有 seq 的（落库失败）用 fallbackSeq 兜底，
 * 取值远大于数据库 seq，保证排序时落在末尾且不会与回放条目去重冲突。
 */
export function advisorEventToEntry(
  value: Record<string, unknown>,
  fallbackSeq: number,
): ConversationEvent | null {
  const kind = typeof value.kind === 'string' ? value.kind : null;
  if (!kind) return null;
  const payload: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (key !== 'kind' && key !== 'seq' && key !== 'turn') payload[key] = entry;
  }
  return {
    seq: typeof value.seq === 'number' ? value.seq : fallbackSeq,
    turn: typeof value.turn === 'number' ? value.turn : 0,
    kind,
    payload,
    createdAt: Date.now(),
  };
}

/**
 * 回放条目与实时条目按 seq 合并去重：一轮结束后 invalidate 触发的 detail 重取
 * 会带回同样的 seq，靠这里避免同一条事件渲染两次。
 */
export function mergeTimeline(replayed: ConversationEvent[], live: ConversationEvent[]): ConversationEvent[] {
  const bySeq = new Map<number, ConversationEvent>();
  for (const event of [...replayed, ...live]) {
    if (!bySeq.has(event.seq)) bySeq.set(event.seq, event);
  }
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}

/** 最后一次路由结果：用于模式横幅与 advisor 消息落库时的 mode/domainName。 */
export function latestRoute(events: ConversationEvent[]): RouteSnapshot | null {
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const event = events[i];
    if (event.kind !== 'route') continue;
    return {
      mode: payloadText(event.payload, 'mode'),
      skillName: payloadText(event.payload, 'skillName'),
    };
  }
  return null;
}

/** 会话是否停在追问上等待访客补充信息。 */
export function lastIsQuestion(events: ConversationEvent[]): boolean {
  return events.length > 0 && events[events.length - 1].kind === 'question';
}

/* -------------------------------------------------------------- 提案（F08 挂起门） */

/** proposal_resolved 的裁决结果；approved 时 file 为落盘的定义文件路径。 */
export interface ProposalResolution {
  approved: boolean;
  name: string;
  file?: string;
}

/**
 * 把 proposal 与其后继的 proposal_resolved 按名称先后配对（先提案先裁决），
 * 得到「提案 seq → 裁决结果」映射；没有配对的提案仍处于待裁决状态。
 */
export function matchProposalResolutions(events: ConversationEvent[]): Map<number, ProposalResolution> {
  const resolutions = new Map<number, ProposalResolution>();
  const pendingByName = new Map<string, number[]>();
  for (const event of events) {
    if (event.kind === 'proposal') {
      const name = payloadText(event.payload, 'name');
      const queue = pendingByName.get(name) ?? [];
      queue.push(event.seq);
      pendingByName.set(name, queue);
    } else if (event.kind === 'proposal_resolved') {
      const name = payloadText(event.payload, 'name');
      const queue = pendingByName.get(name);
      const seq = queue?.shift();
      if (seq === undefined) continue;
      const raw = event.payload.approved;
      resolutions.set(seq, {
        approved: raw === true || raw === 'true',
        name,
        file: payloadText(event.payload, 'file') || undefined,
      });
    }
  }
  return resolutions;
}

/** 最近的未裁决提案事件（会话挂在提案上等待访客裁决时非空）。 */
export function latestPendingProposal(events: ConversationEvent[]): ConversationEvent | null {
  const resolved = matchProposalResolutions(events);
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const event = events[i];
    if (event.kind === 'proposal' && !resolved.has(event.seq)) return event;
  }
  return null;
}

/* -------------------------------------------------------------- 思考面板状态机 */

/** 思考面板状态：idle 无思考 / thinking 思考中（自动展开）/ thought 已思考（自动折叠，可展开）。 */
export type ThinkingStatus = 'idle' | 'thinking' | 'thought';

export interface ThinkingState {
  status: ThinkingStatus;
  text: string;
}

export const INITIAL_THINKING: ThinkingState = { status: 'idle', text: '' };

/**
 * 思考面板驱动（纯函数）：REASONING_* 信号驱动起止与文本累积；正文第一个增量
 * 到达即视为思考结束（自动折叠的触发点）。思考文本随参谋回复的 reasoning 字段
 * 一起落库，回放时以默认折叠的思考卡片重新出现。
 */
export function nextThinkingState(state: ThinkingState, signal: AguiStreamSignal): ThinkingState {
  switch (signal.kind) {
    case 'reasoning-start':
      return state;
    case 'reasoning-delta':
      return signal.delta ? { status: 'thinking', text: state.text + signal.delta } : state;
    case 'reasoning-end':
      return state.text.trim() ? { status: 'thought', text: state.text } : INITIAL_THINKING;
    case 'text-delta':
      return state.status === 'thinking' ? { ...state, status: 'thought' } : state;
    default:
      return state;
  }
}

/* ---------------------------------------------------------------- 子智能体分组 */

/** 子智能体卡片状态：running 调查中；其余为落定后的终态。 */
export type SubagentStatus = 'running' | 'done' | 'failed' | 'timeout' | 'empty';

export interface SubagentRecord {
  /** 任务 id：dispatch 的 subtaskId，与 facts/settled 的 subagentId 同值。 */
  id: string;
  name: string;
  task: string;
  status: SubagentStatus;
  /** 派发写入记录的时间；老数据没有这个时间时不显示耗时，也不显示成 0 秒。 */
  startedAt?: number;
  /** 落定写入记录的时间；进行中没有。 */
  endedAt?: number;
  factCount: number;
  /** 回流的事实原文，点开这个子智能体后按记录回放。 */
  facts: FactItem[];
  summary: string;
  /** 回放出的该子智能体自身过程，按 order 递增；老数据没有记录时是空数组。 */
  steps: SubagentStep[];
}

const SETTLED_STATUSES: SubagentStatus[] = ['done', 'failed', 'timeout', 'empty'];

/**
 * 按任务 id 把 dispatch / subagent_facts / subagent_settled / subagent_step 事件分组为
 * 子智能体卡片：派发登记名称与目标，事实回流累加条数，落定更新终态，自身过程按序挂回。
 * 指向不存在派发的步骤被忽略，不会凭空造出卡片。
 */
export function groupSubagents(events: ConversationEvent[]): SubagentRecord[] {
  const stepsByTask = subagentStepsFromReplay(events);
  const byId = new Map<string, SubagentRecord>();
  const ensure = (id: string): SubagentRecord => {
    const existing = byId.get(id);
    if (existing) return existing;
    const created: SubagentRecord = {
      id,
      name: id,
      task: '',
      status: 'running',
      factCount: 0,
      facts: [],
      summary: '',
      steps: stepsByTask.get(id) ?? [],
    };
    byId.set(id, created);
    return created;
  };
  for (const event of events) {
    if (event.kind === 'dispatch') {
      const id = payloadText(event.payload, 'subtaskId');
      if (!id) continue;
      const record = ensure(id);
      const spec = (event.payload.spec ?? {}) as Record<string, unknown>;
      record.name = payloadText(event.payload, 'name') || record.name;
      record.task = payloadText(spec, 'task') || record.task;
      // 耗时的起算点是这次派发写入记录的时间，不是子智能体首次说话的时间。
      record.startedAt = record.startedAt ?? event.createdAt;
    } else if (event.kind === 'subagent_facts') {
      const id = payloadText(event.payload, 'subagentId');
      if (!id) continue;
      const record = ensure(id);
      const facts = payloadList<FactItem>(event.payload, 'facts');
      record.factCount += facts.length;
      record.facts = [...record.facts, ...facts];
    } else if (event.kind === 'subagent_settled') {
      const id = payloadText(event.payload, 'subagentId');
      if (!id) continue;
      const record = ensure(id);
      const status = payloadText(event.payload, 'status') as SubagentStatus;
      record.status = SETTLED_STATUSES.includes(status) ? status : 'failed';
      record.summary = payloadText(event.payload, 'summary') || record.summary;
      record.endedAt = event.createdAt;
    }
  }
  return [...byId.values()];
}

/* ------------------------------------------- 进行中运行 store（按 sessionId 键控） */

/**
 * 一轮进行中运行的实时状态：流式思考、流式正文、实时时间线、运行标志与错误。
 * 挂在模块级 Map 而不是组件实例上：思考流期间切换会话导致 hook 卸载/重建时，
 * 后端运行不中断，重新挂载即接管同一份累积内容并继续实时增长；发送闭包只写
 * store、不碰 setState，已卸载组件的 setter 永不会被调用，严格模式双挂载下
 * 也不会出现双份状态。
 */
export interface QueuedMessage {
  id: string;
  text: string;
  options?: SendOptions;
  createdAt: number;
}

export interface RunState {
  running: boolean;
  /** The server owns this run after its browser stream disconnects. */
  detached?: boolean;
  detachedAt?: number;
  /** 当前运行执行实例的唯一标识（防止中间 step 更新 startedAt 导致 finally 判重失效）。 */
  runInstanceId?: string;
  parts?: ConversationPart[];
  presentation?: RunPresentation;
  outcome?: RunPresentation['outcome'];
  runId?: string;
  agentId?: string;
  pendingInterrupts?: AguiInterrupt[];
  turn?: number;
  thinkingEnabled?: boolean;
  /** 本轮选择的推理程度档位（恢复中断运行时沿用）。 */
  reasoningEffort?: ReasoningEffort;
  thinking: ThinkingState;
  streamingText: string;
  liveEvents: ConversationEvent[];
  runError: string | null;
  agentUnavailable?: boolean;
  retryInput?: { text: string; options?: SendOptions; visitorEntry?: ConversationEvent };
  /** 本轮开始时刻（用于首 token 采样）。 */
  startedAt: number;
  /** 本轮第一个输出 token 到达的时刻；没有采样就是 null。 */
  firstTokenAt: number | null;
  /** 子智能体自身过程的累积器；随本轮 advisor_message 一起提交。 */
  subagents: SubagentAccumulator;
  /** 排队中的消息列表（会话运行中发送将进入排队）。 */
  queuedMessages?: QueuedMessage[];
  queuePaused?: boolean;
}

/** 无进行中运行时的稳定空快照（useSyncExternalStore 的快照必须引用稳定）。 */
export const IDLE_RUN: RunState = {
  running: false,
  thinking: INITIAL_THINKING,
  streamingText: '',
  liveEvents: [],
  runError: null,
  startedAt: 0,
  firstTokenAt: null,
  subagents: EMPTY_SUBAGENT_ACCUMULATOR,
  queuedMessages: [],
};

/**
 * 一轮结束时决定要不要落库：正文/思考/工具调用/用量至少有一项有内容才写。
 * 只产生思考或只调了工具的回合同样要落库，否则刷新后过程记录就丢了。
 */
export function hasRecordableContent(state: {
  text: string;
  reasoning: string;
  toolCalls: unknown[];
  usage: unknown[];
}): boolean {
  return (
    state.text.trim().length > 0 ||
    state.reasoning.trim().length > 0 ||
    state.toolCalls.length > 0 ||
    state.usage.length > 0
  );
}

const runStates = new Map<string, RunState>();
const runControllers = new Map<string, AbortController>();
const runInterrupts = new Map<string, Promise<unknown>>();
const runCompletions = new Map<string, Promise<void>>();

function interruptCurrentRun(sessionId: string): Promise<unknown> {
  const state = getRunState(sessionId);
  const controller = runControllers.get(sessionId);
  if (!state.runInstanceId) return Promise.resolve();
  let pending = runInterrupts.get(state.runInstanceId);
  if (!pending && controller) {
    pending = interruptSession(sessionId, state.runId);
    runInterrupts.set(state.runInstanceId, pending);
    void pending.catch(() => {});
  }
  controller?.abort();
  return pending ?? Promise.resolve();
}
const runListeners = new Map<string, Set<() => void>>();

/** 订阅某个会话的运行状态变化；退订后订阅集为空时自动清理监听容器。 */
export function subscribeRun(sessionId: string, listener: () => void): () => void {
  const listeners = runListeners.get(sessionId) ?? new Set();
  listeners.add(listener);
  runListeners.set(sessionId, listeners);
  return () => {
    const current = runListeners.get(sessionId);
    if (!current) return;
    current.delete(listener);
    if (current.size === 0) runListeners.delete(sessionId);
  };
}

/** 当前快照：该会话没有进行中运行时返回稳定的 IDLE_RUN。 */
export function getRunState(sessionId: string): RunState {
  return runStates.get(sessionId) ?? IDLE_RUN;
}

function publishRunState(sessionId: string, next: RunState): void {
  if (getRunState(sessionId) === next) return;
  runStates.set(sessionId, next);
  runListeners.get(sessionId)?.forEach((listener) => listener());
}

/** 用纯函数 updater 推进某个会话的运行状态并通知订阅者（更新必须产生新对象）。 */
export function updateRunState(sessionId: string, updater: (state: RunState) => RunState): void {
  publishRunState(sessionId, updater(getRunState(sessionId)));
}

/** 运行收尾清理：移除该会话的 store 条目，快照回落到 IDLE_RUN。 */
export function endRun(sessionId: string): void {
  const current = runStates.get(sessionId);
  if (current?.queuedMessages && current.queuedMessages.length > 0) {
    runStates.set(sessionId, { ...IDLE_RUN, queuedMessages: current.queuedMessages });
    runListeners.get(sessionId)?.forEach((listener) => listener());
    return;
  }
  if (!runStates.delete(sessionId)) return;
  runListeners.get(sessionId)?.forEach((listener) => listener());
}

/** 新一轮开始：整体重置为运行中，保留排队队列，清掉上一轮（含失败残影）的所有状态。 */
export function startRunState(
  prevQueued?: QueuedMessage[],
  runInstanceId?: string,
  startedAt = Date.now(),
): RunState {
  return { ...IDLE_RUN, running: true, runInstanceId, startedAt, queuedMessages: prevQueued ?? [] };
}

/** 访客消息落库后作为本轮第一条实时条目（覆盖上一轮残留）。 */
export function withVisitorEntry(state: RunState, entry: ConversationEvent): RunState {
  return { ...state, turn: entry.turn, liveEvents: [entry] };
}

/** 过程事件并入实时时间线（按 seq 去重排序）。 */
export function appendLiveEvent(state: RunState, entry: ConversationEvent): RunState {
  return { ...state, liveEvents: mergeTimeline(state.liveEvents, [entry]) };
}

/** 流信号推进运行状态：思考走状态机，正文逐字累积，工具调用和用量并入实时条目。 */
export function applyRunSignal(state: RunState, signal: AguiStreamSignal): RunState {
  if (state.thinkingEnabled === false && (signal.kind === 'reasoning-start' || signal.kind === 'reasoning-delta' || signal.kind === 'reasoning-end')) return state;
  if (state.runId && signal.runId && state.runId !== signal.runId) return state;
  if (signal.kind === 'run-started') return { ...state, runId: signal.runId, outcome: 'unknown', presentation: undefined };
  if (signal.kind === 'presentation') {
    if (state.runId && signal.runId !== state.runId) return state;
    return { ...state, runId: signal.runId, presentation: signal.presentation, ...(signal.presentation.outcome === 'limited' ? { outcome: 'limited' as const } : {}) };
  }
  if (signal.kind === 'run-finished') return { ...state, pendingInterrupts: signal.interrupts ?? [], outcome: state.outcome === 'failed' ? 'failed' : signal.interrupted ? 'awaiting_action' : 'completed' };
  if (signal.kind === 'stream-error') return { ...state, outcome: 'failed', runError: signal.message };
  const updated = applyLegacyRunSignal(state, signal);
  const parts = updatePresentationParts(state.parts ?? [], signal, updated.liveEvents);
  return parts === state.parts || (!state.parts && parts.length === 0) ? updated : { ...updated, parts };
}

function applyLegacyRunSignal(state: RunState, signal: AguiStreamSignal): RunState {
  if (signal.kind === 'tool-start' || signal.kind === 'tool-args' || signal.kind === 'tool-result') {
    return applyToolSignal(state, signal);
  }
  if (signal.kind === 'token-usage') return applyUsageSignal(state, signal.sample);
  if (signal.kind.startsWith('subagent-')) {
    return {
      ...state,
      subagents: applySubagentSignal(
        state.subagents,
        signal as Extract<AguiStreamSignal, { kind: `subagent-${string}` }>,
      ),
    };
  }
  const thinking = nextThinkingState(state.thinking, signal);
  // 首个输出 token（思考或正文）到达的时刻只记一次，供首 token 耗时采样。
  const measured =
    state.firstTokenAt === null && (signal.kind === 'text-delta' || signal.kind === 'reasoning-delta')
      ? { ...state, firstTokenAt: Date.now() }
      : state;
  if (signal.kind === 'text-delta') {
    return { ...measured, thinking, streamingText: state.streamingText + signal.delta };
  }
  return thinking === state.thinking && measured === state ? state : { ...measured, thinking };
}

function applyToolSignal(
  state: RunState,
  signal: Extract<AguiStreamSignal, { kind: 'tool-start' | 'tool-args' | 'tool-result' }>,
): RunState {
  const index = state.liveEvents.findIndex(
    (event) => event.kind === 'tool_call' && payloadText(event.payload, 'id') === signal.id,
  );
  const current = index >= 0 ? toolCallFrom(state.liveEvents[index]) : [];
  const at = Date.now();
  // 工具调用步若未先输出思考/正文，则 TOOL_CALL_START 即该步模型的首个输出信号；
  // 工具执行完毕（tool-result）且上一步首 token 已消费时，推进下一步的起算时间。
  const timing =
    signal.kind === 'tool-start' && state.firstTokenAt === null
      ? { firstTokenAt: at }
      : signal.kind === 'tool-result' && state.firstTokenAt === null
        ? { startedAt: at }
        : {};
  const folded = foldToolCall(current, {
    id: signal.id,
    name: signal.kind === 'tool-start' ? signal.name : undefined,
    argsDelta: signal.kind === 'tool-args' ? signal.delta : undefined,
    result: signal.kind === 'tool-result' ? signal.content : undefined,
    ...(signal.kind === 'tool-result' && signal.state !== undefined ? { failed: signal.state === 'failed' } : {}),
    // 起始时间只在首次信号（TOOL_CALL_START）时取当下；后续信号不覆盖。
    ...(signal.kind === 'tool-start' ? { startedAt: at } : {}),
    ...(signal.kind === 'tool-result' ? { endedAt: at } : {}),
  });
  const call = folded[folded.length - 1];
  const payload: Record<string, unknown> = {
    id: call.id,
    name: call.name,
    args: call.args,
    status: call.status,
    ...(call.result !== undefined ? { result: call.result } : {}),
    ...(call.startedAt !== null ? { startedAt: call.startedAt } : {}),
    ...(call.endedAt !== undefined ? { endedAt: call.endedAt } : {}),
  };
  if (index >= 0) {
    const liveEvents = state.liveEvents.map((event, position) =>
      position === index ? { ...event, payload } : event,
    );
    return { ...state, ...timing, liveEvents };
  }
  return appendLiveEvent(
    { ...state, ...timing },
    {
      seq: nextLiveSeq(state),
      turn: currentTurn(state),
      kind: 'tool_call',
      payload,
      createdAt: call.startedAt ?? at,
    },
  );
}

function toolCallFrom(event: ConversationEvent | undefined) {
  if (!event) return [];
  const status = payloadText(event.payload, 'status');
  return [
    {
      id: payloadText(event.payload, 'id'),
      name: payloadText(event.payload, 'name'),
      args: payloadText(event.payload, 'args'),
      result: payloadText(event.payload, 'result') || undefined,
      status: status === 'failed' ? ('failed' as const) : status === 'done' ? ('done' as const) : ('running' as const),
      startedAt: typeof event.payload.startedAt === 'number' ? event.payload.startedAt : null,
      ...(typeof event.payload.endedAt === 'number' ? { endedAt: event.payload.endedAt } : {}),
    },
  ];
}

function applyUsageSignal(state: RunState, sample: UsageSample): RunState {
  const at = Date.now();
  const index = state.liveEvents.findIndex(
    (event) => event.kind === 'usage' && payloadText(event.payload, 'stepId') === sample.stepId,
  );
  const enriched = withSampledRates(state, sample);
  const nextStepTiming = { startedAt: at, firstTokenAt: null };
  if (index >= 0) {
    const previous = state.liveEvents[index];
    const [folded] = foldUsage([usageFrom(previous)], enriched);
    return {
      ...state,
      ...nextStepTiming,
      liveEvents: state.liveEvents.map((event, position) =>
        position === index ? { ...event, payload: { ...folded } } : event,
      ),
    };
  }
  return appendLiveEvent(
    { ...state, ...nextStepTiming },
    {
      seq: nextLiveSeq(state),
      turn: currentTurn(state),
      kind: 'usage',
      payload: { ...enriched },
      createdAt: at,
    },
  );
}

/**
 * 采样不足就不写这两项：`firstTokenMs` 只在首个输出 token 到达过时才有值，
 * `outputTokensPerSecond` 只在用时与输出 token 都拿得到时才有值；没有被采样的回合
 * 不会以 0 参与统计条的平均。
 */
function withSampledRates(state: RunState, sample: UsageSample): UsageSample {
  const measured: UsageSample = { ...sample };
  if (
    measured.firstTokenMs === undefined &&
    state.firstTokenAt !== null &&
    state.startedAt > 0 &&
    state.firstTokenAt >= state.startedAt
  ) {
    measured.firstTokenMs = state.firstTokenAt - state.startedAt;
  }
  if (measured.outputTokensPerSecond === undefined) {
    const time = measured.timeSeconds;
    if (time !== undefined && time > 0 && measured.output > 0) {
      measured.outputTokensPerSecond = Math.round((measured.output / time) * 10) / 10;
    }
  }
  return measured;
}

/** 唯一的用量解析器：四格 token、窗口、耗时与采样全走落库形状的读取；缺字段就留空。 */
export function usageFrom(event: ConversationEvent): UsageSample {
  return toUsageInput(event.payload);
}

function currentTurn(state: RunState): number {
  return state.turn ?? state.liveEvents.reduce((highest, event) => Math.max(highest, event.turn), 1);
}

function nextLiveSeq(state: RunState): number {
  return state.liveEvents.reduce((highest, event) => Math.max(highest, event.seq), SYNTHETIC_SEQ_BASE) + 1;
}

/**
 * 运行收尾（纯函数）：置非运行；回放里已有的实时条目按 seq 去掉；正文已落库
 * （或本来为空）则清空流式正文，落库失败时保留展示。
 */
export function finalizeRunState(state: RunState, replayedSeqs: Set<number>, persisted: boolean): RunState {
  return {
    ...state,
    running: false,
    liveEvents: state.liveEvents.filter((event) => {
      if (replayedSeqs.has(event.seq)) return false;
      return !(persisted && (event.kind === 'tool_call' || event.kind === 'usage'));
    }),
    streamingText: persisted || !state.streamingText.trim() ? '' : state.streamingText,
  };
}

const SYNTHETIC_SEQ_BASE = 1_000_000_000;

/** 没有过程记录时不写这个字段：老数据与没有派发的回合都不出现空壳数组。 */
function subagentRunsField(state: SubagentAccumulator): { subagentRuns?: SubagentRunInput[] } {
  const runs = toSubagentRuns(state);
  return runs.length > 0 ? { subagentRuns: runs } : {};
}

/**
 * 一轮结束时落库的参谋消息：正文、思考、工具调用、用量、子智能体过程。
 * 只产生过程、没有最终正文的回合同样要有这条记录，因此 `text` 允许为空串。
 */
function presentationForRun(state: RunState): RunPresentation | undefined {
  const runId = state.runId ?? state.parts?.[0]?.runId;
  if (!state.presentation && state.outcome === undefined && !state.runId) return undefined;
  const presentation = state.presentation ?? (runId ? { runId, finalMessageIds: [], outcome: state.outcome ?? 'unknown' } : undefined);
  if (!presentation) return undefined;
  if (state.outcome && state.outcome !== 'completed' && state.outcome !== 'limited' && (presentation.outcome === 'completed' || presentation.outcome === 'limited')) {
    const rest = { ...presentation };
    delete rest.finalStartedAt;
    return { ...rest, finalMessageIds: [], outcome: state.outcome };
  }
  return presentation;
}

export function toAdvisorMessageInput(
  state: RunState,
  route: RouteSnapshot | null,
): PostMessageInput {
  const toolCalls: ToolCallInput[] = state.liveEvents
    .filter((event) => event.kind === 'tool_call')
    .map((event) => toToolCallInput(event.payload));
  const usage: UsageInput[] = state.liveEvents
    .filter((event) => event.kind === 'usage')
    .map((event) => toUsageInput(event.payload));
  const presentation = presentationForRun(state);
  return {
    role: 'advisor',
    text: state.streamingText,
    ...(state.parts?.length ? { partsVersion: 1 as const, parts: settlePresentationParts(state.parts) } : {}),
    ...(presentation ? { presentationVersion: 1 as const, presentation } : {}),
    mode: route?.mode ?? '',
    domainName: route?.skillName ?? '',
    ...(state.thinking.text.trim() ? { reasoning: state.thinking.text } : {}),
    toolCalls,
    usage,
    ...subagentRunsField(state.subagents),
  };
}

/** 实时 tool_call 条目的载荷转成落库形状（字段逐个取出，缺失的可选字段不写）。 */
function toToolCallInput(payload: Record<string, unknown>): ToolCallInput {
  const status = payloadText(payload, 'status');
  return {
    id: payloadText(payload, 'id'),
    ...(payload.name !== undefined ? { name: payloadText(payload, 'name') } : {}),
    ...(payload.args !== undefined ? { args: payload.args } : {}),
    ...(payload.result !== undefined ? { result: payload.result } : {}),
    ...(status === 'running' || status === 'done' || status === 'failed' ? { status } : {}),
    ...(typeof payload.startedAt === 'number' ? { startedAt: payload.startedAt } : {}),
    ...(typeof payload.endedAt === 'number' ? { endedAt: payload.endedAt } : {}),
  };
}

/** 实时 usage 条目的载荷转成落库形状。 */
function toUsageInput(payload: Record<string, unknown>): UsageInput {
  const numberOf = (key: string) => (typeof payload[key] === 'number' ? (payload[key] as number) : 0);
  const optional = (key: string): Partial<UsageInput> =>
    typeof payload[key] === 'number' ? { [key]: payload[key] as number } : {};
  const optionalText = (key: string): Partial<UsageInput> =>
    typeof payload[key] === 'string' && payload[key] ? { [key]: payload[key] as string } : {};
  return {
    stepId: payloadText(payload, 'stepId'),
    uncachedInput: numberOf('uncachedInput'),
    cacheRead: numberOf('cacheRead'),
    cacheWrite: numberOf('cacheWrite'),
    output: numberOf('output'),
    projectedTokens: numberOf('projectedTokens'),
    timeSeconds: numberOf('timeSeconds'),
    ...optional('contextWindow'),
    ...optionalText('modelName'),
    ...optional('firstTokenMs'),
    ...optional('outputTokensPerSecond'),
  };
}

/**
 * 会话视图模型：detail 回放 + 运行 store 的实时状态合并出一份按 seq 排序的时间线，
 * 并提供 send()（落库访客消息 → 流式运行（思考随 reasoning 落库）→ 落库助手回复 →
 * 刷新缓存 → 清理 store 条目）。进行中运行的实时状态取自模块级 store，切换会话
 * 再切回时挂载即接管。
 */
export function useAdvisorConversation(sessionId: string) {
  const queryClient = useQueryClient();
  const summaries = useSessionList(false);
  const summary = summaries.data?.find(session => session.sessionId === sessionId);

  const subscribe = useCallback(
    (onStoreChange: () => void) => subscribeRun(sessionId, onStoreChange),
    [sessionId],
  );
  const getSnapshot = useCallback(() => getRunState(sessionId), [sessionId]);
  const run = useSyncExternalStore(subscribe, getSnapshot, () => IDLE_RUN);
  const detailQuery = useQuery({
    queryKey: advisorKeys.session(sessionId),
    queryFn: () => getSessionDetail(sessionId),
    refetchInterval: query => !run.running && (run.detached || query.state.data?.runtime?.phase === 'running'
      || query.state.data?.runtime?.phase === 'finalizing') ? 1000 : false,
    refetchOnReconnect: 'always',
    refetchOnWindowFocus: 'always',
  });
  const remoteRunning = detailQuery.data?.runtime?.phase === 'running' || detailQuery.data?.runtime?.phase === 'finalizing';
  const running = run.running || remoteRunning || Boolean(run.detached);

  const events = useMemo(() => {
    const merged = mergeTimeline(detailQuery.data?.entries ?? [], run.liveEvents);
    if (!run.parts?.length) return merged;
    const ids = new Set(run.parts.map(part => part.id));
    if (merged.some(event => event.kind === 'advisor_message' && Array.isArray(event.payload.parts)
      && event.payload.parts.some(part => part && typeof part === 'object' && 'id' in part && ids.has(String(part.id))))) return merged;
    return [...merged, { seq: SYNTHETIC_SEQ_BASE + 900_000, turn: currentTurn(run), kind: 'advisor_message',
      payload: { partsVersion: 1, parts: run.parts, ...(presentationForRun(run) ? { presentationVersion: 1, presentation: presentationForRun(run) } : {}) }, createdAt: run.startedAt }];
  }, [detailQuery.data, run]);

  const pendingQuestion = useMemo(() => resolvePendingQuestion(detailQuery.data, events), [events, detailQuery.data]);
  const awaitingVisitor = !running && Boolean(pendingQuestion);
  const recoveredReply = detailQuery.data?.pendingQuestionReply ?? unfinishedQuestionReply(events, detailQuery.data?.runtime);
  const pendingQuestionReply = !run.running && detailQuery.data?.runtime?.phase !== 'running'
    && detailQuery.data?.runtime?.phase !== 'finalizing' && detailQuery.data?.runtime?.phase !== 'awaiting_action'
    ? recoveredReply : null;
  const questionRequests = useRef(new Map<string, { signature: string; requestId: string }>());
  const pendingInterrupts = run.running ? [] : run.pendingInterrupts?.length ? run.pendingInterrupts
    : detailQuery.data?.runtime?.phase === 'awaiting_action' ? detailQuery.data.runtime.interrupts ?? [] : [];
  const pendingProposal = useMemo(
    () => (run.running ? null : latestPendingProposal(events)),
    [events, run.running],
  );

  const send = useCallback(
    async (text: string, options?: SendOptions) => {
      const trimmed = text.trim();
      const attachments = options?.attachments ?? [];
      const resuming = Boolean(options?.resume?.length);
      if (!trimmed && attachments.length === 0 && !resuming && !options?.questionReply && !options?.retryEntry) return;

      const currentRun = getRunState(sessionId);
      const latestDetail = queryClient.getQueryData<SessionDetail>(advisorKeys.session(sessionId)) ?? detailQuery.data;
      const currentRemoteRunning = latestDetail?.runtime?.phase === 'running' || latestDetail?.runtime?.phase === 'finalizing';
      const pending = resolvePendingQuestion(detailQuery.data, mergeTimeline(detailQuery.data?.entries ?? [], currentRun.liveEvents));
      let questionReply = options?.questionReply;
      if (!resuming && !options?.retryEntry && !questionReply && pending && trimmed) {
        const key = `${sessionId}:${pending.questionId}`;
        const previous = questionRequests.current.get(key);
        const signature = JSON.stringify({ selectedOptionIds: [], customText: trimmed, attachments: attachments.map(item => item.id) });
        const requestId = previous?.signature === signature ? previous.requestId : randomId();
        questionRequests.current.set(key, { signature, requestId });
        questionReply = { questionId: pending.questionId, requestId, selectedOptionIds: [], customText: trimmed };
      }
      if (questionReply && attachments.length) questionReply = { ...questionReply, attachmentContext: buildAgentPrompt('', attachments) };
      options = { ...options, ...(questionReply ? { questionReply } : {}) };
      const runtimeBusy = latestDetail?.runtime?.phase === 'running'
        || latestDetail?.runtime?.phase === 'finalizing'
        || latestDetail?.runtime?.phase === 'awaiting_action';
      if ((questionReply || options?.retryEntry?.payload.questionReply) && (currentRun.running || runtimeBusy)) {
        throw new Error('session_running');
      }
      if (currentRun.running || currentRun.detached || currentRemoteRunning || !resuming && (currentRun.outcome === 'awaiting_action' || latestDetail?.runtime?.phase === 'awaiting_action')) {
        const queuedItem: QueuedMessage = {
          id: randomId(),
          text,
          options: { ...options, onAccepted: undefined },
          createdAt: Date.now(),
        };
        updateRunState(sessionId, (state) => ({
          ...state,
          ...(!currentRun.running && currentRemoteRunning ? { detached: true, runId: latestDetail?.runtime?.runId } : {}),
          queuedMessages: [...(state.queuedMessages ?? []), queuedItem],
        }));
        options?.onAccepted?.();
        return;
      }

      const displayText = trimmed || attachments.map((item) => item.name).join('、');
      const attachmentSummaries = toAttachmentSummaries(attachments);
      let promptForAgent = buildAgentPrompt(trimmed, attachments);

      const controller = new AbortController();
      runControllers.set(sessionId, controller);
      const existingEntries = latestDetail?.entries ?? [];
      const latestTurn = Math.max(currentRun.turn ?? 0, existingEntries.reduce((max, item) => Math.max(max, item.turn), 0));
      const nextTurnNumber = resuming ? Math.max(1, latestTurn) : latestTurn + 1;
      const optimisticVisitor: ConversationEvent = {
        seq: SYNTHETIC_SEQ_BASE,
        turn: nextTurnNumber,
        kind: 'user_message',
        payload: {
          text: displayText,
          ...(attachmentSummaries ? { attachments: attachmentSummaries } : {}),
        },
        createdAt: Date.now(),
      };
      const runInstanceId = randomId();
      const runStartedAt = Date.now();
      const prevQueued = getRunState(sessionId).queuedMessages ?? [];
      updateRunState(sessionId, () => ({
        ...(resuming || questionReply && !options?.retryEntry ? { ...startRunState(prevQueued, runInstanceId, runStartedAt), turn: nextTurnNumber } : withVisitorEntry(startRunState(prevQueued, runInstanceId, runStartedAt), options?.retryEntry ?? optimisticVisitor)),
        ...(options?.thinking !== undefined ? { thinkingEnabled: options.thinking } : {}),
        ...(options?.reasoningEffort !== undefined ? { reasoningEffort: options.reasoningEffort } : {}),
        retryInput: { text, options, visitorEntry: options?.retryEntry },
        queuePaused: Boolean(resuming && options?.resume?.every(item => item.status === 'cancelled')),
      }));
      let completeRun!: () => void;
      runCompletions.set(runInstanceId, new Promise<void>(resolve => { completeRun = resolve; }));
      const isCurrent = () => getRunState(sessionId).runInstanceId === runInstanceId;
      const updateCurrent = (updater: (state: RunState) => RunState) =>
        updateRunState(sessionId, state => state.runInstanceId === runInstanceId ? updater(state) : state);
      let route: RouteSnapshot | null = latestRoute(existingEntries);
      let syntheticSeq = 1;
      let persisted = false;
      let visitorSaved = Boolean(options?.retryEntry);
      let streamAttempted = false;
      let submissionError: unknown;
      try {
        const visitorEntry = resuming ? null : options?.retryEntry ?? await postSessionMessage(sessionId, {
          role: 'visitor',
          text: displayText,
          ...(questionReply ? { questionReply } : {}),
          ...(attachmentSummaries ? { attachments: attachmentSummaries } : {}),
        });
        visitorSaved = Boolean(visitorEntry) || resuming;
        if (visitorSaved) options?.onAccepted?.();
        if (!isCurrent() || controller.signal.aborted) return;
        if (visitorEntry) updateCurrent((state) => ({
          ...withVisitorEntry(state, { ...visitorEntry, createdAt: Date.now() }),
          retryInput: { text, options, visitorEntry },
        }));
        if (visitorEntry?.payload.questionReply) {
          const reply = visitorEntry.payload.questionReply;
          promptForAgent = reply && typeof reply === 'object' && 'attachmentContext' in reply
            ? questionPrompt(visitorEntry) : buildAgentPrompt(questionPrompt(visitorEntry), attachments);
          options?.onQuestionSaved?.();
          if (visitorEntry.replayed && !options?.retryEntry) return;
        }
        const decision = resuming
          ? { agentId: options?.resumeAgentId ?? currentRun.agentId ?? detailQuery.data?.runtime?.agentId ?? detailQuery.data?.session.agentId, mode: 'resume', reasonCode: '' }
          : await routeSession(sessionId, visitorEntry?.payload.questionReply ? questionPrompt(visitorEntry, false) : displayText, options?.allowBasic ?? false, options?.selectedAgentId);
        if (!isCurrent() || controller.signal.aborted) return;
        if (decision.mode === 'unavailable') {
          updateCurrent(state => ({ ...state, agentUnavailable: true }));
          return;
        }
        if (!decision.agentId) throw new Error('No conversation runtime was selected');
        const runAgentId = decision.agentId;
        updateCurrent(state => ({ ...state, agentId: runAgentId }));
        if (decision.mode === 'basic') {
          updateCurrent(state => appendLiveEvent(state, {
            seq: SYNTHETIC_SEQ_BASE + 1, turn: visitorEntry?.turn ?? nextTurnNumber, kind: 'agent_route',
            payload: { mode: 'basic', reasonCode: decision.reasonCode }, createdAt: Date.now(),
          }));
          route = { mode: 'basic', skillName: '' };
        }
        updateCurrent((state) => ({ ...state, firstTokenAt: null }));
        const handleAdvisorEvent = (value: Record<string, unknown>, identity: { runId?: string } = {}) => {
              if (!isCurrent()) return;
              updateCurrent(state => applyRunSignal(state, { kind: 'advisor-event', value, ...identity }));
              syntheticSeq += 1;
              const entry = advisorEventToEntry(value, SYNTHETIC_SEQ_BASE + syntheticSeq);
              if (!entry) return;
              if (entry.kind === 'route') {
                route = { mode: payloadText(entry.payload, 'mode'), skillName: payloadText(entry.payload, 'skillName') };
              }
              updateCurrent((state) => {
                if (entry.kind === 'dispatch') {
                  return { ...state, subagents: registerSubagentDispatch(state.subagents, entry) };
                }
                if (entry.kind === 'compaction') {
                  const turn = entry.turn > 0 ? entry.turn : currentTurn(state);
                  const normalized = { ...entry, turn };
                  const withoutRunning = state.liveEvents.filter(
                    (item) => !(item.kind === 'compaction' && payloadText(item.payload, 'status') === 'running'),
                  );
                  return { ...state, liveEvents: mergeTimeline(withoutRunning, [normalized]) };
                }
                return appendLiveEvent(state, entry);
              });
        };
        streamAttempted = true;
        await runAdvisorStream(
          sessionId,
          runAgentId,
          promptForAgent,
          {
            onRunRequested: runId => updateCurrent(state => ({ ...state, runId })),
            onTextDelta: (delta) =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'text-delta', delta })),
            onReasoningStart: () =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'reasoning-start' })),
            onReasoningDelta: (delta) =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'reasoning-delta', delta })),
            onReasoningEnd: () =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'reasoning-end' })),
            onSignal: signal => {
              if (signal.kind === 'advisor-event') handleAdvisorEvent(signal.value, signal);
              else updateCurrent(state => applyRunSignal(state, signal));
            },
            onAdvisorEvent: handleAdvisorEvent,
            onToolStart: (id, name) =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'tool-start', id, name })),
            onToolArgs: (id, delta) =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'tool-args', id, delta })),
            onToolResult: (id, content) =>
              updateCurrent((state) => applyRunSignal(state, { kind: 'tool-result', id, content })),
            onTokenUsage: (signal) => updateCurrent((state) => applyRunSignal(state, signal)),
            onSubagentSignal: (signal) =>
              updateCurrent((state) => applyRunSignal(state, signal)),
          },
          { thinking: options?.thinking, reasoningEffort: options?.reasoningEffort, signal: controller.signal, ...(resuming ? { resume: options?.resume } : {}) },
        );
        if (!isCurrent()) return;
        const streamState = getRunState(sessionId);
        if (streamState.outcome === undefined || streamState.outcome === 'unknown') {
          updateCurrent(state => ({ ...state, outcome: 'unknown' }));
        }
        const finished = getRunState(sessionId);
        const advisorMessage = toAdvisorMessageInput(finished, route);
        if (
          hasRecordableContent({
            text: advisorMessage.text,
            reasoning: advisorMessage.reasoning ?? '',
            toolCalls: advisorMessage.toolCalls ?? [],
            usage: advisorMessage.usage ?? [],
          })
        ) {
          // 思考文本随参谋回复的 reasoning 字段一起落库（可选），回放时重新展示。
          // 正文可以为空：只有过程记录的回合同样是这一轮的记录。
          await postSessionMessage(sessionId, advisorMessage);
          persisted = true;
        }
      } catch (error) {
        if (questionReply && !visitorSaved) submissionError = error;
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          const message = error instanceof Error ? error.message : String(error);
          updateCurrent((state) => ({ ...state, runError: submissionError ? null : message, ...(state.outcome !== 'completed' && state.outcome !== 'limited' ? { outcome: 'failed' as const } : {}) }));
        }
      } finally {
        try {
          if (runControllers.get(sessionId) === controller) {
            runControllers.delete(sessionId);
          }
          if (isCurrent()) {
            try {
              await runInterrupts.get(runInstanceId);
            } catch {
              /* Keep local output if the server could not acknowledge persistence. */
            }
            const beforeReconcile = getRunState(sessionId);
            let serverActive = false;
            let recoveryUnknown = false;
            let reconciledDetail: SessionDetail | undefined;
            if (!controller.signal.aborted && streamAttempted && beforeReconcile.outcome !== 'completed'
              && beforeReconcile.outcome !== 'limited' && beforeReconcile.outcome !== 'awaiting_action') {
              try {
                const fresh = await queryClient.fetchQuery({ queryKey: advisorKeys.session(sessionId), queryFn: () => getSessionDetail(sessionId), staleTime: 0 });
                reconciledDetail = fresh;
                serverActive = (fresh.runtime?.phase === 'running' || fresh.runtime?.phase === 'finalizing')
                  && (!beforeReconcile.runId || !fresh.runtime.runId || fresh.runtime.runId === beforeReconcile.runId);
              } catch {
                recoveryUnknown = true;
              }
            }
            if (serverActive || recoveryUnknown) {
              updateCurrent(state => ({ ...state, running: false, detached: true, detachedAt: Date.now(),
                runId: reconciledDetail?.runtime?.runId ?? state.runId, runError: null, outcome: 'unknown' }));
            } else {
              updateCurrent((state) => ({ ...state, ...(controller.signal.aborted ? { outcome: 'cancelled' as const } : {}), ...(state.parts ? { parts: settlePresentationParts(state.parts, Boolean(state.runError) || controller.signal.aborted) } : {}) }));
              try {
                await queryClient.invalidateQueries({ queryKey: advisorKeys.session(sessionId) });
                await queryClient.invalidateQueries({ queryKey: advisorKeys.sessions });
              } catch {
                /* Retain local content if reconciliation is unavailable. */
              }
              const replayed = queryClient.getQueryData<{ entries: ConversationEvent[] }>(advisorKeys.session(sessionId)) ?? reconciledDetail;
              const replayedSeqs = new Set((replayed?.entries ?? []).map((entry) => entry.seq));
              updateCurrent((state) => {
                const saved = (replayed?.entries ?? []).find(event => event.kind === 'advisor_message'
                  && event.payload.presentation && typeof event.payload.presentation === 'object'
                  && 'runId' in event.payload.presentation && event.payload.presentation.runId === state.runId);
                const savedPartIds = new Set(Array.isArray(saved?.payload.parts)
                  ? saved.payload.parts.flatMap(part => part && typeof part === 'object' && 'id' in part ? [String(part.id)] : []) : []);
                const processReplayed = Boolean(saved) && (state.parts ?? []).every(part => savedPartIds.has(part.id))
                  && (!state.thinking.text.trim() || saved?.payload.reasoning === state.thinking.text);
                const terminalOutcome = saved?.payload.presentation && typeof saved.payload.presentation === 'object'
                  && 'outcome' in saved.payload.presentation ? saved.payload.presentation.outcome as RunPresentation['outcome'] : undefined;
                return { ...finalizeRunState(state, replayedSeqs, persisted || Boolean(terminalOutcome)),
                  ...(terminalOutcome ? { outcome: terminalOutcome, runError: null } : {}),
                  ...(processReplayed ? { parts: [], thinking: INITIAL_THINKING } : {}) };
              });
              const finalState = getRunState(sessionId);
              const hasResidue = finalState.runError !== null || finalState.agentUnavailable ||
                finalState.liveEvents.length > 0 || finalState.streamingText.trim().length > 0 ||
                finalState.thinking.text.trim().length > 0 || Boolean(finalState.parts?.length) ||
                Boolean(finalState.pendingInterrupts?.length) ||
                Boolean(finalState.queuedMessages?.length);
              if (isCurrent() && !hasResidue) endRun(sessionId);
              const reconciledEvents = mergeTimeline(replayed?.entries ?? [], finalState.liveEvents);
              const mayAdvance = finalState.outcome === 'completed' && !finalState.queuePaused && !finalState.runError && !finalState.agentUnavailable
                && !latestPendingProposal(reconciledEvents)
                && !reconciledEvents.some(event => event.kind === 'question' && event.turn === currentTurn(finalState));
              if (isCurrent() && mayAdvance && !controller.signal.aborted && finalState.queuedMessages?.length) {
                const nextMsg = finalState.queuedMessages[0];
                updateCurrent(state => ({ ...state, queuedMessages: state.queuedMessages?.slice(1) }));
                void send(nextMsg.text, nextMsg.options);
              }
            }
          }
        } finally {
          runInterrupts.delete(runInstanceId);
          runCompletions.delete(runInstanceId);
          completeRun();
        }
      }
      if (submissionError) throw submissionError;
    },
    [sessionId, detailQuery.data, queryClient],
  );

  useEffect(() => {
    if (run.running || !detailQuery.data) return;
    const fresh = detailQuery.data;
    const runtime = fresh.runtime;
    const state = getRunState(sessionId);
    if (!state.detached) return;
    if (state.detachedAt && detailQuery.dataUpdatedAt <= state.detachedAt) return;
    // Persisted parts replace local fragments only for the same run.
    const saved = fresh.entries.find(event => event.kind === 'advisor_message' && event.payload.presentation
      && typeof event.payload.presentation === 'object' && 'runId' in event.payload.presentation
      && event.payload.presentation.runId === state.runId);
    const active = runtime?.phase === 'running' || runtime?.phase === 'finalizing';
    if (active && !saved) {
      if (!state.runId && runtime.runId) updateRunState(sessionId, current => ({ ...current, runId: runtime.runId }));
      return;
    }
    const outcome = saved?.payload.presentation && typeof saved.payload.presentation === 'object'
      && 'outcome' in saved.payload.presentation ? saved.payload.presentation.outcome as RunPresentation['outcome'] : 'unknown';
    updateRunState(sessionId, current => ({ ...current,
      ...(saved ? { streamingText: '', parts: [], thinking: INITIAL_THINKING,
        liveEvents: current.liveEvents.filter(event => event.kind === 'user_message' && !fresh.entries.some(entry => entry.seq === event.seq)) } : {}),
      ...(!active ? { detached: false, detachedAt: undefined, runError: null, outcome } : {}),
    }));
    if (!active && outcome === 'completed' && !state.queuePaused && state.queuedMessages?.length
      && !resolvePendingQuestion(fresh, fresh.entries) && !latestPendingProposal(fresh.entries)) {
      const [next, ...rest] = state.queuedMessages;
      updateRunState(sessionId, current => ({ ...current, queuedMessages: rest }));
      void send(next.text, next.options);
    }
  }, [detailQuery.data, detailQuery.dataUpdatedAt, run.running, sessionId, send]);

  const answerMutation = useMutation({
    mutationKey: ['advisor', 'question-answer', sessionId],
    mutationFn: async ({ question, answer, thinking, reasoningEffort, attachments, selectedAgentId }: { question: PendingQuestion; answer: QuestionAnswer; thinking?: boolean; reasoningEffort?: ReasoningEffort; attachments?: UploadedAttachment[]; selectedAgentId?: string | null }) => {
      const key = `${sessionId}:${question.questionId}`;
      const signature = JSON.stringify({ selectedOptionIds: [...answer.selectedOptionIds].sort(), customText: answer.customText.trim(), attachments: attachments?.map(item => item.id) ?? [] });
      const previous = questionRequests.current.get(key);
      const requestId = previous?.signature === signature ? previous.requestId : randomId();
      questionRequests.current.set(key, { signature, requestId });
      await new Promise<void>((resolve, reject) => {
        void send(answer.customText || question.options.filter(option => answer.selectedOptionIds.includes(option.id)).map(option => option.label).join('、'), {
          thinking, reasoningEffort, attachments, selectedAgentId, questionReply: { questionId: question.questionId, requestId, ...answer }, onQuestionSaved: resolve,
        }).then(resolve, reject);
      });
    },
  });

  const continueQuestion = async (thinking?: boolean, selectedAgentId?: string | null, reasoningEffort?: ReasoningEffort) => {
    if (getRunState(sessionId).running) return;
    const fresh = await queryClient.fetchQuery({ queryKey: advisorKeys.session(sessionId), queryFn: () => getSessionDetail(sessionId), staleTime: 0 });
    if (getRunState(sessionId).running) return;
    const entry = unfinishedQuestionReply(fresh.entries, fresh.runtime);
    if (!entry) return;
    await send(questionPrompt(entry), { retryEntry: entry, thinking, reasoningEffort, selectedAgentId });
  };

  const stopRun = useCallback(async () => {
    if (runControllers.has(sessionId)) {
      await interruptCurrentRun(sessionId);
      return;
    }
    const state = getRunState(sessionId);
    const detail = await queryClient.fetchQuery({ queryKey: advisorKeys.session(sessionId), queryFn: () => getSessionDetail(sessionId), staleTime: 0 });
    if (detail?.runtime?.phase === 'running' || detail?.runtime?.phase === 'finalizing' || state.detached) {
      await interruptSession(sessionId, detail?.runtime?.runId ?? state.runId);
      await queryClient.invalidateQueries({ queryKey: advisorKeys.session(sessionId) });
    }
  }, [sessionId, queryClient]);

  const interruptAndSend = useCallback(
    async (queuedId?: string) => {
      const state = getRunState(sessionId);
      const queue = state.queuedMessages ?? [];
      if (queue.length === 0) return;
      const target = queuedId ? queue.find((item) => item.id === queuedId) : queue[0];
      if (!target) return;

      const instanceId = state.runInstanceId;
      const completed = instanceId ? runCompletions.get(instanceId) : undefined;
      updateRunState(sessionId, s => ({ ...s, queuePaused: true }));
      try {
        await stopRun();
      } catch {
        return;
      }
      await completed;
      if (!completed) {
        // The stop acknowledgement can precede persistence. Keep the item queued until idle.
        try {
          for (let attempt = 0; attempt < 50; attempt += 1) {
            const fresh = await queryClient.fetchQuery({ queryKey: advisorKeys.session(sessionId), queryFn: () => getSessionDetail(sessionId), staleTime: 0 });
            if (fresh.runtime?.phase !== 'running' && fresh.runtime?.phase !== 'finalizing') {
              updateRunState(sessionId, s => ({ ...s, detached: false, detachedAt: undefined }));
              break;
            }
            if (attempt === 49) return;
            await new Promise(resolve => setTimeout(resolve, 200));
          }
        } catch {
          return;
        }
      }
      const latest = getRunState(sessionId);
      if (latest.running || !(latest.queuedMessages ?? []).some(item => item.id === target.id)) return;
      updateRunState(sessionId, s => ({ ...s, queuedMessages: s.queuedMessages?.filter(item => item.id !== target.id), queuePaused: false }));
      await send(target.text, target.options);
    },
    [sessionId, send, stopRun, queryClient],
  );

  const cancelQueuedMessage = useCallback(
    (queuedId: string) => {
      const state = getRunState(sessionId);
      const target = (state.queuedMessages ?? []).find((item) => item.id === queuedId);
      updateRunState(sessionId, (s) => ({
        ...s,
        queuedMessages: (s.queuedMessages ?? []).filter((item) => item.id !== queuedId),
      }));
      return target;
    },
    [sessionId],
  );

  const stop = useCallback(() => {
    updateRunState(sessionId, state => ({ ...state, queuePaused: true }));
    void stopRun().catch(() => {});
  }, [sessionId, stopRun]);

  const retry = async (allowBasic = false, selectedAgentId?: string | null) => {
    const state = getRunState(sessionId);
    const pending = state.retryInput;
    if (state.running || (!state.runError && !state.agentUnavailable) || !pending) return Promise.resolve();
    if (pending.visitorEntry?.payload.questionReply) {
      const fresh = await queryClient.fetchQuery({ queryKey: advisorKeys.session(sessionId), queryFn: () => getSessionDetail(sessionId), staleTime: 0 });
      if (fresh.runtime && fresh.runtime.phase !== 'idle' && fresh.runtime.phase !== 'awaiting_visitor') return;
      if (fresh.entries.some(entry => entry.turn === pending.visitorEntry?.turn && entry.kind === 'advisor_message'
        && (Boolean(payloadText(entry.payload, 'text').trim()) || Boolean(entry.payload.presentation && typeof entry.payload.presentation === 'object' && 'outcome' in entry.payload.presentation && entry.payload.presentation.outcome === 'completed')))) return;
    }
    return send(pending.text, { ...pending.options, allowBasic,
      ...(allowBasic ? { selectedAgentId: undefined } : selectedAgentId !== undefined ? { selectedAgentId } : {}), retryEntry: pending.visitorEntry });
  };

  return {
    session: detailQuery.data?.session
      ? { ...detailQuery.data.session, ...(summary ? { title: summary.title } : {}) } : null,
    detailLoading: detailQuery.isLoading,
    events,
    streamingText: run.streamingText,
    thinking: run.thinking,
    hasOrderedParts: Boolean(run.parts?.length),
    running,
    queuedMessages: run.queuedMessages ?? [],
    runError: remoteRunning || run.detached ? null : run.runError,
    agentUnavailable: Boolean(run.agentUnavailable),
    runAgentId: run.agentId ?? detailQuery.data?.runtime?.agentId,
    retry,
    pendingQuestion,
    pendingQuestionReply,
    questionBusy: answerMutation.isPending,
    questionError: answerMutation.variables?.question.questionId === pendingQuestion?.questionId ? answerMutation.error : null,
    answerQuestion: (question: PendingQuestion, answer: QuestionAnswer, thinking?: boolean, attachments?: UploadedAttachment[], selectedAgentId?: string | null, reasoningEffort?: ReasoningEffort) => answerMutation.mutateAsync({ question, answer, thinking, attachments, selectedAgentId, reasoningEffort }),
    continueQuestion,
    awaitingVisitor,
    pendingProposal,
    pendingInterrupts,
    resolveInterrupts: (responses: AguiResumeInput[]) => send('', { resume: responses, resumeAgentId: run.agentId ?? detailQuery.data?.runtime?.agentId, thinking: run.thinkingEnabled, reasoningEffort: run.reasoningEffort }),
    send,
    interruptAndSend,
    cancelQueuedMessage,
    stop,
  };
}
