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

import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 12_000;
const BOTTOM_TOLERANCE = 24;

function pageBoundary(text: string, offset: number): number {
  const before = text.charCodeAt(offset - 1);
  const after = text.charCodeAt(offset);
  return before >= 0xd800 && before <= 0xdbff && after >= 0xdc00 && after <= 0xdfff ? offset + 1 : offset;
}

/** Thinking scrolls continuously; technical output uses bounded portions. */
export function ProcessText({ text, code = false, contained = true, live = false, continuous = false }: { text: string; code?: boolean; contained?: boolean; live?: boolean; continuous?: boolean }) {
  const { t } = useTranslation();
  const [page, setPage] = useState<number | null>(live ? null : 0);
  const scrollRef = useRef<HTMLPreElement>(null);
  const following = useRef(live);
  let last = continuous ? 0 : Math.max(0, Math.ceil(text.length / PAGE_SIZE) - 1);
  if (last > 0 && pageBoundary(text, last * PAGE_SIZE) >= text.length) last -= 1;
  const current = page === null ? last : Math.min(page, last);
  useEffect(() => { if (page !== null && page > last) setPage(last); }, [page, last]);
  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (live && following.current && node) node.scrollTop = Math.max(0, node.scrollHeight - node.clientHeight);
  }, [text, current, live]);
  const selectPage = (next: number) => {
    following.current = live && next === last;
    setPage(following.current ? null : next);
  };
  const visible = continuous ? text : text.slice(pageBoundary(text, current * PAGE_SIZE), pageBoundary(text, (current + 1) * PAGE_SIZE));
  return <div className="space-y-2">
    <pre ref={scrollRef} onScroll={live ? event => {
      // Keep inner reading separate from the conversation scroller.
      event.stopPropagation();
      const node = event.currentTarget;
      following.current = current === last && node.scrollHeight - node.scrollTop - node.clientHeight <= BOTTOM_TOLERANCE;
      setPage(following.current ? null : current);
    } : undefined} className={cn(contained && ['overflow-auto overscroll-contain', continuous ? 'h-60' : 'max-h-80'], 'whitespace-pre-wrap break-words text-[13px] leading-6 text-slate-600', code ? 'rounded-lg bg-slate-50 p-3 font-mono text-xs' : 'font-sans')}>{visible}</pre>
    {last > 0 ? <div className="flex items-center gap-3 text-xs text-slate-500">
      <button type="button" disabled={current === 0} onClick={() => selectPage(current - 1)} className="min-h-8 rounded px-2 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:opacity-40">{t('chat.flow.previousPortion')}</button>
      <span className="tabular-nums">{t('chat.flow.portion', { current: current + 1, total: last + 1 })}</span>
      <button type="button" disabled={current === last} onClick={() => selectPage(current + 1)} className="min-h-8 rounded px-2 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:opacity-40">{t('chat.flow.nextPortion')}</button>
    </div> : null}
  </div>;
}
