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
import { useAgentEditor } from '../agentEditGuard';
import { skillKeys, type SkillSummary } from '@/features/skill/SkillsPage';

/** 选用技能：服务端分页的已发布技能表格，勾选后保存；列表下方保留保存按钮。 */
export function SkillSelectionSection({ agentId, canEdit }: { agentId: string; canEdit: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [draftSearch, setDraftSearch] = useState('');
  const skillsQuery = usePagedList<SkillSummary>(skillKeys.skills, '/api/advisor/skills', {
    extra: selectionListParams({ agentId }),
  });
  const selectionQuery = useQuery({
    queryKey: ['advisor', 'agents', agentId, 'skill-selection'],
    queryFn: () => apiFetch<{ skillIds: string[] }>(`/api/advisor/agents/${encodeURIComponent(agentId)}/skill-selection`),
  });
  const [skillIds, setSkillIds] = useState<string[]>([]);
  useEffect(() => {
    if (selectionQuery.data) setSkillIds(selectionQuery.data.skillIds);
  }, [selectionQuery.data]);
  const save = useMutation({
    mutationFn: (value: { skillIds: string[] }) =>
      apiFetch<void>(`/api/advisor/agents/${encodeURIComponent(agentId)}/skill-selection`, {
        method: 'PUT',
        body: JSON.stringify(value),
      }),
    onSuccess: (_result, value) => { queryClient.setQueryData(['advisor', 'agents', agentId, 'skill-selection'], value); },
  });
  const dirty = canEdit && selectionQuery.data !== undefined && JSON.stringify([...skillIds].sort()) !== JSON.stringify([...selectionQuery.data.skillIds].sort());
  useAgentEditor('skills', { dirty, busy: save.isPending, save: () => save.mutateAsync({ skillIds }), discard: () => setSkillIds(selectionQuery.data?.skillIds ?? []) });

  const toggle = (skill: SkillSummary) =>
    setSkillIds((current) =>
      current.includes(skill.skillId) ? current.filter((id) => id !== skill.skillId) : [...current, skill.skillId],
    );

  const search = skillsQuery.params.q;
  return <section>
    <TableToolbar
      search={draftSearch} onSearchChange={setDraftSearch}
      onSearch={() => skillsQuery.submit(draftSearch)}
      placeholder={t('skill.searchPlaceholder')}
      actions={skillIds.length > 0 ? (
        <span className="rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-600">
          {t('table.selectedCount', { count: skillIds.length })}
        </span>
      ) : undefined}
    />
    <DataTable<SkillSummary>
      className="mt-4"
      columns={[
        {
          id: 'name',
          header: t('skill.columnName'),
          cell: (skill) => (
            <label className="flex items-center gap-2.5">
              <input
                type="checkbox"
                aria-label={skill.name}
                className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                disabled={!canEdit || save.isPending}
                checked={skillIds.includes(skill.skillId)}
                onChange={() => toggle(skill)}
              />
              <span className="font-medium text-slate-900">{skill.name}</span>
              <Badge tone="success" className="text-[10.5px]">{t('skill.published')}</Badge>
            </label>
          ),
        },
        {
          id: 'description',
          header: t('skill.columnDescription'),
          cell: (skill) => skill.description
            ? <p className="line-clamp-2 text-xs text-slate-500">{skill.description}</p>
            : <span className="text-xs text-slate-400">-</span>,
        },
      ]}
      data={skillsQuery.data?.items ?? []}
      keyExtractor={(skill) => skill.skillId}
      isLoading={skillsQuery.isPending}
      error={skillsQuery.isError ? t('table.loadError') : undefined}
      onRetry={() => { void skillsQuery.refetch(); }}
      emptyTitle={t(search ? 'table.noResults' : 'skill.empty')}
      emptyAction={search ? <Button variant="outline" onClick={() => { skillsQuery.reset(); setDraftSearch(''); }}>{t('table.clearSearch')}</Button> : undefined}
      page={skillsQuery.data?.page ?? skillsQuery.params.page}
      pageSize={skillsQuery.params.size}
      total={skillsQuery.data?.total ?? 0}
      onPageChange={skillsQuery.setPage}
      pageSizeOptions={[10, 20, 50]}
      onPageSizeChange={skillsQuery.setPageSize}
    />
    {canEdit ? (
      <Button className="mt-4" size="sm" disabled={save.isPending} onClick={() => save.mutate({ skillIds })}>
        {t('skill.saveChoice')}
      </Button>
    ) : null}
    {save.isSuccess ? <p className="mt-2 text-[12px] text-emerald-600">{t('skill.saved')}</p> : null}
  </section>;
}
