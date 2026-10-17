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

import { Fragment, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight, Server, Sparkles, Wrench } from 'lucide-react';
import { toolKeys, type CatalogTool, type McpServer, type McpToolSummary } from '@/api/tool';
import { selectionListParams } from '@/api/listQuery';
import { skillKeys, type SkillSummary } from '@/features/skill/SkillsPage';
import { Badge } from '@/components/ui/badge';
import { DataTable } from '@/components/DataTable';
import { usePagedList } from '@/components/usePagedList';
import { cn } from '@/lib/utils';

interface CreationSettingsSectionProps {
  draftId?: string;
  tools: string[];
  onChangeTools: (tools: string[]) => void;
  skills: string[];
  onChangeSkills: (skills: string[]) => void;
  resources?: Array<Record<string, unknown>>;
  onChangeResources?: (resources: Array<Record<string, unknown>>) => void;
}

export function CreationSettingsSection({
  draftId,
  tools,
  onChangeTools,
  skills,
  onChangeSkills,
}: CreationSettingsSectionProps) {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<'tools' | 'mcp'>('tools');

  // 创建草稿的选择上下文：归档到草稿所有者；未落库的草稿按当前账号取值。
  const context = selectionListParams(draftId ? { draftId } : {});
  const toolsQuery = usePagedList<CatalogTool>(toolKeys.tools, '/api/advisor/tools', {
    extra: { ...context, origin: 'http,declared' },
    enabled: isOpen,
  });
  const serversQuery = usePagedList<McpServer>(toolKeys.servers, '/api/advisor/mcp-servers', {
    extra: context,
    enabled: isOpen,
  });
  const skillsQuery = usePagedList<SkillSummary>(skillKeys.skills, '/api/advisor/skills', {
    extra: context,
    enabled: isOpen,
  });

  const toggleTool = (toolId: string) => {
    onChangeTools(
      tools.includes(toolId) ? tools.filter((id) => id !== toolId) : [...tools, toolId],
    );
  };

  const toggleSkill = (skill: SkillSummary) => {
    onChangeSkills(
      skills.includes(skill.skillId)
        ? skills.filter((id) => id !== skill.skillId)
        : [...skills, skill.skillId],
    );
  };

  const selectedCount = tools.length + skills.length;

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      {/* Accordion Header */}
      <button
        type="button"
        onClick={() => setIsOpen((prev) => !prev)}
        className="flex w-full items-center justify-between p-4 text-left font-medium text-slate-800 hover:bg-slate-50 transition-colors rounded-xl cursor-pointer"
      >
        <div className="flex items-center gap-2">
          {isOpen ? (
            <ChevronDown className="h-4 w-4 text-slate-500" />
          ) : (
            <ChevronRight className="h-4 w-4 text-slate-500" />
          )}
          <span className="text-sm font-semibold">
            {t('agent.creation.moreSettings', '高级能力配置')}
          </span>
          {selectedCount > 0 && (
            <span className="rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-700 font-medium">
              {t('table.selectedCount', { count: selectedCount })}
            </span>
          )}
        </div>
        <span className="text-xs text-slate-400">
          {isOpen ? t('agent.creation.collapse', '收起') : t('agent.creation.expand', '展开')}
        </span>
      </button>

      {/* Accordion Body */}
      {isOpen && (
        <div className="space-y-5 border-t border-slate-100 p-4 pt-3">
          {/* Tools & MCP Tabs */}
          <div className="space-y-3">
            <div className="inline-flex gap-1 rounded-xl bg-slate-100 p-1">
              <button
                type="button"
                aria-pressed={activeTab === 'tools'}
                onClick={() => setActiveTab('tools')}
                className={cn(
                  'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors cursor-pointer',
                  activeTab === 'tools'
                    ? 'bg-white text-slate-900 shadow-2xs font-semibold'
                    : 'text-slate-600 hover:text-slate-900',
                )}
              >
                <Wrench className="h-3.5 w-3.5" />
                <span>{t('tool.catalog', '工具')}</span>
                <span className={cn(
                  'rounded-full px-1.5 py-0.2 text-[10.5px]',
                  activeTab === 'tools' ? 'bg-violet-50 text-violet-700' : 'bg-slate-200/60 text-slate-500',
                )}>
                  {toolsQuery.data?.total ?? 0}
                </span>
              </button>
              <button
                type="button"
                aria-pressed={activeTab === 'mcp'}
                onClick={() => setActiveTab('mcp')}
                className={cn(
                  'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors cursor-pointer',
                  activeTab === 'mcp'
                    ? 'bg-white text-slate-900 shadow-2xs font-semibold'
                    : 'text-slate-600 hover:text-slate-900',
                )}
              >
                <Server className="h-3.5 w-3.5" />
                <span>{t('tool.servers', 'MCP')}</span>
                <span className={cn(
                  'rounded-full px-1.5 py-0.2 text-[10.5px]',
                  activeTab === 'mcp' ? 'bg-violet-50 text-violet-700' : 'bg-slate-200/60 text-slate-500',
                )}>
                  {serversQuery.data?.total ?? 0}
                </span>
              </button>
            </div>

            {activeTab === 'tools' ? (
              <DataTable<CatalogTool>
                columns={[
                  {
                    id: 'name',
                    header: t('tool.columnName'),
                    cell: (tool) => {
                      const isSelected = tools.includes(tool.toolId);
                      const isSelectable = tool.origin !== 'declared' && tool.approved && tool.permission !== 'deny';
                      const disabled = !isSelected && !isSelectable;
                      return (
                        <label className={cn('flex flex-wrap items-center gap-2', disabled ? 'opacity-60' : undefined)}>
                          <input
                            type="checkbox"
                            aria-label={tool.name}
                            disabled={disabled}
                            checked={isSelected}
                            onChange={() => toggleTool(tool.toolId)}
                            className="h-3.5 w-3.5 rounded border-slate-300 text-violet-600 focus:ring-violet-500"
                          />
                          <span className="font-medium text-slate-800">{tool.name}</span>
                          {tool.origin === 'declared' ? (
                            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-400">{t('agent.tools.declared')}</span>
                          ) : !tool.approved ? (
                            <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-700">{t('tool.unavailable')}</span>
                          ) : tool.permission === 'deny' ? (
                            <span className="rounded bg-red-50 px-1.5 py-0.5 text-[11px] text-red-700">{t('tool.level.deny')}</span>
                          ) : null}
                        </label>
                      );
                    },
                  },
                  {
                    id: 'description',
                    header: t('tool.columnDescription'),
                    cell: (tool) => tool.description
                      ? <p className="line-clamp-2 text-xs text-slate-500" title={tool.description}>{tool.description}</p>
                      : <span className="text-xs text-slate-400">-</span>,
                  },
                ]}
                data={toolsQuery.data?.items ?? []}
                keyExtractor={(tool) => tool.toolId}
                isLoading={toolsQuery.isPending}
                error={toolsQuery.isError ? t('table.loadError') : undefined}
                onRetry={() => { void toolsQuery.refetch(); }}
                emptyTitle={t(toolsQuery.params.q ? 'table.noResults' : 'agent.creation.noTools')}
                page={toolsQuery.data?.page ?? toolsQuery.params.page}
                pageSize={toolsQuery.params.size}
                total={toolsQuery.data?.total ?? 0}
                onPageChange={toolsQuery.setPage}
                onPageSizeChange={toolsQuery.setPageSize}
              />
            ) : (
              <DataTable<McpServer>
                scrollable
                className="max-h-[32rem]"
                columns={[
                  { id: 'server', header: t('tool.serverColumnName'), cell: () => null },
                  { id: 'status', header: t('tool.serverColumnStatus'), headerClassName: 'whitespace-nowrap', cellClassName: 'whitespace-nowrap', cell: () => null },
                  { id: 'tools', header: t('tool.serverColumnTools'), align: 'right', cell: () => null },
                ]}
                data={serversQuery.data?.items ?? []}
                keyExtractor={(server) => server.serverId}
                renderRow={(server) => {
                  const summaries: McpToolSummary[] = server.toolSummaries ?? [];
                  return (
                    <Fragment key={server.serverId}>
                      <tr className="bg-slate-50/70">
                        <td className="px-3 py-2.5 pl-4 sm:pl-6">
                          <label className="flex items-center gap-2.5 text-xs font-semibold text-slate-700">
                            <Server aria-hidden className="h-3.5 w-3.5 text-slate-500" />
                            <span>{server.name}</span>
                          </label>
                        </td>
                        <td className="px-3 py-2.5 whitespace-nowrap">
                          <Badge tone={server.status === 'connected' ? 'success' : 'warning'} className="text-[10.5px]">
                            {t(`tool.status.${server.status}`)}
                          </Badge>
                        </td>
                        <td className="px-3 py-2.5 pr-4 text-right text-[12px] text-slate-400 sm:pr-6">
                          {t('tool.serverToolCount', { count: summaries.length })}
                        </td>
                      </tr>
                      {summaries.map((tool) => {
                        const isSelected = tools.includes(tool.toolId);
                        const isSelectable = tool.approved && tool.permission !== 'deny';
                        const disabled = !isSelected && !isSelectable;
                        return (
                          <tr key={tool.toolId} className={cn(isSelected && 'bg-violet-50/20')}>
                            <td className="py-2.5 pr-3 pl-8 sm:pl-12">
                              <label className={cn('flex items-center gap-2 text-xs text-slate-600', disabled ? 'opacity-60' : undefined)}>
                                <input
                                  type="checkbox"
                                  aria-label={tool.name}
                                  disabled={disabled}
                                  checked={isSelected}
                                  onChange={() => toggleTool(tool.toolId)}
                                  className="h-3.5 w-3.5 rounded border-slate-300 text-violet-600 focus:ring-violet-500"
                                />
                                <span>{tool.name}</span>
                              </label>
                            </td>
                            <td className="px-3 py-2.5 whitespace-nowrap" />
                            <td className="px-3 py-2.5 pr-4 text-right text-[11px] text-slate-400 sm:pr-6">
                              <span className="line-clamp-1 truncate max-w-xs block ml-auto" title={tool.description || undefined}>
                                {tool.description || '-'}
                              </span>
                            </td>
                          </tr>
                        );
                      })}
                    </Fragment>
                  );
                }}
                isLoading={serversQuery.isPending}
                error={serversQuery.isError ? t('table.loadError') : undefined}
                onRetry={() => { void serversQuery.refetch(); }}
                emptyTitle={t(serversQuery.params.q ? 'table.noResults' : 'tool.noServers')}
                page={serversQuery.data?.page ?? serversQuery.params.page}
                pageSize={serversQuery.params.size}
                total={serversQuery.data?.total ?? 0}
                onPageChange={serversQuery.setPage}
                onPageSizeChange={serversQuery.setPageSize}
              />
            )}
          </div>

          {/* Skills Section */}
          <div className="space-y-3 border-t border-slate-100 pt-3">
            <div className="flex items-center gap-2 text-sm font-medium text-slate-800">
              <Sparkles className="h-4 w-4 text-violet-600" />
              <span>{t('agent.creation.skillsTitle', '标准化技能')}</span>
              <span className="text-xs font-normal text-slate-400">
                {t('agent.creation.skillsHint', '（复用已发布的技能与工作流）')}
              </span>
            </div>
            <DataTable<SkillSummary>
              columns={[
                {
                  id: 'name',
                  header: t('skill.columnName'),
                  cell: (skill) => (
                    <label className="flex items-center gap-2.5">
                      <input
                        type="checkbox"
                        aria-label={skill.name}
                        checked={skills.includes(skill.skillId)}
                        onChange={() => toggleSkill(skill)}
                        className="h-3.5 w-3.5 rounded border-slate-300 text-violet-600 focus:ring-violet-500"
                      />
                      <span className="font-medium text-slate-800">{skill.name}</span>
                    </label>
                  ),
                },
                {
                  id: 'description',
                  header: t('skill.columnDescription'),
                  cell: (skill) => skill.description
                    ? <p className="line-clamp-2 text-xs text-slate-400">{skill.description}</p>
                    : <span className="text-xs text-slate-400">-</span>,
                },
              ]}
              data={skillsQuery.data?.items ?? []}
              keyExtractor={(skill) => skill.skillId}
              isLoading={skillsQuery.isPending}
              error={skillsQuery.isError ? t('table.loadError') : undefined}
              onRetry={() => { void skillsQuery.refetch(); }}
              emptyTitle={t(skillsQuery.params.q ? 'table.noResults' : 'agent.creation.noSkills')}
              page={skillsQuery.data?.page ?? skillsQuery.params.page}
              pageSize={skillsQuery.params.size}
              total={skillsQuery.data?.total ?? 0}
              onPageChange={skillsQuery.setPage}
              onPageSizeChange={skillsQuery.setPageSize}
            />
          </div>
        </div>
      )}
    </div>
  );
}
