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

import { payloadText, type EventEntry, type SubagentRunInput, type SubagentStepInput } from '@/api/advisor';
import { foldToolCall, type ToolCall } from './conversationModel';

/**
 * 子智能体自身过程的一个步骤。`subtaskId` 是派发时登记的 id，回放时靠它把步骤挂回
 * 对应的派发卡；没有对应派发的步骤在回放时被忽略。
 */
export interface SubagentStep {
  subtaskId: string;
  order: number;
  kind: 'thinking' | 'tool_call';
  text?: string;
  tool?: ToolCall;
}

/** 一个待认领的派发：子任务 id 与派发时登记的子智能体显示名。 */
export interface PendingDispatch {
  subtaskId: string;
  name: string;
}

export interface SubagentAccumulator {
  /** 尚未认领的派发，按派发顺序排队。 */
  waiting: PendingDispatch[];
  /** 已认领的子任务 id。 */
  claimed: string[];
  /** 子智能体事件里的 source（父会话/子智能体）→ 子任务 id。 */
  bySource: Record<string, string>;
  steps: SubagentStep[];
}

export const EMPTY_SUBAGENT_ACCUMULATOR: SubagentAccumulator = { waiting: [], claimed: [], bySource: {}, steps: [] };

function appendStep(state: SubagentAccumulator, subtaskId: string, step: Omit<SubagentStep, 'order' | 'subtaskId'>): SubagentAccumulator {
  const order = state.steps.filter((item) => item.subtaskId === subtaskId).length + 1;
  return { ...state, steps: [...state.steps, { ...step, subtaskId, order }] };
}

/** 派发登记：把「子任务 id + 子智能体名」排进待认领队列。 */
export function registerSubagentDispatch(
  state: SubagentAccumulator,
  event: EventEntry,
): SubagentAccumulator {
  const subtaskId = payloadText(event.payload, 'subtaskId');
  if (
    !subtaskId ||
    state.claimed.includes(subtaskId) ||
    state.waiting.some((pending) => pending.subtaskId === subtaskId)
  ) {
    return state;
  }
  return { ...state, waiting: [...state.waiting, { subtaskId, name: payloadText(event.payload, 'name') }] };
}

/**
 * 认领一个待认领派发。
 *
 * 派发事件带子智能体显示名（`reportDispatch` 的 `name`），子智能体的 `AGENT_START` 事件
 * 带同一个名字，因此优先**同名对账**——两个派发各自的过程乱序到达也不会挂错。信号没带
 * 名字、或队列里没有同名派发时退回队列头部（FIFO），保持升级前的行为。队列为空则返回
 * null，调用方丢弃该事件，不凭空造卡。
 */
function claimDispatch(
  state: SubagentAccumulator,
  source: string,
  name: string | undefined,
): { state: SubagentAccumulator; subtaskId: string } | null {
  const index = name
    ? state.waiting.findIndex((pending) => pending.name === name)
    : 0;
  const position = index >= 0 ? index : 0;
  const next = state.waiting[position];
  if (!next) return null;
  return {
    state: {
      ...state,
      waiting: state.waiting.filter((_, offset) => offset !== position),
      claimed: [...state.claimed, next.subtaskId],
      bySource: { ...state.bySource, [source]: next.subtaskId },
    },
    subtaskId: next.subtaskId,
  };
}

/**
 * 一条子智能体事件并入累积器。
 *
 * 扩展层只在子事件上带 `source`（`父会话/子智能体`），不带 `subtaskId`；同名的派发与会话
 * 生命周期事件是唯一确定性的对账依据，所以优先按名字认领，认不到再退回 FIFO。
 */
export function applySubagentSignal(
  state: SubagentAccumulator,
  signal:
    | { kind: 'subagent-thinking'; text: string; name?: string; source?: string }
    | { kind: 'subagent-text'; text: string; name?: string; source?: string }
    | { kind: 'subagent-tool-call'; toolCallId: string; name?: string; source?: string }
    | { kind: 'subagent-tool-result'; toolCallId: string; name?: string; content?: string; state?: 'done' | 'failed'; source?: string }
    | { kind: 'subagent-lifecycle'; source?: string; name?: string; phase: string },
): SubagentAccumulator {
  const source = typeof signal.source === 'string' && signal.source ? signal.source : '';
  if (!source) return state;
  let subtaskId = state.bySource[source];
  if (!subtaskId) {
    const claimed = claimDispatch(state, source, signal.name);
    if (!claimed) return state;
    state = claimed.state;
    subtaskId = claimed.subtaskId;
  }
  if (signal.kind === 'subagent-thinking' || signal.kind === 'subagent-text') {
    if (!signal.text) return state;
    return appendThinking(state, subtaskId, signal.text);
  }
  if (signal.kind === 'subagent-tool-result') {
    return appendToolResult(state, subtaskId, signal);
  }
  if (signal.kind === 'subagent-lifecycle') {
    // 收尾事件只用于结束这一步，不重复计步，也不产生新步骤。
    return state;
  }
  return appendToolStart(state, subtaskId, signal);
}

function appendThinking(state: SubagentAccumulator, subtaskId: string, text: string): SubagentAccumulator {
  const last = state.steps[state.steps.length - 1];
  if (last && last.subtaskId === subtaskId && last.kind === 'thinking') {
    const merged = { ...last, text: `${last.text ?? ''}${text}` };
    return { ...state, steps: [...state.steps.slice(0, -1), merged] };
  }
  return appendStep(state, subtaskId, { kind: 'thinking', text });
}

