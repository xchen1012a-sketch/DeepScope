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
import type { EventEntry } from '@/api/advisor';
import {
  applySubagentSignal,
  EMPTY_SUBAGENT_ACCUMULATOR,
  registerSubagentDispatch,
  subagentStepsFromReplay,
  toSubagentRuns,
  type SubagentAccumulator,
} from './subagentSteps';

function event(seq: number, kind: string, payload: Record<string, unknown> = {}): EventEntry {
  return { seq, turn: 1, kind, payload, createdAt: seq };
}

/** 已经派发了一个子任务、等待子智能体事件到来的累积器。 */
function dispatched(subtaskId = 's1'): SubagentAccumulator {
  return registerSubagentDispatch(
    EMPTY_SUBAGENT_ACCUMULATOR,
    event(1, 'dispatch', { subtaskId, name: `${subtaskId}-agent` }),
  );
}

describe('registerSubagentDispatch', () => {
  it('queues a dispatched subtask with its display name once', () => {
    const state = registerSubagentDispatch(
      registerSubagentDispatch(dispatched(), event(2, 'dispatch', { subtaskId: 's1', name: '依赖调查员' })),
      event(3, 'dispatch', { subtaskId: 's2', name: '架构调查员' }),
    );
    expect(state.waiting).toEqual([
      { subtaskId: 's1', name: 's1-agent' },
      { subtaskId: 's2', name: '架构调查员' },
    ]);
  });

  it('ignores a dispatch without a subtask id', () => {
    expect(registerSubagentDispatch(EMPTY_SUBAGENT_ACCUMULATOR, event(1, 'dispatch', {}))).toBe(
      EMPTY_SUBAGENT_ACCUMULATOR,
    );
  });
});

describe('applySubagentSignal', () => {
  it('claims the next dispatch for a new subagent source and keeps its steps in order', () => {
    let state = dispatched();
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '先看构建脚本',
      source: 'sess/code-surveyor',
    });
    state = applySubagentSignal(state, {
      kind: 'subagent-tool-call',
      toolCallId: 'c9',
      name: 'read_file',
      source: 'sess/code-surveyor',
    });
    state = applySubagentSignal(state, {
      kind: 'subagent-tool-result',
      toolCallId: 'c9',
      content: 'ok',
      source: 'sess/code-surveyor',
    });
    expect(state.claimed).toEqual(['s1']);
    expect(state.waiting).toEqual([]);
    expect(state.steps.map((step) => step.kind)).toEqual(['thinking', 'tool_call']);
    expect(state.steps.map((step) => step.order)).toEqual([1, 2]);
    expect(state.steps[1]?.tool).toMatchObject({ id: 'c9', name: 'read_file', result: 'ok', status: 'done' });
  });

  it('merges streaming thinking of the same step instead of adding a row per delta', () => {
    let state = dispatched();
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: '先看', source: 'sess/a' });
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: '构建脚本', source: 'sess/a' });
    expect(state.steps).toHaveLength(1);
    expect(state.steps[0]?.text).toBe('先看构建脚本');
  });

  it('pairs a second source with the second dispatch', () => {
    let state = registerSubagentDispatch(dispatched(), event(2, 'dispatch', { subtaskId: 's2' }));
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: 'A', source: 'sess/a' });
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: 'B', source: 'sess/b' });
    expect(state.steps.map((step) => step.subtaskId)).toEqual(['s1', 's2']);
  });

  it('drops events from an unknown source when no dispatch is waiting', () => {
    const state = applySubagentSignal(EMPTY_SUBAGENT_ACCUMULATOR, {
      kind: 'subagent-thinking',
      text: '无中生有',
      source: 'sess/ghost',
    });
    expect(state).toBe(EMPTY_SUBAGENT_ACCUMULATOR);
  });

  it('does not count a lifecycle end as a step', () => {
    let state = dispatched();
    state = applySubagentSignal(state, { kind: 'subagent-lifecycle', phase: 'AGENT_START', source: 'sess/a' });
    state = applySubagentSignal(state, { kind: 'subagent-lifecycle', phase: 'AGENT_END', source: 'sess/a' });
    expect(state.steps).toEqual([]);
  });

  it('ignores a tool result whose tool call was never seen', () => {
    let state = dispatched();
    state = applySubagentSignal(state, {
      kind: 'subagent-tool-result',
      toolCallId: 'unknown',
      content: 'x',
      source: 'sess/a',
    });
    expect(state.steps).toEqual([]);
  });

  it('records a failed subagent tool result as failed', () => {
    let state = dispatched();
    state = applySubagentSignal(state, { kind: 'subagent-tool-call', toolCallId: 'c1', source: 'sess/a' });
    state = applySubagentSignal(state, {
      kind: 'subagent-tool-result',
      toolCallId: 'c1',
      state: 'failed',
      source: 'sess/a',
    });
    expect(state.steps[0]?.tool?.status).toBe('failed');
  });
});

