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
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { api, apiFetch } from '@/lib/apiClient';
import { can } from '@/api/auth';
import { clearAccountIdentity, setAccountIdentity, useAccountIdentity } from '@/lib/accountIdentity';
import { GrantDialog } from './GrantDialog';

vi.mock('@/lib/apiClient', async original => ({
  ...await original<typeof import('@/lib/apiClient')>(),
  api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn(), getToken: () => 'test-token',
}));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(() => { cleanup(); clearAccountIdentity(); vi.resetAllMocks(); });

function AccessIndicator() {
  useAccountIdentity();
  return <p aria-label="Model access">{can('settings.manage') ? 'allowed' : 'denied'}</p>;
}

it('refreshes the current identity after saving the admin menu grants', async () => {
  setAccountIdentity('test-token', { userId: 'admin', username: 'admin', roles: ['admin'], permissions: ['settings.manage', 'rbac.manage'] });
  vi.mocked(api.get).mockImplementation(async path => {
    if (path === '/api/advisor/permissions/tree') return [
      { id: 'model', name: 'Models', children: [{ id: 'settings.manage', name: 'Manage models', children: [] }] },
      { id: 'role', name: 'Roles', children: [{ id: 'rbac.manage', name: 'Manage access', children: [] }] },
    ];
    if (path.endsWith('/permissions')) return { permissions: ['settings.manage', 'rbac.manage'] };
    if (path === '/api/auth/me') return { userId: 'admin', username: 'admin', roles: ['admin'], permissions: ['rbac.manage'], isAdmin: true };
    throw new Error('Unexpected request');
  });
  vi.mocked(apiFetch).mockResolvedValue(undefined);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}>
    <AccessIndicator />
    <GrantDialog role={{ roleId: 'role-admin', roleNumber: 1, name: 'Admin', code: 'admin', description: '', sort: 1, status: 'active', builtin: true, createdAt: 1, permissions: [] }}
      onClose={() => {}} onSaved={() => {}} />
  </QueryClientProvider></I18nextProvider>);
  const user = userEvent.setup();
  expect(screen.getByLabelText('Model access').textContent).toBe('allowed');
  await user.click(await screen.findByRole('button', { name: 'Expand Models' }));
  await user.click(await screen.findByRole('checkbox', { name: 'Manage models' }));
  await user.click(screen.getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(screen.getByLabelText('Model access').textContent).toBe('denied'));
});
