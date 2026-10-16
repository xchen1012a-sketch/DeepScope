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
import { cleanup, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { api, apiFetch, ApiError, getToken } from '@/lib/apiClient';
import UsersPage from './UsersPage';
import RolesPage from './RolesPage';
import { clearAccountIdentity, getAccountIdentity, setAccountIdentity } from '@/lib/accountIdentity';
vi.mock('@/api/auth', () => ({ can: () => true, me: vi.fn(async () => undefined) }));

vi.mock('@/lib/apiClient', async original => ({
  ...await original<typeof import('@/lib/apiClient')>(),
  api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn(), getToken: vi.fn(),
}));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
const role = { roleId: 'support', roleNumber: 2, name: 'Support', code: 'support', description: 'Assist customers', sort: 1, status: 'active', builtin: false, createdAt: 1720000000000, permissions: [] };
beforeEach(() => {
  vi.mocked(getToken).mockReturnValue(null);
  vi.mocked(api.get).mockImplementation(async path => {
    if (path.endsWith('/permissions/tree')) return [{ id: 'agent', name: 'Agents', children: [
      { id: 'agent.view', name: 'View', children: [] }, { id: 'agent.edit', name: 'Edit agent', children: [] },
    ] }];
    if (path.endsWith('/permissions')) return { permissions: ['agent.view'] };
    if (path.includes('purpose=user-assignment')) return [role];
    const params = new URL(path, 'http://localhost').searchParams;
    const items = path.includes('/users') ? [{ userId: 'alice', userNumber: 7, username: 'alice', nickname: 'Alice', remark: 'Operations', roleIds: ['support'], status: 'active', builtin: false, createdAt: 1720000000000 }] : [role];
    return { items, page: Number(params.get('page')), size: 10, total: 21, totalPages: 3 };
  });
  vi.mocked(apiFetch).mockResolvedValue(undefined);
});

it('displays the persistent user number and edits the account, nickname and remark together', async () => {
  vi.mocked(getToken).mockReturnValue('test-token');
  setAccountIdentity('test-token', { userId: 'alice', username: 'alice', nickname: 'Alice', roles: ['user'] });
  const user = mount(UsersPage);
  const row = await screen.findByRole('row', { name: /alice/ });
  expect(within(row).getAllByRole('cell')[0].textContent).toBe('7');
  await user.click(within(row).getByRole('button', { name: 'Edit' }));
  const dialog = screen.getByRole('dialog');
  const account = within(dialog).getByLabelText('Account') as HTMLInputElement;
  expect(account.value).toBe('alice');
  await user.clear(account);
  await user.type(account, 'renamed');
  await user.clear(within(dialog).getByLabelText('Nickname'));
  await user.type(within(dialog).getByLabelText('Nickname'), 'New nickname');
  await user.clear(within(dialog).getByLabelText('Note'));
  await user.type(within(dialog).getByLabelText('Note'), 'New remark');
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/users/alice', {
    method: 'PUT', body: '{"username":"renamed","nickname":"New nickname","remark":"New remark"}',
  }));
  expect(getAccountIdentity('test-token')?.nickname).toBe('New nickname');
  expect(getAccountIdentity('test-token')?.username).toBe('renamed');
});

it('assigns multiple roles through the shared dropdown and persists the selection', async () => {
  const original = vi.mocked(api.get).getMockImplementation()!;
  vi.mocked(api.get).mockImplementation(async path => path.includes('purpose=user-assignment')
    ? [role, { ...role, roleId: 'reviewer', name: 'Reviewer' }] : original(path));
  const user = mount(UsersPage);
  const row = await screen.findByRole('row', { name: /alice/ });
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Assign roles' }));
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).queryByRole('button', { name: 'Support' })).toBeNull();
  await user.click(within(dialog).getByRole('button', { name: 'Roles' }));
  expect(screen.getByRole('menuitemcheckbox', { name: 'Support' }).getAttribute('aria-checked')).toBe('true');
  await user.click(screen.getByRole('menuitemcheckbox', { name: 'Reviewer' }));
  expect(screen.getByRole('menuitemcheckbox', { name: 'Reviewer' }).getAttribute('aria-checked')).toBe('true');
  await user.keyboard('{Escape}');
  expect(within(dialog).getByRole('button', { name: 'Roles' }).textContent).toContain('Support, Reviewer');
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/users/alice/roles', {
    method: 'PUT', body: '{"roleIds":["support","reviewer"]}',
  }));
});

