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

import { describe, expect, it, vi } from 'vitest';
import {
  advisorEventToEntry,
  appendLiveEvent,
  applyRunSignal,
  buildAgentPrompt,
  endRun,
  finalizeRunState,
  getRunState,
  groupSubagents,
  hasRecordableContent,
  IDLE_RUN,
  INITIAL_THINKING,
  lastIsQuestion,
  latestPendingProposal,
  latestRoute,
  matchProposalResolutions,
  mergeTimeline,
  nextThinkingState,
  startRunState,
  subscribeRun,
  toAttachmentSummaries,
  updateRunState,
  usageFrom,
  withVisitorEntry,
  type ConversationEvent,
  type RunState,
} from './useAdvisorConversation';
import { classifyAguiLine, sseLineData } from './aguiClient';
import { EMPTY_SUBAGENT_ACCUMULATOR } from './subagentSteps';

function entry(seq: number, kind: string, payload: Record<string, unknown> = {}): ConversationEvent {
  return { seq, turn: 1, kind, payload, createdAt: seq };
}

/** 把 mock 的 SSE 行解析成流信号；无法识别时直接失败，方便定位问题行。 */
function signalOf(line: string) {
  const signal = classifyAguiLine(line);
  if (!signal) throw new Error(`line was not classified: ${line}`);
  return signal;
}

describe('advisorEventToEntry', () => {
  it('flattens the CUSTOM value into a timeline entry with kind/seq/turn stripped into the envelope', () => {
    const entry = advisorEventToEntry(
      { kind: 'route', seq: 3, turn: 2, mode: 'specialist', skillName: '仓库尽调', reason: '命中' },
      1,
    );
    expect(entry).toEqual({
      seq: 3,
      turn: 2,
      kind: 'route',
      payload: { mode: 'specialist', skillName: '仓库尽调', reason: '命中' },
      createdAt: expect.any(Number) as unknown,
    });
  });

  it('falls back to the synthetic seq when the event was not persisted', () => {
    const entry = advisorEventToEntry({ kind: 'advisor_note', text: '开始' }, 1_000_000_001);
    expect(entry?.seq).toBe(1_000_000_001);
    expect(entry?.payload).toEqual({ text: '开始' });
  });

  it('rejects values without a kind', () => {
    expect(advisorEventToEntry({ seq: 1, turn: 1 }, 99)).toBeNull();
  });
});

describe('mergeTimeline', () => {
  it('merges replayed and live entries by seq without duplicates after a refetch', () => {
    const replayed = [entry(1, 'user_message'), entry(2, 'route'), entry(3, 'question')];
    // 一轮结束后 detail 重取带回同样的 seq 3，实时侧同一 seq 只保留一份
    const live = [entry(2, 'route'), entry(3, 'question'), entry(1_000_000_001, 'advisor_note')];
    const merged = mergeTimeline(replayed, live);
    expect(merged.map((event) => event.seq)).toEqual([1, 2, 3, 1_000_000_001]);
  });

  it('sorts out-of-order input by seq', () => {
    const merged = mergeTimeline([entry(4, 'deliverable'), entry(1, 'user_message')], [entry(3, 'route'), entry(2, 'route')]);
    expect(merged.map((event) => event.seq)).toEqual([1, 2, 3, 4]);
  });

  it('returns an empty timeline for empty inputs', () => {
    expect(mergeTimeline([], [])).toEqual([]);
  });
});

describe('latestRoute', () => {
  it('picks the last route event across the whole timeline', () => {
    const events = [
      entry(2, 'route', { mode: 'general' }),
      entry(3, 'deliverable'),
      entry(5, 'route', { mode: 'specialist', skillName: '架构评审' }),
    ];
    expect(latestRoute(events)).toEqual({ mode: 'specialist', skillName: '架构评审' });
  });

  it('returns null when no route event exists', () => {
    expect(latestRoute([entry(1, 'user_message'), entry(2, 'advisor_note')])).toBeNull();
  });
});

describe('lastIsQuestion', () => {
  it('is true only when the last event is a question', () => {
    expect(lastIsQuestion([entry(1, 'user_message'), entry(2, 'question')])).toBe(true);
    expect(lastIsQuestion([entry(1, 'question'), entry(2, 'deliverable')])).toBe(false);
    expect(lastIsQuestion([])).toBe(false);
  });
});

