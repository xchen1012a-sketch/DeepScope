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

import type { AguiInterrupt, AguiResumeInput, RunPresentation } from '@/api/advisor';
import { getToken } from '@/lib/apiClient';

/** AG-UI CUSTOM 事件里技术参谋过程事件的名字（与后端 ReportActions.EVENT_NAME 一致）。 */
export const ADVISOR_EVENT_NAME = 'advisor_event';

/** 一行 SSE 解析归类后的流信号；无法识别的行返回 null。 */
export type AguiStreamSignal = (
  | { kind: 'run-started' }
  | { kind: 'run-finished'; interrupted: boolean; interrupts?: AguiInterrupt[] }
  | { kind: 'presentation'; presentation: RunPresentation }
  | { kind: 'text-start' | 'text-end' }
  | { kind: 'text-delta'; delta: string }
  | { kind: 'reasoning-start' }
  | { kind: 'reasoning-delta'; delta: string }
  | { kind: 'reasoning-end' }
  | { kind: 'advisor-event'; value: Record<string, unknown> }
  | { kind: 'tool-start'; id: string; name: string }
  | { kind: 'tool-args'; id: string; delta: string }
  | { kind: 'tool-result'; id: string; content: string; state?: 'done' | 'failed' }
  | {
      kind: 'token-usage';
      stepId: string;
      sample: {
        stepId: string;
        uncachedInput: number;
        cacheRead: number;
        cacheWrite: number;
        output: number;
        projectedTokens: number;
        timeSeconds?: number;
        /** 提供方声明的上下文窗口；应用层透出，缺失即不画上下文环。 */
        contextWindow?: number;
        /** 该步调用实际使用的模型名；应用层透出，缺失即不写。 */
        modelName?: string;
        firstTokenMs?: number;
        outputTokensPerSecond?: number;
      };
    }
  | { kind: 'stream-error'; message: string }
  /** 子智能体自己的过程（CUSTOM subagent.*）；source 是「父会话/子智能体」。 */
  | { kind: 'subagent-thinking'; text: string; source?: string }
  | { kind: 'subagent-text'; text: string; source?: string }
  | { kind: 'subagent-tool-call'; toolCallId: string; name?: string; source?: string }
  | {
      kind: 'subagent-tool-result';
      toolCallId: string;
      content?: string;
      state?: 'done' | 'failed';
      source?: string;
    }
  | { kind: 'subagent-lifecycle'; phase: string; name?: string; source?: string }
) & { runId?: string; messageId?: string };

export interface AdvisorRunHandlers {
  /** Retain the request identity even if the first SSE event never reaches the browser. */
  onRunRequested?: (runId: string) => void;
  /** Preferred path: consume ordered identities and boundaries, without duplicating legacy callbacks. */
  onSignal?: (signal: AguiStreamSignal) => void;
  /** 助手文本增量（TEXT_MESSAGE_CONTENT 的 delta）。 */
  onTextDelta: (delta: string) => void;
  /** 过程事件（CUSTOM advisor_event），value 为平铺 payload + kind/seq/turn。 */
  onAdvisorEvent: (value: Record<string, unknown>) => void;
  onToolStart?: (id: string, name: string) => void;
  onToolArgs?: (id: string, delta: string) => void;
  onToolResult?: (id: string, content: string) => void;
  onTokenUsage?: (signal: Extract<AguiStreamSignal, { kind: 'token-usage' }>) => void;
  /** 思考阶段开始（REASONING_START）。 */
  onReasoningStart?: () => void;
  /** 思考文本增量（REASONING_MESSAGE_CONTENT 的 delta）。 */
  onReasoningDelta?: (delta: string) => void;
  /** 思考阶段结束（REASONING_END）。 */
  onReasoningEnd?: () => void;
  /** 子智能体自身的过程信号（subagent.thinking / tool_call / tool_result / text / lifecycle）。 */
  onSubagentSignal?: (
    signal: Extract<AguiStreamSignal, { kind: `subagent-${string}` }>,
  ) => void;
}

