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

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DataTable } from '@/components/DataTable';
import { TableToolbar } from '@/components/TableToolbar';
import { usePagedList } from '@/components/usePagedList';
import { selectionListParams } from '@/api/listQuery';
import { apiFetch } from '@/lib/apiClient';
import { useAgentEditor } from '@/features/agent/agentEditGuard';
import { libraryKeys, type LibrarySummary } from '@/features/knowledge/types';

/** 选用知识库：服务端分页表格与勾选，保存后下一条消息生效，不必新开对话。 */
export function KnowledgeSelection({ agentId, canEdit }: { agentId: string; canEdit: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [draftSearch, setDraftSearch] = useState('');
  const librariesQuery = usePagedList<LibrarySummary>(libraryKeys.libraries, '/api/advisor/knowledge', {
    extra: selectionListParams({ agentId }),
  });
  const selectionQuery = useQuery({
    queryKey: ['advisor', 'agents', agentId, 'knowledge-selection'],
    queryFn: () =>
      apiFetch<{ libraryIds: string[] }>(
        `/api/advisor/agents/${encodeURIComponent(agentId)}/knowledge-selection`,
      ),
  });
  const [libraryIds, setLibraryIds] = useState<string[]>([]);
  useEffect(() => {
    if (selectionQuery.data) setLibraryIds(selectionQuery.data.libraryIds);
  }, [selectionQuery.data]);
  const save = useMutation({
    mutationFn: (value: { libraryIds: string[] }) =>
      apiFetch<void>(`/api/advisor/agents/${encodeURIComponent(agentId)}/knowledge-selection`, {
        method: 'PUT',
        body: JSON.stringify(value),
      }),
    onSuccess: (_result, value) => { queryClient.setQueryData(['advisor', 'agents', agentId, 'knowledge-selection'], value); },
  });
  const dirty = canEdit && selectionQuery.data !== undefined && JSON.stringify([...libraryIds].sort()) !== JSON.stringify([...selectionQuery.data.libraryIds].sort());
  useAgentEditor('knowledge', { dirty, busy: save.isPending, save: () => save.mutateAsync({ libraryIds }), discard: () => setLibraryIds(selectionQuery.data?.libraryIds ?? []) });

  const toggle = (library: LibrarySummary) =>
    setLibraryIds((current) =>
      current.includes(library.libraryId)
        ? current.filter((id) => id !== library.libraryId)
        : [...current, library.libraryId],
    );

  const search = librariesQuery.params.q;
  return <section>
    <TableToolbar
      search={draftSearch} onSearchChange={setDraftSearch}
      onSearch={() => librariesQuery.submit(draftSearch)}
      placeholder={t('knowledge.searchPlaceholder')}
      actions={libraryIds.length > 0 ? (
        <span className="rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-600">
          {t('table.selectedCount', { count: libraryIds.length })}
        </span>
      ) : undefined}
    />
    <DataTable<LibrarySummary>
      className="mt-4"
      columns={[
        {
          id: 'name',
          header: t('knowledge.columnName'),
          cell: (library) => (
            <div className="flex items-start gap-2.5">
              <input
                type="checkbox"
                aria-label={library.name}
                className="mt-0.5 h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                disabled={!canEdit || save.isPending}
                checked={libraryIds.includes(library.libraryId)}
                onChange={() => toggle(library)}
              />
              <label className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-slate-900">{library.name}</span>
                {library.description ? (
                  <span className="mt-0.5 line-clamp-2 block text-xs text-slate-500">{library.description}</span>
                ) : null}
              </label>
            </div>
          ),
        },
        {
          id: 'readyCount',
          header: t('knowledge.columnDocuments'),
          cell: (library) => (
            <Badge tone={library.readyCount > 0 ? 'success' : 'default'} className="font-normal text-xs">
              {t('knowledge.readyCount', { count: library.readyCount })}
            </Badge>
          ),
        },
      ]}
      data={librariesQuery.data?.items ?? []}
      keyExtractor={(library) => library.libraryId}
      isLoading={librariesQuery.isPending}
      error={librariesQuery.isError ? t('table.loadError') : undefined}
      onRetry={() => { void librariesQuery.refetch(); }}
      emptyTitle={t(search ? 'table.noResults' : 'knowledge.empty')}
      emptyAction={search ? <Button variant="outline" onClick={() => { librariesQuery.reset(); setDraftSearch(''); }}>{t('table.clearSearch')}</Button> : undefined}
      page={librariesQuery.data?.page ?? librariesQuery.params.page}
      pageSize={librariesQuery.params.size}
      total={librariesQuery.data?.total ?? 0}
      onPageChange={librariesQuery.setPage}
      pageSizeOptions={[10, 20, 50]}
      onPageSizeChange={librariesQuery.setPageSize}
    />
    {canEdit ? (
      <Button className="mt-4" size="sm" disabled={save.isPending} onClick={() => save.mutate({ libraryIds })}>
        {t('knowledge.saveChoice')}
      </Button>
    ) : null}
    {save.isSuccess ? <p className="mt-2 text-sm text-emerald-600">{t('knowledge.saved')}</p> : null}
  </section>;
}
