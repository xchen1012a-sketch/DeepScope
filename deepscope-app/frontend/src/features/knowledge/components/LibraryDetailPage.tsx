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
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Tabs from '@radix-ui/react-tabs';
import { ArrowLeft, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import { Page } from '@/components/Page';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { api, apiFetch } from '@/lib/apiClient';
import { libraryKeys, type LibraryDetail } from '../types';
import { DocumentManagerTab } from './DocumentManagerTab';
import { RetrievalBenchTab } from './RetrievalBenchTab';
import { RetrievalSettingsTab } from './RetrievalSettingsTab';
import { useKnowledgeUpload } from './useKnowledgeUpload';
import { PdfPageDialog } from './PdfPageDialog';

export function LibraryDetailPage({ libraryId }: { libraryId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState(searchParams.get('tab') || 'documents');

  React.useEffect(() => {
    const tabInUrl = searchParams.get('tab');
    if (tabInUrl && tabInUrl !== activeTab) {
      setActiveTab(tabInUrl);
    }
  }, [searchParams]);

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    setSearchParams({ tab }, { replace: true });
  };

  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);

  const detailQuery = useQuery({
    queryKey: ['advisor', 'knowledge', libraryId],
    queryFn: () => api.get<LibraryDetail>(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}?includeDocuments=false`),
    refetchInterval: query => query.state.data?.rebuilding || (activeTab !== 'documents' && (query.state.data?.parsingCount ?? 0) > 0) ? 2000 : false,
  });

  const library = detailQuery.data;
  const sourceDocument = searchParams.get('document');
  const sourcePage = Number(searchParams.get('page') ?? 1);
  const sourceInfo = useQuery({
    queryKey: ['advisor', 'knowledge', libraryId, 'pdf-info', sourceDocument],
    queryFn: () => api.get<{ filename: string; pageCount: number }>(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}/documents/${encodeURIComponent(sourceDocument!)}/info`),
    enabled: !!sourceDocument && Number.isInteger(sourcePage) && sourcePage > 0,
    retry: false,
  });

  const refresh = () => {
    // DocumentManagerTab refreshes its own page; avoid requesting that page twice.
    queryClient.invalidateQueries({
      queryKey: libraryKeys.libraries,
      predicate: query => query.queryKey[3] !== 'documents',
    });
  };
  const upload = useKnowledgeUpload(libraryId, !!library?.canEdit && !library.rebuilding, () => {
    void queryClient.invalidateQueries({ queryKey: libraryKeys.libraries });
  });

  const deleteMutation = useMutation({
    mutationFn: () =>
      apiFetch<void>(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: libraryKeys.libraries });
      navigate('/knowledge');
    },
  });

  if (detailQuery.isPending && !library) {
    return (
      <Page>
        <div className="space-y-4">
          <Skeleton className="h-20 rounded-2xl" />
          <Skeleton className="h-64 rounded-2xl" />
        </div>
      </Page>
    );
  }

  if (!library) {
    return (
      <Page>
        <EmptyState
          title={t('knowledge.missing', '没有找到这个知识库')}
          action={
            <Button variant="outline" onClick={() => navigate('/knowledge')}>
              {t('knowledge.backToList', '返回知识库列表')}
            </Button>
          }
        />
      </Page>
    );
  }

  const tabClass =
    'rounded-lg px-3 py-1.5 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700 data-[state=active]:bg-white data-[state=active]:text-slate-900 data-[state=active]:shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

  return (
    <Page>
      {upload.input}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link
          to="/knowledge"
          className="inline-flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-800 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>{t('knowledge.backToList', '返回知识库列表')}</span>
        </Link>
      </div>

      <div className="flex flex-wrap items-start justify-between gap-3 pt-1">
        <div>
          <div className="text-lg font-semibold text-slate-900">{library.name}</div>
          {library.description ? (
            <div className="mt-1 text-xs text-slate-500">{library.description}</div>
          ) : null}
        </div>
      </div>

      {/* 工作台三 Tab 导航架构 */}
      {library.rebuilding && <p role="status" className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">{t('knowledge.rebind.rebuilding')}</p>}
      <Tabs.Root value={activeTab} onValueChange={handleTabChange} className="space-y-4">
        <div className="flex items-center justify-between gap-3">
        <Tabs.List className="inline-flex min-w-0 max-w-full items-center gap-1 overflow-x-auto rounded-xl bg-muted p-1">
          <Tabs.Trigger value="documents" className={tabClass}>
            <span>
              {t('knowledge.editor.tabs.documents')}
            </span>
          </Tabs.Trigger>

          <Tabs.Trigger value="search" className={tabClass}>
            <span>{t('knowledge.editor.tabs.test')}</span>
          </Tabs.Trigger>

          <Tabs.Trigger value="settings" className={tabClass}>
            <span>{t('knowledge.editor.tabs.settings')}</span>
          </Tabs.Trigger>
        </Tabs.List>
        {library.canEdit ? (
          <Button
            size="sm"
            variant="outline"
            onClick={() => setIsDeleteDialogOpen(true)}
            className="h-8 w-8 shrink-0 px-0 text-red-600 border-red-200 hover:bg-red-50 hover:text-red-700"
            aria-label={t('knowledge.delete')}
            title={t('knowledge.delete')}
          >
            <Trash2 aria-hidden className="h-3.5 w-3.5" />
          </Button>
        ) : null}
        </div>

        {/* Tab 1: 文档管理 */}
        <Tabs.Content value="documents" className="focus-visible:outline-none">
          <DocumentManagerTab key={libraryId} library={library} onChanged={refresh} upload={upload} />
        </Tabs.Content>

        {/* Tab 2: 命中测试工作台 */}
        <Tabs.Content value="search" className="focus-visible:outline-none">
          {!library.rebuilding && <RetrievalBenchTab
            key={libraryId}
            libraryId={libraryId}
            readyCount={library.readyCount}
            scoreThreshold={library.scoreThreshold}
            onUpload={library.canEdit ? upload.selectFiles : undefined}
            uploading={upload.uploading}
          />}
          {upload.error ? <p role="alert" className="mt-3 text-sm text-red-600">{upload.error}</p> : null}
        </Tabs.Content>

        {/* Tab 3: 检索与分块配置 */}
        <Tabs.Content value="settings" className="focus-visible:outline-none">
          <RetrievalSettingsTab key={libraryId} library={library} onChanged={refresh} onReindexed={() => handleTabChange('documents')} />
        </Tabs.Content>
      </Tabs.Root>

      {/* 删除知识库二次确认弹窗 */}
      {sourceInfo.isError && sourceDocument && <p role="alert" className="text-sm text-destructive">{t('knowledge.pdf.pageError')}</p>}
      <PdfPageDialog preview={sourceDocument && sourceInfo.data ? {
        libraryId, documentId: sourceDocument, filename: sourceInfo.data.filename, pageCount: sourceInfo.data.pageCount,
        initialPage: sourcePage,
      } : null} onClose={() => { const next = new URLSearchParams(searchParams); next.delete('document'); next.delete('page'); setSearchParams(next, { replace: true }); }} />
      <ConfirmDeleteDialog
        open={isDeleteDialogOpen}
        title={t('knowledge.deleteConfirmTitle', '删除知识库')}
        targetName={library.name}
        description={t('knowledge.deleteConfirmDesc', {
          defaultValue: `这将永久删除知识库“${library.name}”及其包含的全部文档与向量切片索引。此操作无法撤销。`,
          name: library.name,
        })}
        isDeleting={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate()}
        onClose={() => setIsDeleteDialogOpen(false)}
      />
    </Page>
  );
}
