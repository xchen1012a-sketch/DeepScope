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

import { useContext, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight } from 'lucide-react';
import { ProcessStateIcon } from './ProcessStateIcon';
import { ProcessStatusLabel } from './ProcessStatusLabel';
import { ProcessText } from './ProcessText';
import { ProcessMotionContext } from './processMotion';
import { cn } from '@/lib/utils';

function ThinkingPreview({ text, animate }: { text: string; animate: boolean }) {
  const trimmed = text.trimEnd();
  const lineStart = trimmed.lastIndexOf('\n') + 1;
  const preview = trimmed.slice(lineStart).trim().replace(/^#{1,6}\s+/, '').replace(/\*\*/g, '');
  const scrollRef = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (node) node.scrollLeft = node.scrollWidth;
  }, [preview, lineStart]);
  return <span className="flex h-6 min-w-0 flex-1 items-center gap-2 overflow-hidden text-slate-500">
    <span aria-hidden="true" className="shrink-0">·</span>
    <span key={lineStart} ref={scrollRef} data-thinking-preview className={cn('thinking-preview-text block min-w-0 flex-1 overflow-hidden whitespace-nowrap', animate && 'thinking-preview-live')}>{preview}</span>
  </span>;
}

export function ThinkingLine({ text, streaming, index }: {
  text: string; streaming: boolean; index?: number; onInspect?: (trigger: HTMLButtonElement) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const motionEnabled = useContext(ProcessMotionContext);
  if (!text.trim()) return null;
  return <div aria-busy={streaming}>
    <button type="button" aria-expanded={open}
      onClick={() => setOpen(value => !value)}
      className="flex w-full items-center gap-2 min-h-9 rounded-lg px-2 py-1.5 text-left text-[13px] text-slate-500 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300">
      {index !== undefined ? <span className="w-6 shrink-0 text-right font-mono text-[10.5px] text-slate-300">{index}</span> : null}
      <ProcessStateIcon kind="thinking" state={streaming ? 'running' : 'done'} />
      <span className="shrink-0 whitespace-nowrap"><ProcessStatusLabel active={streaming}>{t(streaming ? 'chat.activity.analyzing' : 'chat.activity.analysisRecord')}</ProcessStatusLabel></span>
      {!open && streaming ? <ThinkingPreview text={text} animate={motionEnabled} /> : null}
      <ChevronRight aria-hidden="true" className={cn('h-3.5 w-3.5 shrink-0 text-slate-400', open && 'rotate-90')} />
    </button>
    {open ? <div className="pl-8 pr-2 py-2"><ProcessText text={text} live={streaming} continuous /></div> : null}
  </div>;
}
