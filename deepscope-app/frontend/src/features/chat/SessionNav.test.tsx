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
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { vi, afterEach, describe, expect, it } from 'vitest';
import * as advisorApi from '@/api/advisor';
import { advisorKeys, renameSession } from '@/api/advisor';
import { i18nResources } from '@/i18n/resources';
import { SessionNav } from './SessionNav';

vi.mock('@/api/advisor', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/advisor')>();
  return {
    ...actual,
    renameSession: vi.fn(async (sessionId: string, title: string) => ({
      sessionId,
      title,
      agentId: null,
      agentName: null,
      status: 'idle' as const,
      createdAt: 1,
      updatedAt: 2,
    })),
  };
});

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
function Pane() { return <><SessionNav /><p aria-label="Current route">{useLocation().pathname}</p></>; }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(advisorKeys.sessions, [
    { sessionId: 's1', title: 'Review repository', agentId: null, agentName: 'Advisor', status: 'running', createdAt: 1, updatedAt: 2 },
    { sessionId: 's2', title: 'Compare options', agentId: null, agentName: null, status: 'idle', createdAt: 1, updatedAt: 3 },
  ]);
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/chat/s1']}><Routes><Route path="/chat/:sessionId" element={<Pane />} /></Routes></MemoryRouter></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SessionNav compact rows', () => {
  it('refreshes an untitled conversation when its generated title arrives after completion', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    const session = { sessionId: 'late-title', title: '', agentId: null, agentName: null, status: 'idle' as const, createdAt: Date.now(), updatedAt: Date.now() };
    client.setQueryData(advisorKeys.sessions, [session]);
    vi.spyOn(advisorApi, 'listSessions').mockResolvedValue([{ ...session, title: 'Generated title' }]);
    render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter><SessionNav /></MemoryRouter></QueryClientProvider></I18nextProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Generated title' })).toBeTruthy(), { timeout: 2200 });
    client.clear();
  });
  it('opens the existing rename dialog from the session more menu', async () => {
    const user = mount();
    expect(screen.queryByRole('menuitem', { name: 'Rename' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'More actions: Review repository' }));
    await user.click(screen.getByRole('menuitem', { name: 'Rename' }));
    expect(screen.getByRole('dialog', { name: i18n.t('chat.renameTitle') })).toBeTruthy();
    expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('Review repository');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'More actions: Review repository' }));
  });

  it('retains delete confirmation inside the session more menu', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'More actions: Compare options' }));
    await user.click(screen.getByRole('menuitem', { name: 'Delete' }));
    expect(screen.getByRole('dialog', { name: i18n.t('chat.deleteTitle') })).toBeTruthy();
  });

  it('preserves a running status and navigates from the single-line session title', async () => {
    const user = mount();
    expect(screen.getByRole('button', { name: /Review repository.*Running/ })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Compare options' }));
    expect(screen.getByLabelText('Current route').textContent).toBe('/chat/s2');
  });

  it('does not render sketch icons (MessageSquare) for idle sessions', () => {
    mount();
    const idleButton = screen.getByRole('button', { name: 'Compare options' });
    // Inside idle session button, there should be no svg / sketch icon
    expect(idleButton.querySelector('svg')).toBeNull();
  });

  it('activates inline rename on double click and commits with Enter', async () => {
    const user = mount();
    const sessionButton = screen.getByRole('button', { name: 'Compare options' });
    await user.dblClick(sessionButton);

    const inlineInput = screen.getByTestId('session-inline-rename-input') as HTMLInputElement;
    expect(inlineInput).toBeTruthy();
    expect(inlineInput.value).toBe('Compare options');

    await user.clear(inlineInput);
    await user.type(inlineInput, 'New Title{Enter}');

    expect(renameSession).toHaveBeenCalledWith('s2', 'New Title');
    expect(screen.queryByTestId('session-inline-rename-input')).toBeNull();
  });

  it('cancels inline rename on Escape without submitting', async () => {
    const user = mount();
    const sessionButton = screen.getByRole('button', { name: 'Compare options' });
    await user.dblClick(sessionButton);

    const inlineInput = screen.getByTestId('session-inline-rename-input') as HTMLInputElement;
    await user.type(inlineInput, ' Changed');
    await user.keyboard('{Escape}');

    expect(renameSession).not.toHaveBeenCalled();
    expect(screen.queryByTestId('session-inline-rename-input')).toBeNull();
    expect(screen.getByRole('button', { name: 'Compare options' })).toBeTruthy();
  });
});
