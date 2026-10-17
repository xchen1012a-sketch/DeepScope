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

import type { ConversationPart, EventEntry, ToolCallInput } from '@/api/advisor';
import type { AguiStreamSignal } from '../aguiClient';

function closeTail(parts: ConversationPart[]): ConversationPart[] {
  const tail = parts[parts.length - 1];
  if (!tail || (tail.kind === 'tool' || tail.kind === 'event') || tail.status !== 'running') return parts;
  return [...parts.slice(0, -1), { ...tail, status: 'done' }];
}

function identity(parts: ConversationPart[], signal: AguiStreamSignal) {
  const runId = signal.runId ?? parts[parts.length - 1]?.runId ?? 'live';
  return { id: `${runId}:part:${parts.length + 1}`, runId };
}

function toolInput(payload: Record<string, unknown>): ToolCallInput {
  return {
    id: String(payload.id), name: String(payload.name ?? ''), args: payload.args,
    ...(payload.result !== undefined ? { result: payload.result } : {}),
    status: payload.status === 'failed' ? 'failed' : payload.status === 'done' ? 'done' : 'running',
    ...(typeof payload.startedAt === 'number' ? { startedAt: payload.startedAt } : {}),
    ...(typeof payload.endedAt === 'number' ? { endedAt: payload.endedAt } : {}),
  };
}

/** Starts occupy a position; deltas and late tool results only update that position. */
export function updatePresentationParts(parts: ConversationPart[], signal: AguiStreamSignal, events: EventEntry[]): ConversationPart[] {
  if (signal.kind === 'tool-start' || signal.kind === 'tool-args' || signal.kind === 'tool-result') {
    const event = events.find(item => item.kind === 'tool_call' && item.payload.id === signal.id);
    if (!event) return parts;
    const tool = toolInput(event.payload);
    const index = parts.findIndex(part => part.kind === 'tool' && part.tool.id === signal.id);
    if (index >= 0) return parts.map((part, i) => i === index ? { ...part, kind: 'tool', tool, status: tool.status ?? 'running' } : part);
    return [...closeTail(parts), { ...identity(parts, signal), kind: 'tool', tool, status: tool.status ?? 'running' }];
  }
  if (signal.kind === 'advisor-event') {
    if (typeof signal.value.kind !== 'string') return parts;
    const { kind, seq, ...payload } = signal.value;
    const event = { kind, payload, ...(typeof seq === 'number' ? { seq } : {}) };
    const status = payload.status === 'running' ? 'running' : payload.status === 'failed' || kind === 'error' ? 'failed' : 'done';
    const index = parts.findIndex(part => part.kind === 'event' && ((seq !== undefined && part.event.seq === seq)
      || (kind === 'compaction' && part.event.kind === kind && part.status === 'running')));
    if (index >= 0) return parts.map((part, i) => i === index ? { ...part, kind: 'event', event, status } : part);
    return [...closeTail(parts), { ...identity(parts, signal), kind: 'event', event, status }];
  }
  if (signal.kind !== 'text-start' && signal.kind !== 'text-end' && signal.kind !== 'text-delta'
      && signal.kind !== 'reasoning-start' && signal.kind !== 'reasoning-end' && signal.kind !== 'reasoning-delta') return parts;
  const kind = signal.kind.startsWith('text') ? 'text' : 'reasoning';
  const tail = parts[parts.length - 1];
  const same = tail?.kind === kind && tail.status === 'running'
    && (!signal.messageId || !tail.messageId || signal.messageId === tail.messageId);
  if (signal.kind.endsWith('-end')) return same ? closeTail(parts) : parts;
  const delta = 'delta' in signal ? signal.delta : '';
  if (same && (tail.kind === 'text' || tail.kind === 'reasoning')) {
    if (!delta && !signal.messageId) return parts;
    return [...parts.slice(0, -1), { ...tail, text: tail.text + delta,
      ...(signal.messageId ? { messageId: signal.messageId } : {}) }];
  }
  return [...closeTail(parts), { ...identity(parts, signal), kind, text: delta, status: 'running',
    ...(signal.messageId ? { messageId: signal.messageId } : {}) }];
}

export function settlePresentationParts(parts: ConversationPart[], failed = false): ConversationPart[] {
  return parts.map(part => part.status !== 'running' ? part : {
    ...part, status: failed || part.kind === 'tool' ? 'failed' : 'done',
    ...(part.kind === 'tool' ? { tool: { ...part.tool, status: 'failed' as const } } : {}),
  });
}