it('clears assigned roles in the dropdown without saving until confirmation', async () => {
  const user = mount(UsersPage);
  const row = await screen.findByRole('row', { name: /alice/ });
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Assign roles' }));
  const dialog = screen.getByRole('dialog');
  await user.click(within(dialog).getByRole('button', { name: 'Clear' }));
  expect(within(dialog).getByRole('button', { name: 'Roles' }).textContent).toContain('Select roles');
  expect(apiFetch).not.toHaveBeenCalled();
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(apiFetch).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('searches role names on the server while preserving selections across searches', async () => {
  const original = vi.mocked(api.get).getMockImplementation()!;
  const reviewer = { ...role, roleId: 'reviewer', name: 'Reviewer' };
  vi.mocked(api.get).mockImplementation(async path => {
    if (!path.includes('purpose=user-assignment')) return original(path);
    const query = new URL(path, 'http://localhost').searchParams.get('q');
    return query === 'Reviewer' ? [reviewer] : query === 'missing' ? [] : [role];
  });
  const user = mount(UsersPage);
  const row = await screen.findByRole('row', { name: /alice/ });
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Assign roles' }));
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).queryByRole('searchbox', { name: 'Search roles' })).toBeNull();
  await user.click(within(dialog).getByRole('button', { name: 'Roles' }));
  const search = screen.getByRole('searchbox', { name: 'Search roles' });
  expect(screen.getByRole('menu').contains(search)).toBe(true);
  await user.type(search, 'Reviewer');
  await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/advisor/roles?q=Reviewer&purpose=user-assignment'));
  await waitFor(() => expect(screen.queryByRole('menuitemcheckbox', { name: 'Support' })).toBeNull());
  await user.click(screen.getByRole('menuitemcheckbox', { name: 'Reviewer' }));
  await user.click(search);
  await user.clear(search);
  await user.type(search, 'missing');
  await screen.findByText('No matching records');
  expect(within(dialog).getByRole('button', { name: 'Roles' }).textContent).toContain('Support, Reviewer');
  await user.keyboard('{Escape}');
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/users/alice/roles', {
    method: 'PUT', body: '{"roleIds":["support","reviewer"]}',
  }));
});

it('shows role search failures with retry instead of an empty result', async () => {
  const original = vi.mocked(api.get).getMockImplementation()!;
  let searchFails = true;
  vi.mocked(api.get).mockImplementation(async path => {
    if (path.includes('q=Support') && path.includes('purpose=user-assignment')) {
      if (searchFails) throw new Error('Search unavailable');
      return [role];
    }
    return original(path);
  });
  const user = mount(UsersPage);
  const row = await screen.findByRole('row', { name: /alice/ });
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Assign roles' }));
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Roles' }));
  await user.type(screen.getByRole('searchbox', { name: 'Search roles' }), 'Support');
  await screen.findByRole('alert');
  expect(screen.queryByText('No matching records')).toBeNull();
  searchFails = false;
  await user.click(screen.getByRole('button', { name: 'Retry' }));
  expect((await screen.findByRole('menuitemcheckbox', { name: 'Support' })).getAttribute('aria-checked')).toBe('true');
});

