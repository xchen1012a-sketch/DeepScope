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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { applyRunSignal, startRunState, toAdvisorMessageInput, withVisitorEntry, finalizeRunState } from '../useAdvisorConversation';
import { classifyAguiLine, runAdvisorStream, type AguiStreamSignal } from '../aguiClient';
import { buildBlocks, renderedBlocks } from './blocks';

const signals = [
  { kind: 'reasoning-start', runId: 'run' },
  { kind: 'reasoning-delta', delta: 'think A', messageId: 'm1', runId: 'run' },
  { kind: 'reasoning-end', runId: 'run' },
  { kind: 'text-delta', delta: 'read files', messageId: 'm1', runId: 'run' },
  { kind: 'tool-start', id: 'a', name: 'read_file', runId: 'run' },
  { kind: 'tool-start', id: 'b', name: 'search', runId: 'run' },
  { kind: 'tool-result', id: 'b', content: 'found', state: 'done', runId: 'run' },
  { kind: 'tool-result', id: 'a', content: 'read', state: 'done', runId: 'run' },
  { kind: 'text-delta', delta: 'check results', messageId: 'm1', runId: 'run' },
  { kind: 'reasoning-start', runId: 'run' },
  { kind: 'reasoning-delta', delta: 'think B', messageId: 'm2', runId: 'run' },
  { kind: 'reasoning-end', runId: 'run' },
  { kind: 'text-delta', delta: 'answer', messageId: 'm2', runId: 'run' },
] as AguiStreamSignal[];

function round() { return signals.reduce(applyRunSignal, startRunState()); }

