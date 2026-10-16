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
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Trash2 } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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
import { GrantDialog } from './GrantDialog';
import { ApiError, api, apiFetch } from '@/lib/apiClient';
import { type RoleSummary } from './UsersPage';
import { can } from '@/api/auth';
import { CreatedDateFilter } from './CreatedDateFilter';
import { createdDateFilters } from './createdDateRange';

export default function RolesPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const rolesQuery = usePagedList<RoleSummary>(['advisor', 'roles'], '/api/advisor/roles', { filters: { status: '', code: '' } });
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [status, setStatus] = useState('');
  const [createdDate, setCreatedDate] = useState({ start: '', end: '' });
  const [editing, setEditing] = useState<RoleSummary | null>(null);
  const [creating, setCreating] = useState(false);
  const [granting, setGranting] = useState<RoleSummary | null>(null);
  const [deleting, setDeleting] = useState<RoleSummary | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['advisor', 'roles'] });
  const remove = useMutation({
    mutationFn: (roleId: string) => apiFetch(`/api/advisor/roles/${roleId}`, { method: 'DELETE' }),
    onSuccess: () => { void refresh(); setDeleting(null); },
  });
  return (
    <Page>
      <TableToolbar search={name} onSearchChange={setName} searchLabel={t('access.roles.name')}
        placeholder={t('access.inputPlaceholder', { field: t('access.roles.name') })} onSearch={() => {
          const dates = createdDateFilters(createdDate);
          if (dates) rolesQuery.submit(name, { status, code, ...dates });
        }}
        onReset={() => { setName(''); setCode(''); setStatus(''); setCreatedDate({ start: '', end: '' }); rolesQuery.reset(); }}
        filters={<>
          <label className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto"><span className="shrink-0">{t('access.roles.code')}</span>
            <Input className="h-9 w-40 font-mono text-sm" placeholder={t('access.inputPlaceholder', { field: t('access.roles.code') })} value={code} onChange={event => setCode(event.target.value)} />
          </label>
          <div className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto"><span className="shrink-0">{t('access.col.status')}</span>
            <AppSelect aria-label={t('access.col.status')} className="h-9 w-40" value={status} onChange={setStatus} allowClear placeholder={t('access.all')}
              options={[{ value: '', label: t('access.all') }, { value: 'active', label: t('access.active') }, { value: 'disabled', label: t('access.disabled') }]} />
          </div>
          <CreatedDateFilter value={createdDate} onChange={setCreatedDate} />
        </>} />
      <DataTable<RoleSummary>
        toolbarTitle={t('access.roles.listTitle')} actionsAppearance="text"
        toolbarActions={can('role.create') ? <Button type="button" size="sm" onClick={() => setCreating(true)}><Plus className="h-4 w-4" />{t('access.roles.add')}</Button> : null}
        columns={[
          { id: 'id', header: t('access.roles.id'), cell: role => <span className="tabular-nums">{role.roleNumber}</span> },
          { id: 'name', header: t('access.roles.name'), cell: role => <span className="font-medium text-slate-900">{role.name}{role.builtin ? <Badge tone="info" className="ml-2">{t('access.builtin')}</Badge> : null}</span> },
          { id: 'code', header: t('access.roles.code'), cell: role => <span className="font-mono text-xs text-slate-500">{role.code}</span> },
          { id: 'type', header: t('access.roles.type'), cell: role => <Badge tone={role.builtin ? 'info' : 'default'}>{t(role.builtin ? 'access.builtin' : 'access.custom')}</Badge> },
          { id: 'sort', header: t('access.roles.sort'), cell: role => role.sort },
          { id: 'description', header: t('access.roles.description'), cell: role => role.description || '—' },
          { id: 'status', header: t('access.col.status'), cell: role => <Badge tone={role.status === 'active' ? 'success' : 'default'}>{t(role.status === 'active' ? 'access.active' : 'access.disabled')}</Badge> },
          { id: 'created', header: t('access.createdAt'), cell: role => new Date(role.createdAt).toLocaleString() },
        ]}
        actions={role => [
          { id: 'edit', label: t('access.edit'), hidden: !can('role.edit'), disabled: role.builtin && role.roleId !== 'role-admin', onClick: () => setEditing(role) },
          { id: 'delete', label: t('access.delete'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: !can('role.delete'), disabled: role.builtin, onClick: () => { remove.reset(); setDeleting(role); } },
          { id: 'grant', label: t('access.roles.grant'), overflow: true, hidden: !can('role.grant'), onClick: () => setGranting(role) },
        ]}
        data={rolesQuery.data?.items ?? []} keyExtractor={role => role.roleId} isLoading={rolesQuery.isPending}
        error={rolesQuery.isError ? t('table.loadError') : undefined} onRetry={() => { void rolesQuery.refetch(); }}
        emptyTitle={t('access.noData')} page={rolesQuery.data?.page ?? rolesQuery.params.page} pageSize={rolesQuery.params.size} total={rolesQuery.data?.total ?? 0} onPageChange={rolesQuery.setPage} onPageSizeChange={rolesQuery.setPageSize}
      />
      {creating || editing ? <RoleDialog key={editing?.roleId ?? 'new-role'} open role={editing} onClose={() => { setCreating(false); setEditing(null); }} onSaved={refresh} /> : null}
      {granting ? <GrantDialog key={granting.roleId} role={granting} onClose={() => setGranting(null)} onSaved={refresh} /> : null}
      <ConfirmDeleteDialog open={deleting !== null} title={t('access.roles.deleteTitle')} description={t('access.deleteHint', { name: deleting?.name })} confirmText={t('access.delete')}
        isDeleting={remove.isPending} onClose={() => setDeleting(null)} onConfirm={() => { if (deleting) remove.mutate(deleting.roleId); }}>
        {remove.isError ? <p role="alert" className="text-sm text-red-700">{messageOf(remove.error) || t('access.operationError')}</p> : null}
      </ConfirmDeleteDialog>
    </Page>
  );
}

