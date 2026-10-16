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
// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { getSessionDetail, postSessionMessage, type EventEntry, type PendingQuestion, type SessionDetail } from '@/api/advisor';
import { routeSession } from '@/api/agent';
import { runAdvisorStream, type AdvisorRunHandlers } from './aguiClient';
import { endRun, useAdvisorConversation } from './useAdvisorConversation';

vi.mock('@/api/advisor', async original => ({ ...await original<typeof import('@/api/advisor')>(), listSessions: vi.fn(async () => []), getSessionDetail: vi.fn(), postSessionMessage: vi.fn() }));
vi.mock('@/api/agent', () => ({ routeSession: vi.fn() }));
vi.mock('./aguiClient', async original => ({ ...await original<typeof import('./aguiClient')>(), runAdvisorStream: vi.fn() }));

const question: PendingQuestion = { questionId: 'q-1', text: 'Where to deploy?', answerMode: 'single', allowCustom: true, options: [{ id: 'opt-1', label: 'Cloud' }, { id: 'opt-2', label: 'Local' }], seq: 2, turn: 1 };
const answer = { selectedOptionIds: ['opt-1'], customText: '' };
let detail: SessionDetail;
let saved: EventEntry;
let client: QueryClient;
let sessionId: string;
let index = 0;
function wrapper({ children }: { children: ReactNode }) { return <QueryClientProvider client={client}>{children}</QueryClientProvider>; }

beforeEach(() => {
  vi.clearAllMocks();
  sessionId = `question-integration-${++index}`;
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  detail = { session: { sessionId, title: null, agentId: 'advisor', agentName: null, status: 'awaiting_visitor', createdAt: 0, updatedAt: 0 },
    runtime: { phase: 'idle', interrupts: [] }, pendingQuestion: question, pendingQuestionReply: null, deliverables: [],
    entries: [{ seq: 2, turn: 1, kind: 'question', payload: { ...question } }, { seq: 3, turn: 1, kind: 'advisor_message', payload: { text: 'Waiting for your answer' } }] };
  saved = { seq: 4, turn: 2, kind: 'user_message', payload: { text: 'Cloud', questionReply: { questionId: 'q-1', requestId: 'saved-request', contextText: 'Question: Where to deploy?\nAnswer: Cloud' } } };
  vi.mocked(getSessionDetail).mockImplementation(async () => detail);
  vi.mocked(routeSession).mockResolvedValue({ agentId: 'advisor', name: 'Advisor', reason: '' });
  vi.mocked(postSessionMessage).mockImplementation(async (_id, input) => {
    if (input.role === 'visitor') {
      const entry = { ...saved, payload: { ...saved.payload, questionReply: { ...input.questionReply, contextText: 'Question: Where to deploy?\nAnswer: Cloud' } } };
      detail = { ...detail, pendingQuestion: null, pendingQuestionReply: entry, entries: [...detail.entries, entry] };
      return entry;
    }
    const entry = { seq: 5, turn: 2, kind: 'advisor_message', payload: { ...input } };
    detail = { ...detail, pendingQuestionReply: null, entries: [...detail.entries, entry] };
    return entry;
  });
  vi.mocked(runAdvisorStream).mockImplementation(async (_id, _agent, _text, handlers: AdvisorRunHandlers) => {
    handlers.onTextDelta('Deployment result');
    handlers.onSignal?.({ kind: 'run-finished', interrupted: false });
  });
});
afterEach(() => { cleanup(); client.clear(); endRun(sessionId); });

