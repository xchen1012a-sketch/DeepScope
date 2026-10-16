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

import { usePagedList } from '@/components/usePagedList';
import { listQuery } from '@/api/listQuery';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Trash2 } from 'lucide-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Page } from '@/components/Page';
import { DataTable } from '@/components/DataTable';
import { TableToolbar } from '@/components/TableToolbar';
import { AppSelect } from '@/components/ui/select';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { ApiError, api, apiFetch, getToken } from '@/lib/apiClient';
import { getAccountIdentity, setAccountIdentity } from '@/lib/accountIdentity';
import { can } from '@/api/auth';
import { CreatedDateFilter } from './CreatedDateFilter';
import { createdDateFilters } from './createdDateRange';

export interface RoleSummary {
  roleId: string;
  roleNumber: number;
  name: string;
  code: string;
  description: string;
  sort: number;
  status: 'active' | 'disabled';
  builtin: boolean;
  createdAt: number;
  permissions: string[];
}

export interface UserSummary {
  userId: string;
  userNumber: number;
  username: string;
  nickname: string;
  remark: string;
  status: 'active' | 'disabled';
  builtin: boolean;
  createdAt: number;
  roleIds: string[];
}
type RoleOption = Pick<RoleSummary, 'roleId' | 'name'>;

const userKeys = { users: ['advisor', 'users'] as const, roles: ['advisor', 'roles'] as const };

export function listRoles(query = ''): Promise<RoleOption[]> {
  return api.get(listQuery('/api/advisor/roles', query) + (query.trim() ? '&' : '?') + 'purpose=user-assignment');
}

