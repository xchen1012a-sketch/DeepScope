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

import { useTranslation } from 'react-i18next';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Loader2, MoreHorizontal, type LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

export interface TableAction {
  id: string;
  label: string;
  icon?: LucideIcon;
  iconOnly?: boolean;
  tone?: 'default' | 'danger' | 'warning';
  hidden?: boolean;
  disabled?: boolean;
  loading?: boolean;
  overflow?: boolean;
  onClick: () => void;
}

/** The shared row-action format. Business permissions and confirmation flows stay with callers. */
export function TableActions({ actions, label, appearance = 'button' }: { actions: TableAction[]; label?: string; appearance?: 'button' | 'text' }) {
  const { t } = useTranslation();
  const visible = actions.filter(action => !action.hidden);
  const ordered = [...visible.filter(action => action.tone !== 'danger'), ...visible.filter(action => action.tone === 'danger')];
  const inline = ordered.filter(action => !action.overflow);
  const overflow = ordered.filter(action => action.overflow);
  const busy = visible.some(action => action.loading);
  const tones = {
    default: 'text-slate-700 hover:text-slate-900',
    danger: 'border-red-200 text-red-600 hover:border-red-300 hover:bg-red-50 hover:text-red-700',
    warning: 'border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100',
  };
  const renderButton = (action: TableAction) => {
    const Icon = action.loading ? Loader2 : action.icon;
    return <Button key={action.id} type="button" size="sm" variant={appearance === 'text' ? 'link' : 'outline'}
      className={cn('h-8 gap-1.5 px-2.5 text-xs shadow-none', appearance === 'button' && action.iconOnly && 'w-8 px-0', appearance === 'button' ? tones[action.tone ?? 'default'] : action.tone === 'danger' ? 'px-1 text-red-600 hover:text-red-700' : 'px-1 text-primary')}
      aria-label={action.label} title={action.iconOnly ? action.label : undefined} aria-busy={action.loading || undefined}
      disabled={busy || action.disabled} onClick={action.onClick}>
      {Icon && (appearance === 'button' || action.loading) ? <Icon aria-hidden className={cn('h-3.5 w-3.5 shrink-0', action.loading && 'animate-spin motion-reduce:animate-none')} /> : null}
      {appearance === 'button' && action.iconOnly && Icon ? null : action.label}
    </Button>;
  };
  if (visible.length === 0) return null;
  return <div className="flex items-center justify-end gap-1.5 whitespace-nowrap" role="group" aria-label={label ?? t('table.actions')}>
    {inline.filter(action => action.tone !== 'danger').map(renderButton)}
    {appearance === 'text' ? inline.filter(action => action.tone === 'danger').map(renderButton) : null}
    {overflow.length ? <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild><Button type="button" size="sm" variant={appearance === 'text' ? 'link' : 'outline'} className={cn('h-8 px-0 shadow-none', appearance === 'button' ? 'w-8' : 'px-1')} disabled={busy} aria-busy={busy || undefined} aria-label={t('table.moreActions')} title={t('table.moreActions')}>{busy ? <Loader2 aria-hidden className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /> : appearance === 'text' ? t('table.more') : <MoreHorizontal aria-hidden className="h-3.5 w-3.5" />}</Button></DropdownMenu.Trigger>
      <DropdownMenu.Portal><DropdownMenu.Content align="end" sideOffset={6} className="z-[60] min-w-36 rounded-xl border border-border bg-white p-1 shadow-lg">
        {overflow.map(action => {
          const Icon = action.icon;
          return <DropdownMenu.Item key={action.id} disabled={action.disabled || busy} onSelect={action.onClick}
            className={cn('flex cursor-pointer items-center gap-2 rounded-md px-3 py-2 text-sm outline-none data-[highlighted]:bg-slate-100 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-40', action.tone === 'danger' && 'text-red-600', action.tone === 'warning' && 'text-amber-800')}>
            {Icon ? <Icon aria-hidden className="h-3.5 w-3.5 shrink-0" /> : null}{action.label}
          </DropdownMenu.Item>;
        })}
      </DropdownMenu.Content></DropdownMenu.Portal>
    </DropdownMenu.Root> : null}
    {appearance === 'button' ? inline.filter(action => action.tone === 'danger').map(renderButton) : null}
  </div>;
}
