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

import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import * as Tabs from '@radix-ui/react-tabs';
import { ArrowLeft, ListTree, PanelRightClose, PanelRightOpen } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { ProcessPanel } from '../ProcessPanel';
import { ProcessDetails } from './ProcessDetails';
import type { ConversationBlock, TurnProjection } from '../model/blocks';
import type { SubagentRecord } from '../useAdvisorConversation';
import { cn } from '@/lib/utils';

export interface ProcessDrawerProps {
  selectedBlock?: ConversationBlock;
  onBack?: () => void;
  onCloseAutoFocus?: (event: Event) => void;
  sessionId?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  turns: TurnProjection[];
  subagents: SubagentRecord[];
  onLocate: (anchor: string) => void;
}

export function ProcessDrawerTrigger({
  open,
  onClick,
  triggerRef,
}: {
  open: boolean;
  onClick: () => void;
  triggerRef?: React.Ref<HTMLButtonElement>;
}) {
  const { t } = useTranslation();
  return (
    <button
      ref={triggerRef}
      type="button"
      aria-label={t(open ? 'chat.process.hide' : 'chat.process.show')}
      aria-expanded={open}
      onClick={onClick}
      className={cn(
        'group inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-lg px-2.5 text-[12.5px] transition-all duration-200 ease-out active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring cursor-pointer',
        open
          ? 'bg-indigo-50 text-indigo-700 font-medium shadow-2xs'
          : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800',
      )}
    >
      {open ? <PanelRightClose aria-hidden className="h-4 w-4" /> : <PanelRightOpen aria-hidden className="h-4 w-4" />}
    </button>
  );
}

function maximumPanelWidth() {
  return typeof window === 'undefined' ? 768 : Math.max(0, Math.min(window.innerWidth - 48, window.innerWidth * 0.75));
}

function clampPanelWidth(width: number) {
  const maximum = maximumPanelWidth();
  return Math.max(Math.min(280, maximum), Math.min(maximum, width));
}