it('orders role actions as Edit, Delete, More and saves name, identifier, order and status', async () => {
  const user = mount(RolesPage);
  const row = await screen.findByRole('row', { name: /Support/ });
  expect(within(row).getAllByRole('cell')[0].textContent).toBe('2');
  expect(within(row).getAllByRole('button').map(button => button.textContent)).toEqual(['Edit', 'Delete', 'More']);
  await user.click(within(row).getByRole('button', { name: 'Edit' }));
  const dialog = screen.getByRole('dialog');
  await user.clear(within(dialog).getByLabelText('Name'));
  await user.type(within(dialog).getByLabelText('Name'), 'Operations');
  await user.clear(within(dialog).getByLabelText('Role identifier'));
  await user.type(within(dialog).getByLabelText('Role identifier'), 'ops');
  await user.clear(within(dialog).getByLabelText('Order'));
  await user.type(within(dialog).getByLabelText('Order'), '0');
  await user.click(within(dialog).getByRole('button', { name: 'Status' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Disabled' }));
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/roles/support', {
    method: 'PUT', body: '{"name":"Operations","code":"ops","sort":0,"status":"disabled"}',
  }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  expect(screen.getAllByRole('menuitem').map(item => item.textContent)).toEqual(['Menu permissions']);
});
afterEach(() => { cleanup(); clearAccountIdentity(); vi.resetAllMocks(); });
function mount(Component: typeof UsersPage) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><Component /></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}

for (const [resource, Component, add] of [['users', UsersPage, 'New user'], ['roles', RolesPage, 'New role']] as const) {
  it(resource + ': submits date drafts and nickname only on Search and clears every filter', async () => {
    const user = mount(Component);
    await screen.findByRole('row', { name: resource === 'users' ? /alice/ : /Support/ });
    const before = vi.mocked(api.get).mock.calls.length;
    if (resource === 'users') await user.type(screen.getByLabelText('Nickname'), 'Support');
    await user.click(screen.getByLabelText('Start time'));
    fireEvent.change(screen.getByLabelText('Start time'), { target: { value: '2026-10-15 00:00:00' } });
    fireEvent.change(screen.getByLabelText('End time'), { target: { value: '2026-10-16 23:59:59' } });
    await user.click(within(screen.getByRole('dialog', { name: 'Time range' })).getByRole('button', { name: 'Confirm' }));
    expect(api.get).toHaveBeenCalledTimes(before);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => {
      const query = new URL(String(vi.mocked(api.get).mock.lastCall?.[0]), 'http://localhost').searchParams;
      expect(new Date(Number(query.get('createdFrom'))).getDate()).toBe(15);
      expect(new Date(Number(query.get('createdFrom'))).getHours()).toBe(0);
      expect(new Date(Number(query.get('createdBefore'))).getDate()).toBe(17);
      expect(new Date(Number(query.get('createdBefore'))).getHours()).toBe(0);
      if (resource === 'users') expect(query.get('nickname')).toBe('Support');
    });
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/' + resource + '?page=1&size=10'));
    expect((screen.getByLabelText('Start time') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('End time') as HTMLInputElement).value).toBe('');
    if (resource === 'users') expect((screen.getByLabelText('Nickname') as HTMLInputElement).value).toBe('');
  });
  it(resource + ': submits draft filters together and clears to the first server page', async () => {
    const user = mount(Component);
    await screen.findByRole('row', { name: resource === 'users' ? /alice/ : /support/ });
    const search = screen.getByRole('search');
    expect(screen.getByTestId('data-table').contains(screen.getByRole('button', { name: add }))).toBe(true);
    await user.type(screen.getByRole('searchbox'), 'needle');
    const count = vi.mocked(api.get).mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Status' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Disabled' }));
    expect(api.get).toHaveBeenCalledTimes(count);
    await user.click(within(search).getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(new RegExp('/' + resource + '\\?page=1&size=10&q=needle&status=disabled'))));
    await user.click(within(search).getAllByRole('button', { name: 'Clear' }).find(button => button.tagName === 'BUTTON')!);
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/' + resource + '?page=1&size=10'));
    expect(screen.queryByText('Connect model')).toBeNull();
    expect(screen.getByRole('columnheader', { name: 'Created' })).toBeTruthy();
    expect(screen.queryByRole('columnheader', { name: 'Users' })).toBeNull();
  });
  it(resource + ': requires confirmation before deleting and keeps failures visible', async () => {
    const user = mount(Component);
    await screen.findByRole('button', { name: 'Delete' });
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(apiFetch).not.toHaveBeenCalled();
    vi.mocked(apiFetch).mockRejectedValue(new ApiError(400, '{"message":"Protected record"}'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
}

it('loads current role permissions from the server, supports half-selection, and retains failed edits', async () => {
  const user = mount(RolesPage);
  await user.click(await screen.findByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Menu permissions' }));
  const dialog = screen.getByRole('dialog');
  const group = await within(dialog).findByRole('checkbox', { name: 'Agents' });
  expect(group.getAttribute('aria-checked')).toBe('mixed');
  await user.click(group);
  await user.click(within(dialog).getByRole('button', { name: 'Expand Agents' }));
  expect((within(dialog).getByRole('checkbox', { name: 'Edit agent' }) as HTMLInputElement).checked).toBe(true);
  vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(400, '{"message":"Try again"}'));
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await within(dialog).findByRole('alert');
  expect(apiFetch).toHaveBeenLastCalledWith('/api/advisor/roles/support/permissions', expect.objectContaining({ body: '{"permissions":["agent.view","agent.edit"]}' }));
  expect((within(dialog).getByRole('checkbox', { name: 'Edit agent' }) as HTMLInputElement).checked).toBe(true);
});

it('shows a built-in role permission tree as read-only', async () => {
  vi.mocked(api.get).mockImplementation(async path => {
    if (path.endsWith('/permissions/tree')) return [{ id: 'agent', name: 'Agents', children: [{ id: 'agent.view', name: 'View', children: [] }] }];
    if (path.endsWith('/permissions')) return { permissions: ['agent.view'] };
    return { items: [{ ...role, builtin: true }], page: 1, size: 10, total: 1, totalPages: 1 };
  });
  const user = mount(RolesPage);
  const row = await screen.findByRole('row', { name: /Support/ });
  expect((within(row).getByRole('button', { name: 'Edit' }) as HTMLButtonElement).disabled).toBe(true);
  expect((within(row).getByRole('button', { name: 'Delete' }) as HTMLButtonElement).disabled).toBe(true);
  await user.click(await screen.findByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Menu permissions' }));
  const dialog = screen.getByRole('dialog');
  await user.click(await within(dialog).findByRole('button', { name: 'Expand Agents' }));
  expect((await within(dialog).findByRole('checkbox', { name: 'View' }) as HTMLInputElement).disabled).toBe(true);
  expect(within(dialog).queryByRole('button', { name: 'Confirm' })).toBeNull();
});

it('allows editing the admin role and menu permissions while keeping Delete disabled', async () => {
  vi.mocked(api.get).mockImplementation(async path => {
    if (path.endsWith('/permissions/tree')) return [{ id: 'agent', name: 'Agents', children: [{ id: 'agent.view', name: 'View', children: [] }] }];
    if (path.endsWith('/permissions')) return { permissions: ['agent.view', 'rbac.manage'] };
    return { items: [{ ...role, roleId: 'role-admin', builtin: true }], page: 1, size: 10, total: 1, totalPages: 1 };
  });
  const user = mount(RolesPage);
  const row = await screen.findByRole('row', { name: /Support/ });
  expect((within(row).getByRole('button', { name: 'Edit' }) as HTMLButtonElement).disabled).toBe(false);
  expect((within(row).getByRole('button', { name: 'Delete' }) as HTMLButtonElement).disabled).toBe(true);
  await user.click(within(row).getByRole('button', { name: 'Edit' }));
  const editor = screen.getByRole('dialog');
  expect((within(editor).getByLabelText('Role identifier') as HTMLInputElement).disabled).toBe(false);
  expect((within(editor).getByRole('button', { name: 'Status' }) as HTMLButtonElement).disabled).toBe(true);
  await user.click(within(editor).getByRole('button', { name: 'Cancel' }));
  await user.click(within(row).getByRole('button', { name: 'More actions' }));
  const permission = await screen.findByRole('menuitem', { name: 'Menu permissions' });
  expect(permission.querySelector('svg')).toBeNull();
  await user.click(permission);
  const dialog = screen.getByRole('dialog');
  await user.click(await within(dialog).findByRole('button', { name: 'Expand Agents' }));
  const view = await within(dialog).findByRole('checkbox', { name: 'View' });
  expect((view as HTMLInputElement).disabled).toBe(false);
  await user.click(view);
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/roles/role-admin/permissions', expect.objectContaining({ body: '{"permissions":["rbac.manage"]}' })));
});

it('retains a failed account edit so the user can correct a duplicate account', async () => {
  const user = mount(UsersPage);
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  const dialog = screen.getByRole('dialog');
  await user.clear(within(dialog).getByLabelText('Account'));
  expect((within(dialog).getByRole('button', { name: 'Confirm' }) as HTMLButtonElement).disabled).toBe(true);
  await user.type(within(dialog).getByLabelText('Account'), 'existing');
  vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(400, '{"message":"Account already exists"}'));
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await within(dialog).findByText('Account already exists');
  expect((within(dialog).getByLabelText('Account') as HTMLInputElement).value).toBe('existing');
  expect((within(dialog).getByLabelText('Nickname') as HTMLInputElement).value).toBe('Alice');
});

it('separates resetting a password from editing user details', async () => {
  const user = mount(UsersPage);
  await user.click(await screen.findByRole('button', { name: 'More actions' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Reset password' }));
  const dialog = screen.getByRole('dialog');
  await user.type(within(dialog).getByLabelText('Password'), 'fake-new-password');
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/users/alice/password', { method: 'PATCH', body: '{"password":"fake-new-password"}' }));
});
