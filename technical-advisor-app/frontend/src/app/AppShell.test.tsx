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
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { can, logout } from '@/api/auth';
import { advisorKeys } from '@/api/advisor';
import { i18nResources } from '@/i18n/resources';
import AppShell from './AppShell';
import { clearAccountIdentity, setAccountIdentity } from '@/lib/accountIdentity';

vi.mock('@/api/auth', () => ({ can: vi.fn(() => true), getUsername: () => 'Test user', logout: vi.fn(async () => undefined) }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
function Location() { return <p aria-label="Current route">{useLocation().pathname}</p>; }
function mount(path = '/chat/s1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(advisorKeys.sessions, [{ sessionId: 's1', title: 'Review repository', agentId: null, agentName: null, status: 'idle', createdAt: 1, updatedAt: 2 }]);
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={[path]}><Routes><Route element={<AppShell />}><Route path="/chat/:sessionId" element={<Location />} /><Route path="*" element={<Location />} /></Route></Routes></MemoryRouter></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}
beforeEach(() => { vi.mocked(can).mockReturnValue(true); });
beforeAll(() => vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} }));
afterAll(() => vi.unstubAllGlobals());
afterEach(() => { cleanup(); clearAccountIdentity(); vi.clearAllMocks(); });

describe('AppShell sidebar', () => {
  it('collapses and reopens recent sessions without changing the current route', async () => {
    const user = mount();
    const toggle = screen.getByRole('button', { name: 'Recent sessions' });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    await user.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('button', { name: 'Review repository' })).toBeNull();
    expect(screen.getByLabelText('Current route').textContent).toBe('/chat/s1');
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Review repository' })).toBeTruthy();
  });
  it('opens personal settings from the user menu', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'User menu' }));
    await user.click(screen.getByRole('menuitem', { name: 'Personal settings' }));
    expect(screen.getByLabelText('Current route').textContent).toMatch(/^\/account/);
  });

  it('keeps the application navigation visible on account routes', () => {
    vi.mocked(can).mockReturnValue(false);
    mount('/account/profile');
    expect(screen.getByRole('navigation', { name: i18n.t('nav.sidebar') })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'User menu' })).toBeTruthy();
  });
  it('uses a nickname beside the avatar, updates it live, and falls back to the account', () => {
    setAccountIdentity('test-token', { userId: 'admin', username: 'admin', nickname: '管理员昵称', roles: ['admin'] });
    mount();
    expect(screen.getByRole('button', { name: 'User menu' }).textContent).toContain('管理员昵称');
    act(() => setAccountIdentity('test-token', { userId: 'admin', username: 'admin', nickname: ' ', roles: ['admin'] }));
    expect(screen.getByRole('button', { name: 'User menu' }).textContent).toContain('admin');
    expect(screen.getByRole('button', { name: 'User menu' }).textContent).not.toContain('管理员昵称');
  });
  it('expands workspace without a search field while marking the selected route', async () => {
    const user = mount();
    expect(screen.getByRole('button', { name: 'Review repository' }).getAttribute('aria-current')).toBe('page');
    expect(screen.queryByRole('searchbox', { name: 'Search navigation' })).toBeNull();
    expect(screen.queryByRole('link', { name: i18n.t('nav.agent') })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Workspace' }));
    expect(screen.getByRole('link', { name: i18n.t('nav.agent') })).toBeTruthy();
    expect(screen.queryByRole('searchbox', { name: 'Search navigation' })).toBeNull();
    await user.click(screen.getByRole('link', { name: 'Tools & MCP' }));
    expect(screen.getByLabelText('Current route').textContent).toBe('/tools');
    expect(screen.getByRole('link', { name: 'Tools & MCP' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByRole('link', { name: i18n.t('nav.home') }).getAttribute('aria-current')).toBeNull();
  });

  it('filters destinations using existing permissions', async () => {
    vi.mocked(can).mockImplementation(permission => permission === 'chat.use' || permission === 'knowledge.view');
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Workspace' }));
    expect(screen.getByRole('link', { name: i18n.t('nav.knowledge') })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Tools & MCP' })).toBeNull();
    expect(screen.queryByRole('link', { name: i18n.t('nav.settings') })).toBeNull();
    expect(screen.queryByRole('button', { name: i18n.t('nav.group.access') })).toBeNull();
  });

  it('expands settings without a search field separately from workspace', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Workspace' }));
    await user.click(screen.getByRole('button', { name: i18n.t('nav.preferences') }));
    expect(screen.queryByRole('link', { name: i18n.t('nav.agent') })).toBeNull();
    expect(screen.queryByRole('searchbox', { name: 'Search navigation' })).toBeNull();
    await user.click(screen.getByRole('button', { name: i18n.t('nav.group.access') }));
    expect(screen.getByRole('link', { name: i18n.t('nav.roles') })).toBeTruthy();
    expect(screen.getByRole('link', { name: i18n.t('nav.users') })).toBeTruthy();
    await user.click(screen.getByRole('link', { name: i18n.t('nav.roles') }));
    expect(screen.getByLabelText('Current route').textContent).toBe('/access/roles');
    await user.click(screen.getByRole('button', { name: i18n.t('nav.preferences') }));
    expect(screen.queryByRole('searchbox', { name: 'Search navigation' })).toBeNull();
  });

  it('keeps direct destinations accessible after collapsing the sidebar', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
    await user.click(screen.getByRole('link', { name: 'Tools & MCP' }));
    expect(screen.getByLabelText('Current route').textContent).toBe('/tools');
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeTruthy();
  });

  it('expands permissions from the compact rail and retains user and role destinations', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Workspace' }));
    await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
    await user.click(screen.getByRole('button', { name: i18n.t('nav.group.access') }));
    expect(screen.getByRole('button', { name: 'Collapse sidebar' })).toBeTruthy();
    await user.click(screen.getByRole('link', { name: i18n.t('nav.users') }));
    expect(screen.getByLabelText('Current route').textContent).toBe('/access/users');
    expect(screen.getByRole('link', { name: i18n.t('nav.roles') })).toBeTruthy();
  });

  it('puts sign-out in the account menu and returns to login after sign-out', async () => {
    const user = mount();
    expect(screen.queryByRole('menuitem', { name: 'Sign out' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'User menu' }));
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalledOnce();
    await waitFor(() => expect(screen.getByLabelText('Current route').textContent).toBe('/login'));
  });
});
