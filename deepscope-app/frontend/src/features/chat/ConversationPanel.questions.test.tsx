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
import type { ReactNode } from 'react';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import type { PendingQuestion } from '@/api/advisor';
import { api } from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import { setAccountIdentity, clearAccountIdentity } from '@/lib/accountIdentity';
import { clearTabDrafts } from '@/lib/tabDraftStorage';
import { useAdvisorConversation } from './useAdvisorConversation';
import { ConversationPanel } from './ConversationPanel';
import * as agentApi from '@/api/agent';
import { accountKeys } from '@/api/account';
import * as conversationModel from './conversationModel';

vi.mock('./useAdvisorConversation', async original => ({
  ...await original<typeof import('./useAdvisorConversation')>(),
  useAdvisorConversation: vi.fn(),
}));
vi.mock('@/api/auth', () => ({ can: vi.fn(() => true) }));

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources, lng: 'en', defaultNS: 'app',
  interpolation: { escapeValue: false }, react: { useSuspense: false },
});

const question: PendingQuestion = {
  questionId: 'question-1', text: 'What should we investigate?', answerMode: 'single', allowCustom: true,
  options: [{ id: 'architecture', label: 'Architecture' }, { id: 'performance', label: 'Performance' }, { id: 'delivery', label: 'Delivery' }],
  seq: 2, turn: 1,
};
let state: ReturnType<typeof useAdvisorConversation>;
let client: QueryClient;
const answerQuestion = vi.fn<ReturnType<typeof useAdvisorConversation>['answerQuestion']>();

beforeAll(() => vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
}));
afterAll(() => vi.unstubAllGlobals());

beforeEach(() => {
  answerQuestion.mockReset().mockResolvedValue(undefined);
  state = {
    session: { sessionId: 'panel-questions', title: 'Question conversation', agentId: null, agentName: null, status: 'awaiting_visitor', createdAt: 0, updatedAt: 0 },
    detailLoading: false, events: [], streamingText: '', thinking: { status: 'idle', text: '' },
    hasOrderedParts: false, running: false, runAgentId: undefined, queuedMessages: [], runError: null,
    agentUnavailable: false, retry: vi.fn().mockResolvedValue(undefined),
    pendingQuestion: question, pendingQuestionReply: null, questionBusy: false, questionError: null,
    answerQuestion, continueQuestion: vi.fn().mockResolvedValue(undefined), awaitingVisitor: true,
    pendingProposal: null, pendingInterrupts: [], resolveInterrupts: vi.fn().mockResolvedValue(undefined),
    send: vi.fn().mockResolvedValue(undefined), interruptAndSend: vi.fn().mockResolvedValue(undefined),
    cancelQueuedMessage: vi.fn(), stop: vi.fn(),
  };
  vi.mocked(useAdvisorConversation).mockImplementation(() => state);
  vi.spyOn(api, 'get').mockImplementation(async <T,>(path: string): Promise<T> => {
    if (path.endsWith('/model')) return { endpointId: null, choices: [], modelName: 'Test model' } as T;
    return [] as T;
  });
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
});
afterEach(() => { cleanup(); client.clear(); clearTabDrafts(); clearAccountIdentity(); vi.restoreAllMocks(); });