async function mount() {
  const view = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
  await waitFor(() => expect(view.result.current.session).not.toBeNull());
  return view;
}
describe('structured question lifecycle', () => {
  it('accepts a restored visitor-waiting question without treating it as active execution', async () => {
    detail.runtime = { phase: 'awaiting_visitor', interrupts: [] };
    const { result } = await mount();
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(postSessionMessage).toHaveBeenCalledWith(sessionId, expect.objectContaining({ role: 'visitor', questionReply: expect.objectContaining({ questionId: 'q-1' }) }));
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
  });
  it('shows the pending question after assistant text and sends the canonical question context', async () => {
    const { result } = await mount();
    expect(result.current.pendingQuestion?.questionId).toBe('q-1');
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(postSessionMessage).toHaveBeenCalledWith(sessionId, expect.objectContaining({ role: 'visitor', questionReply: { questionId: 'q-1', requestId: expect.any(String), ...answer } }));
    expect(runAdvisorStream).toHaveBeenCalledWith(sessionId, 'advisor', 'Question: Where to deploy?\nAnswer: Cloud', expect.anything(), expect.anything());
    expect(result.current.pendingQuestion).toBeNull();
  });
  it('keeps a failed answer pending and reuses its request id on retry', async () => {
    vi.mocked(postSessionMessage).mockRejectedValueOnce(new Error('offline'));
    const { result } = await mount();
    await act(async () => { await expect(result.current.answerQuestion(question, answer)).rejects.toThrow(); });
    expect(result.current.pendingQuestion?.questionId).toBe('q-1');
    expect(runAdvisorStream).not.toHaveBeenCalled();
    const requestId = vi.mocked(postSessionMessage).mock.calls[0][1].questionReply?.requestId;
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(vi.mocked(postSessionMessage).mock.calls[1][1].questionReply?.requestId).toBe(requestId);
  });
  it('associates a normal composer reply with the same pending question', async () => {
    const { result } = await mount();
    await act(async () => { await result.current.send('A different target'); });
    expect(postSessionMessage).toHaveBeenCalledWith(sessionId, expect.objectContaining({ questionReply: expect.objectContaining({ questionId: 'q-1', selectedOptionIds: [], customText: 'A different target' }) }));
  });
  it('does not save the visitor answer twice after routing fails', async () => {
    vi.mocked(routeSession).mockRejectedValueOnce(new Error('route unavailable'));
    const { result } = await mount();
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(result.current.pendingQuestion).toBeNull();
    await act(async () => { await result.current.retry(); });
    expect(vi.mocked(postSessionMessage).mock.calls.filter(([, input]) => input.role === 'visitor')).toHaveLength(1);
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
  });
  it('restores continue processing after reload without duplicating the saved answer', async () => {
    detail = { ...detail, pendingQuestion: null, pendingQuestionReply: saved, entries: [...detail.entries, saved] };
    const { result } = await mount();
    expect(result.current.pendingQuestionReply?.seq).toBe(4);
    await act(async () => { await result.current.continueQuestion(); });
    expect(vi.mocked(postSessionMessage).mock.calls.filter(([, input]) => input.role === 'visitor')).toHaveLength(0);
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
  });
  it('checks the refreshed runtime before continuing and never restarts active work', async () => {
    detail = { ...detail, pendingQuestion: null, pendingQuestionReply: saved, entries: [...detail.entries, saved] };
    const { result } = await mount();
    detail = { ...detail, runtime: { phase: 'running', interrupts: [] } };
    await act(async () => { await result.current.continueQuestion(); });
    expect(runAdvisorStream).not.toHaveBeenCalled();
    expect(postSessionMessage).not.toHaveBeenCalled();
  });
  it('continues a saved answer after routing succeeded but execution never produced output', async () => {
    detail = { ...detail, pendingQuestion: null, pendingQuestionReply: saved, entries: [...detail.entries, saved,
      { seq: 5, turn: 2, kind: 'agent_route', payload: { mode: 'locked' } }] };
    const { result } = await mount();
    await act(async () => { await result.current.continueQuestion(); });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(vi.mocked(postSessionMessage).mock.calls.filter(([, input]) => input.role === 'visitor')).toHaveLength(0);
  });
  it('does not queue a duplicate when continue is clicked twice during refresh', async () => {
    detail = { ...detail, pendingQuestion: null, pendingQuestionReply: saved, entries: [...detail.entries, saved] };
    const { result } = await mount();
    let release!: () => void;
    vi.mocked(getSessionDetail).mockImplementationOnce(() => new Promise(resolve => { release = () => resolve(detail); }));
    let first!: Promise<void>;
    let second!: Promise<void>;
    await act(async () => {
      first = result.current.continueQuestion();
      second = result.current.continueQuestion();
    });
    await act(async () => { release(); await Promise.all([first, second]); });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(result.current.queuedMessages).toHaveLength(0);
  });
  it('does not start another execution for an idempotently replayed saved answer', async () => {
    vi.mocked(postSessionMessage).mockResolvedValueOnce({ ...saved, replayed: true });
    const { result } = await mount();
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(runAdvisorStream).not.toHaveBeenCalled();
  });
  it('does not attach an already answered question to new messages when refresh fails', async () => {
    const { result } = await mount();
    vi.mocked(postSessionMessage).mockResolvedValue(saved);
    vi.mocked(getSessionDetail).mockRejectedValue(new Error('refresh offline'));
    await act(async () => { await result.current.answerQuestion(question, answer); });
    expect(result.current.pendingQuestion).toBeNull();
    await act(async () => { await result.current.send('A new topic'); });
    const visitors = vi.mocked(postSessionMessage).mock.calls.filter(([, input]) => input.role === 'visitor');
    expect(visitors).toHaveLength(2);
    expect(visitors[1][1].questionReply).toBeUndefined();
  });
  it('preserves uploaded attachment context through save, route failure, reload and continuation', async () => {
    const attachment = { id: 'file-1', name: 'design.md', size: 10, kind: 'document' as const, mimeType: 'text/markdown', extractedText: 'File design.md\nArchitecture details' };
    vi.mocked(routeSession).mockRejectedValueOnce(new Error('Routing offline'));
    const first = await mount();
    await act(async () => { await first.result.current.answerQuestion(question, { selectedOptionIds: [], customText: 'Read this design' }, false, [attachment]); });
    const input = vi.mocked(postSessionMessage).mock.calls[0][1];
    expect(input.questionReply).toMatchObject({ attachmentContext: attachment.extractedText });
    first.unmount(); endRun(sessionId); client.clear();
    const second = await mount();
    await act(async () => { await second.result.current.continueQuestion(); });
    expect(runAdvisorStream).toHaveBeenCalledWith(sessionId, 'advisor', 'Question: Where to deploy?\nAnswer: Cloud\n\nFile design.md\nArchitecture details', expect.anything(), expect.anything());
    expect(vi.mocked(postSessionMessage).mock.calls.filter(([, input]) => input.role === 'visitor')).toHaveLength(1);
  });
  it('acknowledges the saved answer before generation finishes so the composer can clear it promptly', async () => {
    let release!: () => void;
    vi.mocked(runAdvisorStream).mockImplementationOnce(() => new Promise<void>(resolve => { release = resolve; }));
    const { result } = await mount();
    let acknowledged = false;
    let pending!: Promise<void>;
    await act(async () => { pending = result.current.answerQuestion(question, answer).then(() => { acknowledged = true; }); });
    await waitFor(() => expect(runAdvisorStream).toHaveBeenCalledTimes(1));
    expect(acknowledged).toBe(true);
    expect(result.current.pendingQuestion).toBeNull();
    expect(result.current.running).toBe(true);
    await act(async () => { release(); await pending; });
    await waitFor(() => expect(result.current.running).toBe(false));
  });
});
