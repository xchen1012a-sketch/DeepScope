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
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Page } from '@/components/Page';
import { usePagedList } from '@/components/usePagedList';
import { TableToolbar } from '@/components/TableToolbar';
import { DataTable, type DataTableColumn } from '@/components/DataTable';
import { AppSelect } from '@/components/ui/select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/apiClient';
import {
  approveMcpTools,
  confirmMcpChanges,
  connectMcp,
  refreshMcp,
  toolKeys,
  updateToolPermission,
  type ApproveMcpChangesInput,
  type CatalogTool,
  type DiscoveredTool,
  type McpDiff,
  type McpServer,
  type ToolPermission,
} from '@/api/tool';
import { McpChangesDialog } from './components/McpChangesDialog';
import { McpEditorDialog } from './components/McpEditorDialog';
import { ToolDeleteDialog, type ToolDeleteTarget } from './components/ToolDeleteDialog';
import { ToolEditorPanel } from './components/ToolEditorPanel';
import { ToolImportDialog } from './components/ToolImportDialog';
import { ToolTestPanel } from './components/ToolTestPanel';

type PageType = 'catalog' | 'mcp';

/** 同一页用两个类型切换：工具清单，或 MCP 服务。 */
export default function ToolsPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [pageType, setPageType] = useState<PageType>('catalog');
  const toolsQuery = usePagedList<CatalogTool>(toolKeys.tools, '/api/advisor/tools', { enabled: pageType === 'catalog', filters: { permission: '' } });
  const serversQuery = usePagedList<McpServer>(toolKeys.servers, '/api/advisor/mcp-servers', { enabled: pageType === 'mcp' });
  const submitSearch = (query: string) => pageType === 'catalog' ? toolsQuery.submit(query) : serversQuery.submit(query);
  const [searchDraft, setSearchDraft] = useState('');
  const permissionFilter = String(toolsQuery.params.permission ?? '');
  const [importOpen, setImportOpen] = useState(false);
  const [mcpOpen, setMcpOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ToolDeleteTarget | null>(null);
  const [testToolTarget, setTestToolTarget] = useState<CatalogTool | null>(null);
  const [editToolTarget, setEditToolTarget] = useState<CatalogTool | null>(null);
  const [editServerTarget, setEditServerTarget] = useState<McpServer | null>(null);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: toolKeys.tools });
    queryClient.invalidateQueries({ queryKey: toolKeys.servers });
  };

  const handleTabChange = (type: PageType) => {
    setPageType(type);
    toolsQuery.reset({ permission: '' });
    serversQuery.reset();
    setSearchDraft('');
  };

  return (
    <Page>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex gap-1 rounded-xl bg-muted p-1" role="tablist">
            {(['catalog', 'mcp'] as PageType[]).map((type) => (
              <button
                key={type}
                type="button"
                role="tab"
                aria-selected={pageType === type}
                onClick={() => handleTabChange(type)}
                className={
                  pageType === type
                    ? 'rounded-lg bg-white px-3 py-1.5 text-sm font-medium text-slate-900 shadow-sm'
                    : 'rounded-lg px-3 py-1.5 text-sm font-medium text-slate-500 hover:text-slate-700'
                }
              >
                {t(type === 'catalog' ? 'tool.catalog' : 'tool.servers')}
              </button>
            ))}
          </div>

          <TableToolbar
            className="w-full sm:w-auto"
            search={searchDraft}
            onSearchChange={setSearchDraft}
            onSearch={() => submitSearch(searchDraft)}
            placeholder={t(pageType === 'catalog' ? 'tool.searchToolPlaceholder' : 'tool.searchServerPlaceholder')}
          />

          {pageType === 'catalog' ? (
            <AppSelect
              size="md"
              value={permissionFilter}
              onChange={(val) => {
                toolsQuery.setFilters({ permission: val });
              }}
              options={[
                { value: '', label: t('skill.statusAll', '全部') },
                { value: 'allow', label: t('tool.level.allow', '放行') },
                { value: 'ask', label: t('tool.level.ask', '需确认') },
                { value: 'deny', label: t('tool.level.deny', '拒绝') },
              ]}
              allowClear
              onClear={() => {
                toolsQuery.setFilters({ permission: '' });
              }}
              placeholder={t('tool.permissionFilterPlaceholder', '权限')}
              className="w-28 text-sm"
            />
          ) : null}
        </div>

        {pageType === 'catalog' ? (
          <Button onClick={() => setImportOpen(true)}>
            <Plus className="h-4 w-4" />
            {t('tool.import')}
          </Button>
        ) : (
          <Button onClick={() => setMcpOpen(true)}>
            <Plus className="h-4 w-4" />
            {t('tool.addMcp')}
          </Button>
        )}
      </div>

      {pageType === 'catalog' ? (
        <ToolTable
          tools={toolsQuery.data?.items ?? []}
          total={toolsQuery.data?.total ?? 0}
          page={toolsQuery.data?.page ?? toolsQuery.params.page}
          pageSize={toolsQuery.params.size}
          onPageChange={toolsQuery.setPage}
          pageSizeOptions={[10, 20, 50]}
          onPageSizeChange={toolsQuery.setPageSize}
          isLoading={toolsQuery.isPending}
          error={toolsQuery.isError ? t('table.loadError') : undefined}
          onRetry={() => { void toolsQuery.refetch(); }}
          onChanged={refresh}
          onDelete={(tool) =>
            setDeleteTarget({
              type: 'tool',
              id: tool.toolId,
              name: tool.name,
              revision: tool.revision,
            })
          }
          onTest={(tool) => setTestToolTarget(tool)}
          onEdit={(tool) => setEditToolTarget(tool)}
        />
      ) : (
        <ServerTable
          servers={serversQuery.data?.items ?? []}
          total={serversQuery.data?.total ?? 0}
          page={serversQuery.data?.page ?? serversQuery.params.page}
          pageSize={serversQuery.params.size}
          onPageChange={serversQuery.setPage}
          pageSizeOptions={[10, 20, 50]}
          onPageSizeChange={serversQuery.setPageSize}
          isLoading={serversQuery.isPending}
          error={serversQuery.isError ? t('table.loadError') : undefined}
          onRetry={() => { void serversQuery.refetch(); }}
          onChanged={refresh}
          onDelete={(server) =>
            setDeleteTarget({
              type: 'server',
              id: server.serverId,
              name: server.name,
              revision: server.revision,
            })
          }
          onEdit={(server) => setEditServerTarget(server)}
        />
      )}

      <ToolImportDialog open={importOpen} onClose={() => setImportOpen(false)} onDone={refresh} />
      <ToolTestPanel
        open={Boolean(testToolTarget)}
        tool={testToolTarget}
        onClose={() => setTestToolTarget(null)}
      />
      <ToolEditorPanel
        open={Boolean(editToolTarget)}
        tool={editToolTarget}
        onClose={() => setEditToolTarget(null)}
        onSaved={refresh}
      />
      <McpEditorDialog
        open={Boolean(editServerTarget)}
        server={editServerTarget}
        onClose={() => setEditServerTarget(null)}
        onSaved={refresh}
      />
      <ConnectDialog open={mcpOpen} onClose={() => setMcpOpen(false)} onDone={refresh} />
      <ToolDeleteDialog
        open={Boolean(deleteTarget)}
        target={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onDeleted={refresh}
      />
    </Page>
  );
}

