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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { setAccountIdentity, clearAccountIdentity } from '@/lib/accountIdentity';
import { saveToken, clearToken } from '@/lib/apiClient';
import AccountPage from './AccountPage';
import AppShell from '@/app/AppShell';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
const profile = { userId: 'alice', username: 'alice', nickname: 'Alice', roles: ['user'], roleNames: ['Research analyst'], permissions: ['chat.use', 'unknown.custom'], isAdmin: false, createdAt: 1000, avatarUrl: null, avatarUpdatedAt: 0 };
let preferences = { thinkEnabled: false, defaultModelId: '', defaultAgentId: '', initialized: true };
let nicknameFails = false;
let failOverview = false;
let calls: Array<{ path: string; init?: RequestInit }>;
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => { storage.set(key, value); },
    removeItem: (key: string) => { storage.delete(key); }, clear: () => storage.clear() });
  calls = []; nicknameFails = false; failOverview = false;
  preferences = { thinkEnabled: false, defaultModelId: '', defaultAgentId: '', initialized: true };
  localStorage.clear(); saveToken('test-only-token'); setAccountIdentity('test-only-token', profile);
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const path = String(input); calls.push({ path, init });
    if (path === '/api/auth/me') return response(profile);
    if (path === '/api/me/profile') return nicknameFails ? response({ code: 'invalid_nickname' }, 400) : response({ ...profile, ...JSON.parse(String(init?.body)) });
    if (path === '/api/me/preferences') {
      if (init?.method === 'PUT') preferences = { ...preferences, ...JSON.parse(String(init.body)), initialized: true };
      return response(preferences);
    }
    if (path === '/api/me/password' || path === '/api/me/sessions/revoke-others' || init?.method === 'DELETE') return new Response(null, { status: 204 });
    if (path === '/api/me/overview') return failOverview ? response({}, 500) : response({ agents: 3, skills: null, knowledge: 0, tools: 1, credentials: [{ toolId: 'tool-1', name: 'Orders', origin: 'http', configured: true }] });
    if (path === '/api/advisor/models/active') return response({ endpointId: 'current-model', modelName: 'Current' });
    if (path === '/api/advisor/agents/available') return response([{ agentId: 'agent-1', name: 'Analyst', description: '', origin: 'personal' }]);
    if (path === '/api/advisor/sessions') return response([{ sessionId: 's1', title: 'Saved conversation', agentId: null, agentName: null, status: 'idle', createdAt: 1000, updatedAt: 2000 }]);
    return response({}, 404);
  }));
});
afterEach(() => { cleanup(); clearToken(); clearAccountIdentity(); vi.unstubAllGlobals(); });
function Location() { return <span aria-label="Route">{useLocation().pathname}</span>; }
function mount(section = 'profile') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/account/' + section]}><Routes>
    <Route path="/account/*" element={<AccountPage />} /><Route path="*" element={<Location />} />
  </Routes></MemoryRouter></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}
