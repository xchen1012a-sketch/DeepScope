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
import { ADVISOR_EVENT_NAME, classifyAguiLine, runAdvisorStream } from './aguiClient';

it('reports the actual request run identity before a failed network request', async () => {
  const requested = vi.fn();
  const fetchMock = vi.fn().mockRejectedValue(new TypeError('offline'));
  vi.stubGlobal('localStorage', { getItem: () => null });
  vi.stubGlobal('fetch', fetchMock);
  try {
    await expect(runAdvisorStream('s', 'a', 'q', { onTextDelta: () => {}, onAdvisorEvent: () => {}, onRunRequested: requested })).rejects.toThrow('offline');
    const payload = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(requested).toHaveBeenCalledExactlyOnceWith(payload.runId);
  } finally { vi.unstubAllGlobals(); }
});

describe('stream terminal contract', () => {
  afterEach(() => vi.unstubAllGlobals());
  function stream(types: Array<Record<string, unknown>>) {
    vi.stubGlobal('localStorage', { getItem: () => null });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(types.map(event => `data: ${JSON.stringify({ runId: 'r', ...event })}\n\n`).join(''))));
    return runAdvisorStream('s', 'a', 'q', { onTextDelta: () => {}, onAdvisorEvent: () => {} });
  }
  it('rejects EOF without a terminal event', async () => {
    await expect(stream([{ type: 'RUN_STARTED' }, { type: 'TEXT_MESSAGE_CONTENT', delta: 'partial' }])).rejects.toThrow('AGUI_STREAM_INTERRUPTED');
  });
  it('accepts explicit completion', async () => {
    await expect(stream([{ type: 'RUN_STARTED' }, { type: 'RUN_FINISHED' }])).resolves.toBeUndefined();
  });
  it('preserves the native interrupt identity and sends a resume without another user message', async () => {
    const interrupts = [{ id: 'confirm-1', reason: 'tool_call', toolCallId: 'tool-1', metadata: { toolName: 'write_file' } }];
    expect(classifyAguiLine(`data: ${JSON.stringify({ type: 'RUN_FINISHED', runId: 'r', outcome: { type: 'interrupt', interrupts } })}`)).toMatchObject({ interrupts });
    await stream([{ type: 'RUN_FINISHED' }]);
    await runAdvisorStream('s', 'a', '', { onTextDelta: () => {}, onAdvisorEvent: () => {} }, { resume: [{ interruptId: 'confirm-1', status: 'resolved', payload: { approved: false } }] });
    const request = vi.mocked(fetch).mock.calls[1][1];
    const body = JSON.parse(String(request?.body));
    expect(body.resume).toEqual([{ interruptId: 'confirm-1', status: 'resolved', payload: { approved: false } }]);
    expect(body.messages).toEqual([]);
  });
  it('retains a run error even when followed by a completion event', async () => {
    await expect(stream([{ type: 'RUN_STARTED' }, { type: 'RUN_ERROR', message: 'failed' }, { type: 'RUN_FINISHED' }])).rejects.toThrow('failed');
  });
});

describe('forwardedProps reasoning effort', () => {
  afterEach(() => vi.unstubAllGlobals());
  async function forwardedPropsFor(options?: Parameters<typeof runAdvisorStream>[4]) {
    vi.stubGlobal('localStorage', { getItem: () => null });
    vi.stubGlobal('fetch', vi.fn(async () => new Response('data: {"type":"RUN_FINISHED","runId":"r"}\n\n')));
    await runAdvisorStream('s', 'a', 'q', { onTextDelta: () => {}, onAdvisorEvent: () => {} }, options);
    return (JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body)) as { forwardedProps: unknown }).forwardedProps;
  }
  it('carries the selected tier only while deep thinking is on', async () => {
    expect(await forwardedPropsFor({ thinking: true, reasoningEffort: 'xhigh' })).toEqual({ thinking: true, reasoningEffort: 'xhigh' });
    expect(await forwardedPropsFor({ thinking: false, reasoningEffort: 'xhigh' })).toEqual({ thinking: false });
    expect(await forwardedPropsFor({ thinking: true })).toEqual({ thinking: true });
  });
});

