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

import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Search } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/** Controlled draft search; request/cache/pagination ownership stays in the feature. */
export function TableToolbar({ search, onSearchChange, onSearch, placeholder, filters, actions, className, searchLabel, onReset }: {
  search: string; onSearchChange: (value: string) => void; onSearch: () => void;
  placeholder: string; filters?: ReactNode; actions?: ReactNode; className?: string;
  searchLabel?: string; onReset?: () => void;
}) {
  const { t } = useTranslation();
  if (searchLabel) return <form role="search" onSubmit={event => { event.preventDefault(); onSearch(); }}
    className={cn('space-y-4 rounded-xl border border-border bg-white p-4', className)}>
    <div className="flex flex-wrap items-start gap-3 [&>*]:w-full sm:[&>*]:w-52">
      <label className="flex items-center gap-2 text-xs text-slate-600 sm:!w-auto">
        <span className="shrink-0">{searchLabel}</span>
        <Input type="search" className="h-9 w-40 text-sm" aria-label={searchLabel} placeholder={placeholder} value={search} onChange={event => onSearchChange(event.target.value)} />
      </label>
      {filters}
    </div>
    <div className="flex flex-wrap items-center justify-end gap-3">
      <div className="flex gap-2">
        <Button type="submit" size="sm">{t('access.search')}</Button>
        {onReset ? <Button type="button" size="sm" variant="outline" onClick={onReset}>{t('access.reset')}</Button> : null}
      </div>
      {actions}
    </div>
  </form>;
  return <div className={cn("flex w-full flex-wrap items-center justify-between gap-3", className)}>
    <div className="flex min-w-0 flex-1 flex-wrap items-center gap-3">
      <form role="search" className="flex w-full items-center gap-2 sm:w-auto" onSubmit={event => { event.preventDefault(); onSearch(); }}>
        <div className="relative min-w-0 flex-1 sm:w-56">
          <Search aria-hidden className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input type="search" aria-label={placeholder} placeholder={placeholder} value={search} onChange={event => onSearchChange(event.target.value)} className="pl-9 text-sm" />
        </div>
        <Button type="submit" variant="outline">{t('table.search')}</Button>
      </form>
      {filters}
    </div>
    {actions}
  </div>;
}
