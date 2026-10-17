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
import { useEffect, useId, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Check, ChevronDown, Loader2, X } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
export interface RemoteSearchPage<T> { items: T[]; total: number; source?: 'preset' | 'discovered'; complete?: boolean; hasMore?: boolean; snapshot?: string }
import { ApiError } from '@/lib/apiClient';

export interface SearchChoice { value: string; label: string }
export function RemoteSearchSelect({ id, label, value, displayValue, scope, freeText = false, disabled = false, placeholder, load, onChange, allowClear = false }: {
  id: string; label: string; value: string; displayValue?: string; scope: string; freeText?: boolean;
  disabled?: boolean; placeholder?: string; load: (query: string, signal: AbortSignal, page: number, snapshot?: string) => Promise<SearchChoice[] | RemoteSearchPage<SearchChoice>>;
  onChange: (value: string) => void; allowClear?: boolean;
}) {
  const { t } = useTranslation();
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [active, setActive] = useState(-1);
  useEffect(() => { const timer = setTimeout(() => { setSearch(query); setActive(-1); }, 200); return () => clearTimeout(timer); }, [query]);
  const choices = useInfiniteQuery({
    queryKey: ['advisor', 'model-search', listId, id, scope, search],
    initialPageParam: { page: 1 } as { page: number; snapshot?: string },
    queryFn: async ({ signal, pageParam }): Promise<RemoteSearchPage<SearchChoice>> => {
      const result = pageParam.snapshot ? await load(search, signal, pageParam.page, pageParam.snapshot) : await load(search, signal, pageParam.page);
      return Array.isArray(result) ? { items: result, total: result.length, hasMore: false } : result;
    },
    getNextPageParam: (last, pages) => last.hasMore && last.items.length ? { page: pages.length + 1, snapshot: last.snapshot } : undefined,
    enabled: open && !disabled, staleTime: 0, retry: false,
  });
  const metadata = choices.data?.pages[0];
  const options = choices.data?.pages.flatMap(page => page.items) ?? [];
  const loadNext = () => {
    if (choices.hasNextPage && !choices.isFetching) void choices.fetchNextPage({ cancelRefetch: false });
  };
  useEffect(() => {
    if (open && active >= 0) document.getElementById(`${listId}-${active}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, open, listId]);
  const select = (option: SearchChoice) => { onChange(option.value); setOpen(false); setQuery(''); setSearch(''); setActive(-1); };
  const show = () => { if (!disabled) { if (!open) { setQuery(''); setSearch(''); setActive(-1); } setOpen(true); } };
  const visibleValue = freeText ? value : open ? query : displayValue ?? value;
  return <Popover.Root open={open && !disabled} onOpenChange={setOpen}>
    <Popover.Anchor asChild>
      <div className="relative min-w-0">
        <Input id={id} ref={input} role="combobox" aria-label={label} aria-expanded={open && !disabled}
          aria-controls={open ? listId : undefined} aria-autocomplete="list" autoComplete="off"
          aria-activedescendant={open && active >= 0 && options[active] ? `${listId}-${active}` : undefined}
          disabled={disabled} value={visibleValue} placeholder={open && !freeText ? t('modelSettings.manage.searchProvider') : placeholder}
          className={cn(allowClear && value ? 'pr-16' : 'pr-10', freeText && 'font-mono')}
          onFocus={show} onClick={show}
          onChange={event => { setQuery(event.target.value); setOpen(true); setActive(-1); if (freeText) onChange(event.target.value); }}
          onKeyDown={event => {
            if (event.nativeEvent.isComposing) return;
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault();
              if (!open) show();
              setActive(current => {
                if (!options.length) return -1;
                const direction = event.key === 'ArrowDown' ? 1 : -1;
                return current < 0 ? direction > 0 ? 0 : options.length - 1 : (current + direction + options.length) % options.length;
              });
            } else if (event.key === 'Enter' && open && active >= 0 && options[active]) { event.preventDefault(); select(options[active]); }
            else if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); }
            else if (event.key === 'Tab') setOpen(false);
          }} />
        {allowClear && value && !disabled ? <button type="button" aria-label={t('access.reset')} onClick={() => { onChange(''); setQuery(''); setSearch(''); }}
          className="absolute inset-y-0 right-9 flex w-7 items-center justify-center text-slate-400 hover:text-slate-600"><X aria-hidden className="h-3.5 w-3.5" /></button> : null}
        <button type="button" tabIndex={-1} disabled={disabled} aria-label={t('modelSettings.manage.showOptions', { label })}
          onMouseDown={event => event.preventDefault()} onClick={() => { const next = !open; input.current?.focus(); setOpen(next); }}
          className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-slate-400 disabled:opacity-40">
          <ChevronDown aria-hidden="true" className={cn('h-3.5 w-3.5 transition-transform', open && 'rotate-180')} />
        </button>
      </div>
    </Popover.Anchor>
    <Popover.Portal container={input.current?.closest<HTMLElement>('[role="dialog"]') ?? undefined}>
      <Popover.Content role="presentation" align="start" sideOffset={5} collisionPadding={12}
        onOpenAutoFocus={event => event.preventDefault()} onCloseAutoFocus={event => event.preventDefault()}
        onInteractOutside={event => { if (input.current?.parentElement?.contains(event.target as Node)) event.preventDefault(); }}
        className="z-[60] w-[var(--radix-popover-trigger-width)] max-w-[calc(100vw-2rem)] rounded-xl border border-slate-200 bg-white p-1 shadow-lg">
        {freeText && metadata?.source ? <p className="px-3 py-2 text-xs text-slate-500">{t(metadata.source === 'preset' ? 'modelSettings.manage.presetSuggestions' : metadata.complete === false ? 'modelSettings.manage.incompleteSuggestions' : 'modelSettings.manage.discoveredSuggestions')}</p> : null}
        {choices.isFetching ? <p role="status" className="flex items-center gap-2 px-3 py-2 text-xs text-slate-500"><Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin" />{t('modelSettings.manage.loadingOptions')}</p> : null}
        {choices.isError ? <div role="alert" className="space-y-2 p-2 text-xs text-slate-500"><p>{t('modelSettings.manage.optionsError')}</p><Button size="sm" variant="outline" onClick={() => { if (choices.isFetchNextPageError && !(choices.error instanceof ApiError && choices.error.status === 409)) void choices.fetchNextPage({ cancelRefetch: false }); else void choices.refetch(); }}>{t('table.retry')}</Button></div> : null}
          <div id={listId} role="listbox" aria-label={label} className="max-h-56 overflow-y-auto overscroll-contain"
            onScroll={event => { const node = event.currentTarget; if (node.scrollHeight - node.scrollTop - node.clientHeight < 48) loadNext(); }}>
            {options.map((option, index) => <div key={option.value} id={`${listId}-${index}`} role="option" aria-selected={option.value === value}
              onMouseDown={event => event.preventDefault()} onMouseMove={() => setActive(index)} onClick={() => select(option)}
              className={cn('flex cursor-pointer items-center justify-between gap-3 rounded-lg px-3 py-2 text-sm text-slate-700', active === index && 'bg-slate-100', value === option.value && 'text-indigo-600')}>
              <span className={cn('break-all', freeText && 'font-mono text-xs')}>{option.label}</span>{value === option.value ? <Check className="h-3.5 w-3.5 shrink-0" /> : null}
            </div>)}
            {!choices.isFetching && !options.length ? <p role="status" className="px-3 py-2 text-xs text-slate-500">{t(freeText ? 'modelSettings.manage.manualModelHint' : 'table.noResults')}</p> : null}
          </div>
        {metadata && metadata.total > options.length ? <p className="px-3 py-2 text-xs text-slate-500">{t('modelSettings.manage.optionRange', { count: options.length, total: metadata.total })}</p> : null}
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>;
}
