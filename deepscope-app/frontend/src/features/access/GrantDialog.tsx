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
import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PermissionTree, type PermissionNode } from '@/components/PermissionTree';
import { Button } from '@/components/ui/button';
import { Dialog, DialogBody, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api, apiFetch } from '@/lib/apiClient';
import type { RoleSummary } from './UsersPage';
import { modelError } from '@/api/model';
import { me } from '@/api/auth';

export function GrantDialog({ role, onClose, onSaved }: { role: RoleSummary; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const data = useQuery({
    queryKey: ['advisor', 'role-permission-editor', role.roleId], staleTime: 0, gcTime: 0, retry: false,
    queryFn: async () => {
      const [nodes, grant] = await Promise.all([
        api.get<PermissionNode[]>('/api/advisor/permissions/tree'),
        api.get<{ permissions: string[] }>(`/api/advisor/roles/${encodeURIComponent(role.roleId)}/permissions`),
      ]);
      return { nodes, permissions: grant.permissions };
    },
  });
  return <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
    <DialogContent size="md">
      <DialogHeader><DialogTitle>{t('access.roles.grant')}</DialogTitle></DialogHeader>
      {data.isPending ? <DialogBody><p role="status">{t('modelSettings.manage.loadingOptions')}</p></DialogBody>
        : data.isError ? <DialogBody><p role="alert">{t('table.loadError')}</p><Button variant="outline" onClick={() => void data.refetch()}>{t('table.retry')}</Button></DialogBody>
          : <GrantEditor role={role} nodes={data.data.nodes} initial={data.data.permissions} onClose={onClose} onSaved={onSaved} />}
    </DialogContent>
  </Dialog>;
}

function GrantEditor({ role, nodes, initial, onClose, onSaved }: {
  role: RoleSummary; nodes: PermissionNode[]; initial: string[]; onClose: () => void; onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [permissions, setPermissions] = useState(initial);
  const isAdminRole = role.roleId === 'role-admin';
  const save = useMutation({
    mutationFn: () => apiFetch(`/api/advisor/roles/${encodeURIComponent(role.roleId)}/permissions`, { method: 'PUT', body: JSON.stringify({ permissions }) }),
    onSuccess: async () => { await me(); onSaved(); onClose(); },
  });
  return <>
    <DialogBody className="space-y-4">
      <PermissionTree nodes={nodes} value={permissions} onChange={next => setPermissions(isAdminRole ? [...new Set([...next, 'rbac.manage'])] : next)}
        disabled={save.isPending} disabledIds={isAdminRole ? ['rbac.manage'] : []} />
      {save.isError ? <p role="alert" className="text-sm text-red-700">{modelError(save.error, t('access.operationError'))}</p> : null}
    </DialogBody>
    <DialogFooter>
      <Button variant="outline" disabled={save.isPending} onClick={onClose}>{t('chat.cancel')}</Button>
      <Button className="bg-violet-100 text-violet-800 hover:bg-violet-200" disabled={save.isPending} onClick={() => save.mutate()}>{t('access.confirm')}</Button>
    </DialogFooter>
  </>;
}