describe('sseLineData', () => {
  it('extracts the JSON payload from data lines and ignores other lines', () => {
    expect(sseLineData('data: {"type":"RUN_STARTED"}')).toBe('{"type":"RUN_STARTED"}');
    expect(sseLineData('data:{"type":"RUN_FINISHED"}')).toBe('{"type":"RUN_FINISHED"}');
    expect(sseLineData(': keep-alive')).toBeNull();
    expect(sseLineData('')).toBeNull();
  });
});

describe('nextThinkingState', () => {
  it('accumulates reasoning text while thinking and collapses when body text arrives', () => {
    const lines = [
      'data: {"type":"REASONING_START","threadId":"s1","runId":"r1"}',
      'data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"第一步，"}',
      'data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"排查依赖"}',
    ];
    let state = INITIAL_THINKING;
    for (const line of lines) {
      state = nextThinkingState(state, signalOf(line));
    }
    expect(state).toEqual({ status: 'thinking', text: '第一步，排查依赖' });

    state = nextThinkingState(
      state,
      signalOf('data: {"type":"TEXT_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m2","delta":"结论"}'),
    );
    expect(state).toEqual({ status: 'thought', text: '第一步，排查依赖' });
  });

  it('starts thinking from a bare reasoning delta and ends on REASONING_END', () => {
    let state = nextThinkingState(
      INITIAL_THINKING,
      signalOf('data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"思考"}'),
    );
    expect(state).toEqual({ status: 'thinking', text: '思考' });
    state = nextThinkingState(state, signalOf('data: {"type":"REASONING_END","threadId":"s1","runId":"r1"}'));
    expect(state).toEqual({ status: 'thought', text: '思考' });
  });

  it('stays idle for streams without reasoning events', () => {
    const state = nextThinkingState(
      INITIAL_THINKING,
      signalOf('data: {"type":"TEXT_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"直接回答"}'),
    );
    expect(state).toEqual(INITIAL_THINKING);
  });
});

