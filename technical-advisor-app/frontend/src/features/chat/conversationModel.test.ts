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
import type { ConversationEvent } from './useAdvisorConversation';
import {
  DEFAULT_CONTEXT_WINDOW,
  cacheHit,
  contextBreakdown,
  contextOccupancy,
  contextRemaining,
  contextWindowOf,
  foldToolCall,
  foldUsage,
  formatRunDuration,
  inferContextWindow,
  subagentElapsed,
  thinkLine,
  toolFromEvent,
  type UsageSample,
} from './conversationModel';

function entry(seq: number, kind: string, payload: Record<string, unknown> = {}, turn = 1): ConversationEvent {
  return { seq, turn, kind, payload, createdAt: 1_700_000_000_000 + seq * 1000 };
}

describe('thinkLine', () => {
  it('follows the latest non-blank line while reasoning is streaming', () => {
    expect(thinkLine('先看依赖\n\n再看调用方', true)).toBe('再看调用方');
  });

  it('settles on the first non-blank line', () => {
    expect(thinkLine('先看依赖\n再看调用方', false)).toBe('先看依赖');
  });

  it('returns an empty summary when the reasoning has no visible line', () => {
    expect(thinkLine(' \n\t', true)).toBe('');
  });
});

describe('foldToolCall', () => {
  it('keeps one row from start through result', () => {
    const started = foldToolCall([], { id: 'c1', name: 'read_file', argsDelta: '', startedAt: 10 });
    const withArgs = foldToolCall(started, { id: 'c1', name: 'read_file', argsDelta: '{"p":' });
    const done = foldToolCall(withArgs, {
      id: 'c1',
      name: 'read_file',
      argsDelta: '"a"}',
      result: 'ok',
      endedAt: 20,
    });
    expect(done).toEqual([
      {
        id: 'c1',
        name: 'read_file',
        args: '{"p":"a"}',
        result: 'ok',
        status: 'done',
        startedAt: 10,
        endedAt: 20,
        durationMs: 10,
      },
    ]);
  });

  it('does not let a later signal overwrite the recorded start time', () => {
    const started = foldToolCall([], { id: 'c1', name: 'read_file', startedAt: 10 });
    const done = foldToolCall(started, { id: 'c1', result: 'ok', endedAt: 30 });
    expect(done[0]?.startedAt).toBe(10);
    expect(done[0]?.durationMs).toBe(20);
  });

  it('leaves a call running until a result arrives', () => {
    const calls = foldToolCall([], { id: 'c1', name: 'search', startedAt: 5 });
    expect(calls[0]?.status).toBe('running');
    expect(calls[0]?.startedAt).toBe(5);
    expect(calls[0]?.endedAt).toBeUndefined();
    expect(calls[0]?.durationMs).toBeNull();
  });
});

describe('toolFromEvent', () => {
  it('uses the recorded start time instead of the commit time', () => {
    const event = entry(7, 'tool_call', {
      id: 'c1',
      name: 'demo_lookup',
      status: 'done',
      startedAt: 1_000,
      endedAt: 4_500,
    });
    const call = toolFromEvent(event);
    expect(call.startedAt).toBe(1_000);
    expect(call.durationMs).toBe(3_500);
  });

  it('falls back to no duration when only the commit time is known', () => {
    const event = entry(8, 'tool_call', { id: 'c2', name: 'x', status: 'done', endedAt: 2_000 });
    const call = toolFromEvent(event);
    expect(call.startedAt).toBeNull();
    expect(call.durationMs).toBeNull();
  });

  it('marks a tool that ended in error as failed', () => {
    const event = entry(9, 'tool_call', { id: 'c3', name: 'x', status: 'failed', startedAt: 1, endedAt: 2 });
    expect(toolFromEvent(event).status).toBe('failed');
  });
});

describe('foldUsage', () => {
  const first: UsageSample = {
    stepId: 's1',
    uncachedInput: 10,
    cacheRead: 0,
    cacheWrite: 0,
    output: 4,
  };

  it('replaces an earlier sample of the same step instead of adding it', () => {
    const folded = foldUsage(
      [first],
      { stepId: 's1', uncachedInput: 12, cacheRead: 80, cacheWrite: 3, output: 9 },
    );
    expect(folded).toEqual([
      { stepId: 's1', uncachedInput: 12, cacheRead: 80, cacheWrite: 3, output: 9 },
    ]);
  });

  it('keeps distinct steps', () => {
    const folded = foldUsage([first], { ...first, stepId: 's2', output: 1 });
    expect(folded.map((sample) => sample.stepId)).toEqual(['s1', 's2']);
  });
});

describe('cacheHit', () => {
  it('uses cache reads over uncached input, cache reads and cache writes, with one decimal', () => {
    const hit = cacheHit([
      { stepId: 's1', uncachedInput: 50, cacheRead: 900, cacheWrite: 50, output: 20 },
    ]);
    expect(hit?.percent).toBe('90.0');
    expect(hit?.cacheRead).toBe(900);
    expect(hit?.output).toBe(20);
  });

  it('hides the figure when the provider reported neither cache reads nor cache writes', () => {
    expect(
      cacheHit([{ stepId: 's1', uncachedInput: 20, cacheRead: 0, cacheWrite: 0, output: 8 }]),
    ).toBeNull();
  });
});

describe('contextOccupancy', () => {
  it('returns the used share when both the projected prompt and the window are known', () => {
    expect(contextOccupancy(2500, 10000)).toEqual({ used: 2500, capacity: 10000, percent: 25 });
  });

  it('stays hidden when either figure is missing', () => {
    expect(contextOccupancy(undefined, 10000)).toBeNull();
    expect(contextOccupancy(2500, undefined)).toBeNull();
    expect(contextOccupancy(0, 0)).toBeNull();
  });
});