export default function UsersPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const usersQuery = usePagedList<UserSummary>(userKeys.users, '/api/advisor/users', { filters: { status: '', roleId: '' } });
  const rolesQuery = useQuery({ queryKey: userKeys.roles, queryFn: () => listRoles(), placeholderData: keepPreviousData });
  const [name, setName] = useState('');
  const [nickname, setNickname] = useState('');
  const [createdDate, setCreatedDate] = useState({ start: '', end: '' });
  const [status, setStatus] = useState('');
  const [roleId, setRoleId] = useState('');
  const [editing, setEditing] = useState<UserSummary | null>(null);
  const [editingMode, setEditingMode] = useState<'profile' | 'roles' | 'password'>('profile');
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<UserSummary | null>(null);
  const roles = rolesQuery.data ?? [];
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: userKeys.users });
    queryClient.invalidateQueries({ queryKey: userKeys.roles });
  };
  const setStatusOf = useMutation({
    mutationFn: (user: UserSummary) =>
      apiFetch(`/api/advisor/users/${user.userId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status: user.status === 'active' ? 'disabled' : 'active' }),
      }),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (userId: string) => apiFetch(`/api/advisor/users/${userId}`, { method: 'DELETE' }),
    onSuccess: () => { refresh(); setDeleting(null); },
  });
  return (
    <Page>
      <TableToolbar search={name} onSearchChange={setName} searchLabel={t('access.users.username')}
        placeholder={t('access.inputPlaceholder', { field: t('access.users.username') })} onSearch={() => {
          const dates = createdDateFilters(createdDate);
          if (dates) usersQuery.submit(name, { status, roleId, nickname: nickname.trim(), ...dates });
        }}
        onReset={() => { setName(''); setNickname(''); setCreatedDate({ start: '', end: '' }); setStatus(''); setRoleId(''); usersQuery.reset(); }}
        filters={<>
          <label className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto"><span className="shrink-0">{t('access.users.nickname')}</span>
            <Input className="h-9 w-40 text-sm" placeholder={t('access.inputPlaceholder', { field: t('access.users.nickname') })} value={nickname} onChange={event => setNickname(event.target.value)} />
          </label>
          <div className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto"><span className="shrink-0">{t('access.col.status')}</span>
            <AppSelect aria-label={t('access.col.status')} className="h-9 w-40" value={status} onChange={setStatus} allowClear placeholder={t('access.all')}
              options={[{ value: '', label: t('access.all') }, { value: 'active', label: t('access.active') }, { value: 'disabled', label: t('access.disabled') }]} />
          </div>
          <div className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto"><span className="shrink-0">{t('access.users.role')}</span>
            <AppSelect aria-label={t('access.users.role')} className="h-9 w-40" value={roleId} onChange={setRoleId} allowClear placeholder={t('access.all')}
              options={[{ value: '', label: t('access.all') }, ...roles.map(role => ({ value: role.roleId, label: role.name }))]} />
            {rolesQuery.isError ? <p role="alert">{t('table.loadError')} <Button type="button" variant="ghost" size="sm" onClick={() => void rolesQuery.refetch()}>{t('table.retry')}</Button></p> : null}
          </div>
          <CreatedDateFilter value={createdDate} onChange={setCreatedDate} />
        </>} />
      <DataTable<UserSummary>
        toolbarTitle={t('access.users.listTitle')} actionsAppearance="text"
        toolbarActions={can('user.create') ? <Button type="button" size="sm" onClick={() => { setEditingMode('profile'); setCreating(true); }}><Plus className="h-4 w-4" />{t('access.users.add')}</Button> : null}
        columns={[
          { id: 'id', header: t('access.users.id'), cell: user => <span className="tabular-nums">{user.userNumber}</span> },
          { id: 'name', header: t('access.users.username'), cell: user => <span className="font-medium text-slate-900">{user.username}{user.builtin ? <Badge tone="info" className="ml-2">{t('access.builtin')}</Badge> : null}</span> },
          { id: 'nickname', header: t('access.users.nickname'), cell: user => user.nickname || '—' },
          { id: 'roles', header: t('access.users.role'), cell: user => <div className="flex flex-wrap gap-1">{roles.filter(role => user.roleIds.includes(role.roleId)).map(role => <span key={role.roleId} className="rounded bg-accent px-1.5 py-0.5 text-xs text-accent-foreground">{role.name}</span>)}</div> },
          { id: 'status', header: t('access.col.status'), cell: user => <Badge tone={user.status === 'active' ? 'success' : 'default'}>{t(user.status === 'active' ? 'access.active' : 'access.disabled')}</Badge> },
          { id: 'remark', header: t('access.remark'), cell: user => user.remark || '—' },
          { id: 'created', header: t('access.createdAt'), cell: user => new Date(user.createdAt).toLocaleString() },
        ]}
        actions={user => [
          { id: 'edit', label: t('access.edit'), hidden: !can('user.edit'), onClick: () => { setEditingMode('profile'); setEditing(user); } },
          { id: 'roles', label: t('access.users.assignRoles'), overflow: true, hidden: !can('role.grant'), onClick: () => { setEditingMode('roles'); setEditing(user); } },
          { id: 'password', label: t('access.users.resetPassword'), overflow: true, hidden: !can('user.edit'), onClick: () => { setEditingMode('password'); setEditing(user); } },
          { id: 'status', label: t(user.status === 'active' ? 'access.disableAction' : 'access.enableAction'), overflow: true, hidden: !can('user.edit'), disabled: user.builtin || setStatusOf.isPending, loading: setStatusOf.isPending && setStatusOf.variables?.userId === user.userId, onClick: () => setStatusOf.mutate(user) },
          { id: 'delete', label: t('access.delete'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: user.builtin || !can('user.delete'), onClick: () => { remove.reset(); setDeleting(user); } },
        ]}
        data={usersQuery.data?.items ?? []} keyExtractor={user => user.userId} isLoading={usersQuery.isPending}
        error={usersQuery.isError ? t('table.loadError') : undefined} onRetry={() => { void usersQuery.refetch(); }}
        emptyTitle={t('access.noData')} page={usersQuery.data?.page ?? usersQuery.params.page} pageSize={usersQuery.params.size} total={usersQuery.data?.total ?? 0} onPageChange={usersQuery.setPage} onPageSizeChange={usersQuery.setPageSize}
      />
      {setStatusOf.isError ? <p className="text-sm text-red-700">{messageOf(setStatusOf.error)}</p> : null}
      <ConfirmDeleteDialog open={deleting !== null} title={t('access.users.deleteTitle')} description={t('access.deleteHint', { name: deleting?.username })} confirmText={t('access.delete')}
        isDeleting={remove.isPending} onClose={() => setDeleting(null)} onConfirm={() => { if (deleting) remove.mutate(deleting.userId); }}>
        {remove.isError ? <p role="alert" className="text-sm text-red-700">{messageOf(remove.error) || t('access.operationError')}</p> : null}
      </ConfirmDeleteDialog>
      {creating || editing ? <UserDialog
        key={(editing?.userId ?? 'new-user') + editingMode}
        open={creating || editing !== null}
        user={editing}
        mode={editingMode}
        roles={roles}
        onClose={() => { setCreating(false); setEditing(null); }}
        onSaved={refresh}
      /> : null}
    </Page>
  );
}

function UserDialog({
  open,
  user,
  mode = 'profile',
  roles,
  onClose,
  onSaved,
}: {
  open: boolean;
  user: UserSummary | null;
  roles: RoleOption[];
  mode?: 'profile' | 'roles' | 'password';
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [username, setUsername] = useState(user?.username ?? '');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState(user?.nickname ?? '');
  const [remark, setRemark] = useState(user?.remark ?? '');
  const [roleIds, setRoleIds] = useState<string[]>(user?.roleIds ?? []);
  const [roleSearch, setRoleSearch] = useState('');
  const roleOptions = useQuery({
    queryKey: ['advisor', 'role-assignment-search', roleSearch],
    queryFn: () => listRoles(roleSearch),
    enabled: (mode === 'roles' || !user) && can('role.grant') && Boolean(roleSearch),
    retry: false,
  });
  const visibleRoles = roleSearch ? roleOptions.data ?? [] : roles;
  const save = useMutation({
    mutationFn: () => {
      if (user && mode === 'roles') return apiFetch(`/api/advisor/users/${user.userId}/roles`, { method: 'PUT', body: JSON.stringify({ roleIds }) });
      if (user && mode === 'password') return apiFetch(`/api/advisor/users/${user.userId}/password`, { method: 'PATCH', body: JSON.stringify({ password }) });
      const body = { username, password, nickname, remark, roleIds };
      if (user) {
        return apiFetch(`/api/advisor/users/${user.userId}`, { method: 'PUT', body: JSON.stringify({ username: username.trim(), nickname, remark }) });
      }
      return api.post('/api/advisor/users', body);
    },
    onSuccess: () => {
      const token = getToken();
      const current = getAccountIdentity(token);
      if (token && user && mode === 'profile' && current?.userId === user.userId) {
        setAccountIdentity(token, { ...current, username: username.trim(), nickname: nickname.trim() });
      }
      onSaved();
      onClose();
    },
  });
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>{t(mode === 'roles' ? 'access.users.assignRoles' : mode === 'password' ? 'access.users.resetPassword' : user ? 'access.users.edit' : 'access.users.add')}</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-3">
          {mode === 'profile' ? <Field label={t('access.users.username')}><Input value={username} onChange={event => setUsername(event.target.value)} /></Field> : null}
          {!user ? (
            <>
              <Field label={t('access.users.password')}><Input type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></Field>
            </>
          ) : mode === 'password' ? (
            <Field label={t('access.users.password')}><Input type="password" value={password} placeholder={t('access.users.passwordHint')} onChange={(event) => setPassword(event.target.value)} /></Field>
          ) : null}
          {mode === 'profile' ? <Field label={t('access.users.nickname')}><Input value={nickname} onChange={(event) => setNickname(event.target.value)} /></Field> : null}
          {mode === 'roles' || !user ? <Field label={t('access.users.role')}>
            <AppSelect multiple value={roleIds} onChange={setRoleIds} allowClear className="h-9 w-full min-w-0"
              aria-label={t('access.users.role')} placeholder={t('access.users.selectRoles')}
              disabled={!can('role.grant') || save.isPending}
              labelOptions={roles.map(role => ({ value: role.roleId, label: role.name }))}
              search={{ onChange: setRoleSearch, placeholder: t('access.roles.searchPlaceholder'),
                loading: Boolean(roleSearch) && roleOptions.isFetching, error: Boolean(roleSearch) && roleOptions.isError,
                onRetry: () => { void roleOptions.refetch(); } }}
              options={visibleRoles.map(role => ({ value: role.roleId, label: role.name }))} />
          </Field> : null}
          {mode === 'profile' ? <Field label={t('access.remark')}><Input value={remark} onChange={(event) => setRemark(event.target.value)} /></Field> : null}
          {save.isError ? <p className="text-sm text-red-700">{messageOf(save.error)}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('chat.cancel')}</Button>
          <Button disabled={(mode === 'profile' && !username.trim()) || (!user && !password.trim()) || (mode === 'password' && !password.trim()) || save.isPending} onClick={() => save.mutate()}>{t('access.confirm')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid grid-cols-[86px_1fr] items-start gap-3 text-[12.5px] text-slate-700">
      <span className="pt-2.5 text-right font-medium">{label}</span>
      <span className="text-slate-900">{children}</span>
    </label>
  );
}

function messageOf(error: unknown) {
  if (error instanceof ApiError && error.body) {
    try {
      const parsed = JSON.parse(error.body) as { message?: string };
      if (parsed.message) return parsed.message;
    } catch {
      return error.body;
    }
  }
  return '';
}
