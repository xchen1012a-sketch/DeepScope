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

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Loader2, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { TableToolbar } from '@/components/TableToolbar';
import { usePagedList } from '@/components/usePagedList';
import { Badge } from '@/components/ui/badge';
import { DataTable } from '@/components/DataTable';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { AppSelect } from '@/components/ui/select';
import { KnowledgeHelp } from './KnowledgeHelp';
import { useKnowledgeUpload, type KnowledgeUpload } from './useKnowledgeUpload';
import { apiFetch } from '@/lib/apiClient';
import { formatBytes, formatRelativeTime } from '@/lib/format';
import type { LibraryDetail, LibraryDocument } from '../types';
import { PdfPageDialog, type PdfPreview } from './PdfPageDialog';

export function DocumentManagerTab({
  library,
  onChanged,
  upload,
}: {
  library: LibraryDetail;
  onChanged: () => void;
  upload?: KnowledgeUpload;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const documentKeys = ['advisor', 'knowledge', library.libraryId, 'documents'] as const;
  const documentsQuery = usePagedList<LibraryDocument>(documentKeys,
    `/api/advisor/knowledge/${encodeURIComponent(library.libraryId)}/documents`,
    {
      filters: { status: '' },
      refetchInterval: query => (query.state.data?.parsingCount ?? 0) > 0 || query.state.data?.items.some(item => item.status === 'parsing') ? 2000 : false,
    });
  const previousParsing = useRef<{ libraryId: string; count: number }>();
  const parsingCount = documentsQuery.data?.parsingCount;
  useEffect(() => {
    if (parsingCount === undefined) return;
    const previous = previousParsing.current;
    previousParsing.current = { libraryId: library.libraryId, count: parsingCount };
    if (previous?.libraryId === library.libraryId && parsingCount < previous.count) {
      onChanged();
    }
  }, [library.libraryId, onChanged, parsingCount]);
  const changed = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['advisor', 'knowledge', library.libraryId, 'documents'] });
    onChanged();
  }, [library.libraryId, onChanged, queryClient]);

  const [searchDraft, setSearchDraft] = useState('');
  const search = documentsQuery.params.q;
  const statusFilter = String(documentsQuery.params.status ?? '');
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const [documentToDelete, setDocumentToDelete] = useState<LibraryDocument | null>(null);
  const [preview, setPreview] = useState<PdfPreview | null>(null);

  // 批量/单文件上传
  const localUpload = useKnowledgeUpload(library.libraryId, library.canEdit, changed);
  const uploader = upload ?? localUpload;
  const uploadFiles = uploader.stageFiles;
  const empty = documentsQuery.data?.total === 0 && !search && !statusFilter;
  const uploadButton = <Button disabled={uploader.uploading || library.rebuilding} onClick={uploader.selectFiles}>
    {uploader.uploading ? <Loader2 aria-hidden className="h-4 w-4 animate-spin" /> : null}
    {t(uploader.uploading ? 'knowledge.uploading' : 'knowledge.editor.documents.upload')}
  </Button>;

  // 重新处理切片
  const reprocessMutation = useMutation({
    mutationFn: (documentId: string) =>
      apiFetch<void>(
        `/api/advisor/knowledge/${encodeURIComponent(library.libraryId)}/documents/${encodeURIComponent(documentId)}/reprocess`,
        { method: 'POST' },
      ),
    onSuccess: changed,
  });

  // 删除单篇文档
  const deleteMutation = useMutation({
    mutationFn: (documentId: string) =>
      apiFetch<void>(
        `/api/advisor/knowledge/${encodeURIComponent(library.libraryId)}/documents/${encodeURIComponent(documentId)}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => {
      setDocumentToDelete(null);
      changed();
    },
  });

  // 状态筛选选项
  const statusOptions = [
    { value: '', label: t('knowledge.status.all', '全部状态') },
    { value: 'ready', label: t('knowledge.status.ready', '已就绪') },
    { value: 'parsing', label: t('knowledge.status.parsing', '处理中') },
    { value: 'failed', label: t('knowledge.status.failed', '失败') },
  ];

  return (
    <div className={`space-y-4 rounded-xl ${isDraggingOver ? 'ring-2 ring-primary' : ''}`}
      onDragOver={event => { if (library.canEdit && event.dataTransfer.types.includes('Files')) { event.preventDefault(); setIsDraggingOver(true); } }}
      onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setIsDraggingOver(false); }}
      onDrop={event => { if (library.canEdit && event.dataTransfer.files?.length) { event.preventDefault(); setIsDraggingOver(false); void uploadFiles(event.dataTransfer.files); } }}>
      {!upload ? localUpload.input : null}
      {uploader.error ? <p role="alert" className="text-sm text-red-600">{uploader.error}</p> : null}
      {reprocessMutation.isError ? <p role="alert" className="text-sm text-destructive">{t('knowledge.pdf.reprocessError')}</p> : null}
      {isDraggingOver ? <p role="status" className="text-sm text-primary">{t('knowledge.editor.documents.drop')}</p> : null}

      {/* 搜索与状态过滤条 */}
      <TableToolbar
        search={searchDraft}
        onSearchChange={setSearchDraft}
        onSearch={() => documentsQuery.submit(searchDraft)}
        placeholder={t('knowledge.searchDocsPlaceholder')}
        filters={<AppSelect
            size="md"
            value={statusFilter}
            onChange={status => documentsQuery.setFilters({ status })}
            options={statusOptions}
            allowClear
            onClear={() => documentsQuery.setFilters({ status: '' })}
            placeholder={t('knowledge.status.all', '全部状态')}
            className="w-32 text-sm"
          />}
        actions={library.canEdit && !empty ? <div className="flex items-center gap-1">{uploadButton}<KnowledgeHelp label={t('knowledge.editor.documents.uploadHelpLabel')}>{t('knowledge.editor.documents.uploadHelp')}</KnowledgeHelp></div> : undefined}
      />

      {/* 标准化 DataTable 表格 */}
      <DataTable<LibraryDocument>
        columns={[
          {
            id: 'filename',
            header: t('knowledge.columnDocName', '文档名称'),
            headerClassName: 'py-3.5 pl-4 pr-3 sm:pl-6 min-w-[220px]',
            cell: (doc) => (
              <div className="min-w-0">
                <span className="font-medium text-slate-900 truncate max-w-sm text-sm" title={doc.filename}>
                  {doc.filename}
                </span>
              </div>
            ),
          },
          {
            id: 'sizeBytes',
            header: t('knowledge.columnDocSize', '大小'),
            headerClassName: 'px-3 py-3.5 min-w-[100px]',
            cell: (doc) => (
              <span className="font-mono text-xs text-slate-500">
                {formatBytes(doc.sizeBytes)}
              </span>
            ),
          },
          {
            id: 'status',
            header: t('knowledge.columnDocStatus', '状态'),
            headerClassName: 'px-3 py-3.5 min-w-[130px]',
            cell: (doc) => {
              if (doc.status === 'partial') {
                return <div className="space-y-1"><Badge tone="default">{t('knowledge.pdf.partial')}</Badge>
                  <p className="text-xs text-muted-foreground">{t('knowledge.pdf.incomplete', { count: doc.failedPages ?? 0 })}</p></div>;
              }
              if (doc.status === 'ready') {
                return (
                  <div className="space-y-1"><Badge tone="success" className="text-xs font-normal">
                    {t(doc.failure ? 'knowledge.pdf.previousReady' : 'knowledge.status.ready')}
                  </Badge>{doc.failure ? <p className="max-w-xs text-xs text-amber-800">{doc.failure}</p> : null}</div>
                );
              }
              if (doc.status === 'parsing') {
                return (
                  <Badge tone="default" className="text-xs font-normal gap-1.5 text-blue-600 bg-blue-50 border-blue-200">
                    <Loader2 className="h-3 w-3 animate-spin text-blue-600" />
                    {t(`knowledge.pdf.stage.${doc.stage || 'parsing'}`)}
                    {doc.stage === 'parsing' && doc.pageCount ? <span>{t('knowledge.pdf.progress', { done: doc.processedPages ?? 0, total: doc.pageCount })}</span> : null}
                    {doc.stage === 'embedding' && doc.totalChunks ? <span>{t('knowledge.embeddingProgress', { done: doc.embeddedChunks ?? 0, total: doc.totalChunks })}</span> : null}
                  </Badge>
                );
              }
              return <div className="flex items-center gap-1"><Badge tone="destructive" className="text-xs font-normal">{t('knowledge.status.failed')}</Badge>
                <KnowledgeHelp label={t('knowledge.editor.documents.failureHelp', { name: doc.filename })}>{doc.failure || t('knowledge.editor.documents.processFailed')}</KnowledgeHelp></div>;
            },
          },
          {
            id: 'createdAt',
            header: t('knowledge.columnDocTime', '创建时间'),
            headerClassName: 'px-3 py-3.5 hidden md:table-cell min-w-[120px]',
            cell: (doc) => (
              <span className="text-xs text-slate-400">
                {doc.createdAt ? formatRelativeTime(doc.createdAt, i18n.language) : '-'}
              </span>
            ),
          },

        ]}
        actions={doc => [
          { id: 'pages', label: t('knowledge.content.view'), hidden: doc.filename.toLowerCase().endsWith('.pdf') && !doc.pageCount, onClick: () => setPreview({ libraryId: library.libraryId, documentId: doc.documentId, filename: doc.filename, pageCount: doc.pageCount }) },
          { id: 'reprocess', label: t('knowledge.reprocess'), hidden: !library.canEdit, disabled: reprocessMutation.isPending || doc.status === 'parsing', loading: reprocessMutation.isPending && reprocessMutation.variables === doc.documentId, onClick: () => reprocessMutation.mutate(doc.documentId) },
          { id: 'delete', label: t('knowledge.deleteDocument'), icon: Trash2, iconOnly: true, tone: 'danger', hidden: !library.canEdit, onClick: () => setDocumentToDelete(doc) },
        ]}
        data={documentsQuery.data?.items ?? []}
        isLoading={documentsQuery.isPending}
        error={documentsQuery.isError ? t('table.loadError') : undefined}
        onRetry={() => { void documentsQuery.refetch(); }}
        keyExtractor={(doc) => doc.documentId}
        emptyTitle={
          search.trim() || statusFilter
            ? t('knowledge.noFilteredDocuments', '未找到匹配的文档')
            : t('knowledge.noDocuments', '还没有文档')
        }
        emptyAction={
          library.canEdit && empty ? uploadButton : undefined
        }
        page={documentsQuery.data?.page ?? documentsQuery.params.page}
        pageSize={documentsQuery.params.size}
        total={documentsQuery.data?.total ?? 0}
        onPageChange={documentsQuery.setPage}
        pageSizeOptions={[10, 20, 50]}
        onPageSizeChange={documentsQuery.setPageSize}
      />

      <PdfPageDialog preview={preview} onClose={() => setPreview(null)} />
      {/* 删除文档二次确认弹窗 */}
      <ConfirmDeleteDialog
        open={documentToDelete != null}
        title={t('knowledge.deleteDocConfirmTitle', '删除文档')}
        targetName={documentToDelete?.filename ?? ''}
        description={
          documentToDelete
            ? t('knowledge.deleteDocConfirmDesc', {
                defaultValue: `确定要删除文档“${documentToDelete.filename}”吗？删除后将无法恢复其切片与索引。`,
                name: documentToDelete.filename,
              })
            : ''
        }
        isDeleting={deleteMutation.isPending}
        onConfirm={() => {
          if (documentToDelete) {
            deleteMutation.mutate(documentToDelete.documentId);
          }
        }}
        onClose={() => setDocumentToDelete(null)}
      />
    </div>
  );
}