describe('groupSubagents', () => {
  it('groups dispatch, facts and settled events by task id in arrival order', () => {
    const events = [
      entry(1, 'user_message'),
      entry(2, 'dispatch', { subtaskId: 't1', name: '依赖调查员', spec: { task: '查清依赖风险' } }),
      entry(3, 'dispatch', { subtaskId: 't2', name: '架构调查员', spec: { task: '梳理架构' } }),
      entry(4, 'subagent_facts', {
        subagentId: 't1',
        facts: [
          { id: 'F1', fact: 'a', source: 's' },
          { id: 'F2', fact: 'b', source: 's' },
        ],
      }),
      entry(5, 'subagent_settled', { subagentId: 't2', status: 'done', summary: '已梳理' }),
    ];
    expect(groupSubagents(events)).toEqual([
      {
        id: 't1',
        name: '依赖调查员',
        task: '查清依赖风险',
        status: 'running',
        startedAt: 2,
        endedAt: undefined,
        factCount: 2,
        facts: [
          { id: 'F1', fact: 'a', source: 's' },
          { id: 'F2', fact: 'b', source: 's' },
        ],
        summary: '',
        steps: [],
      },
      {
        id: 't2',
        name: '架构调查员',
        task: '梳理架构',
        status: 'done',
        startedAt: 3,
        endedAt: 5,
        factCount: 0,
        facts: [],
        summary: '已梳理',
        steps: [],
      },
    ]);
  });

  it('keeps the dispatch time as the start and the settled time as the end', () => {
    const [record] = groupSubagents([
      entry(2, 'dispatch', { subtaskId: 's1', name: 'code-surveyor', spec: { task: '看构建脚本' } }),
      entry(9, 'subagent_settled', { subagentId: 's1', status: 'done', summary: '结论' }),
    ]);
    expect(record.startedAt).toBe(2);
    expect(record.endedAt).toBe(9);
  });

  it('keeps the recorded facts so the read-only view can replay them', () => {
    const [record] = groupSubagents([
      entry(2, 'dispatch', { subtaskId: 's1', name: 'code-surveyor', spec: { task: 'T' } }),
      entry(3, 'subagent_facts', { subagentId: 's1', facts: [{ id: 'F1', fact: 'a', source: 's' }] }),
    ]);
    expect(record.facts).toEqual([{ id: 'F1', fact: 'a', source: 's' }]);
    expect(record.factCount).toBe(1);
  });

  it('rebuilds one ordered subagent record from the replay', () => {
    const [record] = groupSubagents([
      entry(1, 'dispatch', { subtaskId: 's1', name: 'code-surveyor', spec: { task: '看构建脚本' } }),
      entry(2, 'subagent_step', { subtaskId: 's1', order: 1, kind: 'thinking', text: '先看构建脚本' }),
      entry(3, 'subagent_step', {
        subtaskId: 's1',
        order: 2,
        kind: 'tool_call',
        toolCallId: 'c9',
        name: 'read_file',
        status: 'done',
      }),
      entry(4, 'subagent_settled', { subagentId: 's1', status: 'done', summary: '结论' }),
    ]);
    expect(record.steps.map((step) => step.kind)).toEqual(['thinking', 'tool_call']);
    expect(record.steps.map((step) => step.order)).toEqual([1, 2]);
    expect(record.steps[1]?.tool?.name).toBe('read_file');
    expect(record.status).toBe('done');
  });

  it('ignores steps that point at no dispatch instead of inventing a card', () => {
    expect(
      groupSubagents([entry(1, 'subagent_step', { subtaskId: 'ghost', order: 1, kind: 'thinking' })]),
    ).toHaveLength(0);
  });

  it('settles dispatched agents and maps unknown statuses to failed', () => {
    const events = [
      entry(1, 'dispatch', { subtaskId: 't1', name: 'A', spec: { task: 'T' } }),
      entry(2, 'subagent_settled', { subagentId: 't1', status: 'timeout', summary: '超时了' }),
      entry(3, 'dispatch', { subtaskId: 't2', name: 'B', spec: { task: 'T2' } }),
      entry(4, 'subagent_settled', { subagentId: 't2', status: 'mystery', summary: 'S' }),
    ];
    expect(groupSubagents(events).map(({ status }) => status)).toEqual(['timeout', 'failed']);
  });

  it('returns an empty list for conversations without subagent events', () => {
    expect(groupSubagents([entry(1, 'user_message'), entry(2, 'decompose')])).toEqual([]);
  });
});

describe('matchProposalResolutions / latestPendingProposal', () => {
  it('pairs a resolution with the first same-name proposal and clears the pending state', () => {
    const events = [
      entry(1, 'user_message'),
      entry(2, 'proposal', { name: '尽调报告生成器', description: '生成报告', goal: '沉淀报告技能', content: '# 草稿' }),
      entry(3, 'advisor_message'),
      entry(4, 'proposal_resolved', { approved: true, name: '尽调报告生成器', file: 'skills/report.md' }),
    ];
    const resolutions = matchProposalResolutions(events);
    expect(resolutions.get(2)).toEqual({ approved: true, name: '尽调报告生成器', file: 'skills/report.md' });
    expect(latestPendingProposal(events)).toBeNull();
  });

  it('matches same-name proposals FIFO and keeps later ones pending', () => {
    const events = [
      entry(1, 'proposal', { name: '助手' }),
      entry(2, 'proposal', { name: '助手' }),
      entry(3, 'proposal_resolved', { approved: false, name: '助手' }),
      entry(4, 'proposal', { name: '另一个' }),
    ];
    const resolutions = matchProposalResolutions(events);
    expect(resolutions.get(1)).toEqual({ approved: false, name: '助手' });
    expect(resolutions.has(2)).toBe(false);
    expect(resolutions.has(4)).toBe(false);
    expect(latestPendingProposal(events)?.seq).toBe(4);
  });

  it('accepts the string "true" as approved and ignores resolutions without a pending proposal', () => {
    const events = [
      entry(1, 'proposal', { name: 'A' }),
      entry(2, 'proposal_resolved', { approved: 'true', name: 'A' }),
      entry(3, 'proposal_resolved', { approved: true, name: 'missing' }),
    ];
    const resolutions = matchProposalResolutions(events);
    expect(resolutions.get(1)).toMatchObject({ approved: true, name: 'A' });
    expect(resolutions.size).toBe(1);
  });

  it('returns no pending proposal for conversations without proposal events', () => {
    const events = [entry(1, 'user_message'), entry(2, 'route'), entry(3, 'advisor_message')];
    expect(matchProposalResolutions(events).size).toBe(0);
    expect(latestPendingProposal(events)).toBeNull();
  });
});