describe('name-keyed claiming', () => {
  /** 两个不同名派发，队列顺序是「依赖调查员、架构调查员」。 */
  function twoDispatches(): SubagentAccumulator {
    const state = registerSubagentDispatch(
      EMPTY_SUBAGENT_ACCUMULATOR,
      event(1, 'dispatch', { subtaskId: 't1', name: '依赖调查员' }),
    );
    return registerSubagentDispatch(state, event(2, 'dispatch', { subtaskId: 't2', name: '架构调查员' }));
  }

  it('attaches out-of-order subagent events to the right dispatch by name', () => {
    let state = twoDispatches();
    // 第二个派发的过程先到：按到达顺序会挂到 t1，按名字才对
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '架构',
      name: '架构调查员',
      source: 'sess/arch',
    });
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '依赖',
      name: '依赖调查员',
      source: 'sess/deps',
    });
    expect(state.steps.find((step) => step.text === '架构')?.subtaskId).toBe('t2');
    expect(state.steps.find((step) => step.text === '依赖')?.subtaskId).toBe('t1');
    expect(state.waiting).toEqual([]);
  });

  it('gives each of two same-named dispatches its own process in FIFO order', () => {
    let state = registerSubagentDispatch(
      EMPTY_SUBAGENT_ACCUMULATOR,
      event(1, 'dispatch', { subtaskId: 'r1', name: '事实调查员' }),
    );
    state = registerSubagentDispatch(state, event(2, 'dispatch', { subtaskId: 'r2', name: '事实调查员' }));
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '第一轮',
      name: '事实调查员',
      source: 'sess/one',
    });
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '第二轮',
      name: '事实调查员',
      source: 'sess/two',
    });
    expect(state.steps.find((step) => step.text === '第一轮')?.subtaskId).toBe('r1');
    expect(state.steps.find((step) => step.text === '第二轮')?.subtaskId).toBe('r2');
  });

  it('falls back to the head of the queue when the signal carries no name', () => {
    let state = twoDispatches();
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: 'A', source: 'sess/a' });
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: 'B', source: 'sess/b' });
    expect(state.steps.find((step) => step.text === 'A')?.subtaskId).toBe('t1');
    expect(state.steps.find((step) => step.text === 'B')?.subtaskId).toBe('t2');
  });

  it('falls back to the head of the queue when no queued dispatch has that name', () => {
    let state = twoDispatches();
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '名字对不上',
      name: '没登记过的名字',
      source: 'sess/x',
    });
    expect(state.steps[0]?.subtaskId).toBe('t1');
  });

  it('claims by the name carried on a lifecycle event', () => {
    let state = twoDispatches();
    state = applySubagentSignal(state, {
      kind: 'subagent-lifecycle',
      phase: 'AGENT_START',
      name: '架构调查员',
      source: 'sess/arch',
    });
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: '架构', source: 'sess/arch' });
    state = applySubagentSignal(state, {
      kind: 'subagent-lifecycle',
      phase: 'AGENT_START',
      name: '依赖调查员',
      source: 'sess/deps',
    });
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: '依赖', source: 'sess/deps' });
    expect(state.steps.find((step) => step.text === '架构')?.subtaskId).toBe('t2');
    expect(state.steps.find((step) => step.text === '依赖')?.subtaskId).toBe('t1');
  });

  it('keeps a source on its first claim even when later events carry another name', () => {
    let state = twoDispatches();
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '第一次',
      name: '架构调查员',
      source: 'sess/arch',
    });
    state = applySubagentSignal(state, {
      kind: 'subagent-thinking',
      text: '第二次',
      name: '依赖调查员',
      source: 'sess/arch',
    });
    // 同一 source 的后续事件沿用首次认领，不会被名字改成另一个派发
    expect(state.steps).toHaveLength(1);
    expect(state.steps[0]?.subtaskId).toBe('t2');
    expect(state.steps[0]?.text).toBe('第一次第二次');
  });

  it('still drops an unknown source instead of inventing a card', () => {
    const state = applySubagentSignal(EMPTY_SUBAGENT_ACCUMULATOR, {
      kind: 'subagent-thinking',
      text: '无中生有',
      name: '谁也不是',
      source: 'sess/ghost',
    });
    expect(state).toBe(EMPTY_SUBAGENT_ACCUMULATOR);
  });
});

