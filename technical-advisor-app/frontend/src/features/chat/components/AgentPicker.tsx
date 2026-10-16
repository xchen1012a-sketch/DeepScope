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

import { useEffect, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Bot, Check, ChevronDown, LockKeyhole } from 'lucide-react';
import { getAvailableAgent, pageAvailableAgents } from '@/api/agent';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

export interface AgentPickerProps {
  value: string | null;
  onChange: (id: string | null) => void;
  disabled?: boolean;
  running?: boolean;
  activeAgentId?: string | null;
  activeAgentName?: string | null;
}
export function AgentPicker({ value, onChange, disabled, running, activeAgentId, activeAgentName }: AgentPickerProps) {
  const { t } = useTranslation();
  const identity = useAccountIdentity();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [request, setRequest] = useState({ q: '', page: 1 });
  const input = useRef<HTMLInputElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const timer = window.setTimeout(() => setRequest(current => current.q === search.trim() ? current : { q: search.trim(), page: 1 }), 200);
    return () => window.clearTimeout(timer);
  }, [search]);
  const options = useQuery({ queryKey: ['advisor', 'agent-picker', identity?.userId, request.q, request.page],
    queryFn: ({ signal }) => pageAvailableAgents(request.q, request.page, 5, signal),
    enabled: open, retry: false, staleTime: 0, gcTime: 0 });
  const selection = useQuery({ queryKey: ['advisor', 'agent-picker-selection', identity?.userId, value],
    queryFn: () => getAvailableAgent(value!), enabled: value !== null, retry: false, staleTime: 0 });
  const selected = selection.data;
  const active = useQuery({ queryKey: ['advisor', 'agent-picker-selection', identity?.userId, activeAgentId],
    queryFn: () => getAvailableAgent(activeAgentId!),
    enabled: !!running && !!activeAgentId && activeAgentId !== 'basic-chat' && !activeAgentName,
    retry: false, staleTime: 0 });
  useEffect(() => {
    const element = panel.current;
    if (!element || !open) return;
    const wheel = (event: WheelEvent) => {
      if (event.ctrlKey || event.metaKey || event.deltaY === 0) return;
      event.preventDefault(); event.stopPropagation();
      if (options.isFetching || search.trim() !== request.q) return;
      const next = Math.max(1, Math.min(options.data?.totalPages || 1, request.page + (event.deltaY > 0 ? 1 : -1)));
      if (next !== request.page) setRequest(current => ({ ...current, page: next }));
    };
    let touchY: number | null = null;
    const startTouch = (event: TouchEvent) => { touchY = event.touches[0]?.clientY ?? null; };
    const moveTouch = (event: TouchEvent) => {
      const current = event.touches[0]?.clientY;
      if (touchY === null || current === undefined) return;
      event.preventDefault(); event.stopPropagation();
      if (Math.abs(current - touchY) < 24 || options.isFetching || search.trim() !== request.q) return;
      const next = Math.max(1, Math.min(options.data?.totalPages || 1, request.page + (current < touchY ? 1 : -1)));
      touchY = null;
      if (next !== request.page) setRequest(currentRequest => ({ ...currentRequest, page: next }));
    };
    element.addEventListener('wheel', wheel, { passive: false });
    element.addEventListener('touchstart', startTouch, { passive: true });
    element.addEventListener('touchmove', moveTouch, { passive: false });
    return () => {
      element.removeEventListener('wheel', wheel);
      element.removeEventListener('touchstart', startTouch);
      element.removeEventListener('touchmove', moveTouch);
    };
  }, [open, options.isFetching, options.data?.totalPages, request, search]);
  const name = value === null ? t('agentPicker.free') : selected?.name
    ?? (value === activeAgentId && activeAgentName ? activeAgentName : t('agentPicker.unavailable'));
  const shownName = value && selection.isSuccess && !selected ? t('agentPicker.unavailable') : name;
  const activeName = activeAgentName || active.data?.name || (value === activeAgentId ? selected?.name : null)
    || (activeAgentId && activeAgentId !== 'basic-chat' ? t('agentPicker.unavailable') : t('agentPicker.free'));
  const results = options.data?.items ?? [];
  const choose = (id: string | null) => { onChange(id); setOpen(false); setSearch(''); setRequest({ q: '', page: 1 }); };
  const optionClass = 'flex min-h-11 w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-left text-xs hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';
  return <div className="min-w-0">
    <Popover.Root open={open} onOpenChange={next => { setOpen(next); if (!next) { setSearch(''); setRequest({ q: '', page: 1 }); } }}>
      <Popover.Trigger asChild>
        <Button variant="ghost" size="sm" disabled={disabled} aria-label={t('agentPicker.choose') + ': ' + shownName}
          className="h-9 min-w-0 max-w-[180px] gap-1.5 px-2 text-xs font-normal text-muted-foreground">
          {value ? <LockKeyhole aria-hidden className="h-3.5 w-3.5 shrink-0" /> : <Bot aria-hidden className="h-3.5 w-3.5 shrink-0" />}
          <span className="truncate">{shownName}</span><ChevronDown aria-hidden className="h-3 w-3 shrink-0" />
        </Button>
      </Popover.Trigger>
      <Popover.Portal><Popover.Content ref={panel} side="top" align="end" sideOffset={10} collisionPadding={12}
        aria-label={t('agentPicker.choose')} className="z-[60] flex max-h-[min(340px,var(--radix-popover-content-available-height))] w-80 max-w-[calc(100vw-24px)] flex-col overflow-hidden overscroll-contain rounded-xl border border-border bg-white p-2 shadow-lg"
        onKeyDown={event => {
          if (!['PageDown', 'PageUp'].includes(event.key)) return;
          event.preventDefault(); event.stopPropagation();
          if (options.isFetching || search.trim() !== request.q) return;
          const next = Math.max(1, Math.min(options.data?.totalPages || 1, request.page + (event.key === 'PageDown' ? 1 : -1)));
          if (next !== request.page) setRequest(current => ({ ...current, page: next }));
        }}
        onOpenAutoFocus={event => { event.preventDefault(); input.current?.focus(); }}>
        <Input ref={input} aria-label={t('agentPicker.search')} placeholder={t('agentPicker.search')} value={search}
          onChange={event => setSearch(event.target.value)} className="mb-2 h-9 shrink-0 text-xs" />
        <div role="listbox" aria-label={t('agentPicker.choose')} className="min-h-0 max-h-[216px] space-y-1 overflow-y-auto overscroll-contain"
          onKeyDown={event => {
            if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
            const items = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="option"]')];
            const current = items.indexOf(document.activeElement as HTMLButtonElement);
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
            event.preventDefault(); items[next]?.focus();
          }}>
          <button type="button" role="option" aria-selected={value === null} onClick={() => choose(null)} className={optionClass}>
            <span className="min-w-0 space-y-0.5"><span className="block text-[11.5px] font-normal leading-4 text-foreground">{t('agentPicker.free')}</span><span className="block text-[11px] leading-4 text-muted-foreground">{t('agentPicker.freeHelp')}</span></span>
            {value === null ? <Check aria-hidden className="h-4 w-4 shrink-0" /> : null}
          </button>
          {options.isFetching ? <p role="status" className="p-3 text-xs text-muted-foreground">{t('shell.loading')}</p> : null}
          {options.isError ? <div role="alert" className="p-3 text-xs text-destructive"><p>{t('agentPicker.error')}</p><Button variant="ghost" size="sm" onClick={() => void options.refetch()}>{t('agentPicker.retry')}</Button></div> : null}
          {options.isSuccess && !options.isFetching && !results.length ? <p className="p-3 text-xs text-muted-foreground">{t('agentPicker.empty')}</p> : null}
          {!options.isFetching && search.trim() === request.q && results.map(agent => <button key={agent.agentId} type="button" role="option" aria-selected={value === agent.agentId}
            onClick={() => choose(agent.agentId)} className={cn(optionClass, value === agent.agentId && 'bg-accent')}>
            <span className="min-w-0 space-y-0.5"><span className="block truncate text-[11.5px] font-normal leading-4 text-foreground">{agent.name}</span><span className="block truncate text-[11px] leading-4 text-muted-foreground">{agent.description}</span></span>
            {value === agent.agentId ? <Check aria-hidden className="h-4 w-4 shrink-0" /> : null}
          </button>)}
        </div>
      </Popover.Content></Popover.Portal>
    </Popover.Root>
    {running && activeAgentId !== undefined && value !== activeAgentId ? <p role="status" className="max-w-64 truncate px-2 text-[11px] text-muted-foreground"
      title={t('agentPicker.pending', { current: activeName, next: shownName })}>{t('agentPicker.pending', { current: activeName, next: shownName })}</p> : null}
  </div>;
}
