/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { api, apiResponse } from '@/lib/apiClient';
import type { PdfPage, PdfSource } from '../types';
import { Markdown } from '@/features/chat/components/Markdown';
import { DocumentContent } from './DocumentContent';

export interface PdfPreview {
  libraryId: string;
  documentId: string;
  filename: string;
  pageCount?: number;
  initialPage?: number;
  source?: PdfSource | null;
  question?: string;
}

export function PdfPageDialog({ preview, onClose }: { preview: PdfPreview | null; onClose: () => void }) {
  return <Dialog open={preview !== null} onOpenChange={open => { if (!open) onClose(); }}>
    {preview && (/\.pdf$/i.test(preview.filename)
      ? <PdfPageContent key={`${preview.libraryId}:${preview.documentId}:${preview.initialPage ?? preview.source?.pageNumber ?? 1}`} preview={preview} />
      : <DocumentContent key={`${preview.libraryId}:${preview.documentId}`} preview={preview} />)}
  </Dialog>;
}

function PdfPageContent({ preview }: { preview: PdfPreview }) {
  const { t } = useTranslation();
  const [pageNumber, setPageNumber] = useState(preview.initialPage ?? preview.source?.pageNumber ?? 1);
  const [imageUrl, setImageUrl] = useState('');
  const [imageFailed, setImageFailed] = useState(false);
  const [selectedBlock, setSelectedBlock] = useState(preview.source?.blockId ?? '');
  const [question, setQuestion] = useState(preview.question ?? '');
  const [raw, setRaw] = useState(false);
  const base = `/api/advisor/knowledge/${encodeURIComponent(preview.libraryId)}/documents/${encodeURIComponent(preview.documentId)}`;
  const pageQuery = useQuery({
    queryKey: ['advisor', 'knowledge', preview.libraryId, 'pdf', preview.documentId, pageNumber],
    queryFn: () => api.get<PdfPage>(`${base}/pages/${pageNumber}`),
    retry: false,
  });
  const countQuery = useQuery({
    queryKey: ['advisor', 'knowledge', preview.libraryId, 'pdf-count', preview.documentId],
    queryFn: () => api.get<{ total: number }>(`${base}/pages?page=1&size=1`),
    enabled: preview.pageCount === undefined,
  });
  const pageCount = preview.pageCount ?? countQuery.data?.total ?? pageNumber;
  const modelQuery = useQuery({
    queryKey: ['advisor', 'knowledge-pdf-model'],
    queryFn: () => api.get<{ configured: boolean; modelName?: string }>('/api/advisor/knowledge/pdf-model'),
  });
  const verification = useMutation({
    mutationFn: () => api.post<{ verification: string }>(`${base}/pages/${pageNumber}/verify`, { query: question.trim() }),
  });
  useEffect(() => {
    const controller = new AbortController();
    let url = '';
    setImageUrl('');
    setImageFailed(false);
    void apiResponse(`${base}/pages/${pageNumber}/image`, { signal: controller.signal })
      .then(response => response.blob()).then(blob => {
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setImageUrl(url);
      }).catch(() => { if (!controller.signal.aborted) setImageFailed(true); });
    return () => { controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [base, pageNumber]);
  const page = pageQuery.data;
  const selected = page?.blocks.find(block => block.id === selectedBlock);
  const box = selected?.box ?? (preview.source?.pageNumber === pageNumber ? preview.source.box : null);
  const sourceSpans = preview.source?.pageNumber === pageNumber && selectedBlock === preview.source.blockId ? preview.source.spans : undefined;
  const boxes = sourceSpans && sourceSpans.length > 1 ? sourceSpans.flatMap(span => span.box ? [span.box] : []) : box ? [box] : [];
  function changePage(next: number) { setSelectedBlock(''); verification.reset(); setPageNumber(next); }
  return <DialogContent size="xl" className="h-[84dvh]">
    <DialogHeader>
      <DialogTitle className="truncate">{preview.filename}</DialogTitle>
      <DialogDescription>{t('knowledge.pdf.previewDescription')}</DialogDescription>
    </DialogHeader>
    <div className="flex shrink-0 items-center gap-3 border-b px-6 py-3">
      <Button size="sm" variant="outline" aria-label={t('knowledge.pdf.previous')} disabled={pageNumber <= 1} onClick={() => changePage(pageNumber - 1)}><ChevronLeft aria-hidden className="h-4 w-4" /></Button>
      <span aria-live="polite" className="text-sm tabular-nums">{t('knowledge.pdf.pageOf', { page: pageNumber, total: pageCount })}{page?.pageLabel ? ` · ${page.pageLabel}` : ''}</span>
      <Button size="sm" variant="outline" aria-label={t('knowledge.pdf.next')} disabled={pageNumber >= pageCount} onClick={() => changePage(pageNumber + 1)}><ChevronRight aria-hidden className="h-4 w-4" /></Button>
      {page && <span className="ml-auto text-xs text-muted-foreground">{t(`knowledge.pdf.method.${page.method}`)}</span>}
    </div>
    <div className="grid min-h-0 flex-1 grid-cols-1 overflow-auto md:grid-cols-2 md:overflow-hidden">
      <div className="overflow-auto bg-muted/40 p-4 md:border-r" aria-label={t('knowledge.pdf.original')}>
        {imageFailed ? <p role="alert" className="text-sm text-destructive">{t('knowledge.pdf.imageError')}</p> : imageUrl ? <div className="relative mx-auto w-full max-w-xl">
          <img src={imageUrl} alt={t('knowledge.pdf.originalPage', { page: pageNumber })} className="block w-full shadow-sm" />
          {boxes.map((location, index) => <span key={index} aria-hidden className="pointer-events-none absolute border-2 border-amber-500 bg-amber-200/25" style={{ left: `${location.x * 100}%`, top: `${location.y * 100}%`, width: `${location.width * 100}%`, height: `${location.height * 100}%` }} />)}
        </div> : <p role="status" className="text-sm text-muted-foreground">{t('knowledge.pdf.loading')}</p>}
      </div>
      <div className="space-y-3 overflow-auto p-5" aria-label={t('knowledge.pdf.parsed')}>
        <div className="flex justify-end"><Button size="sm" variant="outline" onClick={() => setRaw(value => !value)}>{t(raw ? 'knowledge.presentation.formatted' : 'knowledge.presentation.raw')}</Button></div>
        {pageQuery.isPending && <p role="status">{t('knowledge.pdf.loading')}</p>}
        {pageQuery.isError && <p role="alert" className="text-sm text-destructive">{t('knowledge.pdf.pageError')}</p>}
        {page?.status === 'blank' && <p className="text-sm text-muted-foreground">{t('knowledge.pdf.blank')}</p>}
        {page?.blocks.map(block => <section key={block.id}
          className={`min-w-0 space-y-2 rounded-lg border p-3 ${selectedBlock === block.id ? 'border-primary bg-primary/5' : 'border-border'}`}>
          <button type="button" onClick={() => setSelectedBlock(block.id)} aria-pressed={selectedBlock === block.id} className="block w-full rounded text-left text-xs text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">{t(`knowledge.pdf.block.${block.type}`, { defaultValue: block.type })}{block.interpretation ? ` · ${t('knowledge.pdf.interpretation')}` : ''} · {t('knowledge.presentation.locate')}</button>
          {raw ? <pre className="whitespace-pre-wrap break-words text-sm leading-relaxed">{block.text}</pre> : <Markdown text={block.text} className="min-w-0 text-sm [overflow-wrap:anywhere]" />}
        </section>)}
        <form className="space-y-2 border-t pt-3" onSubmit={event => { event.preventDefault(); if (question.trim() && !verification.isPending) verification.mutate(); }}>
          <label className="block text-sm font-medium" htmlFor="pdf-verify-question">{t('knowledge.pdf.verifyQuestion')}</label>
          <Input id="pdf-verify-question" value={question} onChange={event => setQuestion(event.target.value)} maxLength={2000} disabled={verification.isPending} />
          <p className="text-xs text-muted-foreground">{modelQuery.data?.configured ? t('knowledge.pdf.activeModel', { name: modelQuery.data.modelName }) : t('knowledge.pdf.modelMissing')}</p>
          <Button size="sm" variant="outline" disabled={!modelQuery.data?.configured || !question.trim() || verification.isPending || !page} type="submit">{t(verification.isPending ? 'knowledge.pdf.verifying' : 'knowledge.pdf.verify')}</Button>
          <p className="text-xs text-muted-foreground">{t('knowledge.pdf.verifyHelp')}</p>
          {verification.isError && <p role="alert" className="text-sm text-destructive">{t('knowledge.pdf.verifyError')}</p>}
          {verification.data && <div role="status"><Markdown text={verification.data.verification} className="text-sm" /></div>}
        </form>
      </div>
    </div>
  </DialogContent>;
}
