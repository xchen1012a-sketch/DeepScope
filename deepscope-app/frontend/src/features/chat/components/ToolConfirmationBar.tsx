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
import { ChevronLeft, ChevronRight, ShieldAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import type { AguiInterrupt, AguiResumeInput } from '@/api/advisor';
import { ProcessText } from './ProcessText';

export interface ToolConfirmationBarProps {
  interrupts: AguiInterrupt[];
  busy: boolean;
  onResolve: (responses: AguiResumeInput[]) => void;
}

export function ToolConfirmationBar({
  interrupts,
  busy,
  onResolve,
}: ToolConfirmationBarProps) {
  const { t } = useTranslation();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [decisions, setDecisions] = useState<Record<string, boolean>>({});

  if (!interrupts || interrupts.length === 0) return null;

  const total = interrupts.length;
  const safeIndex = Math.min(Math.max(0, currentIndex), total - 1);
  const current = interrupts[safeIndex];

  const supported = (item: AguiInterrupt) =>
    item.metadata?.['deepscope.interruptKind'] === 'permission_confirm' &&
    (!item.expiresAt ||
      (Number.isFinite(Date.parse(item.expiresAt)) && Date.parse(item.expiresAt) > Date.now()));

  const toolName =
    typeof current.metadata?.toolName === 'string'
      ? current.metadata.toolName
      : current.toolCallId ?? current.id;

  const handleDecision = (approved: boolean) => {
    if (total === 1) {
      onResolve([
        {
          interruptId: current.id,
          status: 'resolved',
          payload: { approved },
        },
      ]);
      return;
    }

    const nextDecisions = { ...decisions, [current.id]: approved };
    setDecisions(nextDecisions);

    const allDecided = interrupts.every(
      (item) => typeof nextDecisions[item.id] === 'boolean',
    );

    if (allDecided) {
      onResolve(
        interrupts.map((item) => ({
          interruptId: item.id,
          status: 'resolved',
          payload: { approved: Boolean(nextDecisions[item.id]) },
        })),
      );
    } else {
      const nextUndecided = interrupts.findIndex(
        (item, idx) => idx > safeIndex && typeof nextDecisions[item.id] !== 'boolean',
      );
      if (nextUndecided !== -1) {
        setCurrentIndex(nextUndecided);
      } else {
        const firstUndecided = interrupts.findIndex(
          (item) => typeof nextDecisions[item.id] !== 'boolean',
        );
        if (firstUndecided !== -1) {
          setCurrentIndex(firstUndecided);
        } else {
          setCurrentIndex((prev) => Math.min(total - 1, prev + 1));
        }
      }
    }
  };

  const isCurrentSupported = supported(current);

  return (
    <section
      className="mx-4 mb-2 shrink-0 rounded-xl border border-amber-200/90 bg-amber-50/70 p-3 shadow-xs"
      aria-label={t('chat.confirmation.title')}
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-amber-100/80 pb-2">
        <div className="flex items-center gap-2">
          <ShieldAlert className="h-4 w-4 text-amber-600 shrink-0" />
          <span className="text-xs font-semibold text-slate-800">
            {t('chat.confirmation.title')}
          </span>
          <Badge tone="default" className="font-mono text-[11px]">
            {toolName}
          </Badge>
        </div>

        <div className="flex items-center gap-1.5 text-xs text-slate-500">
          <Button
            size="sm"
            variant="ghost"
            disabled={busy || safeIndex === 0}
            onClick={() => setCurrentIndex((prev) => Math.max(0, prev - 1))}
            className="h-6 px-1.5 text-xs text-slate-600 hover:bg-amber-100/50"
            aria-label={t('chat.confirmation.prev')}
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            <span>{t('chat.confirmation.prev')}</span>
          </Button>

          <span className="font-mono text-[11px] font-medium text-slate-600">
            {t('chat.confirmation.counter', { current: safeIndex + 1, total })}
          </span>

          <Button
            size="sm"
            variant="ghost"
            disabled={busy || safeIndex === total - 1}
            onClick={() => setCurrentIndex((prev) => Math.min(total - 1, prev + 1))}
            className="h-6 px-1.5 text-xs text-slate-600 hover:bg-amber-100/50"
            aria-label={t('chat.confirmation.next')}
          >
            <span>{t('chat.confirmation.next')}</span>
            <ChevronRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      <div className="py-2 text-xs">
        {current.message ? (
          <p className="mb-1 text-slate-600">{current.message}</p>
        ) : null}
        {current.metadata?.toolInput ? (
          <div className="max-h-32 overflow-auto rounded-lg bg-white/80 p-2 font-mono text-[11px] text-slate-700 border border-amber-100">
            <ProcessText
              text={JSON.stringify(current.metadata.toolInput, null, 2)}
              code
            />
          </div>
        ) : null}
        {!isCurrentSupported ? (
          <p className="text-xs text-amber-800">
            {t('chat.confirmation.unsupported')}
          </p>
        ) : null}
      </div>

      <div className="flex items-center justify-end gap-2 pt-1">
        {isCurrentSupported ? (
          <>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => handleDecision(false)}
              className="h-7 px-3 text-xs"
            >
              {t('chat.confirmation.denySimple')}
            </Button>
            <Button
              size="sm"
              disabled={busy}
              onClick={() => handleDecision(true)}
              className="h-7 px-3 text-xs"
            >
              {t('chat.confirmation.allowSimple')}
            </Button>
          </>
        ) : (
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() =>
              onResolve(
                interrupts.map((item) => ({
                  interruptId: item.id,
                  status: 'cancelled',
                })),
              )
            }
            className="h-7 px-3 text-xs"
          >
            {t('chat.confirmation.cancel')}
          </Button>
        )}
      </div>
    </section>
  );
}
