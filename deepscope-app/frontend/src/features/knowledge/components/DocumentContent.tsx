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
import { createElement, useMemo, useState, type ReactNode } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Pagination } from '@/components/Pagination';
import { Markdown } from '@/features/chat/components/Markdown';
import { api } from '@/lib/apiClient';
import type { PdfPreview } from './PdfPageDialog';

interface Content { format: 'markdown' | 'text' | 'html'; text: string }
interface Chunks { items: { chunkId: string; text: string }[]; total: number }

export function DocumentContent({ preview }: { preview: PdfPreview }) {
  const { t } = useTranslation();
  const [view, setView] = useState('source');
  const [raw, setRaw] = useState(false);
  const [page, setPage] = useState(1);
  const base = `/api/advisor/knowledge/${encodeURIComponent(preview.libraryId)}/documents/${encodeURIComponent(preview.documentId)}`;
  const source = useQuery({ queryKey: ['advisor', 'document-content', preview.libraryId, preview.documentId], queryFn: () => api.get<Content>(`${base}/content`), enabled: view === 'source', retry: false });
  const chunks = useQuery({ queryKey: ['advisor', 'document-chunks', preview.libraryId, preview.documentId, page], queryFn: () => api.get<Chunks>(`${base}/chunks?page=${page}&size=10`), enabled: view === 'chunks', retry: false });
  const active = view === 'source' ? source : chunks;
  function renderText(text: string, format: Content['format']) {
    if (raw || format === 'text') return <pre className="whitespace-pre-wrap break-words font-mono text-sm leading-relaxed">{text}</pre>;
    return format === 'html' ? <SafeHtml text={text} /> : <Markdown text={text} documentMode className="min-w-0 text-sm [overflow-wrap:anywhere]" />;
  }
  return <DialogContent size="xl" className="h-[84dvh]">
    <DialogHeader>
      <DialogTitle className="truncate">{preview.filename}</DialogTitle>
      <DialogDescription>{t('knowledge.content.description')}</DialogDescription>
    </DialogHeader>
    <Tabs.Root value={view} onValueChange={setView} className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 px-6 py-3">
        <Tabs.List aria-label={t('knowledge.content.views')} className="inline-flex gap-1 rounded-xl bg-muted p-1">
          {['source', 'chunks'].map(value => <Tabs.Trigger key={value} value={value} className="rounded-lg px-3 py-1.5 text-sm text-muted-foreground data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow-sm">{t(`knowledge.content.${value}`)}</Tabs.Trigger>)}
        </Tabs.List>
        <Button size="sm" variant="outline" onClick={() => setRaw(value => !value)}>{t(raw ? 'knowledge.presentation.formatted' : 'knowledge.presentation.raw')}</Button>
      </div>
      <Tabs.Content value={view} className="flex min-h-0 flex-1 flex-col focus-visible:outline-none">
        <DialogBody className="space-y-4">
          {active.isPending && <p role="status">{t('knowledge.pdf.loading')}</p>}
          {active.isError && <div role="alert" className="space-y-2 text-sm text-destructive"><p>{t('knowledge.content.error')}</p><Button size="sm" variant="outline" onClick={() => void active.refetch()}>{t('knowledge.content.reload')}</Button></div>}
          {view === 'source' && source.data && (source.data.text ? renderText(source.data.text, source.data.format) : <p>{t('knowledge.content.empty')}</p>)}
          {view === 'chunks' && chunks.data && (chunks.data.items.length ? chunks.data.items.map((chunk, index) => <section key={chunk.chunkId} className="space-y-2 rounded-lg border p-4">
            <h3 className="text-xs text-muted-foreground">{t('knowledge.content.fragment', { number: (page - 1) * 10 + index + 1 })}</h3>
            {renderText(chunk.text, /\.txt$/i.test(preview.filename) ? 'text' : 'markdown')}
          </section>) : <p>{t('knowledge.content.noChunks')}</p>)}
        </DialogBody>
        {view === 'chunks' && !!chunks.data?.total && <DialogFooter><Pagination page={page} pageSize={10} total={chunks.data.total} onChange={setPage} /></DialogFooter>}
      </Tabs.Content>
    </Tabs.Root>
  </DialogContent>;
}

/** Parse into an inert template, then recreate only safe tags without source attributes. */
function SafeHtml({ text }: { text: string }) {
  const content = useMemo(() => {
    const template = document.createElement('template');
    template.innerHTML = text;
    const allowed = new Set(['p', 'div', 'span', 'section', 'article', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'b', 'i', 'ul', 'ol', 'li', 'dl', 'dt', 'dd', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'caption', 'blockquote', 'pre', 'code', 'br', 'hr']);
    const blocked = new Set(['script', 'style', 'iframe', 'object', 'embed', 'img', 'video', 'audio', 'form', 'input', 'button', 'link', 'meta', 'head', 'svg', 'math']);
    function render(node: Node, key: number, depth = 0): ReactNode {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent;
      if (!(node instanceof Element) || blocked.has(node.localName)) return null;
      if (depth > 50) return node.textContent;
      const children = Array.from(node.childNodes, (child, index) => render(child, index, depth + 1));
      const tag = allowed.has(node.localName) ? node.localName : 'span';
      const props = { key, ...(['td', 'th'].includes(tag) ? {
        colSpan: Math.min(50, Math.max(1, Number(node.getAttribute('colspan')) || 1)),
        rowSpan: Math.min(50, Math.max(1, Number(node.getAttribute('rowspan')) || 1)),
      } : {}) };
      return createElement(tag, props, ...(['br', 'hr'].includes(tag) ? [] : children));
    }
    return Array.from(template.content.childNodes, (node, index) => render(node, index));
  }, [text]);
  return <div className="md-text min-w-0 overflow-x-auto text-sm [overflow-wrap:anywhere]">{content}</div>;
}
