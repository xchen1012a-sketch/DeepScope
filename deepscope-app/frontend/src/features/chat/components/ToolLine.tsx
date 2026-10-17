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

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ProcessStateIcon } from './ProcessStateIcon';
import { ProcessStatusLabel } from './ProcessStatusLabel';
import { ProcessDetails } from './ProcessDetails';
import { formatRunDuration } from '../conversationModel';
import type { ConversationBlock } from '../model/blocks';
import { useNowTicker } from './useNowTicker';
import { describeTool } from '../processPresentation';

export function ToolLine({ block, onInspect }: { block: ConversationBlock; onInspect?: (block: ConversationBlock, trigger: HTMLButtonElement) => void }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const tool = block.tool;
  const running = tool?.status === 'running';
  const now = useNowTicker(Boolean(running));
  if (!tool) return null;
  const description = describeTool(tool);
  const elapsed = running && tool.startedAt !== null && now >= tool.startedAt && now - tool.startedAt < 3_600_000 ? now - tool.startedAt : null;
  const duration = formatRunDuration(tool.durationMs ?? elapsed);
  return <div aria-busy={running} className="rounded-lg">
    <button type="button" aria-expanded={onInspect ? undefined : open} aria-haspopup={onInspect ? 'dialog' : undefined}
      onClick={event => onInspect ? onInspect(block, event.currentTarget) : setOpen(value => !value)}
      className="group flex min-h-9 w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-[13px] text-slate-500 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300">
      <ProcessStateIcon state={description.hasFailure ? 'failed' : tool.status} />
      <ProcessStatusLabel active={running}>{t(description.titleKey)}</ProcessStatusLabel>
      {description.target ? <span className="min-w-0 truncate text-[12.5px] text-slate-500">{description.target}</span> : null}
      <span className="flex-1" />
      {duration ? <span className="shrink-0 text-xs tabular-nums text-slate-400">{duration}</span> : null}
      <ChevronRight aria-hidden="true" className={cn("h-3.5 w-3.5 shrink-0 text-slate-300 opacity-40 transition-all group-hover:opacity-100 group-hover:text-slate-500", open && "rotate-90 opacity-100 text-slate-500")} />
    </button>
    {!onInspect && open ? <div className="pl-8 pr-2 py-2"><ProcessDetails block={block} /></div> : null}
  </div>;
}