function appendToolStart(
  state: SubagentAccumulator,
  subtaskId: string,
  signal: { toolCallId: string; name?: string },
): SubagentAccumulator {
  if (state.steps.some((step) => step.subtaskId === subtaskId && step.tool?.id === signal.toolCallId)) {
    return state;
  }
  const [tool] = foldToolCall([], {
    id: signal.toolCallId,
    name: signal.name,
    startedAt: Date.now(),
  });
  return appendStep(state, subtaskId, { kind: 'tool_call', tool });
}

function appendToolResult(
  state: SubagentAccumulator,
  subtaskId: string,
  signal: { toolCallId: string; content?: string; state?: 'done' | 'failed' },
): SubagentAccumulator {
  const index = state.steps.findIndex(
    (step) => step.subtaskId === subtaskId && step.kind === 'tool_call' && step.tool?.id === signal.toolCallId,
  );
  if (index < 0) return state;
  const step = state.steps[index];
  const [tool] = foldToolCall(step.tool ? [step.tool] : [], {
    id: signal.toolCallId,
    result: signal.content ?? '',
    failed: signal.state === 'failed',
    endedAt: Date.now(),
  });
  const steps = state.steps.map((item, position) => (position === index ? { ...item, tool } : item));
  return { ...state, steps };
}

/** 累积器转成随 `advisor_message` POST 一起提交的载荷。 */
export function toSubagentRuns(state: SubagentAccumulator): SubagentRunInput[] {
  const byId = new Map<string, SubagentStepInput[]>();
  for (const step of [...state.steps].sort((left, right) => left.order - right.order)) {
    const events = byId.get(step.subtaskId) ?? [];
    events.push(toStepInput(step));
    byId.set(step.subtaskId, events);
  }
  return [...byId.entries()].map(([subtaskId, events]) => ({ subtaskId, events }));
}

function toStepInput(step: SubagentStep): SubagentStepInput {
  if (step.kind === 'thinking') {
    return { kind: 'thinking', text: step.text ?? '' };
  }
  const tool = step.tool;
  return {
    kind: 'tool_call',
    toolCallId: tool?.id ?? '',
    ...(tool?.name ? { name: tool.name } : {}),
    ...(tool?.args ? { args: tool.args } : {}),
    ...(tool?.result !== undefined ? { result: tool.result } : {}),
    ...(tool?.status ? { status: tool.status } : {}),
    ...(tool?.startedAt !== null && tool?.startedAt !== undefined ? { startedAt: tool.startedAt } : {}),
    ...(tool?.endedAt !== undefined ? { endedAt: tool.endedAt } : {}),
  };
}

/**
 * 回放：把 `subagent_step` 行按 `subtaskId` + 递增 `order` 还原成有序步骤。
 *
 * 行自带的 `subtaskId` 优先（前端提交时一定写）；只有行里没带 `subtaskId` 时才按
 * `name` 找到同名派发——与实时路径同样的对账口径。两种都对不上、或都没有对应派发的行
 * 被忽略，不会凭空造出子智能体卡。
 */
export function subagentStepsFromReplay(events: EventEntry[]): Map<string, SubagentStep[]> {
  const dispatched = new Set<string>();
  const byName = new Map<string, string[]>();
  for (const event of events) {
    if (event.kind !== 'dispatch') continue;
    const subtaskId = payloadText(event.payload, 'subtaskId');
    if (!subtaskId) continue;
    dispatched.add(subtaskId);
    const name = payloadText(event.payload, 'name');
    if (name) byName.set(name, [...(byName.get(name) ?? []), subtaskId]);
  }
  const byId = new Map<string, SubagentStep[]>();
  const claimedByName = new Map<string, number>();
  for (const event of events) {
    if (event.kind !== 'subagent_step') continue;
    const subtaskId = replaySubtaskId(event, dispatched, byName, claimedByName);
    if (!subtaskId) continue;
    const step = stepFromReplay(event, subtaskId);
    if (!step) continue;
    byId.set(subtaskId, [...(byId.get(subtaskId) ?? []), step]);
  }
  for (const steps of byId.values()) {
    steps.sort((left, right) => left.order - right.order);
  }
  return byId;
}

/** 一行 subagent_step 挂到哪个子任务：显式 id 优先，否则按同名派发 FIFO 认领。 */
function replaySubtaskId(
  event: EventEntry,
  dispatched: Set<string>,
  byName: Map<string, string[]>,
  claimedByName: Map<string, number>,
): string | null {
  const explicit = payloadText(event.payload, 'subtaskId');
  if (explicit) return dispatched.has(explicit) ? explicit : null;
  const name = payloadText(event.payload, 'name');
  if (!name) return null;
  const candidates = byName.get(name) ?? [];
  const used = claimedByName.get(name) ?? 0;
  const next = candidates[used];
  if (!next) return null;
  claimedByName.set(name, used + 1);
  return next;
}

function stepFromReplay(event: EventEntry, subtaskId: string): SubagentStep | null {
  const kind = payloadText(event.payload, 'kind');
  const order = typeof event.payload.order === 'number' ? event.payload.order : 0;
  if (kind === 'thinking') {
    return { subtaskId, order, kind, text: payloadText(event.payload, 'text') };
  }
  if (kind !== 'tool_call') return null;
  const status = payloadText(event.payload, 'status');
  return {
    subtaskId,
    order,
    kind,
    tool: {
      id: payloadText(event.payload, 'toolCallId'),
      name: payloadText(event.payload, 'name') || payloadText(event.payload, 'toolCallId'),
      args: payloadText(event.payload, 'args'),
      result: payloadText(event.payload, 'result') || undefined,
      status: status === 'failed' ? 'failed' : status === 'running' ? 'running' : 'done',
      startedAt: typeof event.payload.startedAt === 'number' ? event.payload.startedAt : null,
      ...(typeof event.payload.endedAt === 'number' ? { endedAt: event.payload.endedAt } : {}),
    },
  };
}