function ToolTable({
  tools,
  total,
  page,
  pageSize,
  onPageChange,
  pageSizeOptions,
  onPageSizeChange,
  isLoading,
  error,
  onRetry,
  onChanged,
  onDelete,
  onTest,
  onEdit,
}: {
  tools: CatalogTool[];
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  pageSizeOptions?: number[];
  onPageSizeChange?: (pageSize: number) => void;
  isLoading?: boolean;
  error?: string;
  onRetry?: () => void;
  onChanged: () => void;
  onDelete: (tool: CatalogTool) => void;
  onTest: (tool: CatalogTool) => void;
  onEdit: (tool: CatalogTool) => void;
}) {
  const { t } = useTranslation();
  const permission = useMutation({
    mutationFn: (input: { id: string; permission: ToolPermission }) =>
      updateToolPermission(input.id, input.permission),
    onSuccess: onChanged,
  });

  const columns: DataTableColumn<CatalogTool>[] = [
    {
      id: 'name',
      header: t('tool.columnName'),
      headerClassName: 'py-3.5 pl-4 pr-3 sm:pl-6',
      cellClassName: 'py-3.5 pl-4 pr-3 sm:pl-6',
      cell: (tool) => (
        <div className="flex items-center gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="font-semibold text-slate-900 truncate" title={tool.name}>
                {tool.name}
              </span>
              <Badge tone="default" className="shrink-0 whitespace-nowrap text-[11px] px-1.5 py-0.5">
                {t(`tool.origin.${tool.origin}`)}
              </Badge>
            </div>
          </div>
        </div>
      ),
    },
    {
      id: 'description',
      header: t('tool.columnDescription'),
      headerClassName: 'px-3 py-3.5 hidden md:table-cell',
      cellClassName: 'px-3 py-3.5 hidden md:table-cell',
      cell: (tool) =>
        tool.description ? (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <p className="line-clamp-2 text-xs text-slate-500 max-w-md cursor-default">
                  {tool.description}
                </p>
              </TooltipTrigger>
              <TooltipContent className="max-w-md whitespace-pre-wrap text-xs">
                {tool.description}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        ),
    },
    {
      id: 'type',
      header: t('tool.columnType'),
      headerClassName: 'px-3 py-3.5 hidden lg:table-cell',
      cellClassName: 'px-3 py-3.5 hidden lg:table-cell',
      cell: (tool) => {
        const text = tool.httpMethod
          ? `${tool.httpMethod} ${tool.httpUrl || ''}`.trim()
          : tool.origin === 'declared'
            ? 'Declared'
            : tool.httpUrl || '—';
        return tool.httpUrl || tool.httpMethod ? (
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="text-xs font-mono text-slate-500 truncate block max-w-xs cursor-default">
                  {text}
                </span>
              </TooltipTrigger>
              <TooltipContent className="max-w-md break-all text-xs font-mono">
                {text}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          <span className="text-xs font-mono text-slate-500 truncate block max-w-xs">
            {text}
          </span>
        );
      },
    },
    {
      id: 'permission',
      header: t('tool.columnPermission'),
      headerClassName: 'px-3 py-3.5',
      cellClassName: 'px-3 py-3.5',
      cell: (tool) => (
        <AppSelect
          size="sm"
          aria-label={t('tool.permission')}
          className="h-8 w-24 text-xs"
          disabled={!tool.canEdit || permission.isPending}
          value={tool.permission}
          onChange={(val) =>
            permission.mutate({
              id: tool.toolId,
              permission: val as ToolPermission,
            })
          }
          options={[
            { value: 'allow', label: t('tool.level.allow') },
            { value: 'ask', label: t('tool.level.ask') },
            { value: 'deny', label: t('tool.level.deny') },
          ]}
        />
      ),
    },

  ];

  return (
    <DataTable
      columns={columns}
      actions={tool => [
        { id: 'test', label: t('tool.test'), onClick: () => onTest(tool) },
        { id: 'edit', label: t('tool.edit'), hidden: !tool.canEdit, onClick: () => onEdit(tool) },
        { id: 'delete', label: t('tool.delete'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: !tool.canEdit, onClick: () => onDelete(tool) },
      ]}
      data={tools}
      keyExtractor={(tool) => tool.toolId}
      isLoading={isLoading}
      error={error}
      onRetry={onRetry}
      emptyTitle={t('tool.empty')}
      page={page}
      pageSize={pageSize}
      total={total}
      onPageChange={onPageChange}
      pageSizeOptions={pageSizeOptions}
      onPageSizeChange={onPageSizeChange}
    />
  );
}

function ServerTable({
  servers,
  total,
  page,
  pageSize,
  onPageChange,
  pageSizeOptions,
  onPageSizeChange,
  isLoading,
  error,
  onRetry,
  onChanged,
  onDelete,
  onEdit,
}: {
  servers: McpServer[];
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  pageSizeOptions?: number[];
  onPageSizeChange?: (pageSize: number) => void;
  isLoading?: boolean;
  error?: string;
  onRetry?: () => void;
  onChanged: () => void;
  onDelete: (server: McpServer) => void;
  onEdit: (server: McpServer) => void;
}) {
  const { t } = useTranslation();
  const [activeDiffServer, setActiveDiffServer] = useState<{
    server: McpServer;
    diff: McpDiff;
  } | null>(null);

  const refresh = useMutation({
    mutationFn: refreshMcp,
    onSuccess: (updatedServer) => {
      onChanged();
      const diff = updatedServer.diff ?? updatedServer.pendingDiff;
      if (diff && (diff.added?.length || diff.changed?.length || diff.removed?.length)) {
        setActiveDiffServer({ server: updatedServer, diff });
      }
    },
  });
  const applyChanges = useMutation({
    mutationFn: (input: { serverId: string; payload: ApproveMcpChangesInput }) =>
      confirmMcpChanges(input.serverId, input.payload),
    onSuccess: () => {
      onChanged();
      setActiveDiffServer(null);
    },
  });

  const columns: DataTableColumn<McpServer>[] = [
    {
      id: 'name',
      header: t('tool.serverColumnName'),
      headerClassName: 'py-3.5 pl-4 pr-3 sm:pl-6',
      cellClassName: 'py-3.5 pl-4 pr-3 sm:pl-6',
      cell: (server) => (
        <div className="flex items-center gap-3">
          <div className="min-w-0">
            <span className="font-semibold text-slate-900 block truncate" title={server.name}>
              {server.name}
            </span>
          </div>
        </div>
      ),
    },
    {
      id: 'transport',
      header: t('tool.serverColumnTransport'),
      headerClassName: 'px-3 py-3.5 hidden md:table-cell',
      cellClassName: 'px-3 py-3.5 hidden md:table-cell',
      cell: (server) => {
        const text = server.url || server.command || '—';
        return (
          <div className="max-w-xs space-y-1">
            {server.url || server.command ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span className="font-mono text-xs text-slate-600 block truncate cursor-default">
                      {text}
                    </span>
                  </TooltipTrigger>
                  <TooltipContent className="max-w-md break-all text-xs font-mono">
                    {text}
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : (
              <span className="font-mono text-xs text-slate-400 block truncate">
                {text}
              </span>
            )}
            {server.transport && server.transport.toLowerCase() !== 'http' ? (
              <span className="inline-block text-[10px] text-slate-400 uppercase tracking-wider font-semibold">
                {server.transport}
              </span>
            ) : null}
          </div>
        );
      },
    },
    {
      id: 'tools',
      header: t('tool.serverColumnTools'),
      headerClassName: 'px-3 py-3.5 hidden lg:table-cell',
      cellClassName: 'px-3 py-3.5 hidden lg:table-cell',
      cell: (server) => (
        <div className="max-w-xs">
          <span className="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700 border border-indigo-100">
            {t('tool.contributed', { count: server.toolCount })}
          </span>
          {server.tools && server.tools.length > 0 ? (
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <p className="mt-1 line-clamp-1 text-xs text-slate-400 cursor-default">
                    {server.tools.join('、')}
                  </p>
                </TooltipTrigger>
                <TooltipContent className="max-w-xs text-xs">
                  <span className="block leading-relaxed">{server.tools.join('、')}</span>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          ) : null}
        </div>
      ),
    },
    {
      id: 'status',
      header: t('tool.serverColumnStatus'),
      headerClassName: 'px-3 py-3.5 whitespace-nowrap',
      cellClassName: 'px-3 py-3.5 whitespace-nowrap',
      cell: (server) => (
        <div className="space-y-1">
          <Badge
            tone={
              server.status === 'connected'
                ? 'success'
                : server.status === 'error'
                  ? 'danger'
                  : 'default'
            }
            className="text-xs"
          >
            {t(`tool.status.${server.status}`)}
          </Badge>
          {server.statusMessage ? (
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <p className="text-xs text-red-600 truncate max-w-xs cursor-default">
                    {server.statusMessage}
                  </p>
                </TooltipTrigger>
                <TooltipContent className="max-w-xs text-xs text-red-600">
                  {server.statusMessage}
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          ) : null}
        </div>
      ),
    },

  ];

  return (
    <>
      <DataTable
        columns={columns}
        actions={server => {
          const pendingDiff = server.pendingDiff ?? server.diff;
          const hasPending = Boolean(pendingDiff && (pendingDiff.added?.length || pendingDiff.changed?.length || pendingDiff.removed?.length));
          return [
            { id: 'changes', label: t('tool.mcpChanges.pendingChanges'), icon: AlertTriangle, tone: 'warning', hidden: !hasPending, onClick: () => { if (pendingDiff) setActiveDiffServer({ server, diff: pendingDiff }); } },
            { id: 'edit', label: t('tool.edit'), onClick: () => onEdit(server) },
            { id: 'refresh', label: t('tool.refresh'), icon: RefreshCw, loading: refresh.isPending && refresh.variables === server.serverId, disabled: refresh.isPending, onClick: () => refresh.mutate(server.serverId) },
            { id: 'delete', label: t('tool.delete'), icon: Trash2, iconOnly: true, tone: 'danger', onClick: () => onDelete(server) },
          ];
        }}
        data={servers}
        keyExtractor={(server) => server.serverId}
        isLoading={isLoading}
      error={error}
      onRetry={onRetry}
        emptyTitle={t('tool.noServers')}
        page={page}
        pageSize={pageSize}
        total={total}
        onPageChange={onPageChange}
        pageSizeOptions={pageSizeOptions}
        onPageSizeChange={onPageSizeChange}
      />

      <McpChangesDialog
        open={Boolean(activeDiffServer)}
        server={activeDiffServer?.server ?? null}
        diff={activeDiffServer?.diff ?? null}
        onClose={() => setActiveDiffServer(null)}
        onApply={async (serverId, payload) => {
          await applyChanges.mutateAsync({ serverId, payload });
        }}
        isApplying={applyChanges.isPending}
      />
    </>
  );
}

function ConnectDialog({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [pending, setPending] = useState<McpServer[] | null>(null);
  const [chosen, setChosen] = useState<Record<string, ToolPermission>>({});
  const connect = useMutation({
    mutationFn: connectMcp,
    onSuccess: (servers) => {
      setPending(servers);
      const next: Record<string, ToolPermission> = {};
      servers.forEach((server) => (server.discovered ?? []).forEach((tool) => {
        next[`${server.serverId}:${tool.name}`] = 'ask';
      }));
      setChosen(next);
    },
  });
  const approve = useMutation({
    mutationFn: async () => {
      for (const server of pending ?? []) {
        const tools = (server.discovered ?? [])
          .filter((tool) => chosen[`${server.serverId}:${tool.name}`])
          .map((tool) => ({ name: tool.name, permission: chosen[`${server.serverId}:${tool.name}`] }));
        if (tools.length > 0) await approveMcpTools(server.serverId, tools);
      }
    },
    onSuccess: () => {
      onDone();
      onClose();
      setPending(null);
      setText('');
    },
  });
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>{t('tool.addMcp')}</DialogTitle>
          <DialogDescription>{t('tool.mcpHint')}</DialogDescription>
        </DialogHeader>
        <DialogBody className="max-h-[60vh] space-y-3 overflow-y-auto">
          {pending == null ? (
            <>
              <Textarea
                rows={8}
                aria-label={t('tool.mcpPaste')}
                value={text}
                onChange={(event) => setText(event.target.value)}
                placeholder={t('tool.mcpExampleConfig')}
                className="font-mono text-xs"
              />
            </>
          ) : (
            pending.map((server) => (
              <div key={server.serverId} className="space-y-2">
                <p className="text-sm font-medium">{server.name}</p>
                {server.status === 'error' ? <p className="text-sm text-red-700">{server.statusMessage || t('tool.connectError')}</p> : null}
                {(server.discovered ?? []).map((tool: DiscoveredTool) => (
                  <label key={tool.name} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={Boolean(chosen[`${server.serverId}:${tool.name}`])}
                      onChange={(event) =>
                        setChosen((current) => {
                          const next = { ...current };
                          if (event.target.checked) next[`${server.serverId}:${tool.name}`] = next[`${server.serverId}:${tool.name}`] ?? 'ask';
                          else delete next[`${server.serverId}:${tool.name}`];
                          return next;
                        })
                      }
                    />
                    <span className="flex-1">{tool.name}</span>
                    <PermissionSelect
                      value={chosen[`${server.serverId}:${tool.name}`] ?? 'ask'}
                      onChange={(permission) => setChosen((current) => ({ ...current, [`${server.serverId}:${tool.name}`]: permission }))}
                    />
                  </label>
                ))}
              </div>
            ))
          )}
          {connect.isError ? <p className="text-sm text-red-700">{connectionFailure(connect.error, t('tool.connectError'))}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('chat.cancel')}</Button>
          {pending == null ? (
            <Button disabled={!text.trim() || connect.isPending} onClick={() => connect.mutate(text)}>{t('tool.connect')}</Button>
          ) : (
            <Button disabled={approve.isPending} onClick={() => approve.mutate()}>{t('tool.approve')}</Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function connectionFailure(error: unknown, fallback: string) {
  if (error instanceof ApiError && error.body) {
    try {
      const parsed = JSON.parse(error.body) as { message?: string };
      if (parsed.message) return parsed.message;
    } catch {
      if (error.body.trim()) return error.body;
    }
  }
  return fallback;
}

function PermissionSelect({ value, onChange }: { value: ToolPermission; onChange: (value: ToolPermission) => void }) {
  const { t } = useTranslation();
  return (
    <AppSelect
      size="sm"
      className="w-24 text-xs"
      value={value}
      onChange={(val) => onChange(val as ToolPermission)}
      options={[
        { value: 'allow', label: t('tool.level.allow') },
        { value: 'ask', label: t('tool.level.ask') },
        { value: 'deny', label: t('tool.level.deny') },
      ]}
    />
  );
}
