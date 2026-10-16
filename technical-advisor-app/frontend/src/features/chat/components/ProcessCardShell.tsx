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

import type { ComponentType, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '@/lib/utils';

/** 卡片状态：子智能体与过程块共用一套状态词条。 */
export type ProcessCardStatus = 'running' | 'done' | 'failed' | 'timeout' | 'empty';

const STATUS_TONE: Record<ProcessCardStatus, string> = {
  running: 'bg-indigo-50 text-indigo-600',
  done: 'bg-slate-100 text-slate-500',
  failed: 'bg-red-50 text-red-600',
  timeout: 'bg-amber-50 text-amber-700',
  empty: 'bg-amber-50 text-amber-700',
};

/** 卡片上的状态小标；进行中自带一个脉冲点。 */
export function StatusChip({ status }: { status: ProcessCardStatus }) {
  const { t } = useTranslation();
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10.5px] font-medium',
        STATUS_TONE[status],
      )}
    >
      {status === 'running' ? <span className="h-1 w-1 animate-pulse rounded-full bg-indigo-500" /> : null}
      {t(`chat.settledStatus.${status}`)}
    </span>
  );
}

/**
 * 时间线卡片与子智能体卡片共用的外壳：同样的圆角、内边距、字号与图标行——
 * 左图标 + 标题 + 状态，右侧时间，下面一行摘要。
 */
export function ProcessCardShell({
  icon: Icon,
  tone,
  title,
  status,
  right,
  summary,
  onClick,
}: {
  icon: ComponentType<{ className?: string }>;
  tone: string;
  title: string;
  status?: ReactNode;
  right?: string;
  summary?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full rounded-xl border border-border/60 bg-white px-3 py-3 text-left shadow-sm transition-all duration-200 ease-out hover:-translate-y-0.5 hover:border-slate-300/80 hover:bg-slate-50/80 hover:shadow-md active:translate-y-0 active:scale-[0.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-center gap-2">
        <Icon className={cn('h-3.5 w-3.5 shrink-0', tone)} />
        <span className="min-w-0 truncate text-[12.5px] font-medium text-slate-700">{title}</span>
        {status}
        {right ? <span className="ml-auto shrink-0 font-mono text-[11px] text-slate-400">{right}</span> : null}
      </div>
      {summary ? <p className="mt-1.5 line-clamp-1 text-[12.5px] leading-relaxed text-slate-500">{summary}</p> : null}
    </button>
  );
}