it('hides reasoning by account preference while preserving model thinking and effort', async () => {
  vi.spyOn(conversationModel, 'getStoredThinkingPreference').mockReturnValue(true);
  const preference = { thinkEnabled: false, reasoningEffort: 'max', defaultModelId: '', defaultAgentId: '', initialized: true };
  client.setQueryData(accountKeys.preferences(''), preference);
  state = { ...state, pendingQuestion: null, awaitingVisitor: false, events: [
    { seq: 1, turn: 1, kind: 'user_message', payload: { text: 'Question' } },
    { seq: 2, turn: 1, kind: 'advisor_message', payload: { text: 'Final answer', reasoning: 'Private reasoning text' } },
  ] };
  render(<ConversationPanel sessionId="panel-questions" />, { wrapper: Wrapper });
  const picker = await screen.findByRole('button', { name: /Choose model/ });
  expect(picker.textContent).toContain(i18n.t('composer.think'));
  expect(picker.textContent).toContain(i18n.t('composer.effort.max'));
  expect(screen.getByText('Final answer')).toBeTruthy();
  expect(document.querySelector('[data-process-trigger]')).toBeNull();
  await act(async () => client.setQueryData(accountKeys.preferences(''), { ...preference, thinkEnabled: true }));
  const processTrigger = await waitFor(() => {
    const button = document.querySelector<HTMLButtonElement>('[data-process-trigger]');
    expect(button).not.toBeNull();
    return button!;
  });
  await userEvent.setup().click(processTrigger);
  await userEvent.setup().click(screen.getByRole('button', { name: i18n.t('chat.activity.analysisRecord') }));
  expect(await screen.findByText('Private reasoning text')).toBeTruthy();
  await act(async () => client.setQueryData(accountKeys.preferences(''), preference));
  await waitFor(() => expect(screen.queryByText('Private reasoning text')).toBeNull());
  expect(picker.textContent).toContain(i18n.t('composer.think'));
  await userEvent.setup().type(composer(), 'Next question{Enter}');
  expect(state.send).toHaveBeenCalledWith('Next question', expect.objectContaining({ thinking: true, reasoningEffort: 'max' }));
});

it('toggles live reasoning visibility without discarding the streaming state', async () => {
  const preference = { thinkEnabled: false, reasoningEffort: 'high', defaultModelId: '', defaultAgentId: '', initialized: true };
  client.setQueryData(accountKeys.preferences(''), preference);
  state = { ...state, running: true, pendingQuestion: null, awaitingVisitor: false,
    thinking: { status: 'thinking', text: 'Live reasoning text' },
    events: [{ seq: 1, turn: 1, kind: 'user_message', payload: { text: 'Question' } }],
  };
  mount();
  expect(screen.queryByText('Live reasoning text')).toBeNull();
  await act(async () => client.setQueryData(accountKeys.preferences(''), { ...preference, thinkEnabled: true }));
  await waitFor(() => expect(screen.queryAllByText('Live reasoning text').length).toBeGreaterThan(0));
  await act(async () => client.setQueryData(accountKeys.preferences(''), preference));
  await waitFor(() => expect(screen.queryByText('Live reasoning text')).toBeNull());
  expect(state.thinking.text).toBe('Live reasoning text');
});

it('uses an explicit new selection when retrying a failed message', async () => {
  state = { ...state, pendingQuestion: null, awaitingVisitor: false, runError: 'offline',
    session: { ...state.session!, agentId: 'old', agentName: 'Old agent' } };
  vi.spyOn(agentApi, 'getAvailableAgent').mockResolvedValue({ agentId: 'old', name: 'Old agent', description: '', origin: 'personal' });
  vi.spyOn(agentApi, 'pageAvailableAgents').mockResolvedValue({ items: [{ agentId: 'replacement', name: 'Replacement agent', description: '', origin: 'personal' }], page: 1, size: 5, total: 1, totalPages: 1 });
  const user = userEvent.setup();
  mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await user.click(await screen.findByRole('option', { name: 'Replacement agent' }));
  await user.click(screen.getByRole('button', { name: 'Retry this message' }));
  expect(state.retry).toHaveBeenCalledWith(false, 'replacement');
});

function Wrapper({ children }: { children: ReactNode }) {
  return <I18nextProvider i18n={i18n}><QueryClientProvider client={client}>{children}</QueryClientProvider></I18nextProvider>;
}
function mount() {
  return render(<ConversationPanel sessionId="panel-questions" initialThinking />, { wrapper: Wrapper });
}
const composer = () => screen.getByPlaceholderText('Collaborate with DeepScope') as HTMLTextAreaElement;

