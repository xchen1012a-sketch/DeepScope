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

import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { DataTable } from '@/components/DataTable';
import { usePagedList } from '@/components/usePagedList';
import { TableToolbar } from '@/components/TableToolbar';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { Page } from '@/components/Page';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { api, apiFetch } from '@/lib/apiClient';
import { libraryKeys, type LibrarySummary } from '../types';

export function KnowledgeListPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();


  const [searchDraft, setSearchDraft] = useState('');
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [libraryToDelete, setLibraryToDelete] = useState<LibrarySummary | null>(null);

  const librariesQuery = usePagedList<LibrarySummary>(libraryKeys.libraries, '/api/advisor/knowledge');
  const search = librariesQuery.params.q;

  const refresh = () => queryClient.invalidateQueries({ queryKey: libraryKeys.libraries });

  const deleteMutation = useMutation({
    mutationFn: (libraryId: string) =>
      apiFetch<void>(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}`, { method: 'DELETE' }),
    onSuccess: () => {
      setLibraryToDelete(null);
      refresh();
    },
  });

  const getStrategyLabel = (lib: LibrarySummary) => {
    if (lib.chunkSize === 512 && lib.overlap === 50 && lib.recallLimit === 5) {
      return { text: t('knowledge.presetBalanced', '通用平衡'), tone: 'default' as const };
    }
    if (lib.chunkSize === 256 && lib.overlap === 30 && lib.recallLimit === 3) {
      return { text: t('knowledge.presetPrecise', '精准问答'), tone: 'success' as const };
    }
    if (lib.chunkSize === 1024 && lib.overlap === 100 && lib.recallLimit === 8) {
      return { text: t('knowledge.presetLongDoc', '长文研读'), tone: 'default' as const };
    }
    return {
      text: `${lib.chunkSize}字 / ${lib.recallLimit}条`,
      tone: 'default' as const,
    };
  };

  return (
    <Page>
      <div>
      <TableToolbar
        search={searchDraft} onSearchChange={setSearchDraft}
        onSearch={() => librariesQuery.submit(searchDraft)}
        placeholder={t('knowledge.searchPlaceholder')}
        actions={<Button onClick={() => setIsCreateOpen(true)}><Plus className="h-4 w-4" />{t('knowledge.new')}</Button>}
      />
      </div>

      {/* 标准化 DataTable 列表 */}
      <DataTable<LibrarySummary>
        columns={[
          {
            id: 'name',
            header: t('knowledge.columnName', '知识库名称与描述'),
            headerClassName: 'py-3.5 pl-4 pr-3 sm:pl-6 min-w-[240px]',
            cell: (lib) => (
              <div className="flex items-start gap-3 py-1">
                <div className="min-w-0 flex-1">
                  <Link
                    to={`/knowledge/${lib.libraryId}`}
                    className="font-medium text-slate-900 hover:text-indigo-600 transition-colors block truncate text-sm"
                  >
                    {lib.name}
                  </Link>
                  {lib.description ? (
                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <p className="mt-0.5 truncate text-xs text-slate-500 cursor-default max-w-md">
                            {lib.description}
                          </p>
                        </TooltipTrigger>
                        <TooltipContent className="max-w-md whitespace-pre-wrap text-xs">
                          {lib.description}
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>
                  ) : (
                    <span className="mt-0.5 block text-xs text-slate-400">-</span>
                  )}
                </div>
              </div>
            ),
          },
          {
            id: 'readyCount',
            header: t('knowledge.columnDocuments', '文档数量'),
            headerClassName: 'px-3 py-3.5 min-w-[120px]',
            cell: (lib) => (
              <div className="flex items-center gap-1.5">
                <Badge tone={lib.readyCount > 0 ? 'success' : 'default'} className="font-normal text-xs">
                  {t('knowledge.readyCount', { count: lib.readyCount })}
                </Badge>
              </div>
            ),
          },
          {
            id: 'strategy',
            header: t('knowledge.columnStrategy', '分块策略'),
            headerClassName: 'px-3 py-3.5 hidden md:table-cell min-w-[140px]',
            cell: (lib) => {
              const strategy = getStrategyLabel(lib);
              return (
                <div className="flex items-center gap-1.5 text-xs text-slate-600">
                  <span className="font-mono text-slate-700 bg-slate-100 px-2 py-0.5 rounded-md text-[11.5px]">
                    {strategy.text}
                  </span>
                </div>
              );
            },
          },
          {
            id: 'embeddingModel',
            header: t('knowledge.columnEmbeddingModel', '向量模型'),
            headerClassName: 'px-3 py-3.5 hidden sm:table-cell min-w-[130px]',
            cell: (lib) => (
              <span className="text-xs text-slate-600">
                {lib.embeddingModelName || t('knowledge.defaultModel', '默认')}
              </span>
            ),
          },
          {
            id: 'threshold',
            header: t('knowledge.columnThreshold', '相似度门槛'),
            headerClassName: 'px-3 py-3.5 hidden lg:table-cell min-w-[110px]',
            cell: (lib) => (
              <span className="font-mono text-xs text-slate-500">
                ≥ {lib.scoreThreshold.toFixed(2)}
              </span>
            ),
          },

        ]}
        actions={lib => [
          { id: 'edit', label: t('knowledge.edit'), hidden: !lib.canEdit, onClick: () => navigate(`/knowledge/${lib.libraryId}`) },
          { id: 'delete', label: t('knowledge.delete'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: !lib.canEdit, onClick: () => setLibraryToDelete(lib) },
        ]}
        data={librariesQuery.data?.items ?? []}
        keyExtractor={(lib) => lib.libraryId}
        isLoading={librariesQuery.isPending}
        error={librariesQuery.isError ? t('table.loadError') : undefined}
        onRetry={() => { void librariesQuery.refetch(); }}
        emptyTitle={t(search ? 'table.noResults' : 'knowledge.empty')}
        emptyAction={search ? <Button variant="outline" onClick={() => { librariesQuery.reset(); setSearchDraft(''); }}>{t('table.clearSearch')}</Button> :
          <Button onClick={() => setIsCreateOpen(true)} className="mt-2">
            <Plus className="h-4 w-4" />
            {t('knowledge.new', '新建知识库')}
          </Button>
        }
        page={librariesQuery.data?.page ?? librariesQuery.params.page}
        pageSize={librariesQuery.params.size}
        total={librariesQuery.data?.total ?? 0}
        onPageChange={librariesQuery.setPage}
        pageSizeOptions={[10, 20, 50]}
        onPageSizeChange={librariesQuery.setPageSize}
      />

      {/* 新建知识库弹窗 */}
      <CreateLibraryDialog
        open={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onCreated={(newLibId) => {
          refresh();
          if (newLibId) {
            navigate(`/knowledge/${newLibId}`);
          }
        }}
      />

      {/* 统一删除二次确认弹窗 */}
      <ConfirmDeleteDialog
        open={libraryToDelete != null}
        title={t('knowledge.deleteConfirmTitle', '删除知识库')}
        targetName={libraryToDelete?.name ?? ''}
        description={
          libraryToDelete
            ? t('knowledge.deleteConfirmDesc', {
                defaultValue: `这将永久删除知识库“${libraryToDelete.name}”及其包含的全部文档与向量切片索引。此操作无法撤销。`,
                name: libraryToDelete.name,
              })
            : ''
        }
        isDeleting={deleteMutation.isPending}
        onConfirm={() => {
          if (libraryToDelete) {
            deleteMutation.mutate(libraryToDelete.libraryId);
          }
        }}
        onClose={() => setLibraryToDelete(null)}
      />
    </Page>
  );
}

function CreateLibraryDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (newLibraryId?: string) => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const create = useMutation({
    mutationFn: () =>
      api.post<LibrarySummary>('/api/advisor/knowledge', {
        name: name.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: (result) => {
      setName('');
      setDescription('');
      onClose();
      onCreated(result?.libraryId);
    },
  });

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    if (name.trim() && !create.isPending) {
      create.mutate();
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md" className="rounded-2xl">
        <DialogHeader>
          <DialogTitle>{t('knowledge.new', '新建知识库')}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit}>
          <DialogBody className="space-y-4">
            <label className="block space-y-1.5 text-sm text-slate-700">
              <span className="font-medium text-slate-800">
                {t('knowledge.name', '名称')} <span className="text-red-500">*</span>
              </span>
              <Input
                autoFocus
                placeholder={t('knowledge.namePlaceholder', '例：产品开发技术规范')}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>

            <label className="block space-y-1.5 text-sm text-slate-700">
              <span className="font-medium text-slate-800">
                {t('knowledge.description', '描述')}
              </span>
              <Input
                placeholder={t('knowledge.descPlaceholder', '简要说明该知识库包含哪些业务材料...')}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>

            {create.isError ? (
              <p className="text-sm text-red-600 bg-red-50 p-2.5 rounded-lg border border-red-100">
                {t('knowledge.createError', '没有建成。换一个名称再试。')}
              </p>
            ) : null}
          </DialogBody>
          <DialogFooter className="gap-2.5">
            <Button type="button" variant="outline" onClick={onClose}>
              {t('chat.cancel', '取消')}
            </Button>
            <Button
              type="submit"
              disabled={name.trim() === '' || create.isPending}
            >
              {create.isPending ? t('shell.loading', '加载中…') : t('knowledge.create', '立即创建 (Enter ↵)')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