it('shows the five settings destinations beside the active section', () => {
  mount('profile');
  expect(screen.getByRole('navigation', { name: 'Settings sections' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Personal settings' })).toBeNull();
  expect(screen.queryByRole('link', { name: 'About' })).toBeNull();
});
it('redirects the removed About URL to profile without querying version information', async () => {
  mount('about');
  await screen.findByRole('textbox', { name: 'Nickname' });
  expect(screen.getByRole('link', { name: 'Profile' }).getAttribute('aria-current')).toBe('page');
  expect(calls.some(call => call.path === '/api/me/about')).toBe(false);
});
it('returns to the application after reopening settings from the global user menu', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      initialEntries={['/tools', { pathname: '/account/security', state: { accountEntry: true } }]} initialIndex={1}>
      <Routes><Route element={<AppShell />}>
        <Route path="/account/*" element={<AccountPage />} /><Route path="*" element={<Location />} />
      </Route></Routes>
    </MemoryRouter>
  </QueryClientProvider></I18nextProvider>);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'User menu' }));
  await user.click(screen.getByRole('menuitem', { name: 'Personal settings' }));
  await screen.findByRole('textbox', { name: 'Nickname' });
  await user.click(screen.getByRole('button', { name: 'Return to application' }));
  expect(screen.getByLabelText('Route').textContent).toBe('/tools');
});
it('saves a trimmed nickname and offers account shortcuts without a permission overview', async () => {
  const user = mount();
  const input = await screen.findByRole('textbox', { name: 'Nickname' });
  await user.clear(input); await user.type(input, '  Changed  '); await user.tab();
  expect(calls.some(call => call.path === '/api/me/profile')).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Save' }));
  await screen.findByText('Saved');
  expect(calls.find(call => call.path === '/api/me/profile')?.init?.body).toBe(JSON.stringify({ nickname: 'Changed' }));
  expect(screen.queryByText('unknown.custom')).toBeNull();
  expect(screen.queryByText('Permissions')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Chat' })).toBeNull();
  expect(screen.queryByText('Chat · Use')).toBeNull();
  expect(screen.getByRole('link', { name: 'Change password' }).getAttribute('href')).toBe('/account/security');
  expect(screen.getAllByRole('link', { name: 'Preferences' }).some(link => link.getAttribute('href') === '/account/preferences')).toBe(true);
});
it('requires confirmation before deleting all sessions and refreshes the remaining list', async () => {
  const user = mount('sessions');
  await screen.findByText('Saved conversation');
  await user.click(screen.getByRole('button', { name: 'Delete all conversations' }));
  expect(calls.some(call => call.init?.method === 'DELETE')).toBe(false);
  expect(screen.getByRole('dialog').textContent).toContain('1');
  await user.click(screen.getByRole('button', { name: 'Confirm deletion' }));
  await waitFor(() => expect(calls.filter(call => call.init?.method === 'DELETE').map(call => call.path)).toEqual(['/api/me/sessions/s1']));
});
it('uses the managed account value and role display names without showing a legacy role identifier', async () => {
  mount('profile');
  await screen.findByText('Research analyst');
  expect(screen.getByText('Account')).toBeTruthy();
  expect(screen.getByText('alice')).toBeTruthy();
  expect(screen.queryByText('user')).toBeNull();
});
it('refreshes after a partial bulk deletion and retries only the remaining conversation', async () => {
  const first = { sessionId: 'ok', title: 'Deleted conversation', agentId: null, agentName: null, status: 'idle', createdAt: 1, updatedAt: 2 };
  const second = { ...first, sessionId: 'failed', title: 'Retry this conversation' };
  let remaining = [first, second];
  let fail = true;
  const deleted: string[] = [];
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (String(input) === '/api/advisor/sessions') return response(remaining);
    if (init?.method === 'DELETE') {
      const id = String(input).split('/').pop()!;
      deleted.push(id);
      if (id === 'failed' && fail) return response({}, 500);
      remaining = remaining.filter(session => session.sessionId !== id);
      return new Response(null, { status: 204 });
    }
    return original(input, init);
  });
  const user = mount('sessions');
  await screen.findByText('Deleted conversation');
  await user.click(screen.getByRole('button', { name: 'Delete all conversations' }));
  await user.click(screen.getByRole('button', { name: 'Confirm deletion' }));
  await screen.findByRole('alert');
  expect(screen.queryByText('Deleted conversation')).toBeNull();
  expect(screen.getByText('Retry this conversation')).toBeTruthy();
  await waitFor(() => expect((screen.getByRole('button', { name: 'Confirm deletion' }) as HTMLButtonElement).disabled).toBe(false));
  fail = false;
  await user.click(screen.getByRole('button', { name: 'Confirm deletion' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(deleted).toEqual(['ok', 'failed', 'failed']);
  await screen.findByText('No conversations yet.');
});
it('cancels deleting all conversations without sending a delete request', async () => {
  const user = mount('sessions');
  await screen.findByText('Saved conversation');
  await user.click(screen.getByRole('button', { name: 'Delete all conversations' }));
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(calls.some(call => call.init?.method === 'DELETE')).toBe(false);
});
it('retains edited nickname on failure and offers retry through the same input', async () => {
  nicknameFails = true; const user = mount();
  const input = await screen.findByRole('textbox', { name: 'Nickname' }) as HTMLInputElement;
  await user.clear(input); await user.type(input, 'Keep me{Enter}');
  await screen.findByRole('alert'); expect(input.value).toBe('Keep me');
  nicknameFails = false; await user.type(input, '{Enter}');
  await screen.findByText('Saved');
});
it('validates repeated passwords and clears the form only after a successful change', async () => {
  const user = mount('security');
  await user.type(screen.getByLabelText('Current password'), 'old-test-password');
  await user.type(screen.getByLabelText('New password'), 'new-test-password');
  await user.type(screen.getByLabelText('Confirm new password'), 'different');
  expect((screen.getByRole('button', { name: 'Change password' }) as HTMLButtonElement).disabled).toBe(true);
  await user.clear(screen.getByLabelText('Confirm new password'));
  await user.type(screen.getByLabelText('Confirm new password'), 'new-test-password');
  await user.click(screen.getByRole('button', { name: 'Change password' }));
  await screen.findByText('Password changed. Other devices have been signed out.');
  expect((screen.getByLabelText('Current password') as HTMLInputElement).value).toBe('');
});
it('requires confirmation before signing out other devices', async () => {
  const user = mount('security');
  await user.click(screen.getByRole('button', { name: 'Sign out other devices' }));
  expect(calls.some(call => call.path.endsWith('revoke-others'))).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(calls.some(call => call.path.endsWith('revoke-others'))).toBe(true));
});
it('saves preferences immediately without overwriting other fields', async () => {
  const user = mount('preferences');
  await user.click(await screen.findByRole('switch', { name: 'Thinking process' }));
  await screen.findByText('Saved');
  expect(calls.find(call => call.path === '/api/me/preferences' && call.init?.method === 'PUT')?.init?.body).toBe('{"thinkEnabled":true}');
});
it('migrates a legacy local false once using a conditional request', async () => {
  preferences.initialized = false; localStorage.setItem('advisor:thinking_preference', 'false');
  mount('preferences');
  await screen.findByRole('switch', { name: 'Thinking process' });
  const call = calls.find(call => call.path === '/api/me/preferences' && call.init?.method === 'PUT');
  expect(new Headers(call?.init?.headers).get('If-None-Match')).toBe('*');
  expect(call?.init?.body).toBe('{"thinkEnabled":false}');
});
it('reloads server preferences after another device wins the one-time migration', async () => {
  preferences.initialized = false; localStorage.setItem('advisor:thinking_preference', 'true');
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (String(input) === '/api/me/preferences' && init?.method === 'PUT') {
      preferences = { ...preferences, thinkEnabled: false, initialized: true };
      return response({}, 412);
    }
    return original(input, init);
  });
  mount('preferences');
  expect((await screen.findByRole('switch', { name: 'Thinking process' })).getAttribute('aria-checked')).toBe('false');
  expect(screen.queryByRole('alert')).toBeNull();
});
it('keeps the server value when saving a preference fails', async () => {
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (String(input) === '/api/me/preferences' && init?.method === 'PUT') return response({}, 500);
    return original(input, init);
  });
  const user = mount('preferences');
  await user.click(await screen.findByRole('switch', { name: 'Thinking process' }));
  await screen.findByRole('alert');
  expect(screen.getByRole('switch', { name: 'Thinking process' }).getAttribute('aria-checked')).toBe('false');
});
it('redirects the removed resources page to profile without querying resource data', async () => {
  mount('assets');
  await screen.findByRole('textbox', { name: 'Nickname' });
  expect(screen.queryByRole('link', { name: 'My resources' })).toBeNull();
  expect(screen.queryByText('Credential configuration')).toBeNull();
  expect(calls.some(call => call.path === '/api/me/overview')).toBe(false);
});
it('does not surface obsolete resource errors after the resource page is removed', async () => {
  failOverview = true; mount('assets');
  await screen.findByRole('textbox', { name: 'Nickname' });
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByText('No credential configurations yet.')).toBeNull();
});
it('opens a recent conversation through its real existing route', async () => {
  const user = mount('sessions');
  await screen.findByText('Saved conversation');
  await user.click(screen.getByRole('link', { name: 'Open Saved conversation' }));
  expect(screen.getByLabelText('Route').textContent).toBe('/chat/s1');
});
it('rejects an unsupported avatar before making an upload request', async () => {
  mount(); const input = await screen.findByLabelText('Upload avatar');
  fireEvent.change(input, { target: { files: [new File(['invalid'], 'x.svg', { type: 'image/svg+xml' })] } });
  await screen.findByRole('alert'); expect(calls.some(call => call.path === '/api/me/avatar')).toBe(false);
});
