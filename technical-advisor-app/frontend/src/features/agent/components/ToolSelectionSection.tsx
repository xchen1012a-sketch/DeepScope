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

import { Fragment, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Server, Wrench } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DataTable } from '@/components/DataTable';
import { TableToolbar } from '@/components/TableToolbar';
import { usePagedList } from '@/components/usePagedList';
import {
  getToolSelection,
  saveToolSelection,
  toolKeys,
  type CatalogTool,
  type McpServer,
  type McpToolSummary,
} from '@/api/tool';
import { selectionListParams } from '@/api/listQuery';
import { useAgentEditor } from '../agentEditGuard';
import { cn } from '@/lib/utils';

type ToolTabType = 'tools' | 'mcp';

/** 选用工具：工具与 MCP 两栏都用标准表格与服务端分页，勾选后保存。 */
export function ToolSelectionSection({ agentId, canEdit }: { agentId: string; canEdit: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const context = selectionListParams({ agentId });
  const toolsQuery = usePagedList<CatalogTool>(toolKeys.tools, '/api/advisor/tools', {
    extra: { ...context, origin: 'http,declared' },
  });
  const serversQuery = usePagedList<McpServer>(toolKeys.servers, '/api/advisor/mcp-servers', { extra: context });
  const selectionQuery = useQuery({ queryKey: toolKeys.selection(agentId), queryFn: () => getToolSelection(agentId) });
  const [toolIds, setToolIds] = useState<string[]>([]);
  const [serverIds, setServerIds] = useState<string[]>([]);
  const [activeTab, setActiveTab] = useState<ToolTabType>('tools');
  const [draftToolSearch, setDraftToolSearch] = useState('');
  const [draftMcpSearch, setDraftMcpSearch] = useState('');

  useEffect(() => {
    if (!selectionQuery.data) return;
    setToolIds(selectionQuery.data.toolIds);
    setServerIds(selectionQuery.data.serverIds);
  }, [selectionQuery.data]);

  const save = useMutation({
    mutationFn: (value: { toolIds: string[]; serverIds: string[] }) => saveToolSelection(agentId, value),
    onSuccess: (_result, value) => { queryClient.setQueryData(toolKeys.selection(agentId), value); },
  });

  const dirty = canEdit && selectionQuery.data !== undefined && (JSON.stringify([...toolIds].sort()) !== JSON.stringify([...selectionQuery.data.toolIds].sort()) || JSON.stringify([...serverIds].sort()) !== JSON.stringify([...selectionQuery.data.serverIds].sort()));
  useAgentEditor('tools', { dirty, busy: save.isPending, save: () => save.mutateAsync({ toolIds, serverIds }), discard: () => { setToolIds(selectionQuery.data?.toolIds ?? []); setServerIds(selectionQuery.data?.serverIds ?? []); } });

  const toggleTool = (toolId: string) =>
    setToolIds((current) => (current.includes(toolId) ? current.filter((id) => id !== toolId) : [...current, toolId]));
  const toggleServer = (serverId: string) =>
    setServerIds((current) => (current.includes(serverId) ? current.filter((id) => id !== serverId) : [...current, serverId]));

  const toolSearch = toolsQuery.params.q;
  const mcpSearch = serversQuery.params.q;
  const selectedTotal = toolIds.length + serverIds.length;

  return <section className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="inline-flex gap-1 rounded-xl bg-slate-100 p-1">
        <button
          type="button"
          aria-pressed={activeTab === 'tools'}
          onClick={() => setActiveTab('tools')}
          className={cn(
            'flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-xs font-medium transition-colors',
            activeTab === 'tools' ? 'bg-white text-slate-900 shadow-2xs font-semibold' : 'text-slate-600 hover:text-slate-900',
          )}
        >
          <Wrench className="h-3.5 w-3.5" />
          <span>{t('tool.catalog')}</span>
          <span className={cn('rounded-full px-1.5 py-0.2 text-[10.5px]', activeTab === 'tools' ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-200/60 text-slate-500')}>
            {toolsQuery.data?.total ?? 0}
          </span>
        </button>
        <button
          type="button"
          aria-pressed={activeTab === 'mcp'}
          onClick={() => setActiveTab('mcp')}
          className={cn(
            'flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-xs font-medium transition-colors',
            activeTab === 'mcp' ? 'bg-white text-slate-900 shadow-2xs font-semibold' : 'text-slate-600 hover:text-slate-900',
          )}
        >
          <Server className="h-3.5 w-3.5" />
          <span>{t('tool.servers')}</span>
          <span className={cn('rounded-full px-1.5 py-0.2 text-[10.5px]', activeTab === 'mcp' ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-200/60 text-slate-500')}>
            {serversQuery.data?.total ?? 0}
          </span>
        </button>
      </div>
      {selectedTotal > 0 ? (
        <span className="rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-600">
          {t('agent.tools.selected', { tools: toolIds.length, servers: serverIds.length })}
        </span>
      ) : null}
    </div>

    {activeTab === 'tools' ? (
      <>
        <TableToolbar
          search={draftToolSearch} onSearchChange={setDraftToolSearch}
          onSearch={() => toolsQuery.submit(draftToolSearch)}
          placeholder={t('tool.searchToolPlaceholder')}
        />
        <DataTable<CatalogTool>
          columns={[
            {
              id: 'name',
              header: t('tool.columnName'),
              cell: (tool) => {
                const isSelected = toolIds.includes(tool.toolId);
                const isSelectable = tool.origin !== 'declared' && tool.approved && tool.permission !== 'deny';
                const disabled = !canEdit || save.isPending || (!isSelected && !isSelectable) || tool.origin === 'declared';
                return (
                  <label className={cn('flex flex-wrap items-center gap-2', disabled ? 'opacity-60' : undefined)}>
                    <input
                      type="checkbox"
                      aria-label={tool.name}
                      className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                      disabled={disabled}
                      checked={isSelected}
                      onChange={() => toggleTool(tool.toolId)}
                    />
                    <span className="font-medium text-slate-900">{tool.name}</span>
                    {tool.httpMethod ? (
                      <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600">{tool.httpMethod}</span>
                    ) : null}
                    {tool.origin === 'declared' ? (
                      <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-400">{t('agent.tools.declared')}</span>
                    ) : !tool.approved ? (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[11px] font-medium text-amber-700">{t('tool.unavailable')}</span>
                    ) : tool.permission === 'deny' ? (
                      <span className="rounded bg-red-50 px-1.5 py-0.5 text-[11px] font-medium text-red-700">{t('tool.level.deny')}</span>
                    ) : null}
                  </label>
                );
              },
            },
            {
              id: 'description',
              header: t('tool.columnDescription'),
              cell: (tool) => tool.description
                ? <p className="line-clamp-2 text-xs text-slate-500">{tool.description}</p>
                : <span className="text-xs text-slate-400">-</span>,
            },
          ]}
          data={toolsQuery.data?.items ?? []}
          keyExtractor={(tool) => tool.toolId}
          isLoading={toolsQuery.isPending}
          error={toolsQuery.isError ? t('agent.tools.loadError') : undefined}
          onRetry={() => { void toolsQuery.refetch(); }}
          emptyTitle={t(toolSearch ? 'table.noResults' : 'agent.tools.noneSelected')}
          emptyAction={toolSearch ? <Button variant="outline" onClick={() => { toolsQuery.reset(); setDraftToolSearch(''); }}>{t('table.clearSearch')}</Button> : undefined}
          page={toolsQuery.data?.page ?? toolsQuery.params.page}
          pageSize={toolsQuery.params.size}
          total={toolsQuery.data?.total ?? 0}
          onPageChange={toolsQuery.setPage}
          pageSizeOptions={[10, 20, 50]}
          onPageSizeChange={toolsQuery.setPageSize}
        />
      </>
    ) : (
      <>
        <TableToolbar
          search={draftMcpSearch} onSearchChange={setDraftMcpSearch}
          onSearch={() => serversQuery.submit(draftMcpSearch)}
          placeholder={t('tool.searchServerPlaceholder')}
        />
        <DataTable<McpServer>
          columns={[
            { id: 'server', header: t('tool.serverColumnName'), cell: () => null },
            { id: 'status', header: t('tool.serverColumnStatus'), cell: () => null },
            { id: 'tools', header: t('tool.serverColumnTools'), align: 'right', cell: () => null },
          ]}
          data={serversQuery.data?.items ?? []}
          keyExtractor={(server) => server.serverId}
          renderRow={(server) => {
            const isServerChecked = serverIds.includes(server.serverId);
            const summaries: McpToolSummary[] = server.toolSummaries ?? [];
            return (
              <Fragment key={server.serverId}>
                <tr className="bg-slate-50/70 transition-colors">
                  <td className="px-3 py-2.5 pl-4 sm:pl-6">
                    <label className="flex items-center gap-2.5">
                      <input
                        type="checkbox"
                        aria-label={server.name}
                        disabled={!canEdit || save.isPending}
                        checked={isServerChecked}
                        onChange={() => toggleServer(server.serverId)}
                        className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                      />
                      <Server aria-hidden className="h-4 w-4 text-slate-500" />
                      <span className="font-medium text-slate-900">{server.name}</span>
                    </label>
                  </td>
                  <td className="px-3 py-2.5">
                    <Badge tone={server.status === 'connected' ? 'success' : 'warning'} className="text-[10.5px]">
                      {t(`tool.status.${server.status}`)}
                    </Badge>
                  </td>
                  <td className="px-3 py-2.5 pr-4 text-right text-[12px] text-slate-400 sm:pr-6">
                    {t('tool.serverToolCount', { count: summaries.length })}
                  </td>
                </tr>
                {summaries.map((tool) => {
                  const isSelected = isServerChecked || toolIds.includes(tool.toolId);
                  const isSelectable = tool.approved && tool.permission !== 'deny';
                  const disabled = !canEdit || save.isPending || isServerChecked || (!isSelected && !isSelectable);
                  return (
                    <tr key={tool.toolId} className={cn('transition-colors', isSelected && 'bg-indigo-50/20')}>
                      <td className="py-2.5 pr-3 pl-8 sm:pl-12">
                        <label className={cn('flex flex-wrap items-center gap-2', disabled ? 'opacity-60' : undefined)}>
                          <input
                            type="checkbox"
                            aria-label={tool.name}
                            disabled={disabled}
                            checked={isSelected}
                            onChange={() => toggleTool(tool.toolId)}
                            className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                          />
                          <span className="font-medium text-slate-800">{tool.name}</span>
                          {!tool.approved ? (
                            <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-700">{t('tool.unavailable')}</span>
                          ) : tool.permission === 'deny' ? (
                            <span className="rounded bg-red-50 px-1.5 py-0.5 text-[11px] text-red-700">{t('tool.level.deny')}</span>
                          ) : null}
                        </label>
                      </td>
                      <td className="px-3 py-2.5" />
                      <td className="px-3 py-2.5 pr-4 text-right text-xs text-slate-500 sm:pr-6">
                        <span className="line-clamp-1">{tool.description || '-'}</span>
                      </td>
                    </tr>
                  );
                })}
              </Fragment>
            );
          }}
          isLoading={serversQuery.isPending}
          error={serversQuery.isError ? t('agent.tools.loadError') : undefined}
          onRetry={() => { void serversQuery.refetch(); }}
          emptyTitle={t(mcpSearch ? 'table.noResults' : 'tool.noServers')}
          emptyAction={mcpSearch ? <Button variant="outline" onClick={() => { serversQuery.reset(); setDraftMcpSearch(''); }}>{t('table.clearSearch')}</Button> : undefined}
          page={serversQuery.data?.page ?? serversQuery.params.page}
          pageSize={serversQuery.params.size}
          total={serversQuery.data?.total ?? 0}
          onPageChange={serversQuery.setPage}
          pageSizeOptions={[10, 20, 50]}
          onPageSizeChange={serversQuery.setPageSize}
        />
      </>
    )}

    {canEdit ? (
      <Button size="sm" disabled={save.isPending} onClick={() => save.mutate({ toolIds, serverIds })}>
        {t('agent.tools.save')}
      </Button>
    ) : null}
    {save.isSuccess ? <p className="text-[12px] text-emerald-600">{t('agent.tools.saved')}</p> : null}
  </section>;
}
