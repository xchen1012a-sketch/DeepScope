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

import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { FileText, Sparkles, Terminal, Wrench } from 'lucide-react';
import type { SlashCommand } from '@/api/advisor';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

interface SlashCommandMenuProps {
  commands: SlashCommand[];
  highlightIndex: number;
  onSelect: (command: SlashCommand) => void;
  onHighlight: (index: number) => void;
}

export function SlashCommandMenu({
  commands,
  highlightIndex,
  onSelect,
  onHighlight,
}: SlashCommandMenuProps) {
  const { t } = useTranslation();
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const activeEl = list.children[highlightIndex] as HTMLElement | undefined;
    if (activeEl && typeof activeEl.scrollIntoView === 'function') {
      activeEl.scrollIntoView({ block: 'nearest' });
    }
  }, [highlightIndex]);

  return (
    <div
      data-testid="slash-command-menu"
      className="absolute bottom-full mb-2 left-0 right-0 z-50 rounded-xl border border-border bg-white shadow-xl overflow-hidden text-[12px] animate-in fade-in zoom-in-95 duration-100"
    >
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-slate-100 bg-slate-50/70 text-[11px] font-medium text-slate-500">
        <span>{t('chat.composer.slashMenu.title')}</span>
        <span className="text-[10.5px] text-slate-400">{t('chat.composer.slashMenu.hint')}</span>
      </div>

      <div ref={listRef} className="max-h-56 overflow-y-auto p-1 space-y-0.5">
        <TooltipProvider delayDuration={350}>
        {commands.length === 0 ? (
          <div className="py-4 text-center text-slate-400 text-[11.5px]">
            {t('chat.composer.slashMenu.noMatch')}
          </div>
        ) : (
          commands.map((cmd, index) => {
            const isHighlighted = highlightIndex === index;
            const isResource = cmd.targetType === 'skill' || cmd.targetType === 'tool' || cmd.targetType === 'mcp';
            const Icon =
              cmd.targetType === 'action'
                ? Terminal
                : cmd.targetType === 'skill'
                  ? Sparkles
                  : cmd.targetType === 'mcp' || cmd.targetType === 'tool'
                    ? Wrench
                    : FileText;

            const badgeStyle =
              cmd.targetType === 'action'
                ? 'bg-indigo-50 text-indigo-700'
                : cmd.targetType === 'skill'
                  ? 'bg-purple-50 text-purple-700'
                  : cmd.targetType === 'mcp' || cmd.targetType === 'tool'
                    ? 'bg-emerald-50 text-emerald-700'
                    : 'bg-amber-50 text-amber-700';

            return (
              <Tooltip key={cmd.code}>
                  <button
                    type="button"
                    data-testid={`slash-command-${cmd.code}`}
                    onMouseEnter={() => onHighlight(index)}
                    onClick={() => onSelect(cmd)}
                    className={`group flex w-full items-center justify-between rounded-lg px-2.5 py-1.5 text-left transition-colors ${
                      isHighlighted ? 'bg-indigo-50/80 text-indigo-900' : 'hover:bg-slate-50 text-slate-700'
                    }`}
                  >
                    <div className="grid min-w-0 flex-1 grid-cols-[auto_minmax(0,1fr)] items-center gap-2 pr-3">
                      <Icon className="h-3.5 w-3.5 shrink-0 text-slate-400 group-hover:text-indigo-600" />
                      <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-0.5">
                        {!isResource ? (
                          <span className="shrink-0 font-mono font-semibold text-indigo-600">/{cmd.code}</span>
                        ) : null}
                        <span className="max-w-full shrink-0 whitespace-normal break-all font-medium text-slate-800">{cmd.name}</span>
                        {cmd.description ? (
                          <TooltipTrigger asChild>
                            <span className="min-w-0 flex-1 basis-40 truncate text-[11px] text-slate-400">
                              {cmd.description}
                            </span>
                          </TooltipTrigger>
                        ) : null}
                      </div>
                    </div>
                    <span className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${badgeStyle}`}>
                      {t(`chat.composer.slashMenu.${cmd.targetType}`)}
                    </span>
                  </button>
                {cmd.description ? (
                  <TooltipContent side="top" className="max-h-64 max-w-md overflow-y-auto whitespace-pre-wrap break-words">
                    {cmd.description}
                  </TooltipContent>
                ) : null}
              </Tooltip>
            );
          })
        )}
        </TooltipProvider>
      </div>
    </div>
  );
}
