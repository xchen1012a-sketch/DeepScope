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

import React from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { TableActions, type TableAction } from './TableActions';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import { Pagination } from '@/components/Pagination';
import { cn } from '@/lib/utils';

export interface DataTableColumn<T> {
  id?: string;
  header: React.ReactNode;
  headerClassName?: string;
  cellClassName?: string;
  align?: 'left' | 'center' | 'right';
  cell: (item: T, index: number) => React.ReactNode;
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  data: T[];
  keyExtractor: (item: T, index: number) => string | number;
  renderRow?: (item: T, index: number) => React.ReactNode;
  isLoading?: boolean;
  error?: string;
  onRetry?: () => void;
  actions?: (item: T) => TableAction[];
  actionsAppearance?: 'button' | 'text';
  toolbarTitle?: string;
  toolbarActions?: React.ReactNode;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: React.ReactNode;
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
  pageSizeOptions?: number[];
  onPageSizeChange?: (pageSize: number) => void;
  className?: string;
  /** Fill a bounded parent; only table rows scroll, keeping pagination visible. */
  scrollable?: boolean;
}

export function DataTable<T>({
  columns,
  data,
  keyExtractor,
  renderRow,
  isLoading = false,
  error,
  onRetry,
  actions,
  actionsAppearance,
  toolbarTitle,
  toolbarActions,
  emptyTitle,
  emptyDescription,
  emptyAction,
  page,
  pageSize,
  total,
  onPageChange,
  pageSizeOptions = [10, 20, 50],
  onPageSizeChange,
  className,
  scrollable = false,
}: DataTableProps<T>) {
  const { t } = useTranslation();
  const resolvedColumns: DataTableColumn<T>[] = actions
    ? [...columns, { id: 'actions', header: t('table.actions'), align: 'right', cell: item => <TableActions actions={actions(item)} appearance={actionsAppearance} /> }]
    : columns;
  const columnClass = (col: DataTableColumn<T>, index: number) => cn(
    'px-3 py-3.5 align-middle',
    index === 0 && 'pl-4 sm:pl-6',
    index === resolvedColumns.length - 1 && 'pr-4 sm:pr-6',
    (col.align === 'right' || col.id === 'actions') && 'text-right',
    col.align === 'center' && 'text-center',
    col.id === 'actions' && 'w-px whitespace-nowrap',
  );

  return (
    <div
      className={cn(
        'overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm',
        scrollable && 'flex min-h-0 flex-1 flex-col',
        className,
      )}
      data-testid="data-table"
      aria-busy={isLoading || undefined}
    >
      {toolbarTitle || toolbarActions ? <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 p-4">
        <h2 className="text-sm font-semibold text-slate-800">{toolbarTitle}</h2>{toolbarActions}
      </div> : null}
      <div className={cn('overflow-x-auto', scrollable && 'min-h-0 flex-1 overflow-y-auto overscroll-contain')} tabIndex={scrollable ? 0 : undefined}>
        <table className="w-full text-left text-sm text-slate-600">
          <thead className={cn('border-b border-slate-100 bg-slate-50/75 text-xs font-semibold text-slate-500 uppercase tracking-wider', scrollable && 'sticky top-0 z-10 bg-slate-50')}>
            <tr>
              {resolvedColumns.map((col, index) => (
                <th
                  key={col.id ?? index}
                  scope="col"
                  className={cn(columnClass(col, index), col.headerClassName)}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading ? <tr><td colSpan={resolvedColumns.length} className="p-4 sm:p-6"><div className="space-y-3" data-testid="data-table-loading">{Array.from({ length: 4 }, (_, index) => <Skeleton key={index} className="h-12 w-full rounded-lg" />)}</div></td></tr>
              : error ? <tr><td colSpan={resolvedColumns.length} className="p-6"><div role="alert" className="flex min-h-40 flex-col items-center justify-center gap-3 text-sm text-red-700"><p>{error}</p>{onRetry ? <Button variant="outline" size="sm" onClick={onRetry}>{t('table.retry')}</Button> : null}</div></td></tr>
              : data.length === 0 ? <tr><td colSpan={resolvedColumns.length}><EmptyState title={emptyTitle ?? t('table.empty')} description={emptyDescription} action={emptyAction} className="rounded-none border-0 shadow-none" /></td></tr>
              : renderRow ? data.map((item, index) => renderRow(item, index))
              : data.map((item, index) => (
                <tr key={keyExtractor(item, index)} className="transition-colors hover:bg-slate-50/60">
                  {resolvedColumns.map((col, cIndex) => <td key={col.id ?? cIndex} className={cn(columnClass(col, cIndex), col.cellClassName)}>{col.cell(item, index)}</td>)}
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      {!isLoading && !error && total !== undefined && onPageChange && (
        <Pagination
          page={page ?? 1}
          pageSize={pageSize ?? 10}
          total={total}
          onChange={onPageChange}
          pageSizeOptions={pageSizeOptions}
          onPageSizeChange={onPageSizeChange}
          className={scrollable ? 'shrink-0' : undefined}
        />
      )}
    </div>
  );
}
