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

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { AppSelect } from '@/components/ui/select';
import { cn } from '@/lib/utils';

export interface PaginationProps {
  page: number;
  pageSize: number;
  total: number;
  onChange: (page: number) => void;
  pageSizeOptions?: number[];
  onPageSizeChange?: (pageSize: number) => void;
  className?: string;
}

export function Pagination({
  page,
  pageSize,
  total,
  onChange,
  pageSizeOptions = [10, 20, 50],
  onPageSizeChange,
  className,
}: PaginationProps) {
  const { t } = useTranslation();
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), totalPages);


  // Generate pagination items
  const pages: (number | string)[] = [];
  if (totalPages <= 7) {
    for (let i = 1; i <= totalPages; i++) {
      pages.push(i);
    }
  } else {
    pages.push(1);
    if (safePage > 3) {
      pages.push('dots-left');
    }
    const start = Math.max(2, safePage - 1);
    const end = Math.min(totalPages - 1, safePage + 1);
    for (let i = start; i <= end; i++) {
      pages.push(i);
    }
    if (safePage < totalPages - 2) {
      pages.push('dots-right');
    }
    pages.push(totalPages);
  }

  return (
    <div
      className={cn(
        'flex flex-col sm:flex-row items-center justify-between gap-3 border-t border-slate-100 bg-white px-4 py-3 sm:px-6',
        className,
      )}
      data-testid="pagination"
    >
      <div className="flex flex-wrap items-center gap-3 text-xs text-slate-500">
        <span>
          {t('pagination.info', {
            page: safePage,
            totalPages,
            total,
            defaultValue: `第 ${safePage} / ${totalPages} 页 · 共 ${total} 项`,
          })}
        </span>
        {onPageSizeChange ? (
          <div className="flex items-center gap-1">
            <AppSelect
              size="sm"
              value={String(pageSize)}
              onChange={(val) => {
                const next = Number(val);
                if (next > 0) onPageSizeChange(next);
              }}
              options={pageSizeOptions.map((opt) => ({
                value: String(opt),
                label: t('pagination.pageSizeOption', {
                  count: opt,
                  defaultValue: `${opt} 条/页`,
                }),
              }))}
              className="h-7 w-24 text-xs font-normal"
            />
          </div>
        ) : null}
      </div>
      <div className="flex items-center gap-1.5">
        <Button
          variant="outline"
          size="sm"
          className="h-8 px-2.5 text-xs text-slate-600 hover:text-slate-900 border-slate-200"
          disabled={safePage <= 1}
          onClick={() => onChange(safePage - 1)}
          aria-label={t('pagination.prev', '上一页')}
        >
          <ChevronLeft className="mr-1 h-3.5 w-3.5" />
          {t('pagination.prev', '上一页')}
        </Button>

        <div className="flex items-center gap-1">
          {pages.map((p) =>
            typeof p === 'string' ? (
              <span key={p} className="px-1.5 text-xs text-slate-400">
                …
              </span>
            ) : (
              <Button
                key={p}
                variant={p === safePage ? 'default' : 'ghost'}
                size="sm"
                className={cn(
                  'h-8 w-8 p-0 text-xs',
                  p === safePage
                    ? 'bg-primary text-primary-foreground font-medium shadow-none'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                )}
                onClick={() => onChange(p)}
                aria-current={p === safePage ? 'page' : undefined}
              >
                {p}
              </Button>
            ),
          )}
        </div>

        <Button
          variant="outline"
          size="sm"
          className="h-8 px-2.5 text-xs text-slate-600 hover:text-slate-900 border-slate-200"
          disabled={safePage >= totalPages}
          onClick={() => onChange(safePage + 1)}
          aria-label={t('pagination.next', '下一页')}
        >
          {t('pagination.next', '下一页')}
          <ChevronRight className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}
