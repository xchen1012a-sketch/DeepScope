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

import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ActionBatch } from './ActionBatch';
import { ProcessMotionContext } from './processMotion';
import { toolPresentation } from '../toolPresentation';
import { processDurationParts } from '../conversationModel';
import type { ConversationBlock, TurnProjection } from '../model/blocks';

export function BlockFrame({ block, highlight, children }: {
  block: ConversationBlock; highlight: string | null; children: ReactNode;
}) {
  return <div data-anchor={block.anchor} className={cn(highlight === block.anchor && 'rounded-xl ring-2 ring-indigo-300')}>{children}</div>;
}

/** One disclosure for the whole process, preserving the ordered record and the final answer. */
export function TurnGroup({ turn, forcedOpen, highlight, renderBlock, activityEnabled = true }: {
  activityEnabled?: boolean;
  turn: TurnProjection; forcedOpen: boolean; highlight: string | null;
  renderBlock: (block: ConversationBlock) => ReactNode;
}) {
  const { t } = useTranslation();
  const [manual, setManual] = useState<boolean | null>(null);
  const [expandedBatches, setExpandedBatches] = useState<Set<string>>(() => new Set());
  const setBatchOpen = (anchor: string, open: boolean) => setExpandedBatches(current => {
    if (current.has(anchor) === open) return current;
    const next = new Set(current);
    if (open) next.add(anchor); else next.delete(anchor);
    return next;
  });
  const located = turn.process.some(block => block.anchor === highlight);
  useEffect(() => { if (located || forcedOpen) setManual(true); }, [located, forcedOpen]);
  const open = manual ?? !turn.collapsedByDefault;
  const collapsible = turn.collapsedByDefault && turn.process.length > 0;
  const duration = processDurationParts(turn.durationMs);
  const durationText = duration === null ? null : duration.belowSecond ? t('chat.flow.durationUnderSecond')
    : [duration.hours > 0 ? t('chat.flow.durationHours', { count: duration.hours }) : null,
      duration.minutes > 0 ? t('chat.flow.durationMinutes', { count: duration.minutes }) : null,
      duration.seconds > 0 ? t('chat.flow.durationSeconds', { count: duration.seconds }) : null].filter(Boolean).join(' ');
  const trigger = <button type="button" data-process-trigger="true" aria-expanded={open}
    onClick={() => setManual(!open)}
    className="flex min-h-9 items-center gap-2 rounded-lg px-1 text-left text-[13px] text-slate-500 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300">
    <span>{durationText ? t(turn.durationKind === 'total' ? 'chat.flow.totalElapsed' : 'chat.flow.elapsed', { duration: durationText }) : t('chat.flow.viewProcess')}</span>
    {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
  </button>;
  const finalBlocks = turn.finalBlocks ?? (turn.finalText ? [turn.finalText] : []);
  const blocks = turn.ordered ?? [...turn.process, ...finalBlocks];
  const processAnchors = new Set(turn.process.map(block => block.anchor));
  const isAction = (block: ConversationBlock) => block.kind === 'tool_call' || block.kind === 'dispatch';
  const groups: ConversationBlock[][] = [];
  for (const block of blocks) {
    const last = groups[groups.length - 1];
    if (isAction(block) && last?.every(isAction)) last.push(block);
    else groups.push([block]);
  }
  const focus = activityEnabled ? [...groups].reverse().find(group => group.some(item => item.status === 'running' || item.tool?.status === 'running'))?.[0].anchor : undefined;
  let inserted = false;
  return <div className="space-y-3" data-turn-group={turn.turn}>
    {turn.completionNotice === 'iteration_limit' ? <p role="status" className="text-xs text-amber-700">{t('chat.flow.iterationLimit')}</p> : null}
    {groups.map((group, index) => {
      const block = group[0];
      const process = processAnchors.has(block.anchor);
      const first = process && !inserted;
      if (process) inserted = true;
      return <ProcessMotionContext.Provider key={block.anchor} value={block.anchor === focus}><div className={cn(process && collapsible && !open && !first && 'hidden')}>
        {first && collapsible ? trigger : null}
        {!process || !collapsible || open ? <div className="space-y-1">
          {isAction(block) && groups[index - 1]?.[0].kind !== 'advisor_message' ? <p data-process-description className="py-1 text-[13px] leading-6 text-slate-500">{
            [...new Set(group.map(item => item.kind === 'dispatch' ? 'spawn' : item.tool ? toolPresentation(item.tool.name, item.tool.args ?? '').action : undefined))]
              .map(action => t(`chat.flow.stepActions.${action ?? 'other'}`)).join(' · ')
          }</p> : null}
          {isAction(block) ? <ActionBatch blocks={group} highlight={highlight} open={expandedBatches.has(block.anchor)} onOpenChange={value => setBatchOpen(block.anchor, value)}>
            {group.map(item => <BlockFrame key={item.anchor} block={item} highlight={highlight}>{renderBlock(item)}</BlockFrame>)}
          </ActionBatch> : group.map(item => <BlockFrame key={item.anchor} block={item} highlight={highlight}>{renderBlock(item)}</BlockFrame>)}
        </div> : null}
      </div></ProcessMotionContext.Provider>;
    })}
  </div>;
}
