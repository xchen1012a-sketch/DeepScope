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
import { CircleHelp } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

/** Existing tooltip primitive keeps explanatory help available by hover and keyboard focus. */
export function KnowledgeHelp({ label, children }: { label: string; children: ReactNode }) {
  return <TooltipProvider><Tooltip><TooltipTrigger asChild>
    <button type="button" aria-label={label} className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded text-slate-400 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <CircleHelp aria-hidden className="h-3.5 w-3.5" />
    </button>
  </TooltipTrigger><TooltipContent>{children}</TooltipContent></Tooltip></TooltipProvider>;
}