describe('applyRunSignal', () => {
  it('accumulates reasoning and body text through the store-shaped run state', () => {
    let state = startRunState();
    state = applyRunSignal(state, signalOf('data: {"type":"REASONING_START","threadId":"s1","runId":"r1"}'));
    state = applyRunSignal(
      state,
      signalOf('data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"先查依赖"}'),
    );
    state = applyRunSignal(
      state,
      signalOf('data: {"type":"TEXT_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m2","delta":"结论"}'),
    );
    expect(state.running).toBe(true);
    expect(state.thinking).toEqual({ status: 'thought', text: '先查依赖' });
    expect(state.streamingText).toBe('结论');
  });

  it('returns the same reference for signals that change nothing', () => {
    const state = startRunState();
    expect(applyRunSignal(state, { kind: 'advisor-event', value: {} })).toBe(state);
  });

  it('folds a tool call and replaces usage for the same step', () => {
    vi.useFakeTimers();
    try {
      let state = startRunState();
      vi.setSystemTime(1_000);
      state = applyRunSignal(state, { kind: 'tool-start', id: 'c1', name: 'read_file' });
      vi.setSystemTime(1_100);
      state = applyRunSignal(state, { kind: 'tool-args', id: 'c1', delta: '{"path":"a"}' });
      vi.setSystemTime(1_500);
      state = applyRunSignal(state, { kind: 'tool-result', id: 'c1', content: 'ok' });
      state = applyRunSignal(state, {
        kind: 'token-usage',
        stepId: 'step-1',
        sample: {
          stepId: 'step-1',
          uncachedInput: 1,
          cacheRead: 0,
          cacheWrite: 0,
          output: 1,
          projectedTokens: 1,
        },
      });
      state = applyRunSignal(state, {
        kind: 'token-usage',
        stepId: 'step-1',
        sample: {
          stepId: 'step-1',
          uncachedInput: 4,
          cacheRead: 9,
          cacheWrite: 0,
          output: 2,
          projectedTokens: 13,
        },
      });
      const tool = state.liveEvents.find((event) => event.kind === 'tool_call');
      expect(tool?.payload).toMatchObject({
        id: 'c1',
        name: 'read_file',
        args: '{"path":"a"}',
        result: 'ok',
        status: 'done',
        // 起始时间是 TOOL_CALL_START 那一刻记下的，不是回合结束时的重取时间
        startedAt: 1_000,
        endedAt: 1_500,
      });
      const usage = state.liveEvents.filter((event) => event.kind === 'usage');
      expect(usage).toHaveLength(1);
      expect(usage[0]?.payload).toMatchObject({ stepId: 'step-1', cacheRead: 9, output: 2 });
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the context window when a later sample of the same step omits it', () => {
    let state = startRunState();
    state = applyRunSignal(state, {
      kind: 'token-usage',
      stepId: 'step-1',
      sample: {
        stepId: 'step-1',
        uncachedInput: 10,
        cacheRead: 0,
        cacheWrite: 0,
        output: 2,
        projectedTokens: 12,
        contextWindow: 8_000,
      },
    });
    state = applyRunSignal(state, {
      kind: 'token-usage',
      stepId: 'step-1',
      sample: {
        stepId: 'step-1',
        uncachedInput: 24,
        cacheRead: 6,
        cacheWrite: 0,
        output: 5,
        projectedTokens: 30,
      },
    });
    const [usage] = state.liveEvents.filter((event) => event.kind === 'usage');
    expect(usage?.payload).toMatchObject({ cacheRead: 6, output: 5, contextWindow: 8_000 });
    const [sample] = usage ? [usageFrom(usage)] : [];
    expect(sample?.contextWindow).toBe(8_000);
    expect(sample?.projectedTokens).toBe(30);
  });

  it('records the failed state a tool result reports', () => {
    let state = startRunState();
    state = applyRunSignal(state, { kind: 'tool-start', id: 'c9', name: 'read_file' });
    state = applyRunSignal(state, { kind: 'tool-result', id: 'c9', content: '', state: 'failed' });
    const [tool] = state.liveEvents.filter((event) => event.kind === 'tool_call');
    expect(tool?.payload).toMatchObject({ id: 'c9', status: 'failed' });
  });

  it('measures firstTokenMs per step rather than accumulating prior tool steps', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(1_000);
      let state = startRunState();
      // Step 1: tool call starts at 1_300ms (300ms TTFT), usage emitted at 1_400ms, tool finishes at 2_500ms
      vi.setSystemTime(1_300);
      state = applyRunSignal(state, { kind: 'tool-start', id: 'c1', name: 'read_file' });
      vi.setSystemTime(1_400);
      state = applyRunSignal(state, {
        kind: 'token-usage',
        stepId: 'step-1',
        sample: { stepId: 'step-1', uncachedInput: 10, cacheRead: 0, cacheWrite: 0, output: 5, projectedTokens: 10 },
      });
      vi.setSystemTime(2_500);
      state = applyRunSignal(state, { kind: 'tool-result', id: 'c1', content: 'ok' });

      // Step 2: text delta starts at 2_900ms (400ms after tool-result at 2_500ms), usage emitted at 3_200ms
      vi.setSystemTime(2_900);
      state = applyRunSignal(state, { kind: 'text-delta', delta: '答复' });
      vi.setSystemTime(3_200);
      state = applyRunSignal(state, {
        kind: 'token-usage',
        stepId: 'step-2',
        sample: { stepId: 'step-2', uncachedInput: 20, cacheRead: 0, cacheWrite: 0, output: 8, projectedTokens: 20 },
      });

      const usages = state.liveEvents.filter((event) => event.kind === 'usage').map(usageFrom);
      expect(usages.map((u) => u.firstTokenMs)).toEqual([300, 400]);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('hasRecordableContent', () => {
  it('records a round that produced process but no final text', () => {
    expect(
      hasRecordableContent({
        text: '',
        reasoning: '先看构建脚本',
        toolCalls: [{ id: 'c1', name: 'read_file', args: { path: 'package.json' }, status: 'done' }],
        usage: [],
      }),
    ).toBe(true);
  });

  it('records a round that produced only usage', () => {
    expect(hasRecordableContent({ text: '', reasoning: '', toolCalls: [], usage: [{ stepId: 's1' }] })).toBe(true);
  });

  it('records a round that produced a tool call but no text', () => {
    expect(hasRecordableContent({ text: '   ', reasoning: '', toolCalls: [{ id: 'c1' }], usage: [] })).toBe(true);
  });

  it('skips a round that produced nothing at all', () => {
    expect(hasRecordableContent({ text: '', reasoning: '', toolCalls: [], usage: [] })).toBe(false);
    expect(hasRecordableContent({ text: '   ', reasoning: '\n', toolCalls: [], usage: [] })).toBe(false);
  });
});

describe('withVisitorEntry / appendLiveEvent', () => {
  it('seeds live entries with the persisted visitor message and merges process events by seq', () => {
    let state = withVisitorEntry(startRunState(), entry(1, 'user_message', { text: '问' }));
    state = appendLiveEvent(state, entry(2, 'route', { mode: 'general' }));
    expect(state.liveEvents.map((event) => event.seq)).toEqual([1, 2]);
    // 下一轮开始：访客消息覆盖上一轮残留的实时条目
    state = withVisitorEntry(state, entry(10, 'user_message', { text: '再问' }));
    expect(state.liveEvents.map((event) => event.seq)).toEqual([10]);
  });
});

describe('finalizeRunState', () => {
  it('stops running, drops replayed live entries and clears the streamed body once persisted', () => {
    const state: RunState = {
      running: true,
      thinking: { status: 'thought', text: '思路' },
      streamingText: '正文',
      liveEvents: [entry(1, 'user_message'), entry(1_000_000_001, 'advisor_note')],
      runError: null,
      startedAt: 0,
      firstTokenAt: null,
      subagents: EMPTY_SUBAGENT_ACCUMULATOR,
    };
    const finalized = finalizeRunState(state, new Set([1]), true);
    expect(finalized).toEqual({
      running: false,
      thinking: { status: 'thought', text: '思路' },
      streamingText: '',
      liveEvents: [entry(1_000_000_001, 'advisor_note')],
      runError: null,
      startedAt: 0,
      firstTokenAt: null,
      subagents: EMPTY_SUBAGENT_ACCUMULATOR,
    });
  });

  it('keeps the streamed body visible when persisting failed', () => {
    const state: RunState = {
      running: true,
      thinking: { status: 'thought', text: '思路' },
      streamingText: '正文',
      liveEvents: [],
      runError: '落库失败',
      startedAt: 0,
      firstTokenAt: null,
      subagents: EMPTY_SUBAGENT_ACCUMULATOR,
    };
    const finalized = finalizeRunState(state, new Set(), false);
    expect(finalized.running).toBe(false);
    expect(finalized.streamingText).toBe('正文');
    expect(finalized.runError).toBe('落库失败');
  });
});

describe('run store（接管与清理）', () => {
  it('publishes updates to subscribers and keeps accumulated state for takeover', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeRun('store-s1', listener);
    updateRunState('store-s1', () => startRunState());
    updateRunState('store-s1', (state) =>
      applyRunSignal(
        state,
        signalOf('data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"接管"}'),
      ),
    );
    // 切走再切回：重新读取快照能拿到已累积的思考流，且仍在运行
    expect(getRunState('store-s1')).toMatchObject({
      running: true,
      thinking: { status: 'thinking', text: '接管' },
    });
    expect(listener).toHaveBeenCalled();
    unsubscribe();
    endRun('store-s1');
  });

  it('cleans the entry on endRun and falls back to the stable idle snapshot', () => {
    updateRunState('store-s2', () => startRunState());
    endRun('store-s2');
    expect(getRunState('store-s2')).toBe(IDLE_RUN);
    expect(getRunState('store-none')).toBe(IDLE_RUN);
  });

  it('resets residual failure state when a new run starts', () => {
    updateRunState('store-s3', (state) => ({ ...state, running: false, runError: '失败' }));
    expect(getRunState('store-s3').runError).toBe('失败');
    updateRunState('store-s3', () => startRunState());
    expect(getRunState('store-s3')).toMatchObject({ running: true, runError: null });
    endRun('store-s3');
  });

  it('stops notifying after unsubscribe', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeRun('store-s4', listener);
    unsubscribe();
    updateRunState('store-s4', () => startRunState());
    expect(listener).not.toHaveBeenCalled();
    endRun('store-s4');
  });
});

describe('buildAgentPrompt / toAttachmentSummaries', () => {
  it('combines visitor text with extracted attachment contents and strips heavy fields from summaries', () => {
    const attachments = [
      {
        id: 'a1',
        name: 'arch.md',
        size: 128,
        kind: 'document' as const,
        mimeType: 'text/markdown',
        extractedText: '[附件: arch.md]\n# Architecture',
      },
      {
        id: 'a2',
        name: 'repo.zip',
        size: 2048,
        kind: 'archive' as const,
        mimeType: 'application/zip',
        extractedText: '[压缩包附件: repo.zip]\n- pom.xml',
      },
    ];
    expect(buildAgentPrompt('请评审架构', attachments)).toBe(
      '请评审架构\n\n[附件: arch.md]\n# Architecture\n\n[压缩包附件: repo.zip]\n- pom.xml',
    );
    expect(buildAgentPrompt('', attachments)).toBe(
      '[附件: arch.md]\n# Architecture\n\n[压缩包附件: repo.zip]\n- pom.xml',
    );
    expect(toAttachmentSummaries(attachments)).toEqual([
      { id: 'a1', name: 'arch.md', size: 128, kind: 'document', mimeType: 'text/markdown' },
      { id: 'a2', name: 'repo.zip', size: 2048, kind: 'archive', mimeType: 'application/zip' },
    ]);
  });
});