describe('contextRemaining', () => {
  it('defaults to 100% remaining on DEFAULT_CONTEXT_WINDOW when no usage is reported yet', () => {
    expect(contextRemaining(undefined, undefined)).toEqual({
      used: 0,
      capacity: DEFAULT_CONTEXT_WINDOW,
      remaining: DEFAULT_CONTEXT_WINDOW,
      percent: 0,
      remainingPercent: 100,
    });
  });

  it('computes used and remaining percentages accurately', () => {
    expect(contextRemaining(8_000, 32_000)).toEqual({
      used: 8_000,
      capacity: 32_000,
      remaining: 24_000,
      percent: 25,
      remainingPercent: 75,
    });
  });
});

describe('contextWindowOf', () => {
  const base = { stepId: 's', uncachedInput: 0, cacheRead: 0, cacheWrite: 0, output: 0 };

  it('does not reuse a previous model window when the latest model is unknown', () => {
    expect(contextWindowOf([
      { ...base, stepId: 'old', modelName: 'A', contextWindow: 1_000_000 },
      { ...base, stepId: 'new', modelName: 'B' },
    ])).toBeUndefined();
  });

  it('falls back to the last sample that reported a window', () => {
    expect(
      contextWindowOf([
        { ...base, stepId: 's1', contextWindow: 8_000 },
        { ...base, stepId: 's2' },
      ]),
    ).toBe(8_000);
  });

  it('returns nothing when no sample reported a window', () => {
    expect(contextWindowOf([{ ...base, stepId: 's1' }])).toBeUndefined();
    expect(contextWindowOf([])).toBeUndefined();
  });
});

describe('contextBreakdown', () => {
  it('splits the projected prompt into system prompt, tools and messages', () => {
    const breakdown = contextBreakdown(4_000, 2);
    expect(breakdown).not.toBeNull();
    expect(breakdown?.systemPromptTokens).toBeGreaterThan(0);
    expect(breakdown?.toolTokens).toBe(240);
    expect(
      (breakdown?.systemPromptTokens ?? 0) + (breakdown?.toolTokens ?? 0) + (breakdown?.messageTokens ?? 0),
    ).toBe(4_000);
  });

  it('stays hidden without a projected prompt so the panel does not show zeros', () => {
    expect(contextBreakdown(undefined, 3)).toBeNull();
    expect(contextBreakdown(0, 3)).toBeNull();
  });

  it('never estimates more tools than the prompt contains', () => {
    const breakdown = contextBreakdown(100, 50);
    expect(breakdown?.toolTokens).toBeLessThanOrEqual(100);
    expect(breakdown?.messageTokens).toBeGreaterThanOrEqual(0);
  });
});

describe('formatRunDuration', () => {
  it('shows milliseconds under a second, tenths under 10s, seconds under a minute and minutes with seconds after that', () => {
    expect(formatRunDuration(0)).toBe('1ms');
    expect(formatRunDuration(240)).toBe('240ms');
    expect(formatRunDuration(1_500)).toBe('1.5s');
    expect(formatRunDuration(12_000)).toBe('12s');
    expect(formatRunDuration(75_000)).toBe('1m 15s');
  });

  it('shows nothing when the start time is missing', () => {
    expect(subagentElapsed(undefined, undefined, 1_000)).toBeNull();
    expect(formatRunDuration(null)).toBeNull();
  });

  it('freezes a settled run at the end time', () => {
    expect(subagentElapsed(1_000, 6_000, 99_000)).toBe(5_000);
  });

  it('counts a running subagent up to now', () => {
    expect(subagentElapsed(1_000, undefined, 9_000)).toBe(8_000);
  });
});

describe('inferContextWindow', () => {
  it('identifies exact market models', () => {
    expect(inferContextWindow('deepseek-chat')).toBe(131_072);
    expect(inferContextWindow('deepseek-reasoner')).toBe(131_072);
    expect(inferContextWindow('gpt-4o')).toBe(128_000);
    expect(inferContextWindow('gpt-4o-mini')).toBe(128_000);
    expect(inferContextWindow('o1')).toBe(200_000);
    expect(inferContextWindow('qwen-turbo')).toBe(1_000_000);
    expect(inferContextWindow('qwen-plus')).toBe(131_072);
    expect(inferContextWindow('qwen-max')).toBe(32_768);
    expect(inferContextWindow('claude-3-5-sonnet')).toBe(200_000);
    expect(inferContextWindow('gemini-1.5-pro')).toBe(2_000_000);
  });

  it('normalizes repository prefixes and casing', () => {
    expect(inferContextWindow('deepseek-ai/DeepSeek-V3')).toBe(131_072);
    expect(inferContextWindow('Qwen/Qwen2.5-72B-Instruct')).toBe(131_072);
  });

  it('extracts token suffixes', () => {
    expect(inferContextWindow('custom-model-128k')).toBe(131_072);
    expect(inferContextWindow('llama-3-32k')).toBe(32_768);
    expect(inferContextWindow('mistral-1m')).toBe(1_000_000);
  });

  it('falls back to family heuristic and default window', () => {
    expect(inferContextWindow('deepseek-future-v4')).toBe(131_072);
    expect(inferContextWindow('claude-4-preview')).toBe(200_000);
    expect(inferContextWindow('completely-unknown-model')).toBe(DEFAULT_CONTEXT_WINDOW);
    expect(inferContextWindow('')).toBe(DEFAULT_CONTEXT_WINDOW);
    expect(inferContextWindow(undefined)).toBe(DEFAULT_CONTEXT_WINDOW);
  });
});
