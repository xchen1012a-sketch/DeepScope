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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { api } from '@/lib/apiClient';
import * as accountApi from '@/api/account';
import * as agentApi from '@/api/agent';
import * as newConversation from '@/features/account/newConversation';
import { clearAccountIdentity, setAccountIdentity } from '@/lib/accountIdentity';
import { i18nResources } from '@/i18n/resources';
import ChatPage from './ChatPage';
import * as conversationModel from './conversationModel';

vi.mock('@/api/auth', () => ({ can: () => false, getUsername: () => 'admin', getToken: () => 'test-only' }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'zh-CN', defaultNS: 'app', react: { useSuspense: false } });
afterEach(() => { cleanup(); clearAccountIdentity(); vi.restoreAllMocks(); });

it('uses the shared composer slash menu on the start page, without a duplicate draft card', async () => {
  vi.spyOn(conversationModel, 'getStoredThinkingPreference').mockReturnValue(true);
  vi.spyOn(accountApi, 'getPreferences').mockResolvedValue({ thinkEnabled: false, reasoningEffort: 'high', defaultModelId: '', defaultAgentId: '', initialized: true });
  vi.spyOn(api, 'get').mockImplementation(async <T,>(path: string): Promise<T> => (path.endsWith('/commands') ? [] : {}) as T);
  setAccountIdentity('test-only', { userId: '1', username: 'admin', nickname: '测试昵称', roles: [] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter><ChatPage /></MemoryRouter></QueryClientProvider></I18nextProvider>);
  expect(screen.getByRole('heading').textContent).toContain('测试昵称');
  const input = screen.getByPlaceholderText('与 DeepScope 协作') as HTMLTextAreaElement;
  const user = userEvent.setup();
  await user.type(input, '/');
  expect(screen.getByTestId('slash-command-menu')).toBeTruthy();
  await user.click(screen.getByTestId('slash-command-clear'));
  await waitFor(() => expect(document.activeElement).toBe(input));
  await user.keyboard('{Enter}');
  expect(input.value).toBe('');
  expect(screen.queryByText('备好的问题')).toBeNull();
  expect(screen.getByRole('button', { name: /选择模型/ }).textContent).toContain(i18n.t('composer.think'));
  client.clear();
});
it('creates a new conversation with an explicit agent and lets free mode override the personal default', async () => {
  vi.spyOn(accountApi, 'getPreferences').mockResolvedValue({ thinkEnabled: false, reasoningEffort: 'high', defaultModelId: '', defaultAgentId: 'default-agent', initialized: true });
  const available: agentApi.AvailableAgent[] = [
    { agentId: 'default-agent', name: 'Default agent', description: '', origin: 'personal' },
    { agentId: 'chosen-agent', name: 'Chosen agent', description: '', origin: 'personal' },
  ];
  vi.spyOn(agentApi, 'getAvailableAgent').mockImplementation(async id => available.find(agent => agent.agentId === id) ?? null);
  vi.spyOn(agentApi, 'pageAvailableAgents').mockResolvedValue({ items: available, page: 1, size: 5, total: 2, totalPages: 1 });
  vi.spyOn(api, 'get').mockResolvedValue([]);
  const create = vi.spyOn(newConversation, 'createPersonalConversation').mockResolvedValue({ sessionId: 'new', title: '', agentId: null, agentName: null, status: 'idle', createdAt: 0, updatedAt: 0 });
  setAccountIdentity('test-only', { userId: '1', username: 'admin', roles: [] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter>
    <Routes><Route path="/" element={<ChatPage />} /><Route path="/chat/new" element={<p>Created</p>} /></Routes>
  </MemoryRouter></QueryClientProvider></I18nextProvider>);
  const user = userEvent.setup();
  const picker = await screen.findByRole('button', { name: '选择智能体: Default agent' });
  await user.click(picker);
  await user.click(await screen.findByRole('option', { name: 'Chosen agent' }));
  await user.click(screen.getByRole('button', { name: '选择智能体: Chosen agent' }));
  await user.click(screen.getByRole('option', { name: /自由模式/ }));
  await user.type(screen.getByPlaceholderText('与 DeepScope 协作'), 'hello{Enter}');
  await screen.findByText('Created');
  expect(create).toHaveBeenCalledWith({ agentId: undefined, modelEndpointId: null });
  client.clear();
});
