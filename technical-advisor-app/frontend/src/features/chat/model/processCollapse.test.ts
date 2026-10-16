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

import { describe, expect, it } from 'vitest';
import type { ConversationPart, EventEntry } from '@/api/advisor';
import { classifyAguiLine } from '../aguiClient';
import { buildBlocks } from './blocks';
import { applyRunSignal, startRunState, toAdvisorMessageInput } from '../useAdvisorConversation';

const parts: ConversationPart[] = [
  { id: 'r:1', runId: 'r', kind: 'reasoning', text: 'thinking', status: 'done' },
  { id: 'r:2', runId: 'r', kind: 'text', messageId: 'before', text: 'checking files', status: 'done' },
  { id: 'r:3', runId: 'r', kind: 'tool', tool: { id: 't', name: 'read_file', status: 'done' }, status: 'done' },
  { id: 'r:4', runId: 'r', kind: 'text', messageId: 'final-a', text: 'answer A', status: 'done' },
  { id: 'r:5', runId: 'r', kind: 'text', messageId: 'final-b', text: 'answer B', status: 'done' },
];
const presentation = { runId: 'r', runStartedAt: 1000, finalStartedAt: 83000, runEndedAt: 95000,
  finalMessageIds: ['final-a', 'final-b'], outcome: 'completed' };
function entry(extra: Record<string, unknown> = {}): EventEntry {
  return { seq: 2, turn: 1, kind: 'advisor_message', payload: { partsVersion: 1, parts, presentationVersion: 1, presentation, ...extra } };
}

describe('confirmed round presentation', () => {
  it('folds a verified iteration-limit summary while exposing the limit notice', () => {
    const [turn] = buildBlocks([entry({ presentation: { ...presentation, outcome: 'limited', terminationReason: 'MAX_ITERATIONS' } })], false);
    expect(turn.collapsedByDefault).toBe(true);
    expect(turn.completionNotice).toBe('iteration_limit');
    expect(turn.finalBlocks?.map(block => block.text)).toEqual(['answer A', 'answer B']);
    expect(turn.durationMs).toBe(82000);
  });
  it('uses total run time for a repaired historical summary when its first content timestamp was not retained', () => {
    const [turn] = buildBlocks([entry({ presentation: { ...presentation, finalStartedAt: undefined, outcome: 'limited', terminationReason: 'MAX_ITERATIONS' } })], false);
    expect(turn.collapsedByDefault).toBe(true);
    expect(turn.durationKind).toBe('total');
    expect(turn.durationMs).toBe(94000);
  });
  it('folds intermediate text and thinking while keeping every final text block', () => {
    const [turn] = buildBlocks([entry()], false);
    expect(turn.collapsedByDefault).toBe(true);
    expect(turn.process.map(block => block.kind)).toEqual(['thinking', 'advisor_message', 'tool_call']);
    expect(turn.finalBlocks?.map(block => block.text)).toEqual(['answer A', 'answer B']);
    expect(turn.durationMs).toBe(82000);
  });
  it.each(['failed', 'cancelled', 'awaiting_action', 'unknown'])('keeps %s rounds open', outcome => {
    const [turn] = buildBlocks([entry({ presentation: { ...presentation, outcome } })], false);
    expect(turn.collapsedByDefault).toBe(false);
    expect(turn.durationMs).toBeNull();
  });
  it('does not infer a final answer when confirmed metadata cannot identify one', () => {
    const [turn] = buildBlocks([entry({ presentation: { ...presentation, finalMessageIds: [], finalStartedAt: undefined } })], false);
    expect(turn.collapsedByDefault).toBe(false);
    expect(turn.finalText).toBeNull();
  });
  it('does not fold incomplete or incorrectly referenced presentation metadata', () => {
    for (const extra of [{ ...presentation, finalMessageIds: ['missing'] }, { ...presentation, runId: 'other' }, { ...presentation, finalStartedAt: 999 }]) {
      const [turn] = buildBlocks([entry({ presentation: extra })], false);
      expect(turn.collapsedByDefault).toBe(false);
      expect(turn.durationMs).toBeNull();
    }
  });
  it('keeps legacy interrupted text visible instead of treating it as the final answer', () => {
    const [turn] = buildBlocks([entry({ presentationVersion: undefined, presentation: undefined,
      parts: [parts[2], { ...parts[3], status: 'failed' }] })], false);
    expect(turn.collapsedByDefault).toBe(false);
    expect(turn.finalText).toBeNull();
    expect(turn.process.some(block => block.text === 'answer A')).toBe(true);
  });
  it('retains wire run identity on errors so stale failures cannot affect the active run', () => {
    const active = applyRunSignal(startRunState(), { kind: 'run-started', runId: 'new' });
    for (const event of [{ type: 'RUN_ERROR', runId: 'old', message: 'old failure' },
      { type: 'RAW', runId: 'old', event: { error: 'old raw failure' } }]) {
      const signal = classifyAguiLine(`data: ${JSON.stringify(event)}`)!;
      expect(signal.runId).toBe('old');
      expect(applyRunSignal(active, signal).outcome).toBe('unknown');
    }
  });
  it('ignores terminal events from a different run', () => {
    const active = applyRunSignal(startRunState(), { kind: 'run-started', runId: 'new' });
    const next = applyRunSignal(active, { kind: 'run-finished', runId: 'old', interrupted: false });
    expect(next.outcome).toBe('unknown');
    expect(next.runId).toBe('new');
  });
  it('persists an honest unknown outcome on EOF without inventing server timing', () => {
    let state = applyRunSignal(startRunState(), { kind: 'run-started', runId: 'r' });
    state = applyRunSignal(state, { kind: 'text-delta', runId: 'r', messageId: 'm', delta: 'partial' });
    const saved = toAdvisorMessageInput(state, null);
    expect(saved.presentationVersion).toBe(1);
    expect(saved.presentation).toMatchObject({ runId: 'r', outcome: 'unknown', finalMessageIds: [] });
    expect(saved.presentation?.runStartedAt).toBeUndefined();
    const [turn] = buildBlocks([{ seq: 1, turn: 1, kind: 'advisor_message', payload: { ...saved } }], false);
    expect(turn.collapsedByDefault).toBe(false);
  });
  it('classifies normal finish and suspended finish separately from EOF', () => {
    expect(classifyAguiLine('data: {"type":"RUN_STARTED","runId":"r"}')).toMatchObject({ kind: 'run-started', runId: 'r' });
    expect(classifyAguiLine('data: {"type":"RUN_FINISHED","runId":"r"}')).toMatchObject({ kind: 'run-finished', interrupted: false });
    expect(classifyAguiLine('data: {"type":"RUN_FINISHED","runId":"r","outcome":{"type":"interrupt","interrupts":[{}]}}')).toMatchObject({ kind: 'run-finished', interrupted: true });
  });
  it('carries server timing through live state and the saved message without extra parts', () => {
    const signal = classifyAguiLine(`data: ${JSON.stringify({ type: 'CUSTOM', name: 'advisor_presentation', runId: 'r', value: { presentationVersion: 1, ...presentation } })}`);
    expect(signal).not.toBeNull();
    const state = applyRunSignal({ ...startRunState(), parts }, signal!);
    expect(state.parts).toEqual(parts);
    expect(toAdvisorMessageInput(state, null)).toMatchObject({ presentationVersion: 1, presentation });
  });
});