describe('ordered conversation parts', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('updates an existing process event without losing its running status or identity', () => {
    const first = applyRunSignal(startRunState(), { kind: 'advisor-event', runId: 'run', value: { kind: 'compaction', seq: 7, status: 'running' } });
    expect(first.parts?.[0].status).toBe('running');
    const second = applyRunSignal(first, { kind: 'advisor-event', runId: 'run', value: { kind: 'compaction', seq: 7, status: 'done' } });
    expect(second.parts).toHaveLength(1);
    expect(second.parts?.[0].id).toBe(first.parts?.[0].id);
    expect(second.parts?.[0].status).toBe('done');
  });

  it('ignores an error for a different run before recording a stream failure', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null });
    const wire = [
      { type: 'RUN_STARTED', runId: 'active' },
      { type: 'RUN_ERROR', runId: 'old', message: 'stale failure' },
      { type: 'RAW', runId: 'old', event: { error: 'stale raw failure' } },
      { type: 'TEXT_MESSAGE_CONTENT', runId: 'active', messageId: 'm', delta: 'answer' },
      { type: 'RUN_FINISHED', runId: 'active' },
    ].map(event => `data: ${JSON.stringify(event)}\n\n`).join('');
    vi.stubGlobal('fetch', async () => new Response(wire));
    const signals: AguiStreamSignal[] = [];
    await expect(runAdvisorStream('s', 'agent', 'q', { onSignal: signal => signals.push(signal), onTextDelta: vi.fn(), onAdvisorEvent: vi.fn() })).resolves.toBeUndefined();
    expect(signals.map(signal => signal.kind)).toEqual(['run-started', 'text-delta', 'run-finished']);
  });

  it('uses the actual SSE boundaries once rather than duplicate legacy callbacks', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null });
    const wire = [
      { type: 'TEXT_MESSAGE_START', messageId: 'm1' },
      { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm1', delta: 'before' },
      { type: 'TOOL_CALL_START', toolCallId: 'a', toolCallName: 'read_file' },
      { type: 'TOOL_CALL_RESULT', toolCallId: 'a', content: 'result' },
      { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm2', delta: 'after' },
      { type: 'TEXT_MESSAGE_END', messageId: 'm2' },
      { type: 'RUN_FINISHED' },
    ].map(event => `data: ${JSON.stringify({ ...event, threadId: 's', runId: 'run' })}\n\n`).join('');
    vi.stubGlobal('fetch', async () => new Response(wire));
    let state = startRunState();
    const legacy = vi.fn();
    await runAdvisorStream('s', 'agent', 'question', { onSignal: signal => { state = applyRunSignal(state, signal); }, onTextDelta: legacy, onAdvisorEvent: legacy });
    expect(state.parts?.map(part => part.kind)).toEqual(['text', 'tool', 'text']);
    expect(state.streamingText).toBe('beforeafter');
    expect(legacy).not.toHaveBeenCalled();
  });

  it('preserves proposal identity for approval instead of using the enclosing snapshot seq', () => {
    const state = applyRunSignal(startRunState(), { kind: 'advisor-event', runId: 'run', value: { kind: 'proposal', seq: 4, turn: 1, name: 'reviewer' } });
    const saved = toAdvisorMessageInput(state, null);
    const [turn] = buildBlocks([
      { seq: 4, turn: 1, kind: 'proposal', payload: { name: 'reviewer' } },
      { seq: 9, turn: 1, kind: 'advisor_message', payload: { ...saved } },
    ], false);
    const proposals = renderedBlocks(turn!).filter(block => block.kind === 'proposal');
    expect(proposals).toHaveLength(1);
    expect(proposals[0].event?.seq).toBe(4);
  });

  it('keeps a failed streamed reply attached to its original turn after the visitor is replayed', () => {
    let state = withVisitorEntry(startRunState(), { seq: 42, turn: 5, kind: 'user_message', payload: { text: 'question' } });
    state = applyRunSignal(state, { kind: 'text-delta', runId: 'run', messageId: 'm1', delta: 'partial reply' });
    const replayed = finalizeRunState({ ...state, runError: 'connection lost' }, new Set([42]), false);
    expect(replayed.liveEvents).toEqual([]);
    expect(replayed.turn).toBe(5);
    expect(replayed.parts?.[0].id).toBe(state.parts?.[0].id);
  });

  it('does not record reasoning in a run whose Think switch is off', () => {
    const state = signals.reduce(applyRunSignal, { ...startRunState(), thinkingEnabled: false });
    expect(state.thinking.status).toBe('idle');
    expect(state.parts?.some(part => part.kind === 'reasoning')).toBe(false);
    expect(toAdvisorMessageInput(state, null).reasoning).toBeUndefined();
  });

  it('does not display an empty reasoning lifecycle as an actual thought', () => {
    let state = applyRunSignal(startRunState(), { kind: 'reasoning-start', runId: 'run' });
    state = applyRunSignal(state, { kind: 'reasoning-end', runId: 'run' });
    expect(state.thinking.status).toBe('idle');
    const [turn] = buildBlocks([{ seq: 1, turn: 1, kind: 'advisor_message', payload: { partsVersion: 1, parts: state.parts } }], true);
    expect(renderedBlocks(turn!).some(block => block.kind === 'thinking')).toBe(false);
  });

  it('preserves boundaries even when text resumes with the same message id', () => {
    const parts = round().parts ?? [];
    expect(parts.map(part => part.kind)).toEqual(['reasoning', 'text', 'tool', 'tool', 'text', 'reasoning', 'text']);
    expect(parts.flatMap(part => part.kind === 'text' ? [part.text] : [])).toEqual(['read files', 'check results', 'answer']);
    expect(parts.filter(part => part.kind === 'tool').map(part => [part.tool.id, part.tool.result])).toEqual([['a', 'read'], ['b', 'found']]);
  });

  it('retains wire message identity and start/end boundaries', () => {
    const content = classifyAguiLine('data: {"type":"TEXT_MESSAGE_CONTENT","runId":"run","messageId":"m1","delta":"hi"}');
    expect(content).toMatchObject({ kind: 'text-delta', runId: 'run', messageId: 'm1' });
    expect(classifyAguiLine('data: {"type":"TEXT_MESSAGE_START","runId":"run","messageId":"m1"}')).toMatchObject({ kind: 'text-start' });
    expect(classifyAguiLine('data: {"type":"TEXT_MESSAGE_END","runId":"run","messageId":"m1"}')).toMatchObject({ kind: 'text-end' });
  });

  it('replays the same visible order and anchors without duplicate aggregate tools or text', () => {
    const saved = toAdvisorMessageInput(round(), null);
    expect(saved.partsVersion).toBe(1);
    const [turn] = buildBlocks([
      { seq: 1, turn: 1, kind: 'tool_call', payload: { id: 'a', name: 'read_file', status: 'done' } },
      { seq: 2, turn: 1, kind: 'advisor_message', payload: { ...saved } },
    ], false);
    const blocks = renderedBlocks(turn!);
    expect(blocks.map(block => block.kind)).toEqual(['thinking', 'advisor_message', 'tool_call', 'tool_call', 'advisor_message', 'thinking', 'advisor_message']);
    const live = toAdvisorMessageInput(round(), null);
    const [liveTurn] = buildBlocks([{ seq: 1000000001, turn: 1, kind: 'advisor_message', payload: { ...live } }], true);
    expect(renderedBlocks(liveTurn!).map(block => block.anchor)).toEqual(blocks.map(block => block.anchor));
  });
});