export function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `id-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 从一行 SSE 中取出 data 载荷；非 data 行返回 null。 */
export function sseLineData(line: string): string | null {
  const trimmed = line.trim();
  if (!trimmed.startsWith('data:')) return null;
  return trimmed.slice(5).trim() || null;
}

function parseEvent(raw: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function numberField(record: Record<string, unknown>, key: string): number {
  const value = record[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** 提示总量里缓存读入和缓存写出是子集，未命中输入是剩下的部分。 */
function tokenUsageSignal(value: Record<string, unknown>): AguiStreamSignal | null {
  if (!isObject(value.delta)) return null;
  const modelCall = isObject(value.modelCall) ? value.modelCall : {};
  const stepId = typeof modelCall.replyId === 'string' && modelCall.replyId ? modelCall.replyId : 'usage';
  const input = numberField(value.delta, 'inputTokens');
  const cacheRead = numberField(value.delta, 'cachedTokens');
  const cacheWrite = numberField(value.delta, 'cacheCreationTokens');
  const sample = {
    stepId,
    uncachedInput: Math.max(0, input - cacheRead - cacheWrite),
    cacheRead,
    cacheWrite,
    output: numberField(value.delta, 'outputTokens'),
    projectedTokens: input,
    ...(typeof value.delta.time === 'number' ? { timeSeconds: value.delta.time } : {}),
    // 上下文窗口与模型名由应用层自定义 converter 透出；缺失就不写，读侧据此降级。
    ...(typeof value.contextWindow === 'number' ? { contextWindow: value.contextWindow } : {}),
    ...(typeof value.modelName === 'string' && value.modelName ? { modelName: value.modelName } : {}),
    ...(typeof value.firstTokenMs === 'number' ? { firstTokenMs: value.firstTokenMs } : {}),
    ...(typeof value.outputTokensPerSecond === 'number'
      ? { outputTokensPerSecond: value.outputTokensPerSecond }
      : {}),
  };
  return { kind: 'token-usage', stepId, sample };
}

/**
 * 工具结果的状态。应用层的自定义 converter 把 `ToolResultEndEvent` 的 state 透出到
 * `TOOL_CALL_RESULT.rawEvent.state`；只有明确成功时才是 done，其余
 * （error/denied/interrupted）一律按失败处理，字段缺失则交给工具调用自身的 status
 * （老数据降级为不显示失败态）。
 */
function toolResultState(event: Record<string, unknown>): { state: 'done' | 'failed' } | Record<string, never> {
  const raw = isObject(event.rawEvent) ? event.rawEvent : {};
  const value = typeof event.state === 'string' ? event.state : typeof raw.state === 'string' ? raw.state : '';
  const state = value.toLowerCase();
  if (state === 'success') return { state: 'done' };
  if (state === 'error' || state === 'denied' || state === 'interrupted' || state === 'failed') {
    return { state: 'failed' };
  }
  return {};
}

/**
 * 子智能体自身的流事件（CUSTOM `subagent.*`）。文本类事件的值里是 `delta`，
 * 工具类事件里是 `toolCallId` / `toolName` / `state`（state 只有子智能体的工具结果才带）。
 */
function subagentSignal(name: string, value: Record<string, unknown>): AguiStreamSignal | null {
  const source = typeof value.source === 'string' && value.source ? { source: value.source } : {};
  const text = typeof value.delta === 'string' ? value.delta : typeof value.text === 'string' ? value.text : '';
  if (name === 'subagent.thinking') return { kind: 'subagent-thinking', text, ...source };
  if (name === 'subagent.text') return { kind: 'subagent-text', text, ...source };
  if (name === 'subagent.tool_call') {
    if (typeof value.toolCallId !== 'string') return null;
    return {
      kind: 'subagent-tool-call',
      toolCallId: value.toolCallId,
      ...(typeof value.toolName === 'string' ? { name: value.toolName } : {}),
      ...source,
    };
  }
  if (name === 'subagent.tool_result') {
    if (typeof value.toolCallId !== 'string') return null;
    const state = typeof value.state === 'string' ? value.state.toLowerCase() : '';
    return {
      kind: 'subagent-tool-result',
      toolCallId: value.toolCallId,
      ...(typeof value.content === 'string' ? { content: value.content } : {}),
      ...(state === 'error' || state === 'denied' || state === 'interrupted' || state === 'failed'
        ? { state: 'failed' as const }
        : state === 'success'
          ? { state: 'done' as const }
          : {}),
      ...source,
    };
  }
  if (name === 'subagent.lifecycle') {
    return {
      kind: 'subagent-lifecycle',
      phase: typeof value.type === 'string' ? value.type : '',
      // AGENT_START 带子智能体名字（派发 payload 里也有这个名字），用来把过程挂回正确的
      // 派发；AGENT_END 没有这个名字，缺失即 undefined。
      ...(typeof value.name === 'string' && value.name ? { name: value.name } : {}),
      ...source,
    };
  }
  return null;
}

/** 把一行 SSE 归类为流信号；非 data 行、非法 JSON 与本页不关心的事件都返回 null。 */
export function classifyAguiLine(line: string): AguiStreamSignal | null {
  const raw = sseLineData(line);
  if (!raw) return null;
  const event = parseEvent(raw);
  if (!event) return null;
  const type = typeof event.type === 'string' ? event.type : '';
  const identity = { ...(typeof event.runId === 'string' ? { runId: event.runId } : {}), ...(typeof event.messageId === 'string' ? { messageId: event.messageId } : {}) };
  if (type === 'RUN_STARTED') return { kind: 'run-started', ...identity };
  if (type === 'RUN_FINISHED') {
    const interrupted = isObject(event.outcome) && event.outcome.type === 'interrupt';
    const rawInterrupts = isObject(event.outcome) ? event.outcome.interrupts : undefined;
    const interrupts = Array.isArray(rawInterrupts) ? rawInterrupts.filter((item): item is AguiInterrupt => isObject(item) && typeof item.id === 'string' && item.id.length > 0 && typeof item.reason === 'string') : [];
    return { kind: 'run-finished', interrupted, ...(interrupts.length ? { interrupts } : {}), ...identity };
  }
  if (type === 'CUSTOM' && event.name === 'advisor_presentation' && isObject(event.value)) {
    const value = event.value;
    if (value.presentationVersion !== 1 || typeof value.runId !== 'string' || value.runId !== identity.runId
      || typeof value.runStartedAt !== 'number' || !Number.isSafeInteger(value.runStartedAt) || value.runStartedAt < 0
      || !Array.isArray(value.finalMessageIds) || !value.finalMessageIds.every(id => typeof id === 'string' && id.length > 0)
      || !['completed', 'limited', 'failed', 'cancelled', 'awaiting_action', 'unknown'].includes(String(value.outcome))) return null;
    const presentation: RunPresentation = {
      runId: value.runId, runStartedAt: value.runStartedAt, finalMessageIds: value.finalMessageIds,
      outcome: value.outcome as RunPresentation['outcome'],
      ...(value.terminationReason === 'MAX_ITERATIONS' ? { terminationReason: 'MAX_ITERATIONS' as const } : {}),
      ...(typeof value.finalStartedAt === 'number' ? { finalStartedAt: value.finalStartedAt } : {}),
      ...(typeof value.runEndedAt === 'number' ? { runEndedAt: value.runEndedAt } : {}),
    };
    return { kind: 'presentation', presentation, ...identity };
  }
  if (type === 'TEXT_MESSAGE_START') return { kind: 'text-start', ...identity };
  if (type === 'TEXT_MESSAGE_END') return { kind: 'text-end', ...identity };
  if (type === 'TEXT_MESSAGE_CONTENT' && typeof event.delta === 'string') {
    return { kind: 'text-delta', delta: event.delta, ...identity };
  }
  if (type === 'REASONING_MESSAGE_CONTENT' && typeof event.delta === 'string') {
    return { kind: 'reasoning-delta', delta: event.delta, ...identity };
  }
  if (type === 'REASONING_START' || type === 'REASONING_MESSAGE_START') return { kind: 'reasoning-start', ...identity };
  if (type === 'REASONING_END' || type === 'REASONING_MESSAGE_END') return { kind: 'reasoning-end', ...identity };
  if (type === 'CUSTOM' && event.name === ADVISOR_EVENT_NAME && isObject(event.value)) {
    return { kind: 'advisor-event', value: event.value, ...identity };
  }
  if (type === 'TOOL_CALL_START' && typeof event.toolCallId === 'string') {
    return {
      kind: 'tool-start',
      ...identity,
      id: event.toolCallId,
      name: typeof event.toolCallName === 'string' ? event.toolCallName : event.toolCallId,
    };
  }
  if (type === 'TOOL_CALL_ARGS' && typeof event.toolCallId === 'string' && typeof event.delta === 'string') {
    return { kind: 'tool-args', id: event.toolCallId, delta: event.delta, ...identity };
  }
  if (type === 'TOOL_CALL_RESULT' && typeof event.toolCallId === 'string') {
    return {
      kind: 'tool-result',
      ...identity,
      id: event.toolCallId,
      content: typeof event.content === 'string' ? event.content : '',
      ...toolResultState(event),
    };
  }
  if (type === 'CUSTOM' && event.name === 'token_usage' && isObject(event.value)) {
    return tokenUsageSignal(event.value);
  }
  if (type === 'CUSTOM' && typeof event.name === 'string' && event.name.startsWith('subagent.')) {
    return subagentSignal(event.name, isObject(event.value) ? event.value : {});
  }
  if (type === 'RUN_ERROR' && typeof event.message === 'string') {
    return { kind: 'stream-error', message: event.message, ...identity };
  }
  if (type === 'RAW' && isObject(event.event) && typeof event.event.error === 'string') {
    return { kind: 'stream-error', message: event.event.error, ...identity };
  }
  return null;
}

/** 推理程度档位（与后端 SwitchingChatModel.REASONING_EFFORTS 白名单一致）。 */
export type ReasoningEffort = 'low' | 'medium' | 'high' | 'xhigh' | 'max';

export interface AdvisorRunOptions {
  resume?: AguiResumeInput[];
  /** 是否开启深度思考（通过 forwardedProps.thinking 传给后端模型适配器）。 */
  thinking?: boolean;
  /** 推理程度档位，仅深度思考开启时随 forwardedProps 传给后端。 */
  reasoningEffort?: ReasoningEffort;
  /** 停止按钮中断这一次读取。 */
  signal?: AbortSignal;
}

/**
 * 发起一轮 AG-UI 运行并逐行解析 SSE 流：运行入口为 /agui/run/{agentId}，
 * agentId 来自会话绑定（锁模式）或路由结果（自由模式）。返回的 promise 在流结束后
 * resolve；运行出错（RUN_ERROR / RAW error / 非 2xx）时 reject 并带上服务端给的错误信息。
 */
export async function runAdvisorStream(
  sessionId: string,
  agentId: string,
  text: string,
  handlers: AdvisorRunHandlers,
  options?: AdvisorRunOptions,
): Promise<void> {
  if (!agentId) throw new Error('A conversation runtime is required');
  const token = getToken();
  const requestedRunId = randomId();
  handlers.onRunRequested?.(requestedRunId);
  const response = await fetch(`/agui/run/${encodeURIComponent(agentId)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      threadId: sessionId,
      runId: requestedRunId,
      messages: options?.resume?.length ? [] : [{ id: randomId(), role: 'user', content: text }],
      ...(options?.resume?.length ? { resume: options.resume } : {}),
      forwardedProps: {
        thinking: Boolean(options?.thinking),
        ...(options?.thinking && options?.reasoningEffort
          ? { reasoningEffort: options.reasoningEffort }
          : {}),
      },
    }),
    signal: options?.signal,
  });

  if (!response.ok || !response.body) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || `AG-UI run failed: HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let streamError: string | null = null;
  let terminal = false;
  let activeRunId: string | undefined;

  const consume = (line: string) => {
    const signal = classifyAguiLine(line);
    if (!signal) return;
    if (activeRunId && signal.runId && activeRunId !== signal.runId) return;
    if (signal.kind === 'run-started' && signal.runId) activeRunId = signal.runId;
    if (signal.kind === 'run-finished' || signal.kind === 'stream-error') terminal = true;
    if (signal.kind === 'stream-error') streamError = signal.message;
    if (handlers.onSignal) { handlers.onSignal(signal); return; }
    switch (signal.kind) {
      case 'text-delta':
        handlers.onTextDelta(signal.delta);
        break;
      case 'reasoning-start':
        handlers.onReasoningStart?.();
        break;
      case 'reasoning-delta':
        handlers.onReasoningDelta?.(signal.delta);
        break;
      case 'reasoning-end':
        handlers.onReasoningEnd?.();
        break;
      case 'advisor-event':
        handlers.onAdvisorEvent(signal.value);
        break;
      case 'tool-start':
        handlers.onToolStart?.(signal.id, signal.name);
        break;
      case 'tool-args':
        handlers.onToolArgs?.(signal.id, signal.delta);
        break;
      case 'tool-result':
        handlers.onToolResult?.(signal.id, signal.content);
        break;
      case 'token-usage':
        handlers.onTokenUsage?.(signal);
        break;
      case 'stream-error':
        streamError = signal.message;
        break;
      default:
        if (signal.kind.startsWith('subagent-')) {
          handlers.onSubagentSignal?.(signal as Extract<AguiStreamSignal, { kind: `subagent-${string}` }>);
        }
        break;
    }
  };

  // 流结束（done）或出错后统一收尾；buffer 里残留的最后一行也要消费。
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) consume(line);
  }
  if (buffer) consume(buffer);

  if (streamError) throw new Error(streamError);
  if (!terminal) throw new Error('AGUI_STREAM_INTERRUPTED: Connection closed before a terminal event');
}
