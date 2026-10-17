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

import { useContext, useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { ProcessMotionContext } from './processMotion';
import { ProcessStatusLabel } from './ProcessStatusLabel';
import { describeTool } from '../processPresentation';
import { ProcessStateIcon } from './ProcessStateIcon';
import { cn } from '@/lib/utils';
import type { ConversationBlock } from '../model/blocks';

/** A live batch is independent of the completed turn's outer disclosure. */
export function ActionBatch({ blocks, highlight, children, open, onOpenChange }: {
  blocks: ConversationBlock[]; highlight: string | null; children: ReactNode;
  open: boolean; onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const motion = useContext(ProcessMotionContext);
  const categories = [...new Set(blocks.map(block => block.kind === 'dispatch' ? 'spawn' : block.tool ? describeTool(block.tool).action : 'other'))];
  const located = blocks.some(block => block.anchor === highlight);
  useEffect(() => { if (located && !open) onOpenChange(true); }, [located, open, onOpenChange]);
  const multiple = blocks.length > 1;
  const expanded = !multiple || open || located;
  const running = blocks.some(block => block.status === 'running' || block.tool?.status === 'running');
  const activeBlock = running ? [...blocks].reverse().find(block => block.status === 'running' || block.tool?.status === 'running') : undefined;
  let activeTicker: string | null = null;
  if (activeBlock) {
    if (activeBlock.tool) {
      const desc = describeTool(activeBlock.tool);
      activeTicker = desc.target ? `${t(desc.titleKey)}: ${desc.target}` : t(desc.titleKey);
    } else if (activeBlock.kind === 'dispatch') {
      activeTicker = activeBlock.summary ? `${t('chat.activity.dispatched')}: ${activeBlock.summary}` : t('chat.activity.dispatched');
    } else if (activeBlock.kind === 'thinking') {
      activeTicker = activeBlock.summary || t('chat.activity.analyzing');
    }
  }

  return <div className="space-y-1" data-action-batch onClickCapture={event => {
    const button = (event.target as HTMLElement).closest('button');
    if (button && !button.hasAttribute('data-batch-trigger')) onOpenChange(true);
  }}>
    {multiple ? <button type="button" data-batch-trigger aria-expanded={expanded} onClick={() => onOpenChange(!expanded)}
      className="flex min-h-9 w-full items-center gap-2 rounded-lg border border-slate-200/60 bg-slate-50/70 px-2.5 py-1.5 text-left text-[13px] text-slate-600 transition-colors hover:bg-slate-100/70 hover:border-slate-300/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300">
      <ProcessStateIcon state={running ? 'running' : 'done'} />
      <div className="flex min-w-0 flex-1 items-center gap-2 overflow-hidden">
        <ProcessStatusLabel active={running}>{categories.map(action => t(`chat.activity.batchCategories.${action}`)).join(' · ')} · {t('chat.flow.batchOperations', { count: blocks.length })}</ProcessStatusLabel>
        {running && activeTicker ? (
          <span className="truncate rounded-md bg-indigo-50 px-1.5 py-0.5 text-xs font-medium text-indigo-600 animate-pulse">
            {activeTicker}
          </span>
        ) : null}
      </div>
      {running ? <span className="shrink-0 text-xs text-indigo-500 font-medium">{t('chat.flow.batchRunning')}</span> : null}
      <ChevronRight aria-hidden="true" className={cn("h-3.5 w-3.5 shrink-0 text-slate-400 transition-transform duration-200", expanded && "rotate-90 text-slate-600")} />
    </button> : null}
    {expanded ? <ProcessMotionContext.Provider value={multiple ? false : motion}><div className="space-y-1">{children}</div></ProcessMotionContext.Provider> : null}
  </div>;
}