it('restores unfinished text after reloading a running conversation without restoring attachments', async () => {
  setAccountIdentity('draft-token', { userId: 'chat-draft-owner', username: 'owner', roles: [] });
  state.pendingQuestion = null;
  state.running = true;
  const user = userEvent.setup();
  const view = mount();
  await user.type(composer(), 'Continue after refreshing');
  view.unmount();
  mount();
  expect(composer().value).toBe('Continue after refreshing');
  expect(state.send).not.toHaveBeenCalled();
});

it('copies the visitor message and shows its saved Shanghai timestamp beside the action', async () => {
  const user = userEvent.setup();
  const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
  state.pendingQuestion = null;
  state.awaitingVisitor = false;
  state.events = [{ seq: 1, turn: 1, kind: 'user_message', payload: { text: 'Original user text' }, createdAt: Date.parse('2026-10-16T04:34:00Z') }];
  mount();
  const time = screen.getByText('12:34');
  await user.click(within(time.parentElement!).getByRole('button', { name: 'Copy' }));
  expect(writeText).toHaveBeenCalledWith('Original user text');
  expect(time.getAttribute('datetime')).toBe('2026-10-16T04:34:00.000Z');
});

it('replaces the composer with the question card outside conversation scrolling', async () => {
  const user = userEvent.setup();
  const { container } = mount();
  const heading = screen.getByRole('heading', { name: question.text });
  const card = heading.closest('[aria-labelledby]')!;
  expect(container.querySelector('[data-conversation-scroll]')?.contains(card)).toBe(false);
  expect(screen.queryByPlaceholderText('Collaborate with DeepScope')).toBeNull();
  expect(screen.queryByRole('button', { name: /Choose agent/ })).toBeNull();
  expect(screen.getAllByRole('radio')).toHaveLength(4);
  expect(within(screen.getByRole('radio', { name: 'Other' }).closest('label')!).getByText('4')).toBeTruthy();
  await user.click(screen.getByRole('radio', { name: 'Performance' }));
  expect(answerQuestion).toHaveBeenCalledExactlyOnceWith(question, { selectedOptionIds: ['performance'], customText: '' }, true, undefined, null, 'high');
  expect(state.send).not.toHaveBeenCalled();
});

it('restores agent selection together with ordinary input after the question card exits', () => {
  const view = mount();
  expect(screen.queryByRole('button', { name: /Choose agent/ })).toBeNull();
  state.pendingQuestion = null;
  state.awaitingVisitor = false;
  view.rerender(<ConversationPanel sessionId="panel-questions" initialThinking />);
  expect(composer()).toBeTruthy();
  expect(screen.getByRole('button', { name: /Choose agent/ })).toBeTruthy();
});

it('renders the new question title and actual option count when the pending question changes', () => {
  const view = mount();
  const nextQuestion = { ...question, questionId: 'question-2', text: 'Which constraint matters?', options: question.options.slice(0, 2) };
  state = { ...state, pendingQuestion: nextQuestion };
  view.rerender(<ConversationPanel sessionId="panel-questions" initialThinking />);
  expect(screen.queryByRole('heading', { name: question.text })).toBeNull();
  expect(screen.getByRole('heading', { name: 'Which constraint matters?' })).toBeTruthy();
  expect(screen.getAllByRole('radio')).toHaveLength(3);
  expect(within(screen.getByRole('radio', { name: 'Other' }).closest('label')!).getByText('3')).toBeTruthy();
});

it.each(['questionBusy', 'running'] as const)('disables card answer controls while %s is active', async flag => {
  const user = userEvent.setup();
  state = { ...state, [flag]: true };
  mount();
  const option = screen.getByRole('radio', { name: 'Performance' });
  expect(option.matches(':disabled')).toBe(true);
  await user.click(option);
  expect(answerQuestion).not.toHaveBeenCalled();
});

