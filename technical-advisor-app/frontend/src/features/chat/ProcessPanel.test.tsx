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
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { buildBlocks, timelineCards, nextTimelineScrollTop, TIMELINE_TOP_THRESHOLD } from './model/blocks';
import type { SubagentRecord } from './useAdvisorConversation';
import { ProcessPanel } from './ProcessPanel';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

function TestProviders({ children }: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }));
  return <QueryClientProvider client={client}><I18nextProvider i18n={i18n}>{children}</I18nextProvider></QueryClientProvider>;
}
const renderWithI18n = (ui: React.ReactElement) => render(ui, { wrapper: TestProviders });

// 仓库未启用 vitest 全局配置，测试库不会自动清理，这里手动保证用例之间互不残留。
afterEach(cleanup);
afterEach(() => vi.restoreAllMocks());
afterEach(() => vi.useRealTimers());

it('keeps different parts of the same saved message independently locatable', async () => {
 const parts = ['first', 'second'].map((text, index) => ({id: `r:part:${index}`, runId: 'r', kind: 'text', messageId: `m${index}`, text, status: 'done'}));
 const turns = buildBlocks([{seq: 2, turn: 1, kind: 'advisor_message', payload: {partsVersion: 1, parts}, createdAt: 0}], false);
 const cards = timelineCards(turns);
 vi.spyOn(advisorApi, 'getSessionTimelinePage').mockResolvedValue({items: cards, page: 1, size: 10, total: 2, totalPages: 1, hasMore: false});
 const locate = vi.fn();
 renderWithI18n(<ProcessPanel pane="trajectory" sessionId="parts-session" turns={turns} subagents={[]} onLocate={locate} />);
 await screen.findByText('first');
 fireEvent.click(screen.getByRole('button', {name: /first/}));
 fireEvent.click(screen.getByRole('button', {name: /second/}));
 expect(locate.mock.calls.map(call => call[0])).toEqual([cards.find(card => card.summary === 'first')!.anchor, cards.find(card => card.summary === 'second')!.anchor]);
});

const record = (over: Partial<SubagentRecord> = {}): SubagentRecord => ({
  id: 't1',
  name: 'Investigator',
  task: 'dig the repo',
  status: 'done',
  startedAt: 1_000,
  endedAt: 61_000,
  factCount: 0,
  facts: [],
  summary: 'all clear',
  steps: [],
  ...over,
});

describe('nextTimelineScrollTop', () => {
  it('sticks to the top while the user is watching the top', () => {
    expect(nextTimelineScrollTop(true, 100, 260, 0)).toBe(0);
    expect(nextTimelineScrollTop(true, 100, 260, TIMELINE_TOP_THRESHOLD)).toBe(0);
  });

  it('compensates the inserted card height when the user has scrolled down', () => {
    expect(nextTimelineScrollTop(false, 100, 260, 40)).toBe(200);
    expect(nextTimelineScrollTop(false, 100, 80, 40)).toBe(20);
    expect(nextTimelineScrollTop(false, 100, 20, 40)).toBe(0);
  });
});

import * as advisorApi from '@/api/advisor';