export function ProcessSidePanel({
  selectedBlock,
  onBack,
  onClose,
  onCloseAutoFocus,
  sessionId,
  turns,
  subagents,
  onLocate,
  panelRef,
}: {
  selectedBlock?: ConversationBlock;
  onBack?: () => void;
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
  sessionId?: string;
  turns: TurnProjection[];
  subagents: SubagentRecord[];
  onLocate: (anchor: string) => void;
  panelRef?: React.Ref<HTMLElement>;
}) {
  const { t } = useTranslation();
  const focusTimeline = useRef(false);
  const [width, setWidth] = useState(() => clampPanelWidth(420));
  const dragStart = useRef<{ x: number; width: number } | null>(null);
  const resize = (next: number) => setWidth(clampPanelWidth(next));
  useEffect(() => {
    const clamp = () => setWidth(current => clampPanelWidth(current));
    window.addEventListener('resize', clamp);
    return () => window.removeEventListener('resize', clamp);
  }, []);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        onCloseAutoFocus?.(e);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose, onCloseAutoFocus]);

  const tabClass =
    'rounded-lg px-3 py-1.5 text-xs font-medium text-slate-500 transition-all duration-200 ease-out hover:text-slate-700 data-[state=active]:bg-white data-[state=active]:shadow-2xs data-[state=active]:font-semibold data-[state=active]:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

  return (
    <aside
      ref={panelRef}
      role="dialog"
      aria-label={t('chat.process.title')}
      tabIndex={-1}
      style={{ width }}
      className="process-side-panel relative flex h-full max-w-[calc(100vw-3rem)] shrink-0 flex-col border-l border-slate-200/80 bg-white min-h-0 z-10 outline-none"
    >
      <div role="separator" aria-label={t('chat.resizeProcess')} aria-orientation="vertical" tabIndex={0}
        aria-valuemin={Math.min(280, maximumPanelWidth())} aria-valuemax={Math.round(maximumPanelWidth())} aria-valuenow={Math.round(width)}
        className="absolute inset-y-0 -left-1 z-20 w-2 cursor-col-resize touch-none hover:bg-indigo-100 focus-visible:bg-indigo-100 focus-visible:outline-none"
        onPointerDown={event => { dragStart.current = { x: event.clientX, width }; event.currentTarget.setPointerCapture(event.pointerId); event.preventDefault(); }}
        onPointerMove={event => { if (dragStart.current) resize(dragStart.current.width + dragStart.current.x - event.clientX); }}
        onPointerUp={event => { dragStart.current = null; event.currentTarget.releasePointerCapture(event.pointerId); }}
        onPointerCancel={() => { dragStart.current = null; }}
        onLostPointerCapture={() => { dragStart.current = null; }}
        onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); resize(width + (event.key === 'ArrowLeft' ? 24 : -24)); } }} />
      <div className="flex h-16 shrink-0 items-center justify-between border-b border-border/70 px-5 pr-24 sm:pr-28">
        <div className="flex items-center gap-2 min-w-0">
          <ListTree className="h-4 w-4 text-indigo-600 shrink-0" />
          <h2 className="text-[14px] font-semibold text-slate-900 truncate">
            {t('chat.process.title')}
          </h2>
        </div>
      </div>

      {selectedBlock ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <button
            type="button"
            onClick={() => {
              focusTimeline.current = true;
              onBack?.();
            }}
            className="mb-4 flex min-h-9 items-center gap-1.5 rounded-lg px-2 text-xs font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            {t('chat.activity.back')}
          </button>
          <ProcessDetails key={selectedBlock.anchor} block={selectedBlock} />
        </div>
      ) : (
        <Tabs.Root defaultValue="trajectory" className="flex min-h-0 flex-1 flex-col pt-3">
          <div className="px-5 pb-3">
            <Tabs.List
              aria-label={t('chat.process.title')}
              className="inline-flex gap-1 rounded-xl bg-slate-100 p-1"
            >
              <Tabs.Trigger
                value="trajectory"
                className={tabClass}
                ref={(element) => {
                  if (element && focusTimeline.current) {
                    focusTimeline.current = false;
                    element.focus();
                  }
                }}
              >
                {t('chat.view.trajectory')}
              </Tabs.Trigger>
              <Tabs.Trigger
                value="subagents"
                className={cn(tabClass, 'relative')}
                aria-label={subagents.length > 0 ? `${t('chat.process.subagents')} ${subagents.length}` : undefined}
              >
                {t('chat.process.subagents')}
                {subagents.length > 0 ? (
                  <Badge tone="info" aria-hidden="true" className="absolute -right-1 -top-1 h-4 min-w-4 justify-center px-1 py-0 text-[10px] tabular-nums ring-2 ring-white">
                    {subagents.length}
                  </Badge>
                ) : null}
              </Tabs.Trigger>
            </Tabs.List>
          </div>
          <Tabs.Content
            value="trajectory"
            className="process-pane-enter flex min-h-0 flex-1 flex-col focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
          >
            <ProcessPanel
              pane="trajectory"
              sessionId={sessionId}
              turns={turns}
              subagents={subagents}
              onLocate={(anchor) => {
                onClose();
                onLocate(anchor);
              }}
            />
          </Tabs.Content>
          <Tabs.Content
            value="subagents"
            className="process-pane-enter flex min-h-0 flex-1 flex-col focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
          >
            <ProcessPanel
              pane="subagents"
              sessionId={sessionId}
              turns={turns}
              subagents={subagents}
              onLocate={onLocate}
            />
          </Tabs.Content>
        </Tabs.Root>
      )}
    </aside>
  );
}

export function ProcessDrawer({
  selectedBlock,
  onBack,
  onCloseAutoFocus,
  sessionId,
  open,
  onOpenChange,
  turns,
  subagents,
  onLocate,
}: ProcessDrawerProps) {
  const triggerRef = useRef<HTMLButtonElement | null>(null);

  return (
    <>
      <ProcessDrawerTrigger
        triggerRef={triggerRef}
        open={open}
        onClick={() => onOpenChange(!open)}
      />
      {open ? (
        <ProcessSidePanel
          selectedBlock={selectedBlock}
          onBack={onBack}
          onClose={() => {
            onOpenChange(false);
            if (triggerRef.current?.isConnected) {
              triggerRef.current.focus();
            }
          }}
          onCloseAutoFocus={(e) => {
            if (onCloseAutoFocus) {
              onCloseAutoFocus(e);
            } else if (triggerRef.current?.isConnected) {
              triggerRef.current.focus();
            }
          }}
          sessionId={sessionId}
          turns={turns}
          subagents={subagents}
          onLocate={onLocate}
        />
      ) : null}
    </>
  );
}
