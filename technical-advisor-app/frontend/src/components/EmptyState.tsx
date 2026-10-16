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

import { cn } from '@/lib/utils';

/** 空状态统一用的素描图形：线稿，不填色。 */
function SketchEmpty({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 72 72"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M16 24c1-6 6-9 12-8h16c7 0 12 4 13 10l5 6v22c0 4-3 7-8 7H22c-5 0-8-3-8-8V28c0-2 .5-3 2-4z" />
      <path d="M46 16v10h10" />
      <path d="M26 40c6 1 10 0 16 1" />
      <path d="M26 48c4 1 7 0 11 1" />
    </svg>
  );
}

export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex min-h-56 flex-col items-center justify-center rounded-2xl border border-slate-200/90 bg-white px-6 py-12 text-center shadow-[0_1px_2px_rgba(15,23,42,0.035)]',
        className,
      )}
    >
      <SketchEmpty className="h-16 w-16 text-slate-400" />
      <h3 className="mt-4 text-sm font-semibold text-slate-900">{title}</h3>
      {description && (
        <p className="mt-1 max-w-md text-sm leading-6 text-slate-500">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