describe('ProcessPanel subagents', () => {
  it('refreshes only the open running task, stops after completion and preserves loaded pages', async () => {
    vi.useFakeTimers();
    const running = record({ status: 'running', endedAt: undefined, summary: '' });
    const older = record({ id: 't2', name: 'Older investigator', summary: 'Older result' });
    let completed = false;
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockImplementation(async (_session, requestedPage = 1) => ({
      items: requestedPage === 1 ? [completed ? record({ summary: 'Fresh completed result' }) : running] : [older],
      page: requestedPage, size: 1, total: 2, totalPages: 2, hasMore: requestedPage < 2,
    }));
    const view = renderWithI18n(<ProcessPanel pane="subagents" sessionId="refresh-session" pageSize={1} turns={[]} subagents={[]} onLocate={vi.fn()} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    const node = view.container.querySelector('[data-subagent-scroll]')!;
    fireEvent.scroll(node);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    expect(screen.getByText('Older investigator')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /^Investigator/ }));
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    completed = true;
    await act(async () => { await vi.advanceTimersByTimeAsync(2_100); });
    expect(screen.getByText('Fresh completed result')).toBeTruthy();
    expect(screen.getByText('Done')).toBeTruthy();
    const calls = vi.mocked(advisorApi.getSessionSubagentsPage).mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(6_000); });
    expect(advisorApi.getSessionSubagentsPage).toHaveBeenCalledTimes(calls);
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('Older investigator')).toBeTruthy();
    expect(screen.queryByText('Running')).toBeNull();
  });

  it('stops refreshing a running task after closing its detail', async () => {
    vi.useFakeTimers();
    const running = record({ status: 'running', endedAt: undefined, summary: '' });
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({ items: [running], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false });
    const view = renderWithI18n(<ProcessPanel pane="subagents" sessionId="close-session" turns={[]} subagents={[]} onLocate={vi.fn()} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    fireEvent.click(screen.getByRole('button', { name: /Investigator/ }));
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    const calls = vi.mocked(advisorApi.getSessionSubagentsPage).mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(6_000); });
    expect(advisorApi.getSessionSubagentsPage).toHaveBeenCalledTimes(calls);
    view.unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(6_000); });
    expect(advisorApi.getSessionSubagentsPage).toHaveBeenCalledTimes(calls);
  });

  it('refreshes the page containing an older selected task without rereading unrelated pages', async () => {
    vi.useFakeTimers();
    let viewing = false;
    const read = vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockImplementation(async (_session, requestedPage = 1) => ({
      items: [record({ id: requestedPage === 1 ? 't1' : 't2', name: requestedPage === 1 ? 'Newest investigator' : 'Older investigator', status: viewing && requestedPage === 2 ? 'done' : 'running', endedAt: undefined, summary: viewing && requestedPage === 2 ? 'Older task completed' : '' })],
      page: requestedPage, size: 1, total: 2, totalPages: 2, hasMore: requestedPage < 2,
    }));
    const view = renderWithI18n(<ProcessPanel pane="subagents" sessionId="older-session" pageSize={1} turns={[]} subagents={[]} onLocate={vi.fn()} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    fireEvent.scroll(view.container.querySelector('[data-subagent-scroll]')!);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    viewing = true;
    read.mockClear();
    fireEvent.click(screen.getByRole('button', { name: /Older investigator/ }));
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    expect(screen.getByText('Older task completed')).toBeTruthy();
    expect(read.mock.calls.map(call => call.slice(1))).toEqual([[2, 1]]);
  });
  it('keeps all legacy facts without ids and the freshest streamed analysis', async () => {
    const remote = record({ status: 'running', endedAt: undefined, summary: '', facts: [
      { id: '', fact: 'First finding', source: 'first.md' },
      { id: '', fact: 'Second finding', source: 'second.md' },
    ], steps: [{ subtaskId: 't1', order: 1, kind: 'thinking', text: 'Old analysis' }] });
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({ items: [remote], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false });
    const local = { ...remote, steps: [{ subtaskId: 't1', order: 1, kind: 'thinking' as const, text: 'Old analysis and new tokens' }] };
    renderWithI18n(<ProcessPanel pane="subagents" sessionId="fresh-session" turns={[]} subagents={[local]} onLocate={vi.fn()} />);
    await screen.findByText('Investigator');
    await waitFor(() => expect(advisorApi.getSessionSubagentsPage).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: /Investigator/ }));
    expect(screen.getByText('First finding')).toBeTruthy();
    expect(screen.getByText('Second finding')).toBeTruthy();
    expect(screen.getByText('Old analysis and new tokens')).toBeTruthy();
  });

  it('retains persisted steps alongside a newer live step when neither snapshot is complete', async () => {
    const remote = record({ status: 'running', endedAt: undefined, summary: '', steps: [{ subtaskId: 't1', order: 1, kind: 'tool_call', tool: { id: 'read-1', name: 'read_file', args: '{}', status: 'done', startedAt: null } }] });
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({ items: [remote], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false });
    const local = { ...remote, steps: [{ subtaskId: 't1', order: 2, kind: 'thinking' as const, text: 'Live follow-up analysis' }] };
    renderWithI18n(<ProcessPanel pane="subagents" sessionId="partial-session" turns={[]} subagents={[local]} onLocate={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    fireEvent.click(screen.getByRole('button', { name: /Investigator/ }));
    expect(screen.getByText('Live follow-up analysis')).toBeTruthy();
    expect(screen.getByText('File read returned')).toBeTruthy();
  });

  it('keeps different legacy tool steps without call ids independently readable', async () => {
    const snapshot = record({ steps: [
      { subtaskId: 't1', order: 1, kind: 'tool_call', tool: { id: '', name: 'read_file', args: '{"path":"first.md"}', status: 'done', startedAt: null, endedAt: 1_000, result: 'First file content' } },
      { subtaskId: 't1', order: 2, kind: 'thinking', text: 'Check another file' },
      { subtaskId: 't1', order: 3, kind: 'tool_call', tool: { id: '', name: 'read_file', args: '{"path":"second.md"}', status: 'done', startedAt: null, endedAt: 2_000, result: 'Second file content' } },
    ] });
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({ items: [snapshot], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false });
    renderWithI18n(<ProcessPanel pane="subagents" sessionId="legacy-tools-session" turns={[]} subagents={[snapshot]} onLocate={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    fireEvent.click(screen.getByRole('button', { name: /Investigator/ }));
    expect(screen.getByText('first.md')).toBeTruthy();
    expect(screen.getByText('second.md')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /File read returned\s*first.md/ }));
    expect(screen.getByText('First file content')).toBeTruthy();
  });
  it('updates a completed local task even when its step count did not grow', async () => {
    const remote = record({ status: 'running', endedAt: undefined, summary: '' });
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({items: [remote], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false});
    const ui = (item: SubagentRecord) => <I18nextProvider i18n={i18n}><ProcessPanel pane="subagents" sessionId="status-session" turns={[]} subagents={[item]} onLocate={vi.fn()} /></I18nextProvider>;
    const view = renderWithI18n(ui(remote));
    await screen.findByText('Running');
    view.rerender(ui(record({ endedAt: 62_000 })));
    await waitFor(() => expect(screen.getByText('Done')).toBeTruthy());
    expect(screen.queryByText('Running')).toBeNull();
  });

  it('keeps remote terminal state while retaining fuller local process content', async () => {
    vi.spyOn(advisorApi, 'getSessionSubagentsPage').mockResolvedValue({items: [record()], page: 1, size: 10, total: 1, totalPages: 1, hasMore: false});
    const local = record({status: 'running', endedAt: undefined, steps: [{subtaskId: 't1', order: 1, kind: 'thinking', text: 'recorded analysis'}]});
    renderWithI18n(<ProcessPanel pane="subagents" sessionId="terminal-session" turns={[]} subagents={[local]} onLocate={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('Done')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', {name: /Investigator/}));
    expect(screen.getByText('Analysis record')).toBeTruthy();
  });
  it('restores the list scroll position after coming back from a detail view', () => {
    const { container } = renderWithI18n(
      <ProcessPanel pane="subagents" turns={[]} subagents={[record()]} onLocate={vi.fn()} />,
    );
    const node = container.querySelector('[data-subagent-scroll]') as HTMLElement;
    // jsdom 没有布局，滚动位置用可写属性直接给定。
    Object.defineProperty(node, 'scrollTop', { value: 240, writable: true, configurable: true });

    fireEvent.click(screen.getByRole('button', { name: /Investigator/ }));
    expect(screen.getByRole('button', { name: 'Back' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(node.scrollTop).toBe(240);
  });

  it('requests page 1 on mount and automatically requests and appends page 2 when scrolled to the bottom', async () => {
    const timelineSpy = vi
      .spyOn(advisorApi, 'getSessionTimelinePage')
      .mockImplementation(async (_sid, page = 1, size = 2) => ({
        items:
          page === 1
            ? [
                {
                  anchor: 'flow-3',
                  index: 3,
                  seq: 3,
                  kind: 'advisor_message',
                  status: 'done',
                  summary: 'Newest reply',
                  createdAt: 2_000,
                },
                {
                  anchor: 'flow-2',
                  index: 2,
                  seq: 2,
                  kind: 'tool_call',
                  status: 'done',
                  summary: 'read_file',
                  createdAt: 1_500,
                },
              ]
            : [
                {
                  anchor: 'flow-1',
                  index: 1,
                  seq: 1,
                  kind: 'user_message',
                  status: 'done',
                  summary: 'First question',
                  createdAt: 1_000,
                },
              ],
        page,
        size,
        total: 3,
        totalPages: 2,
        hasMore: page < 2,
      }));

    const { container } = renderWithI18n(
      <ProcessPanel
        pane="trajectory"
        sessionId="sess-123"
        pageSize={2}
        turns={[]}
        subagents={[]}
        onLocate={vi.fn()}
      />,
    );

    expect(await screen.findByText('Newest reply')).toBeTruthy();
    expect(timelineSpy).toHaveBeenCalledWith('sess-123', 1, 2);

    const scrollNode = container.querySelector('[data-timeline-scroll]') as HTMLElement;
    Object.defineProperty(scrollNode, 'scrollHeight', { value: 600, configurable: true });
    Object.defineProperty(scrollNode, 'clientHeight', { value: 300, configurable: true });
    Object.defineProperty(scrollNode, 'scrollTop', { value: 260, writable: true, configurable: true });
    fireEvent.scroll(scrollNode);

    expect(await screen.findByText('First question')).toBeTruthy();
    expect(screen.getByText('Newest reply')).toBeTruthy();
    expect(timelineSpy).toHaveBeenCalledWith('sess-123', 2, 2);

    timelineSpy.mockRestore();
  });
});
