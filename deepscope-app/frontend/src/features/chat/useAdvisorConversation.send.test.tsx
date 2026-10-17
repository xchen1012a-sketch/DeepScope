// @vitest-environment jsdom
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

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { getRunState, startRunState, updateRunState, useAdvisorConversation } from './useAdvisorConversation';
import { routeSession } from '@/api/agent';
import type { AdvisorRunHandlers } from './aguiClient';
import type { SessionDetail } from '@/api/advisor';
import { advisorKeys } from '@/api/advisor';

const runAdvisorStream = vi.hoisted(() => vi.fn());
vi.mock('./aguiClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./aguiClient')>()),
  runAdvisorStream,
}));
vi.mock('@/api/agent', () => ({ routeSession: vi.fn() }));

/** jsdom 环境里没有 localStorage，apiClient 取 token 时会用到。 */
function stubLocalStorage(): void {
  const store = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  });
}

interface Posted {
  url: string;
  body: Record<string, unknown>;
}

/** 记录前端发出的每一次落库 POST，并回答详情查询与落库两条路径。 */
function stubFetch(sessionId: string, agentId: string | null = 'technical-advisor', runtime?: SessionDetail['runtime'], entries: SessionDetail['entries'] = []): Posted[] {
  const posted: Posted[] = [];
  let seq = 0;
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    const path = String(url);
    if (!init || (init.method ?? 'GET') === 'GET') {
      const detail = {
        session: {
          sessionId,
          title: 't',
          agentId,
          agentName: null,
          status: 'idle',
          createdAt: 0,
          updatedAt: 0,
        },
        entries,
        ...(runtime ? { runtime } : {}),
        deliverables: [],
      };
      return Promise.resolve(
        new Response(JSON.stringify(path.endsWith('/sessions') ? [] : path.includes('/sessions') ? detail : []), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }
    const body = init.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    posted.push({ url: path, body });
    if (path.endsWith(':interrupt') && runtime) runtime.phase = 'idle';
    seq += 1;
    return Promise.resolve(
      new Response(
        JSON.stringify({
          seq,
          turn: 1,
          kind: body.role === 'advisor' ? 'advisor_message' : 'user_message',
          payload: body,
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      ),
    );
  });
  return posted;
}

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('useAdvisorConversation send', () => {
  it('acknowledges input after persistence and preserves it when saving fails', async () => {
    const sessionId = 'draft-acceptance';
    stubFetch(sessionId);
    runAdvisorStream.mockResolvedValue(undefined);
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    const accepted = vi.fn();
    const original = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    await act(async () => { await result.current.send('unsaved', { onAccepted: accepted }); });
    expect(accepted).not.toHaveBeenCalled();
    vi.stubGlobal('fetch', original);
    await act(async () => { await result.current.send('saved', { onAccepted: accepted }); });
    expect(accepted).toHaveBeenCalledTimes(1);
    unmount();
  });
  it('uses the same title snapshot as the sidebar even when the detail response is older', async () => {
    const sessionId = 'shared-session-title';
    stubFetch(sessionId);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(advisorKeys.sessions, [{ sessionId, title: 'Shared title', agentId: null, agentName: null, status: 'idle', createdAt: 1, updatedAt: 2 }]);
    const view = renderHook(() => useAdvisorConversation(sessionId), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
    await waitFor(() => expect(view.result.current.session).not.toBeNull());
    expect(view.result.current.session?.title).toBe('Shared title');
    act(() => client.setQueryData(advisorKeys.sessions, [{ sessionId, title: 'Renamed together', agentId: null, agentName: null, status: 'idle', createdAt: 1, updatedAt: 3 }]));
    await waitFor(() => expect(view.result.current.session?.title).toBe('Renamed together'));
    view.unmount();
    client.clear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    runAdvisorStream.mockReset();
  });

  beforeEach(() => {
    stubLocalStorage();
    vi.mocked(routeSession).mockReset();
    vi.mocked(routeSession).mockResolvedValue({ agentId: 'technical-advisor', name: 'Agent', reason: '' });
  });

  it('recovers an active server run after reload and stops it without resending', async () => {
    const sessionId = 'recover-active-reload';
    const posted = stubFetch(sessionId, 'technical-advisor', { phase: 'running', runId: 'server-run', interrupts: [] });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    expect(result.current.running).toBe(true);
    await act(async () => { await result.current.send('next'); });
    expect(result.current.queuedMessages).toHaveLength(1);
    expect(runAdvisorStream).not.toHaveBeenCalled();
    await act(async () => { result.current.stop(); });
    await waitFor(() => expect(posted.some(item => item.url.endsWith(':interrupt') && item.body.expectedRunId === 'server-run')).toBe(true));
    expect(posted.some(item => item.body.role === 'visitor')).toBe(false);
    unmount();
  });
  it('captures each submitted agent choice in the queue without changing the running agent', async () => {
    const sessionId = 'agent-picker-queued-selection';
    stubFetch(sessionId, 'a');
    let release!: () => void;
    vi.mocked(routeSession).mockImplementation(async (_id, _text, _basic, selected) => ({ agentId: selected || 'auto', name: 'Agent', reason: '' }));
    runAdvisorStream.mockImplementationOnce(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'first-a' });
      await new Promise<void>(resolve => { release = resolve; });
      throw new Error('pause automatic queue draining');
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let first!: Promise<void>;
    await act(async () => { first = result.current.send('first', { selectedAgentId: 'a' }); });
    await waitFor(() => expect(release).toBeTruthy());
    await act(async () => {
      await result.current.send('second', { selectedAgentId: 'b' });
      await result.current.send('third', { selectedAgentId: null });
    });
    expect(vi.mocked(routeSession).mock.calls).toEqual([[sessionId, 'first', false, 'a']]);
    expect(result.current.runAgentId).toBe('a');
    expect(result.current.queuedMessages.map(item => item.options?.selectedAgentId)).toEqual(['b', null]);
    await act(async () => { release(); await first; });
    unmount();
  });

  it('retains the failed message agent selection on retry and preserves explicit free mode', async () => {
    const sessionId = 'agent-picker-retry-choice';
    stubFetch(sessionId, null);
    runAdvisorStream.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined);
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('locked question', { selectedAgentId: 'chosen' }); });
    await act(async () => { await result.current.retry(); });
    expect(vi.mocked(routeSession).mock.calls.slice(0, 2)).toEqual([
      [sessionId, 'locked question', false, 'chosen'], [sessionId, 'locked question', false, 'chosen'],
    ]);
    await act(async () => { await result.current.send('free question', { selectedAgentId: null }); });
    expect(vi.mocked(routeSession).mock.lastCall).toEqual([sessionId, 'free question', false, null]);
    unmount();
  });

  it('reconciles a dropped stream with a still active server run instead of reporting failure', async () => {
    const sessionId = 'recover-stream-drop';
    const runtime: NonNullable<SessionDetail['runtime']> = { phase: 'idle', interrupts: [] };
    const posted = stubFetch(sessionId, 'technical-advisor', runtime);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'dropped-run' });
      runtime.phase = 'running';
      runtime.runId = 'dropped-run';
      throw new TypeError('Failed to fetch');
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('hello'); });
    expect(result.current.running).toBe(true);
    expect(result.current.runError).toBeNull();
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('interrupts a recovered server run before immediately sending a queued message', async () => {
    const sessionId = 'recover-interrupt-queue';
    const posted = stubFetch(sessionId, 'technical-advisor', { phase: 'running', runId: 'previous-run', interrupts: [] });
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'next-run' });
      handlers.onSignal?.({ kind: 'run-finished', runId: 'next-run', interrupted: false });
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await waitFor(() => expect(result.current.running).toBe(true));
    await act(async () => { await result.current.send('next message'); });
    await act(async () => { await result.current.interruptAndSend(); });
    expect(posted.find(item => item.url.endsWith(':interrupt'))?.body).toEqual({ expectedRunId: 'previous-run' });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(result.current.queuedMessages).toHaveLength(0);
    unmount();
  });

  it('keeps recovering when both the stream and status request lose the network', async () => {
    const sessionId = 'recover-offline-status';
    stubFetch(sessionId);
    const onlineFetch = globalThis.fetch;
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'offline-run' });
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
      throw new TypeError('offline');
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('hello'); });
    expect(result.current.running).toBe(true);
    expect(result.current.runError).toBeNull();
    expect(getRunState(sessionId).detached).toBe(true);
    vi.stubGlobal('fetch', onlineFetch);
    unmount();
  });

  it('accepts the persisted terminal outcome when a stream drops just after server completion', async () => {
    const sessionId = 'recover-completed-before-read';
    const entries: SessionDetail['entries'] = [];
    stubFetch(sessionId, 'technical-advisor', { phase: 'idle', interrupts: [] }, entries);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'finished-before-read' });
      handlers.onTextDelta('Partial text before connection dropped');
      entries.push({ seq: 10, turn: 1, kind: 'advisor_message', createdAt: Date.now(), payload: {
        text: 'Complete answer', presentation: { runId: 'finished-before-read', finalMessageIds: [], outcome: 'completed' },
      } });
      throw new TypeError('network reset');
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('hello'); });
    expect(result.current.running).toBe(false);
    expect(result.current.runError).toBeNull();
    expect(result.current.events.some(event => event.payload.text === 'Complete answer')).toBe(true);
    expect(result.current.streamingText).toBe('');
    unmount();
  });

  it('recovers a completed run even when its first SSE event was lost', async () => {
    const sessionId = 'recover-before-first-event';
    const entries: SessionDetail['entries'] = [];
    stubFetch(sessionId, 'technical-advisor', { phase: 'idle', interrupts: [] }, entries);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onRunRequested?.('lost-first-event');
      entries.push({ seq: 10, turn: 1, kind: 'advisor_message', createdAt: Date.now(), payload: {
        text: 'Saved answer', presentation: { runId: 'lost-first-event', finalMessageIds: [], outcome: 'completed' },
      } });
      throw new TypeError('network reset');
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('hello'); });
    expect(result.current.running).toBe(false);
    expect(result.current.runError).toBeNull();
    expect(result.current.events.some(event => event.payload.text === 'Saved answer')).toBe(true);
    unmount();
  });

  it.each(['running', 'finalizing', 'idle', 'awaiting_action'] as const)('restores actionable confirmations only while awaiting action, not %s', async phase => {
    const sessionId = `resume-reload-${phase}`;
    stubFetch(sessionId, 'technical-advisor', { phase, runId: 'resume-1', interrupts: [{ id: 'confirm-1', reason: 'permission' }] });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    expect(result.current.pendingInterrupts).toHaveLength(phase === 'awaiting_action' ? 1 : 0);
    unmount();
  });

  it('resumes the selected runtime without posting another visitor or rerouting', async () => {
    const sessionId = 'reliability-resume';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'resume-run' });
      handlers.onSignal?.({ kind: 'text-delta', runId: 'resume-run', delta: 'resumed reply' });
      handlers.onSignal?.({ kind: 'run-finished', runId: 'resume-run', interrupted: false });
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    const resume = [{ interruptId: 'confirm-1', status: 'resolved' as const, payload: { approved: false } }];
    await act(async () => { await result.current.send('', { resume, resumeAgentId: 'technical-advisor' }); });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(vi.mocked(routeSession)).not.toHaveBeenCalled();
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(0);
    expect(runAdvisorStream.mock.calls[0][4]).toMatchObject({ resume });
    unmount();
  });

  it.each(['awaiting_action', 'failed', 'limited'] as const)('keeps queued messages after %s instead of starting another run', async outcome => {
    const sessionId = `reliability-queue-${outcome}`;
    stubFetch(sessionId);
    let release!: () => void;
    runAdvisorStream.mockImplementationOnce(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'queue-run' });
      await new Promise<void>(resolve => { release = resolve; });
      if (outcome === 'failed') throw new Error('model failed');
      handlers.onSignal?.({ kind: 'run-finished', runId: 'queue-run', interrupted: outcome === 'awaiting_action' });
      if (outcome === 'limited') handlers.onSignal?.({ kind: 'presentation', runId: 'queue-run', presentation: { runId: 'queue-run', finalMessageIds: [], outcome: 'limited', terminationReason: 'MAX_ITERATIONS' } });
    }).mockResolvedValue(undefined);
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('first'); });
    await waitFor(() => expect(release).toBeTruthy());
    await act(async () => { await result.current.send('queued'); release(); await sending; });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(getRunState(sessionId).queuedMessages?.map(item => item.text)).toEqual(['queued']);
    unmount();
  });

  it('waits for interrupted reconciliation before steering a queued message', async () => {
    const sessionId = 'reliability-steer';
    stubFetch(sessionId);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let release!: () => void;
    const pendingRefresh = new Promise<void>(resolve => { release = resolve; });
    const invalidation = vi.spyOn(client, 'invalidateQueries').mockImplementationOnce(() => pendingRefresh).mockResolvedValue(undefined);
    runAdvisorStream.mockImplementationOnce(async (_s, _a, _q, handlers: AdvisorRunHandlers, options) => {
      handlers.onTextDelta('partial');
      await new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Stopped', 'AbortError')), { once: true }));
    }).mockResolvedValue(undefined);
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('first'); });
    await waitFor(() => expect(result.current.streamingText).toBe('partial'));
    await act(async () => { await result.current.send('steer'); });
    let steering!: Promise<void>;
    await act(async () => { steering = result.current.interruptAndSend(getRunState(sessionId).queuedMessages![0].id); });
    await waitFor(() => expect(invalidation).toHaveBeenCalled());
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    await act(async () => { release(); await sending; await steering; });
    expect(runAdvisorStream).toHaveBeenCalledTimes(2);
    unmount(); client.clear();
  });

  it('keeps new sends queued until the previous run has finished reconciliation', async () => {
    const sessionId = 'reliability-finalizing';
    stubFetch(sessionId);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let release!: () => void;
    const pendingRefresh = new Promise<void>(resolve => { release = resolve; });
    const invalidation = vi.spyOn(client, 'invalidateQueries').mockImplementationOnce(() => pendingRefresh).mockResolvedValue(undefined);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'finalizing-run' });
      handlers.onSignal?.({ kind: 'text-delta', runId: 'finalizing-run', delta: 'first reply' });
      handlers.onSignal?.({ kind: 'run-finished', runId: 'finalizing-run', interrupted: false });
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('first'); });
    await waitFor(() => expect(invalidation).toHaveBeenCalled());
    await act(async () => { await result.current.send('second'); });
    expect(runAdvisorStream).toHaveBeenCalledTimes(1);
    expect(getRunState(sessionId).queuedMessages?.map(item => item.text)).toEqual(['second']);
    await act(async () => { release(); await sending; });
    unmount(); client.clear();
  });

  it('does not finalize a newer instance when an older refresh finishes late', async () => {
    const sessionId = 'reliability-stale-finally';
    stubFetch(sessionId);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let release!: () => void;
    const pendingRefresh = new Promise<void>(resolve => { release = resolve; });
    const invalidation = vi.spyOn(client, 'invalidateQueries').mockImplementationOnce(() => pendingRefresh).mockResolvedValue(undefined);
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => { handlers.onTextDelta('old'); });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('first'); });
    await waitFor(() => expect(invalidation).toHaveBeenCalled());
    act(() => updateRunState(sessionId, () => ({ ...startRunState([], 'replacement'), streamingText: 'new output' })));
    await act(async () => { release(); await sending; });
    expect(getRunState(sessionId)).toMatchObject({ runInstanceId: 'replacement', running: true, streamingText: 'new output' });
    unmount(); client.clear();
  });

  it('ignores old callbacks after an instance has been replaced', async () => {
    const sessionId = 'reliability-stale-callback';
    stubFetch(sessionId);
    let finish!: () => void;
    let oldHandlers!: AdvisorRunHandlers;
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      oldHandlers = handlers;
      await new Promise<void>(resolve => { finish = resolve; });
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('first'); });
    await waitFor(() => expect(oldHandlers).toBeTruthy());
    act(() => updateRunState(sessionId, () => ({ ...startRunState([], 'replacement'), streamingText: 'new output' })));
    await act(async () => { oldHandlers.onTextDelta('late old output'); finish(); await sending; });
    expect(getRunState(sessionId)).toMatchObject({ runInstanceId: 'replacement', running: true, streamingText: 'new output' });
    unmount();
  });

  it('keeps a cancelled run visible and does not persist a successful reply', async () => {
    const sessionId = 'cancelled-presentation';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(async (_session, _agent, _text, handlers: AdvisorRunHandlers, options) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'cancel-run' });
      handlers.onSignal?.({ kind: 'text-delta', runId: 'cancel-run', messageId: 'partial', delta: 'partial answer' });
      await new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Stopped', 'AbortError')), { once: true }));
    });
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    const sending = result.current.send('question');
    await waitFor(() => expect(result.current.streamingText).toBe('partial answer'));
    result.current.stop();
    await sending;
    await waitFor(() => expect(result.current.running).toBe(false));
    const snapshot = result.current.events.find(event => event.kind === 'advisor_message');
    expect(snapshot?.payload.presentation).toMatchObject({ outcome: 'cancelled', finalMessageIds: [] });
    expect(posted.filter(item => item.body.role === 'advisor')).toHaveLength(0);
  });

  it('retains reasoning-only output when stopping and server persistence is unavailable', async () => {
    const sessionId = 'reliability-reasoning-stop';
    stubFetch(sessionId);
    const delegate = globalThis.fetch;
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => String(url).endsWith(':interrupt')
      ? Promise.resolve(new Response('unavailable', { status: 503 })) : delegate(url, init));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.spyOn(client, 'invalidateQueries').mockImplementation(async () => {
      client.setQueryData<SessionDetail>(['advisor', 'sessions', sessionId], old => old ? ({ ...old, entries: [{ seq: 1, turn: 1, kind: 'user_message', payload: { text: 'question' }, createdAt: 0 }] }) : old);
    });
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers, options) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'reasoning-stop' });
      handlers.onSignal?.({ kind: 'reasoning-delta', runId: 'reasoning-stop', delta: 'partial reasoning' });
      await new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Stopped', 'AbortError')), { once: true }));
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    let sending!: Promise<void>;
    await act(async () => { sending = result.current.send('question', { thinking: true }); });
    await waitFor(() => expect(result.current.thinking.text).toBe('partial reasoning'));
    await act(async () => { result.current.stop(); await sending; });
    expect(getRunState(sessionId).thinking.text).toBe('partial reasoning');
    expect(result.current.events.some(event => event.kind === 'advisor_message')).toBe(true);
    unmount(); client.clear();
  });

  it('releases local parts once the saved snapshot has fully replayed', async () => {
    const sessionId = 'reliability-replayed-cleanup';
    const posted = stubFetch(sessionId);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.spyOn(client, 'invalidateQueries').mockImplementation(async () => {
      client.setQueryData<SessionDetail>(['advisor', 'sessions', sessionId], old => old ? { ...old,
        entries: posted.map((item, index) => ({seq: index + 1, turn: 1, kind: item.body.role === 'advisor' ? 'advisor_message' : 'user_message', payload: item.body, createdAt: 0})) } : old);
    });
    runAdvisorStream.mockImplementation(async (_s, _a, _q, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({kind: 'run-started', runId: 'cleanup'});
      handlers.onSignal?.({kind: 'text-delta', runId: 'cleanup', messageId: 'reply', delta: 'saved reply'});
      handlers.onSignal?.({kind: 'run-finished', runId: 'cleanup', interrupted: false});
    });
    const { result, unmount } = renderHook(() => useAdvisorConversation(sessionId), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await act(async () => { await result.current.send('question'); });
    expect(getRunState(sessionId).runInstanceId).toBeUndefined();
    expect(result.current.events.some(event => event.kind === 'advisor_message')).toBe(true);
    unmount(); client.clear();
  });

  it('retains confirmed final output when only persistence fails', async () => {
    const sessionId = 'presentation-save-failure';
    stubFetch(sessionId);
    const delegate = globalThis.fetch;
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      if (init?.body && JSON.parse(String(init.body)).role === 'advisor') return Promise.resolve(new Response('persistence unavailable', { status: 500 }));
      return delegate(url, init);
    });
    runAdvisorStream.mockImplementation(async (_session, _agent, _text, handlers: AdvisorRunHandlers) => {
      handlers.onSignal?.({ kind: 'run-started', runId: 'save-run' });
      handlers.onSignal?.({ kind: 'text-delta', runId: 'save-run', messageId: 'final', delta: 'complete answer' });
      handlers.onSignal?.({ kind: 'text-end', runId: 'save-run', messageId: 'final' });
      handlers.onSignal?.({ kind: 'run-finished', runId: 'save-run', interrupted: false });
      handlers.onSignal?.({ kind: 'presentation', runId: 'save-run', presentation: { runId: 'save-run', runStartedAt: 1000, finalStartedAt: 83000, runEndedAt: 95000, finalMessageIds: ['final'], outcome: 'completed' } });
    });
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('question');
    await waitFor(() => expect(result.current.running).toBe(false));
    const snapshot = result.current.events.find(event => event.kind === 'advisor_message');
    expect(snapshot?.payload.presentation).toMatchObject({ outcome: 'completed', finalMessageIds: ['final'], finalStartedAt: 83000 });
    expect(result.current.streamingText).toBe('complete answer');
    expect(result.current.runError).toBeTruthy();
  });

  it('uses the explicit basic route and retries without another visitor message', async () => {
    const sessionId = 'basic-retry';
    const posted = stubFetch(sessionId, null);
    vi.mocked(routeSession).mockResolvedValue({ agentId: 'basic-chat', name: 'Basic chat', reason: '', mode: 'basic', reasonCode: 'no_visible_agents' });
    runAdvisorStream.mockRejectedValueOnce(new Error('model unavailable'))
      .mockImplementationOnce(async (_session, _agent, _text, handlers: AdvisorRunHandlers) => { handlers.onTextDelta('reply'); });
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('question');
    expect(result.current.runError).toBe('model unavailable');
    await result.current.retry();
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(posted.filter(item => item.body.role === 'advisor')).toHaveLength(1);
    expect(runAdvisorStream.mock.calls.map(call => call[1])).toEqual(['basic-chat', 'basic-chat']);
  });

  it('does not run an unavailable locked agent until the visitor consents', async () => {
    const sessionId = 'locked-unavailable';
    const posted = stubFetch(sessionId, 'deleted');
    vi.mocked(routeSession).mockResolvedValueOnce({ agentId: 'deleted', name: '', reason: '', mode: 'unavailable', reasonCode: 'bound_agent_unavailable' })
      .mockResolvedValueOnce({ agentId: 'basic-chat', name: 'Basic chat', reason: '', mode: 'basic', reasonCode: 'bound_agent_unavailable' });
    runAdvisorStream.mockImplementation(async (_session, _agent, _text, handlers: AdvisorRunHandlers) => { handlers.onTextDelta('reply'); });
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('question');
    expect(result.current.agentUnavailable).toBe(true);
    expect(runAdvisorStream).not.toHaveBeenCalled();
    await result.current.retry(true);
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(vi.mocked(routeSession).mock.calls[1]).toEqual([sessionId, 'question', true, undefined]);
    expect(runAdvisorStream.mock.calls[0][1]).toBe('basic-chat');
  });

  it('keeps a route 404 as an error rather than bypassing session authorization', async () => {
    const sessionId = 'route-404';
    const posted = stubFetch(sessionId, null);
    vi.mocked(routeSession).mockRejectedValue(new Error('HTTP 404'));
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('question');
    expect(result.current.runError).toBe('HTTP 404');
    expect(runAdvisorStream).not.toHaveBeenCalled();
    await result.current.retry();
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(runAdvisorStream).not.toHaveBeenCalled();
  });

  it('retries a locked conversation with the agent explicitly selected by the visitor', async () => {
    const sessionId = 'locked-replacement';
    const posted = stubFetch(sessionId, 'deleted');
    vi.mocked(routeSession).mockResolvedValueOnce({ agentId: 'deleted', name: '', reason: '', mode: 'unavailable' })
      .mockResolvedValueOnce({ agentId: 'replacement', name: 'Agent', reason: '', mode: 'agent' });
    runAdvisorStream.mockImplementation(async (_session, _agent, _text, handlers: AdvisorRunHandlers) => { handlers.onTextDelta('reply'); });
    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('question');
    await result.current.retry(false, 'replacement');
    expect(posted.filter(item => item.body.role === 'visitor')).toHaveLength(1);
    expect(runAdvisorStream.mock.calls[0][1]).toBe('replacement');
    expect(vi.mocked(routeSession).mock.calls[1]).toEqual([sessionId, 'question', false, 'replacement']);
  });

  it('posts one subagent run per dispatch, keyed by the dispatched subtask id', async () => {
    const sessionId = 'sess-1';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(
      async (
        _session: string,
        _agent: string,
        _text: string,
        handlers: AdvisorRunHandlers,
      ): Promise<void> => {
        handlers.onAdvisorEvent({ kind: 'dispatch', seq: 11, turn: 1, subtaskId: 's1', name: 'code-surveyor' });
        handlers.onSubagentSignal?.({
          kind: 'subagent-thinking',
          text: '先看构建脚本',
          source: `${sessionId}/code-surveyor`,
        });
        handlers.onSubagentSignal?.({
          kind: 'subagent-tool-call',
          toolCallId: 'c9',
          name: 'read_file',
          source: `${sessionId}/code-surveyor`,
        });
        handlers.onSubagentSignal?.({
          kind: 'subagent-tool-result',
          toolCallId: 'c9',
          content: 'ok',
          state: 'done',
          source: `${sessionId}/code-surveyor`,
        });
        handlers.onTextDelta('结论');
      },
    );

    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await result.current.send('看一下构建脚本');

    await waitFor(() => {
      expect(posted.some((item) => item.body.role === 'advisor')).toBe(true);
    });
    const advisor = posted.find((item) => item.body.role === 'advisor');
    const runs = advisor?.body.subagentRuns as Array<{ subtaskId: string; events: Array<Record<string, unknown>> }>;
    expect(runs).toHaveLength(1);
    expect(runs[0]?.subtaskId).toBe('s1');
    expect(runs[0]?.events.map((step) => step.kind)).toEqual(['thinking', 'tool_call']);
    expect(runs[0]?.events[0]).toMatchObject({ kind: 'thinking', text: '先看构建脚本' });
    expect(runs[0]?.events[1]).toMatchObject({ kind: 'tool_call', toolCallId: 'c9', name: 'read_file', status: 'done' });
  });

  it('does not post an advisor message when the round produced nothing', async () => {
    const sessionId = 'sess-2';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(async (): Promise<void> => {});

    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await result.current.send('空回合');

    await waitFor(() => {
      expect(posted.some((item) => item.body.role === 'visitor')).toBe(true);
    });
    expect(posted.some((item) => item.body.role === 'advisor')).toBe(false);
  });

  it('still posts the round when it produced only thinking', async () => {
    const sessionId = 'sess-3';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(
      async (
        _session: string,
        _agent: string,
        _text: string,
        handlers: AdvisorRunHandlers,
      ): Promise<void> => {
        handlers.onReasoningStart?.();
        handlers.onReasoningDelta?.('先看构建脚本');
        handlers.onReasoningEnd?.();
      },
    );

    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await result.current.send('只有思考', { thinking: true });

    await waitFor(() => {
      expect(posted.some((item) => item.body.role === 'advisor')).toBe(true);
    });
    const advisor = posted.find((item) => item.body.role === 'advisor');
    expect(advisor?.body.text).toBe('');
    expect(advisor?.body.reasoning).toBe('先看构建脚本');
    expect(advisor?.body.subagentRuns).toBeUndefined();
  });

  it('resets running state to false when token-usage updates step timing during stream', async () => {
    const sessionId = 'token-usage-finish';
    const posted = stubFetch(sessionId);
    runAdvisorStream.mockImplementation(
      async (
        _session: string,
        _agent: string,
        _text: string,
        handlers: AdvisorRunHandlers,
      ): Promise<void> => {
        handlers.onSignal?.({ kind: 'run-started', runId: 'run-usage' });
        handlers.onSignal?.({ kind: 'text-delta', runId: 'run-usage', delta: '完成回答' });
        // token-usage updates state.startedAt in applyUsageSignal
        handlers.onSignal?.({
          kind: 'token-usage',
          stepId: 'step-done',
          sample: { stepId: 'step-done', uncachedInput: 15, cacheRead: 0, cacheWrite: 0, output: 30, projectedTokens: 45 },
        });
        handlers.onSignal?.({ kind: 'run-finished', runId: 'run-usage', interrupted: false });
      },
    );

    const { result } = renderHook(() => useAdvisorConversation(sessionId), { wrapper });
    await waitFor(() => expect(result.current.session).not.toBeNull());
    await result.current.send('测试完成状态');

    await waitFor(() => {
      expect(result.current.running).toBe(false);
    });
    expect(posted.some((item) => item.body.role === 'advisor')).toBe(true);
  });
});
