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
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { advisorKeys, deleteSession, listSessions, type SessionSummary } from '@/api/advisor';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { DataTable } from '@/components/DataTable';
import { Button } from '@/components/ui/button';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { AccountLoading, RequestError } from './AccountShared';
import { deleteAccountSessions } from '@/api/account';

export default function SessionsSection() {
  const { t, i18n } = useTranslation();
  const identity = useAccountIdentity();
  const client = useQueryClient();
  const [all, setAll] = useState(false);
  const [target, setTarget] = useState<SessionSummary>();
  const [bulkTarget, setBulkTarget] = useState<string[]>();
  const key = ['account', identity?.userId, 'sessions'];
  const query = useQuery({ queryKey: key, queryFn: listSessions, retry: false });
  const remove = useMutation({ mutationFn: deleteSession, onSuccess: async () => {
    setTarget(undefined);
    await Promise.all([client.invalidateQueries({ queryKey: key }), client.invalidateQueries({ queryKey: advisorKeys.sessions })]);
  } });
  const removeAll = useMutation({ mutationFn: deleteAccountSessions,
    onSuccess: () => setBulkTarget(undefined),
    onSettled: async () => {
      await Promise.all([client.invalidateQueries({ queryKey: key }), client.invalidateQueries({ queryKey: advisorKeys.sessions })]);
      const remaining = client.getQueryData<SessionSummary[]>(key);
      if (remaining) setBulkTarget(current => {
        const ids = current?.filter(id => remaining.some(session => session.sessionId === id));
        return ids?.length ? ids : undefined;
      });
    },
  });
  if (query.isPending) return <AccountLoading />;
  if (query.isError) return <RequestError error={query.error} retry={() => void query.refetch()} />;
  const sessions = [...query.data].sort((a, b) => b.updatedAt - a.updatedAt);
  const busy = remove.isPending || removeAll.isPending;
  return <div className="space-y-4">
    {remove.isError ? <RequestError error={remove.error} /> : null}
    {removeAll.isError && !bulkTarget ? <RequestError error={removeAll.error} /> : null}
    <DataTable data={all ? sessions : sessions.slice(0, 10)} keyExtractor={item => item.sessionId} emptyTitle={t('account.noSessions')}
      toolbarActions={<Button variant="outline" size="sm" className="text-destructive hover:text-destructive" disabled={busy || sessions.length === 0}
        onClick={() => { removeAll.reset(); setBulkTarget(sessions.map(session => session.sessionId)); }}>{t('account.deleteAll')}</Button>}
      columns={[{ header: t('account.sessionTitle'), cell: item => <Link className="text-primary underline-offset-4 hover:underline" aria-label={t('account.openSession', { name: item.title || t('account.untitled') })} to={'/chat/' + encodeURIComponent(item.sessionId)}>{item.title || t('account.untitled')}</Link> },
        { header: t('account.updatedAt'), cell: item => new Date(item.updatedAt).toLocaleString(i18n.language) }]}
      actions={item => [{ id: 'delete', label: t('account.remove'), tone: 'danger', disabled: busy || item.status === 'running', onClick: () => { remove.reset(); setTarget(item); } }]} />
    {sessions.length > 10 && !all ? <Button variant="ghost" onClick={() => setAll(true)}>{t('account.viewAll')}</Button> : null}
    <ConfirmDeleteDialog open={!!target} title={t('account.deleteSession')} targetName={target?.title || t('account.untitled')} confirmText={t('account.remove')} isDeleting={remove.isPending}
      onConfirm={() => { if (target) remove.mutate(target.sessionId); }} onClose={() => setTarget(undefined)} />
    <ConfirmDeleteDialog open={!!bulkTarget} title={t('account.deleteAll')}
      description={t('account.deleteAllConfirm', { count: bulkTarget?.length ?? 0 })}
      confirmText={t('account.confirmDelete')} isDeleting={removeAll.isPending}
      onConfirm={() => { if (bulkTarget) removeAll.mutate(bulkTarget); }} onClose={() => setBulkTarget(undefined)}>
      {removeAll.isError ? <RequestError error={removeAll.error} /> : null}
    </ConfirmDeleteDialog>
  </div>;
}