function RoleDialog({ open, role, onClose, onSaved }: { open: boolean; role: RoleSummary | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState(role?.name ?? '');
  const [code, setCode] = useState(role?.code ?? '');
  const [sort, setSort] = useState(String(role?.sort ?? 10));
  const [status, setStatus] = useState(role?.status ?? 'active');
  const validSort = sort.trim() !== '' && Number.isInteger(Number(sort)) && Number(sort) >= 0 && Number(sort) <= 2147483647;
  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), code: code.trim(), sort: Number(sort), status };
      if (role) return apiFetch(`/api/advisor/roles/${role.roleId}`, { method: 'PUT', body: JSON.stringify(body) });
      return api.post('/api/advisor/roles', body);
    },
    onSuccess: () => {
      onSaved();
      onClose();
    },
  });
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md">
        <DialogHeader><DialogTitle>{role ? t('access.roles.edit') : t('access.roles.add')}</DialogTitle></DialogHeader>
        <DialogBody className="space-y-3">
          <Field label={t('access.roles.name')}><Input value={name} onChange={(event) => setName(event.target.value)} /></Field>
          <Field label={t('access.roles.code')}><Input className="font-mono" value={code} onChange={(event) => setCode(event.target.value)} disabled={role?.builtin && role.roleId !== 'role-admin'} /></Field>
          <Field label={t('access.roles.sort')}><Input type="number" min={0} step={1} value={sort} onChange={(event) => setSort(event.target.value)} /></Field>
          <Field label={t('access.col.status')}><AppSelect aria-label={t('access.col.status')} className="w-full" value={status} disabled={role?.builtin} onChange={value => setStatus(value as 'active' | 'disabled')}
            options={[{ value: 'active', label: t('access.active') }, { value: 'disabled', label: t('access.disabled') }]} /></Field>
          {save.isError ? <p role="alert" className="text-sm text-red-700">{messageOf(save.error) || t('access.operationError')}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('chat.cancel')}</Button>
          <Button disabled={!name.trim() || !code.trim() || !validSort || save.isPending} onClick={() => save.mutate()}>{t('access.confirm')}</Button>
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
