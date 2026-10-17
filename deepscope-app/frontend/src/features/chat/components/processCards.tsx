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

import type { ComponentType } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { SessionStatus } from '@/api/advisor';

/* ------------------------------------------------------------------ 状态徽标 */

const STATUS_TONE: Record<SessionStatus, string> = {
  idle: 'bg-slate-300',
  running: 'bg-indigo-500 animate-pulse',
  awaiting_visitor: 'bg-amber-500',
  failed: 'bg-red-500',
  interrupted: 'bg-slate-400',
};

export function StatusDot({ status }: { status: SessionStatus }) {
  return <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', STATUS_TONE[status] ?? 'bg-slate-300')} />;
}

/* -------------------------------------------------------------------- 卡片骨架 */

function Card({
  icon: Icon,
  tone,
  label,
  className,
  children,
}: {
  icon: ComponentType<{ className?: string }>;
  tone: string;
  label: string;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className={cn('rounded-xl border bg-white px-3.5 py-2.5', className)}>
      <div className="flex items-center gap-2">
        <Icon className={cn('h-3.5 w-3.5 shrink-0', tone)} />
        <span className="text-[12.5px] font-medium text-slate-700">{label}</span>
      </div>
      {children ? <div className="mt-1.5 text-[13px] leading-relaxed text-slate-600">{children}</div> : null}
    </div>
  );
}

/* ---------------------------------------------------------------------- 出错 */

export function ErrorCard({ text }: { text: string }) {
  const { t } = useTranslation();
  return (
    <Card icon={AlertTriangle} tone="text-red-600" label={t('chat.error')} className="border-red-200 bg-red-50/60">
      <p className="whitespace-pre-wrap break-all text-red-800">{text}</p>
    </Card>
  );
}