it('removes the interactive card when the hook confirms the question is answered', () => {
  const view = mount();
  state = { ...state, pendingQuestion: null, awaitingVisitor: false,
    events: [{ seq: 3, turn: 2, kind: 'user_message', payload: { text: 'Performance' } }] };
  view.rerender(<ConversationPanel sessionId="panel-questions" initialThinking />);
  expect(screen.queryByRole('heading', { name: question.text })).toBeNull();
  expect(screen.queryByRole('radio')).toBeNull();
  expect(screen.getByText('Performance')).toBeTruthy();
  expect(composer()).toBeTruthy();
});

it('keeps a custom card answer after failure and retries without showing the ordinary composer', async () => {
  const user = userEvent.setup();
  let reject: (reason: Error) => void = () => {};
  answerQuestion.mockImplementationOnce(() => new Promise<void>((_resolve, fail) => { reject = fail; }));
  mount();
  await user.click(screen.getByRole('radio', { name: 'Other' }));
  const input = screen.getByRole('textbox', { name: 'Your answer' }) as HTMLTextAreaElement;
  await user.type(input, 'Investigate memory usage');
  await user.click(screen.getByRole('button', { name: 'Confirm answer' }));
  expect(answerQuestion).toHaveBeenNthCalledWith(1, question, { selectedOptionIds: [], customText: 'Investigate memory usage' }, true, undefined, null, 'high');
  expect(input.value).toBe('Investigate memory usage');
  await act(async () => reject(new Error('offline')));
  expect(input.value).toBe('Investigate memory usage');
  expect(screen.getByText('Could not submit your answer. Your draft is saved here. Please retry.')).toBeTruthy();
  expect(state.send).not.toHaveBeenCalled();
  expect(screen.queryByPlaceholderText('Collaborate with DeepScope')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Retry answer' }));
  await waitFor(() => expect(answerQuestion).toHaveBeenCalledTimes(2));
  expect(answerQuestion).toHaveBeenCalledTimes(2);
  expect(answerQuestion.mock.calls[1]).toEqual(answerQuestion.mock.calls[0]);
});

it('keeps a stop action while the pending question run is still finishing', async () => {
  const user = userEvent.setup();
  state = { ...state, running: true };
  mount();
  expect(screen.queryByPlaceholderText('Collaborate with DeepScope')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Stop' }));
  expect(state.stop).toHaveBeenCalledOnce();
  expect(state.send).not.toHaveBeenCalled();
  expect(answerQuestion).not.toHaveBeenCalled();
});

it('preserves an ordinary composer draft while the question temporarily occupies its place', async () => {
  const user = userEvent.setup();
  state.pendingQuestion = null;
  const view = mount();
  await user.type(composer(), 'Keep my draft');
  state = { ...state, pendingQuestion: question };
  view.rerender(<ConversationPanel sessionId="panel-questions" initialThinking />);
  expect(screen.queryByPlaceholderText('Collaborate with DeepScope')).toBeNull();
  state = { ...state, pendingQuestion: null };
  view.rerender(<ConversationPanel sessionId="panel-questions" initialThinking />);
  expect(composer().value).toBe('Keep my draft');
});

it('keeps queued messages visible and cancellable while the card replaces ordinary input', async () => {
  const user = userEvent.setup();
  state.queuedMessages = [{ id: 'queued-before-question', text: 'Queued follow-up', createdAt: 1 }];
  mount();
  expect(screen.getByText('Queued follow-up')).toBeTruthy();
  expect(screen.getByRole('button', { name: i18n.t('chat.composer.steer') }).matches(':disabled')).toBe(true);
  await user.click(screen.getByRole('button', { name: i18n.t('chat.composer.cancelQueue') }));
  expect(state.cancelQueuedMessage).toHaveBeenCalledWith('queued-before-question');
  expect(screen.queryByPlaceholderText('Collaborate with DeepScope')).toBeNull();
});
