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

import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '@/lib/utils';
import { useRef, type ReactNode } from 'react';
import { CopyTextButton } from './CopyTextButton';

function CodeBlock({ children }: { children?: ReactNode }) {
  const code = useRef<HTMLPreElement>(null);
  return <div className="relative my-3 overflow-hidden rounded-lg border border-slate-200 bg-slate-50">
    <div className="flex justify-end border-b border-slate-200/60 px-2 py-1">
      <CopyTextButton readText={() => code.current?.textContent || ''} />
    </div>
    <pre ref={code} className="!m-0 !rounded-none !border-0 !bg-transparent !text-slate-800">{children}</pre>
  </div>;
}

interface MarkdownNode {
  type: string;
  value?: string;
  url?: string;
  children?: MarkdownNode[];
  data?: { hName: string; hProperties?: Record<string, string> };
}

function highlightQuery(options: { query?: string } = {}) {
  const terms = options.query?.trim().split(/\s+/u).filter(Boolean) ?? [];
  return (tree: MarkdownNode) => {
    if (!terms.length) return;
    const pattern = new RegExp(terms.map(term => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'giu');
    function visit(node: MarkdownNode) {
      if (['code', 'inlineCode', 'link', 'image'].includes(node.type) || !node.children) return;
      node.children = node.children.flatMap(child => {
        if (child.type !== 'text' || !child.value) { visit(child); return [child]; }
        const value = child.value;
        const parts: MarkdownNode[] = [];
        let cursor = 0;
        for (const match of value.matchAll(pattern)) {
          const start = match.index ?? 0;
          if (start > cursor) parts.push({ type: 'text', value: value.slice(cursor, start) });
          parts.push({ type: 'emphasis', data: { hName: 'mark', hProperties: { className: 'rounded-sm bg-amber-100 text-amber-950 not-italic' } }, children: [{ type: 'text', value: match[0] }] });
          cursor = start + match[0].length;
        }
        if (cursor < value.length) parts.push({ type: 'text', value: value.slice(cursor) });
        return parts;
      });
    }
    visit(tree);
  };
}

/** Work on parsed prose only: code, source URLs and technical identifiers stay literal. */
function hideInternalReferences(options: { internalIds?: readonly string[]; enabled?: boolean } = {}) {
  const known = new Set(options.internalIds?.filter(id => /^[FE]\d+$/.test(id)));
  return (tree: MarkdownNode) => {
    if (options.enabled === false) return;
    function visit(node: MarkdownNode, firstText = false) {
      if (['code', 'inlineCode', 'link', 'image', 'definition'].includes(node.type)) return;
      if (node.type === 'text' && node.value) {
        node.value = node.value.replace(/\[[FE]\d+(?:\s*[,、]\s*[FE]?\d+)*\]/g, (marker, offset: number, source: string) => {
          const before = source[offset - 1] ?? '';
          const after = source[offset + marker.length] ?? '';
          const embedded = /[A-Za-z0-9_/\\-]/.test(before + after)
            || (after === '.' && /[A-Za-z0-9_]/.test(source[offset + marker.length + 1] ?? ''));
          return embedded ? marker : '';
        });
        if (firstText) node.value = node.value.replace(/^([FE]\d+)\s*(?:[：:]|[-–—]\s)\s*/, (prefix, id: string) => known.has(id) ? '' : prefix);
      }
      if (node.children) {
        node.children = node.children.filter(child => !(child.type === 'link' && /^#evidence-[FE]\d+$/.test(child.url ?? '')));
        node.children.forEach((child, index) => visit(child, index === 0 && ['paragraph', 'heading'].includes(node.type)));
      }
    }
    visit(tree);
  };
}

export function Markdown({
  text,
  className,
  internalIds,
  query,
  compact = false,
  documentMode = false,
}: {
  text: string;
  className?: string;
  onCite?: (id: string) => void;
  internalIds?: readonly string[];
  query?: string;
  compact?: boolean;
  documentMode?: boolean;
}) {
  return (
    <div className={cn('md-text', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, [hideInternalReferences, { internalIds, enabled: !documentMode }], [highlightQuery, { query }]]}
        components={{
          ...(documentMode ? { img: ({ alt }: { alt?: string }) => <span>{alt}</span> } : {}),
          ...(compact ? {
            h1: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            h2: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            h3: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            h4: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            h5: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            h6: ({ children }: { children?: ReactNode }) => <p className="font-medium">{children}</p>,
            pre: ({ children }: { children?: ReactNode }) => <pre>{children}</pre>,
          } : { pre: ({ children }: { children?: ReactNode }) => <CodeBlock>{children}</CodeBlock> }),
          a: ({ href, children }) => {
            return (
              <a href={href} target="_blank" rel="noreferrer">
                {children}
              </a>
            );
          },
          table: ({ children }) => (
            <div className="md-table-wrap">
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