describe('toSubagentRuns', () => {
  it('groups steps per task and keeps the arrival order', () => {
    let state = dispatched();
    state = applySubagentSignal(state, { kind: 'subagent-thinking', text: '想', source: 'sess/a' });
    state = applySubagentSignal(state, {
      kind: 'subagent-tool-call',
      toolCallId: 'c1',
      name: 'read_file',
      source: 'sess/a',
    });
    expect(toSubagentRuns(state)).toEqual([
      {
        subtaskId: 's1',
        events: [
          { kind: 'thinking', text: '想' },
          expect.objectContaining({ kind: 'tool_call', toolCallId: 'c1', name: 'read_file' }),
        ],
      },
    ]);
  });

  it('returns nothing for a round without subagent process', () => {
    expect(toSubagentRuns(EMPTY_SUBAGENT_ACCUMULATOR)).toEqual([]);
  });
});

describe('subagentStepsFromReplay', () => {
  it('keeps only steps whose dispatch exists', () => {
    const steps = subagentStepsFromReplay([
      event(1, 'dispatch', { subtaskId: 's1' }),
      event(2, 'subagent_step', { subtaskId: 'ghost', order: 1, kind: 'thinking', text: 'x' }),
      event(3, 'subagent_step', { subtaskId: 's1', order: 1, kind: 'thinking', text: 'y' }),
    ]);
    expect([...steps.keys()]).toEqual(['s1']);
  });

  it('falls back to the dispatched name when a row carries no subtask id', () => {
    const steps = subagentStepsFromReplay([
      event(1, 'dispatch', { subtaskId: 't1', name: '依赖调查员' }),
      event(2, 'dispatch', { subtaskId: 't2', name: '架构调查员' }),
      event(3, 'subagent_step', { name: '架构调查员', order: 1, kind: 'thinking', text: '架构' }),
      event(4, 'subagent_step', { name: '依赖调查员', order: 1, kind: 'thinking', text: '依赖' }),
    ]);
    expect(steps.get('t2')?.map((step) => step.text)).toEqual(['架构']);
    expect(steps.get('t1')?.map((step) => step.text)).toEqual(['依赖']);
  });

  it('gives same-named dispatches one row each in FIFO order', () => {
    const steps = subagentStepsFromReplay([
      event(1, 'dispatch', { subtaskId: 'r1', name: '事实调查员' }),
      event(2, 'dispatch', { subtaskId: 'r2', name: '事实调查员' }),
      event(3, 'subagent_step', { name: '事实调查员', order: 1, kind: 'thinking', text: '一' }),
      event(4, 'subagent_step', { name: '事实调查员', order: 2, kind: 'thinking', text: '二' }),
    ]);
    expect(steps.get('r1')?.map((step) => step.text)).toEqual(['一']);
    expect(steps.get('r2')?.map((step) => step.text)).toEqual(['二']);
  });

  it('returns steps sorted by order even when the rows arrive out of order', () => {
    const steps = subagentStepsFromReplay([
      event(1, 'dispatch', { subtaskId: 's1' }),
      event(2, 'subagent_step', { subtaskId: 's1', order: 2, kind: 'thinking', text: 'second' }),
      event(3, 'subagent_step', { subtaskId: 's1', order: 1, kind: 'thinking', text: 'first' }),
    ]);
    expect(steps.get('s1')?.map((step) => step.text)).toEqual(['first', 'second']);
  });

  it('leaves the duration unset when the replayed tool has no recorded start', () => {
    const steps = subagentStepsFromReplay([
      event(1, 'dispatch', { subtaskId: 's1' }),
      event(2, 'subagent_step', {
        subtaskId: 's1',
        order: 1,
        kind: 'tool_call',
        toolCallId: 'c1',
        name: 'read_file',
        status: 'done',
        endedAt: 9_000,
      }),
    ]);
    expect(steps.get('s1')?.[0]?.tool?.startedAt).toBeNull();
  });
});
