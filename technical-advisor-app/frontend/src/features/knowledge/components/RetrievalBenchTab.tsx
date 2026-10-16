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

import React, { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { ChevronDown } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ApiError, api } from '@/lib/apiClient';
import type { SearchHit } from '../types';
import { KnowledgeHelp } from './KnowledgeHelp';
import { PdfPageDialog, type PdfPreview } from './PdfPageDialog';
import { Markdown } from '@/features/chat/components/Markdown';

interface RetrievalBenchTabProps {
  libraryId: string;
  readyCount: number;
  scoreThreshold: number;
  onUpload?: () => void;
  uploading?: boolean;
}

export function RetrievalBenchTab({ libraryId, readyCount, scoreThreshold, onUpload, uploading }: RetrievalBenchTabProps) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [failure, setFailure] = useState('');
  const [preview, setPreview] = useState<PdfPreview | null>(null);
  const [latencyMs, setLatencyMs] = useState(0);
  const [expanded, setExpanded] = useState<number[]>([]);
  const [raw, setRaw] = useState<number[]>([]);
  const resultId = useId();
  const requestPending = useRef(false);
  const searchMutation = useMutation({
    mutationFn: async (searchQuery: string) => {
      const start = performance.now();
      const result = await api.post<SearchHit[]>(`/api/advisor/knowledge/${encodeURIComponent(libraryId)}/search`, { query: searchQuery });
      return { hits: result, ms: Math.round(performance.now() - start) };
    },
    onSuccess: (result, searchQuery) => {
      setHits(result.hits);
      setLatencyMs(result.ms);
      setSubmittedQuery(searchQuery);
      setExpanded([]);
      setRaw([]);
    },
    onError: (error) => {
      setHits(null);
      setFailure(error instanceof ApiError && error.body ? messageOf(error.body) : t('knowledge.searchError', '检索失败，请重试。'));
    },
    onSettled: () => { requestPending.current = false; },
  });

  function handleSearch(event: React.FormEvent) {
    event.preventDefault();
    const value = query.trim();
    if (!value || readyCount === 0 || requestPending.current) return;
    requestPending.current = true;
    setFailure('');
    searchMutation.mutate(value);
  }

  return (
    <div className="space-y-5">
      <form aria-label={t('knowledge.test.formLabel', '检索测试')} onSubmit={handleSearch} className="flex items-center gap-2">
        <Input value={query} onChange={event => setQuery(event.target.value)} placeholder={t('knowledge.test.queryPlaceholder', '输入检索关键词或描述要查找的内容')} aria-label={t('knowledge.test.queryPlaceholder', '输入检索关键词或描述要查找的内容')} className="max-w-xl" />
        <Button type="submit" disabled={!query.trim() || searchMutation.isPending || readyCount === 0}>
          {searchMutation.isPending ? t('knowledge.test.pending', '测试中…') : t('knowledge.test.submit', '测试')}
        </Button>
        <KnowledgeHelp label={t('knowledge.test.helpLabel', '检索测试说明')}>
          {t('knowledge.test.help', '根据当前检索设置查找相关文档片段。')}
        </KnowledgeHelp>
      </form>
      {readyCount === 0 && (
        <div className="flex items-center gap-3 text-sm text-slate-500">
          <span>{t('knowledge.test.nothingReady', '请先上传文档。')}</span>
          {onUpload && <Button variant="outline" size="sm" disabled={uploading} onClick={onUpload}>{t('knowledge.test.upload', '上传文档')}</Button>}
        </div>
      )}
      {failure && <p role="alert" className="text-sm text-red-600">{failure}</p>}
      {hits !== null && (
        <div className="space-y-3" aria-live="polite">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3 text-sm text-slate-500">
            <span>{t('knowledge.test.stats', { defaultValue: '召回 {{count}} 个片段 · 来自 {{documents}} 份文档 · {{ms}} ms', count: hits.length, documents: new Set(hits.map(hit => hit.documentId ?? hit.document)).size, ms: latencyMs })}</span>
            <span className="flex items-center gap-1">
              {t('knowledge.pdf.ranking')}
              <KnowledgeHelp label={t('knowledge.pdf.ranking')}>
                {t('knowledge.pdf.rankingHelp', { threshold: scoreThreshold.toFixed(2) })}
              </KnowledgeHelp>
            </span>
          </div>
          {hits.length === 0 ? <p className="py-6 text-sm text-slate-500">{t('knowledge.test.noHits', '未召回相关片段，试试调整检索词或检索设置。')}</p> : hits.map((hit, index) => (
            <article key={`${hit.document}-${index}`} className="space-y-3 border-b border-slate-200 py-3 last:border-b-0">
              <button type="button" className="flex w-full items-start justify-between gap-4 rounded text-left text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-expanded={expanded.includes(index)} aria-controls={`${resultId}-${index}`} onClick={() => setExpanded(previous => previous.includes(index) ? [] : [index])}>
                <span className="min-w-0 font-medium text-slate-800 break-all"><span className="mr-2 font-mono text-xs text-muted-foreground">{index + 1}.</span>{hit.document}</span>
                <span className="flex shrink-0 items-center gap-3"><span className="font-mono text-slate-500">{hit.rankingMethod === 'rrf' || hit.rankingMethod === 'model-rerank' ? hit.score.toFixed(4) : hit.score.toFixed(2)}</span><ChevronDown aria-hidden className={`h-4 w-4 transition-transform motion-reduce:transition-none ${expanded.includes(index) ? 'rotate-180' : ''}`} /></span>
              </button>
              {hit.source && hit.documentId ? <Button size="sm" variant="outline" onClick={() => setPreview({ libraryId: hit.libraryId ?? libraryId, documentId: hit.documentId!, filename: hit.document, source: hit.source, question: submittedQuery })}>
                {t('knowledge.pdf.sourcePage', { page: hit.source.pageNumber })} · {t(`knowledge.pdf.method.${hit.source.method}`)}
              </Button> : <p className="text-xs text-muted-foreground">{t('knowledge.presentation.documentFragment', '文档片段')}</p>}
              {hit.source?.interpretation && <p className="text-xs text-amber-800">{t('knowledge.pdf.interpretation')}</p>}
              {!expanded.includes(index) && <div className="max-h-28 overflow-hidden text-slate-600">
                <Markdown text={hit.passage} query={submittedQuery} compact className="min-w-0 text-sm leading-6 [overflow-wrap:anywhere] [&_p]:!my-1 [&_ul]:!my-1 [&_ol]:!my-1 [&_.md-table-wrap]:!my-1 [&_td]:!px-2 [&_td]:!py-1 [&_th]:!px-2 [&_th]:!py-1" />
              </div>}
              {expanded.includes(index) && <div id={`${resultId}-${index}`} className="max-h-[min(50dvh,28rem)] overflow-auto overscroll-contain rounded-lg border border-border p-4" tabIndex={0}>
                <div className="mb-3 flex justify-end"><Button size="sm" variant="ghost" onClick={() => setRaw(previous => previous.includes(index) ? previous.filter(item => item !== index) : [...previous, index])}>{t(raw.includes(index) ? 'knowledge.presentation.formatted' : 'knowledge.presentation.raw', raw.includes(index) ? '格式化' : '原始文本')}</Button></div>
                {hit.warnings?.map((warning, warningIndex) => <p key={warningIndex} className="mb-2 text-xs text-amber-800">{warning}</p>)}
                {raw.includes(index) ? <pre data-testid="raw-passage" className="whitespace-pre-wrap break-words text-sm leading-relaxed">{hit.passage}</pre> : <Markdown text={hit.passage} className="min-w-0 text-sm [overflow-wrap:anywhere]" />}
              </div>}
            </article>
          ))}
        </div>
      )}
      <PdfPageDialog preview={preview} onClose={() => setPreview(null)} />
    </div>
  );
}

function messageOf(body: string) {
  try {
    const parsed = JSON.parse(body) as { message?: string };
    return parsed.message || body;
  } catch {
    return body;
  }
}