describe('classifyAguiLine', () => {
  it('maps REASONING_* lines to thinking signals', () => {
    expect(classifyAguiLine('data: {"type":"REASONING_START","threadId":"s1","runId":"r1"}')).toEqual({
      kind: 'reasoning-start', runId: 'r1',
    });
    expect(
      classifyAguiLine(
        'data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"先看依赖"}',
      ),
    ).toEqual({ kind: 'reasoning-delta', delta: '先看依赖', runId: 'r1', messageId: 'm1' });
    expect(classifyAguiLine('data: {"type":"REASONING_END","threadId":"s1","runId":"r1"}')).toEqual({
      kind: 'reasoning-end', runId: 'r1',
    });
  });

  it('keeps mapping text deltas, advisor events and stream errors', () => {
    expect(
      classifyAguiLine('data: {"type":"TEXT_MESSAGE_CONTENT","threadId":"s1","runId":"r1","messageId":"m1","delta":"结论"}'),
    ).toEqual({ kind: 'text-delta', delta: '结论', runId: 'r1', messageId: 'm1' });
    expect(
      classifyAguiLine(
        `data: {"type":"CUSTOM","threadId":"s1","runId":"r1","name":"${ADVISOR_EVENT_NAME}","value":{"kind":"route","seq":2}}`,
      ),
    ).toEqual({ kind: 'advisor-event', value: { kind: 'route', seq: 2 }, runId: 'r1' });
    expect(classifyAguiLine('data: {"type":"RUN_ERROR","threadId":"s1","runId":"r1","message":"模型超时"}')).toEqual({
      kind: 'stream-error',
      message: '模型超时',
      runId: 'r1',
    });
  });

  it('maps a tool call from start through its result onto one id', () => {
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_START","threadId":"s1","runId":"r1","toolCallId":"c1","toolCallName":"read_file"}',
      ),
    ).toEqual({ kind: 'tool-start', id: 'c1', name: 'read_file', runId: 'r1' });
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_ARGS","threadId":"s1","runId":"r1","toolCallId":"c1","delta":"{\\"path\\":"}',
      ),
    ).toEqual({ kind: 'tool-args', id: 'c1', delta: '{"path":', runId: 'r1' });
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_RESULT","threadId":"s1","runId":"r1","toolCallId":"c1","content":"ok","role":"tool"}',
      ),
    ).toEqual({ kind: 'tool-result', id: 'c1', content: 'ok', runId: 'r1' });
  });

  it('carries the tool result state the application layer adds to rawEvent', () => {
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_RESULT","threadId":"s1","runId":"r1","toolCallId":"c1","content":"","role":"tool","rawEvent":{"state":"error"}}',
      ),
    ).toEqual({ kind: 'tool-result', id: 'c1', content: '', state: 'failed', runId: 'r1' });
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_RESULT","threadId":"s1","runId":"r1","toolCallId":"c2","content":"ok","role":"tool","rawEvent":{"state":"success"}}',
      ),
    ).toEqual({ kind: 'tool-result', id: 'c2', content: 'ok', state: 'done', runId: 'r1' });
    expect(
      classifyAguiLine(
        'data: {"type":"TOOL_CALL_RESULT","threadId":"s1","runId":"r1","toolCallId":"c3","content":"ok","role":"tool"}',
      ),
    ).toEqual({ kind: 'tool-result', id: 'c3', content: 'ok', runId: 'r1' });
  });

  it('maps token usage and splits cached input out of the prompt total', () => {
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"token_usage","value":{"delta":{"inputTokens":100,"outputTokens":8,"cachedTokens":70,"cacheCreationTokens":10,"time":1.5},"modelCall":{"replyId":"step-1"}}}',
      ),
    ).toEqual({
      kind: 'token-usage',
      stepId: 'step-1',
      sample: {
        stepId: 'step-1',
        uncachedInput: 20,
        cacheRead: 70,
        cacheWrite: 10,
        output: 8,
        projectedTokens: 100,
        timeSeconds: 1.5,
      },
    });
  });

  it('carries the context window the application layer adds to token_usage', () => {
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"token_usage","value":{"delta":{"inputTokens":100,"outputTokens":8,"cachedTokens":70,"cacheCreationTokens":10,"time":1.5},"modelCall":{"replyId":"step-1"},"contextWindow":8000}}',
      ),
    ).toEqual({
      kind: 'token-usage',
      stepId: 'step-1',
      sample: {
        stepId: 'step-1',
        uncachedInput: 20,
        cacheRead: 70,
        cacheWrite: 10,
        output: 8,
        projectedTokens: 100,
        timeSeconds: 1.5,
        contextWindow: 8_000,
      },
    });
  });

  it('omits the window instead of writing zero when the provider does not report one', () => {
    const signal = classifyAguiLine(
      'data: {"type":"CUSTOM","name":"token_usage","value":{"delta":{"inputTokens":100,"outputTokens":8},"modelCall":{"replyId":"step-2"}}}',
    );
    expect(signal?.kind).toBe('token-usage');
    expect(signal && 'sample' in signal ? signal.sample.contextWindow : 'missing').toBeUndefined();
  });

  it('classifies subagent thinking and tool events instead of dropping them', () => {
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.thinking","value":{"source":"s1/code-surveyor","type":"THINKING_BLOCK_DELTA","delta":"先看构建脚本"}}',
      ),
    ).toEqual({ kind: 'subagent-thinking', text: '先看构建脚本', source: 's1/code-surveyor' });
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.tool_call","value":{"source":"s1/code-surveyor","type":"TOOL_CALL_START","toolCallId":"c1","toolName":"read_file"}}',
      ),
    ).toEqual({ kind: 'subagent-tool-call', toolCallId: 'c1', name: 'read_file', source: 's1/code-surveyor' });
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.tool_result","value":{"source":"s1/code-surveyor","type":"TOOL_RESULT_END","toolCallId":"c1","toolName":"read_file","state":"ERROR"}}',
      ),
    ).toEqual({
      kind: 'subagent-tool-result',
      toolCallId: 'c1',
      state: 'failed',
      source: 's1/code-surveyor',
    });
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.text","value":{"source":"s1/code-surveyor","type":"TEXT_BLOCK_DELTA","delta":"结论"}}',
      ),
    ).toEqual({ kind: 'subagent-text', text: '结论', source: 's1/code-surveyor' });
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.lifecycle","value":{"source":"s1/code-surveyor","type":"AGENT_END","replyId":"r1"}}',
      ),
    ).toEqual({ kind: 'subagent-lifecycle', phase: 'AGENT_END', source: 's1/code-surveyor' });
  });

  it('carries the subagent display name from the lifecycle start event', () => {
    // 派发 payload 里也有同一个 name，前端靠它对账，不再依赖到达顺序
    expect(
      classifyAguiLine(
        'data: {"type":"CUSTOM","name":"subagent.lifecycle","value":{"source":"s1/code-surveyor","type":"AGENT_START","name":"代码勘察员","replyId":"r1"}}',
      ),
    ).toEqual({
      kind: 'subagent-lifecycle',
      phase: 'AGENT_START',
      name: '代码勘察员',
      source: 's1/code-surveyor',
    });
  });

  it('ignores unrelated events, malformed payloads and non-data lines', () => {
    expect(classifyAguiLine('data: {"type":"RUN_STARTED","threadId":"s1","runId":"r1"}')).toEqual({ kind: 'run-started', runId: 'r1' });
    expect(classifyAguiLine('data: {"type":"REASONING_MESSAGE_CONTENT","threadId":"s1","runId":"r1","delta":42}')).toBeNull();
    expect(classifyAguiLine('data: not json')).toBeNull();
    expect(classifyAguiLine('event: message')).toBeNull();
    expect(classifyAguiLine('')).toBeNull();
  });
});
