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

import { lazy, Suspense, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bot, Plus, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import { DataTable } from '@/components/DataTable';
import { usePagedList } from '@/components/usePagedList';
import { TableToolbar } from '@/components/TableToolbar';
import { agentKeys, updateAgent } from '@/api/agent';
import { cn } from '@/lib/utils';
import AgentDetailPage, { DeleteAgentDialog } from './AgentDetailPage';
import { AgentEditProvider } from './AgentEditProvider';
import { useAgentEditGuard } from './agentEditGuard';
const AgentCreationPage = lazy(() => import('./AgentCreationPage'));

interface AgentListItem {
  id: string;
  name: string;
  description: string;
  draft: boolean;
  enabled?: boolean;
  canEdit?: boolean;
  canDelete?: boolean;
  isPublic?: boolean;
  isOwner?: boolean;
}

export default function AgentPage() {
  return <AgentEditProvider><AgentBrowser /></AgentEditProvider>;
}
function AgentBrowser() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { agentId, draftId } = useParams<{ agentId?: string; draftId?: string }>();
  const guard = useAgentEditGuard();
  const [draftSearch, setDraftSearch] = useState('');
  const [creationKey, setCreationKey] = useState(0);
  const [deleting, setDeleting] = useState<AgentListItem | null>(null);
  const queryClient = useQueryClient();
  const overview = usePagedList<AgentListItem>(agentKeys.agents, '/api/advisor/agent-overview');
  const statusChange = useMutation({
    mutationFn: (item: AgentListItem) => updateAgent(item.id, { enabled: item.enabled === false }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: agentKeys.agents }),
        queryClient.invalidateQueries({ queryKey: ['advisor', 'agent-picker'] }),
        queryClient.invalidateQueries({ queryKey: ['advisor', 'agent-picker-selection'] }),
      ]);
    },
  });
  const isNew = location.pathname === '/agent/new';

  const handleSearch = () => {
    overview.submit(draftSearch);
  };

  const visible = overview.data?.items ?? [];
  const page = overview.data?.page ?? overview.params.page;
  const go = (url: string) => guard?.request(() => { setCreationKey(value => value + 1); navigate(url); });
  return <div className="flex h-full min-h-0 bg-white">
    <aside aria-label={t('agent.management.listLabel')} className="hidden min-h-0 w-full flex-col gap-3 bg-slate-50/60 px-3 py-5 md:flex md:w-64 md:shrink-0">
      <div className="flex items-center gap-1.5">
        <div className="relative flex-1">
          <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
          <Input
            aria-label={t('agent.management.search')}
            placeholder={t('agent.management.search')}
            value={draftSearch}
            onChange={event => setDraftSearch(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter') {
                event.preventDefault();
                handleSearch();
              }
            }}
            className="pl-9 h-9 text-xs"
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="h-9 px-2.5 text-xs shrink-0"
          onClick={handleSearch}
        >
          {t('agent.management.searchButton', '搜索')}
        </Button>
      </div>
      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto">
        {overview.isPending ? [1, 2, 3].map(id => <Skeleton key={id} className="h-14 rounded-lg" />) : null}
        {overview.isError ? <div className="px-2 py-3 text-sm text-red-600"><p>{t('agent.management.listError')}</p><button type="button" className="mt-1 text-indigo-600" onClick={() => { void overview.refetch(); }}>{t('agent.management.retry')}</button></div> : null}
        {visible.map(item => <button key={`${item.draft ? 'draft' : 'agent'}:${item.id}`} type="button" aria-pressed={item.draft ? draftId === item.id : agentId === item.id} onClick={() => { if ((item.draft && draftId === item.id) || (!item.draft && agentId === item.id)) return; go(item.draft ? `/agent/creation/${encodeURIComponent(item.id)}` : `/agent/${encodeURIComponent(item.id)}`); }} className={cn('flex h-14 w-full items-center gap-2.5 rounded-lg px-3 text-left transition-colors hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300', (item.draft ? draftId === item.id : agentId === item.id) && 'bg-indigo-50 text-indigo-700 hover:bg-indigo-50')}>
          <Bot aria-hidden="true" className="h-4 w-4 shrink-0 text-slate-400" />
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-1.5 truncate text-sm font-medium">
              <span className="truncate">{item.name || t('agent.management.unnamed')}</span>
              {item.isPublic ? <Badge tone="success" className="px-1.5 py-0 text-[10px] leading-tight">{t('agent.publicBadge')}</Badge> : null}
            </span>
            <span className="block truncate text-xs text-slate-500">{item.draft ? t('agent.management.unfinished') : item.description}</span>
          </span>
        </button>)}
        {!overview.isPending && !overview.isError && visible.length === 0 ? <p className="px-3 py-4 text-sm text-slate-500">{t(overview.params.q ? 'agent.management.noMatches' : 'agent.empty')}</p> : null}
      </div>
      {overview.data && overview.data.totalPages > 1 ? <div className="flex items-center justify-between gap-2">
        <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => overview.setPage(page - 1)}>{t('pagination.prev')}</Button>
        <span className="text-xs text-slate-500">{page} / {overview.data.totalPages}</span>
        <Button size="sm" variant="outline" disabled={page >= overview.data.totalPages} onClick={() => overview.setPage(page + 1)}>{t('pagination.next')}</Button>
      </div> : null}
      <Button variant="outline" onClick={() => go('/agent/new')}><Plus className="h-4 w-4" />{t('agent.newAgent')}</Button>
    </aside>
    <section aria-label={t('agent.management.detailLabel')} className="min-h-0 min-w-0 flex-1 overflow-y-auto">
      {agentId && !isNew ? (
        <AgentDetailPage key={agentId} />
      ) : draftId || isNew ? (
        <Suspense fallback={<Skeleton className="m-6 h-64" />}>
          <AgentCreationPage key={creationKey} />
        </Suspense>
      ) : (
        <div className="h-full overflow-y-auto p-6 md:p-8">
          <div className="mb-6 flex items-center justify-end">
            <Button onClick={() => go('/agent/new')}>
              <Plus className="h-4 w-4" />
              {t('agent.newAgent')}
            </Button>
          </div>
          <TableToolbar className="mb-4 md:hidden" search={draftSearch} onSearchChange={setDraftSearch} onSearch={handleSearch} placeholder={t('agent.management.search')} />
          {statusChange.isError ? <p role="alert" className="mb-3 text-sm text-destructive">{t('agent.management.statusFailed')}</p> : null}
          <DataTable
            columns={[
              { id: 'name', header: t('agent.field.name'), cell: item => <span className="font-semibold text-slate-900">{item.name || t('agent.management.unnamed')}</span> },
              { id: 'description', header: t('agent.field.description'), cell: item => <p className="line-clamp-2 max-w-md text-xs text-slate-500">{item.description || t('agent.management.noDescription')}</p> },
              { id: 'type', header: t('agent.management.listType'), cell: item => (
                <div className="flex items-center gap-1.5 flex-wrap">
                  <Badge tone={item.draft ? 'warning' : 'default'}>{t(item.draft ? 'agent.management.unfinished' : 'agent.personal')}</Badge>
                  {item.isPublic ? <Badge tone="success">{t('agent.publicBadge')}</Badge> : null}
                </div>
              ) },
              { id: 'status', header: t('agent.management.status'), headerClassName: 'whitespace-nowrap', cell: item => <Badge tone={item.draft ? 'warning' : item.enabled === false ? 'default' : 'success'}>{t(item.draft ? 'agent.management.unfinished' : item.enabled === false ? 'agent.management.disabled' : 'agent.management.enabled')}</Badge> },
            ]}
            actions={item => [{
              id: 'edit',
              label: t(item.draft ? 'agent.management.continueSetup' : 'agent.edit'),
              onClick: () => go(item.draft ? `/agent/creation/${encodeURIComponent(item.id)}` : `/agent/${encodeURIComponent(item.id)}`),
            }, {
              id: 'toggle-status',
              label: t(item.enabled === false ? 'agent.management.enable' : 'agent.management.disable'),
              hidden: item.draft || item.canEdit === false,
              disabled: statusChange.isPending,
              loading: statusChange.isPending && statusChange.variables?.id === item.id,
              onClick: () => statusChange.mutate(item),
            }, {
              id: 'delete', label: t('agent.delete'), tone: 'danger',
              hidden: item.canDelete === false, disabled: statusChange.isPending,
              onClick: () => setDeleting(item),
            }]}
            data={visible}
            keyExtractor={item => `${item.draft ? 'draft' : 'agent'}:${item.id}`}
            isLoading={overview.isPending}
            error={overview.isError ? t('agent.management.listError') : undefined}
            onRetry={() => { void overview.refetch(); }}
            emptyTitle={t(overview.params.q ? 'agent.management.noMatches' : 'agent.empty')}
            page={page} pageSize={overview.params.size} total={overview.data?.total ?? 0}
            onPageChange={overview.setPage} onPageSizeChange={overview.setPageSize}
          />
        </div>
      )}
    </section>
    {deleting ? <DeleteAgentDialog key={`${deleting.draft}:${deleting.id}`} agent={{ agentId: deleting.id, name: deleting.name }} draft={deleting.draft} open onClose={() => setDeleting(null)} onDeleted={() => setDeleting(null)} /> : null}
  </div>;
}
