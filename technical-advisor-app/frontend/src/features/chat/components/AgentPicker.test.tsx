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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { getAvailableAgent, pageAvailableAgents, type AvailableAgent } from '@/api/agent';
import { AgentPicker } from './AgentPicker';
vi.mock('@/api/agent', () => ({ pageAvailableAgents: vi.fn(), getAvailableAgent: vi.fn() }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
const options: AvailableAgent[] = [{ agentId: 'a', name: 'Analyst', description: 'Analyze projects', origin: 'personal' }, { agentId: 'b', name: 'Contracts', description: 'Review contracts', origin: 'personal' }];
beforeEach(() => {
  vi.mocked(pageAvailableAgents).mockImplementation(async (q, page) => ({ items: q ? [options[1]] : options, total: q ? 1 : 7, page, size: 5, totalPages: q ? 1 : 2 }));
  vi.mocked(getAvailableAgent).mockImplementation(async id => options.find(agent => agent.agentId === id) ?? null);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });
function mount(initial: string | null = null, running = false, currentName: string | null = 'Analyst') {
  function Harness() {
    const [value, setValue] = useState(initial);
    return <AgentPicker value={value} onChange={setValue} running={running} activeAgentId="a" activeAgentName={currentName} />;
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><Harness /></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}
it('searches actual available agents and switches between locked and free mode', async () => {
  const user = mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Analyst/ });
  await user.type(screen.getByRole('textbox', { name: 'Search agents' }), 'contract');
  await waitFor(() => expect(pageAvailableAgents).toHaveBeenCalledWith('contract', 1, 5, expect.any(AbortSignal)));
  await waitFor(() => expect(screen.queryByRole('option', { name: /Analyst/ })).toBeNull());
  await user.click(screen.getByRole('option', { name: /Contracts/ }));
  expect(screen.getByRole('button', { name: /Choose agent: Contracts/ })).toBeTruthy();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await user.click(screen.getByRole('option', { name: /Free mode/ }));
  expect(screen.getByRole('button', { name: /Choose agent: Free mode/ })).toBeTruthy();
});
it('allows changing the next message while a different agent continues running', async () => {
  const user = mount('a', true);
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await user.click(await screen.findByRole('option', { name: /Contracts/ }));
  expect(screen.getByRole('status').textContent).toContain('Current: Analyst');
  expect(screen.getByRole('status').textContent).toContain('Next message: Contracts');
});
it('keeps a missing selected agent visibly unavailable rather than falling back to free mode', async () => {
  const user = mount('deleted');
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Analyst/ });
  expect(screen.getByRole('button', { name: /Choose agent: Unavailable agent/ })).toBeTruthy();
});
it('shows load errors and supports retry without silently changing selection', async () => {
  vi.mocked(pageAvailableAgents).mockRejectedValueOnce(new Error('offline'));
  const user = mount('a');
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('alert');
  await user.click(screen.getByRole('button', { name: 'Retry' }));
  await waitFor(() => expect(screen.getByRole('option', { name: /Analyst/ })).toBeTruthy());
});
it('queries the backend on every wheel page change, including when scrolling back', async () => {
  const user = mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Analyst/ });
  fireEvent.wheel(screen.getByRole('dialog'), { deltaY: 100, cancelable: true });
  await waitFor(() => expect(pageAvailableAgents).toHaveBeenCalledWith('', 2, 5, expect.any(AbortSignal)));
  await screen.findByRole('option', { name: /Analyst/ });
  fireEvent.wheel(screen.getByRole('dialog'), { deltaY: -100, cancelable: true });
  await waitFor(() => expect(vi.mocked(pageAvailableAgents).mock.calls.filter(call => call[0] === '' && call[1] === 1)).toHaveLength(2));
  expect(screen.queryByText(/Use only this agent/)).toBeNull();
  expect(screen.queryByTestId('pagination')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Next' })).toBeNull();
});
it('uses only the five rows returned by the server and does not change the page at the end', async () => {
  const five = Array.from({ length: 5 }, (_, index) => ({ ...options[0], agentId: 'agent-' + index, name: 'Agent ' + index }));
  vi.mocked(pageAvailableAgents).mockResolvedValue({ items: five, page: 1, size: 5, total: 5, totalPages: 1 });
  const user = mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Agent 4/ });
  expect(screen.getAllByRole('option')).toHaveLength(6); // Free mode plus five backend rows.
  const before = vi.mocked(pageAvailableAgents).mock.calls.length;
  fireEvent.wheel(screen.getByRole('dialog'), { deltaY: 100, cancelable: true });
  expect(vi.mocked(pageAvailableAgents).mock.calls.length).toBe(before);
});
it('can page from the focused search field without adding navigation buttons', async () => {
  const user = mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Analyst/ });
  await user.click(screen.getByRole('textbox', { name: 'Search agents' }));
  await user.keyboard('{PageDown}');
  await waitFor(() => expect(pageAvailableAgents).toHaveBeenCalledWith('', 2, 5, expect.any(AbortSignal)));
  expect(screen.queryByTestId('pagination')).toBeNull();
});
it('resolves the currently running free-mode agent independently from the next choice', async () => {
  const user = mount(null, true, null);
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await user.click(await screen.findByRole('option', { name: /Contracts/ }));
  await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Current: Analyst · Next message: Contracts'));
});
it('requests the next backend page for a touch swipe', async () => {
  const user = mount();
  await user.click(screen.getByRole('button', { name: /Choose agent/ }));
  await screen.findByRole('option', { name: /Analyst/ });
  fireEvent.touchStart(screen.getByRole('dialog'), { touches: [{ clientY: 150 }] });
  fireEvent.touchMove(screen.getByRole('dialog'), { touches: [{ clientY: 80 }], cancelable: true });
  await waitFor(() => expect(pageAvailableAgents).toHaveBeenCalledWith('', 2, 5, expect.any(AbortSignal)));
});
