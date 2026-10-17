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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Check, ChevronDown } from 'lucide-react';
import { can } from '@/api/auth';
import { advisorKeys } from '@/api/advisor';
import { api, apiFetch } from '@/lib/apiClient';
import { cn } from '@/lib/utils';
import type { ReasoningEffort } from '../aguiClient';

const REASONING_EFFORT_TIERS: ReasoningEffort[] = ['low', 'medium', 'high', 'xhigh', 'max'];

interface EndpointView {
  endpointId: string;
  kind: 'chat' | 'embedding';
  modelName: string;
  displayName?: string;
  current: boolean;
}

interface ModelPickerProps {
  sessionId?: string;
  selectedEndpointId?: string | null;
  onSelectModel?: (endpointId: string | null) => void;
  thinking: boolean;
  onToggleThinking: () => void;
  /** 推理程度档位；仅思考开启时可选。 */
  reasoningEffort?: ReasoningEffort;
  onSelectReasoningEffort?: (tier: ReasoningEffort) => void;
  disabled?: boolean;
  fallbackModelName?: string;
}

export function ModelPicker({ sessionId, selectedEndpointId, onSelectModel, thinking, onToggleThinking, reasoningEffort = 'high', onSelectReasoningEffort, disabled, fallbackModelName }: ModelPickerProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const allowed = can('settings.manage');
  const [open, setOpen] = useState(false);
  const models = useQuery({
    queryKey: ['advisor', 'session-model', sessionId],
    queryFn: async () => {
      if (sessionId) return api.get<{ endpointId: string | null; choices: EndpointView[]; modelName?: string; displayName?: string }>(`/api/advisor/sessions/${encodeURIComponent(sessionId)}/model`);
      const result = await api.get<{ chat: EndpointView[] }>('/api/advisor/models');
      return { endpointId: null, choices: result.chat };
    },
    enabled: !!sessionId || allowed,
    retry: false,
  });
  const activate = useMutation({
    mutationKey: advisorKeys.modelActivation,
    mutationFn: async (endpointId: string | null) => {
      if (!sessionId) { onSelectModel?.(endpointId); return; }
      await apiFetch<void>(`/api/advisor/sessions/${encodeURIComponent(sessionId)}/model`, { method: 'PUT', body: JSON.stringify({ endpointId }) });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['advisor', 'session-model', sessionId] });
      setOpen(false);
    },
  });
  const chat = allowed ? models.data?.choices ?? [] : [];
  const selection = sessionId ? models.data?.endpointId : selectedEndpointId;
  const current = chat.find(endpoint => endpoint.endpointId === selection) ?? chat.find(endpoint => selection == null && endpoint.current);
  const name = current?.displayName ?? current?.modelName ?? models.data?.displayName ?? models.data?.modelName ?? fallbackModelName ?? t('chat.composer.modelEmpty');
  const busy = disabled || activate.isPending;
  const rowClass = 'flex min-h-10 cursor-default items-center gap-3 rounded-md px-2.5 py-2 text-[13px] outline-none data-[highlighted]:bg-slate-100 data-[disabled]:opacity-50';
  const [effortDragging, setEffortDragging] = useState(false);
  const [effortDragX, setEffortDragX] = useState<number | null>(null);
  const effortSliderDisabled = busy || !thinking;
  const tierFromPointer = (event: React.PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return reasoningEffort;
    const index = Math.floor(((event.clientX - rect.left) / rect.width) * REASONING_EFFORT_TIERS.length);
    return REASONING_EFFORT_TIERS[Math.min(REASONING_EFFORT_TIERS.length - 1, Math.max(0, index))];
  };
  /** 拖动时选中块连续跟随指针（像素偏移），松手后回弹吸附到档位。 */
  const dragOffsetFromPointer = (event: React.PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return null;
    const block = (rect.width - 4) / REASONING_EFFORT_TIERS.length;
    return Math.min(rect.width - 4 - block, Math.max(0, event.clientX - rect.left - 2 - block / 2));
  };

  return (
    <DropdownMenu.Root modal={false} open={open} onOpenChange={(next) => { setOpen(next); if (next) activate.reset(); }}>
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          disabled={busy}
          aria-label={`${t('chat.composer.chooseModel')}: ${name}`}
          className="inline-flex min-h-9 max-w-[240px] items-center gap-1.5 rounded-lg px-2 py-1 text-[12.5px] text-slate-500 hover:bg-slate-100 hover:text-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
        >
          <span className="truncate">{name}</span>
          {thinking ? <span className="shrink-0 font-medium text-purple-600">· {t('composer.think')} · {t(`composer.effort.${reasoningEffort}`)}</span> : null}
          <ChevronDown className="h-3.5 w-3.5 shrink-0" />
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          side="top" align="end" sideOffset={10} collisionPadding={12}
          className="z-[60] w-64 max-w-[calc(100vw-24px)] rounded-xl border border-slate-200/80 bg-white p-1.5 text-slate-700 shadow-lg"
        >
          <DropdownMenu.Label className="px-2.5 pb-1 pt-1.5 text-[11px] text-slate-400">{t('chat.composer.sessionModels')}</DropdownMenu.Label>
          <DropdownMenu.RadioGroup className="max-h-60 overflow-y-auto" value={selection ?? current?.endpointId ?? ''} onValueChange={(value) => {
            if (value !== selection) activate.mutate(value);
            else setOpen(false);
          }}>
            {chat.map(endpoint => (
              <DropdownMenu.RadioItem
                key={endpoint.endpointId} value={endpoint.endpointId} disabled={busy}
                onSelect={event => event.preventDefault()}
                className={cn(rowClass, endpoint.endpointId === current?.endpointId && 'bg-slate-100/80')}
              >
                <span className="min-w-0 flex-1 truncate" title={endpoint.modelName}>{endpoint.displayName || endpoint.modelName}</span>
                <DropdownMenu.ItemIndicator><Check className="h-3.5 w-3.5 shrink-0" /></DropdownMenu.ItemIndicator>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
          {chat.length === 0 ? (
            <p className="px-2.5 py-2 text-[12px] text-slate-500">
              {models.isFetching ? t('shell.loading') : t('chat.composer.modelEmpty')}
            </p>
          ) : null}
          {models.isError || activate.isError ? <p role="alert" className="px-2.5 py-2 text-[12px] text-red-600">{t('chat.composer.modelError')}</p> : null}
          <DropdownMenu.Separator className="mx-1 my-1.5 h-px bg-slate-200/80" />
          <DropdownMenu.CheckboxItem
            checked={thinking}
            disabled={busy}
            onCheckedChange={() => {
              if (thinking) {
                onSelectReasoningEffort?.('low');
              }
              onToggleThinking();
            }}
            onSelect={event => event.preventDefault()}
            className={rowClass}
          >
            <span className="flex-1 font-medium">{t('composer.think')}</span>
            <span aria-hidden="true" className={cn('flex h-[18px] w-[30px] shrink-0 items-center rounded-full p-0.5 transition-colors motion-reduce:transition-none', thinking ? 'bg-purple-600' : 'bg-slate-300')}>
              <span className={cn('h-3.5 w-3.5 rounded-full bg-white shadow-sm transition-transform motion-reduce:transition-none', thinking && 'translate-x-3')} />
            </span>
          </DropdownMenu.CheckboxItem>
          <DropdownMenu.Separator className="mx-1 my-1.5 h-px bg-slate-200/80" />
          <DropdownMenu.Label className="px-2.5 pb-1 pt-1.5 text-[11px] text-slate-400">{t('composer.reasoningEffort')}</DropdownMenu.Label>
          {/* Codex 风格单行分段滑块：可点击也可按住拖动，实时吸附档位。 */}
          <div
            role="radiogroup"
            aria-label={t('composer.reasoningEffort')}
            className={cn(
              'relative mx-1.5 mb-1 flex touch-none select-none rounded-lg bg-slate-100 p-0.5',
              effortSliderDisabled ? 'opacity-50' : 'cursor-pointer',
            )}
            onPointerDown={event => {
              if (effortSliderDisabled) return;
              event.preventDefault();
              event.currentTarget.setPointerCapture?.(event.pointerId);
              setEffortDragging(true);
              setEffortDragX(dragOffsetFromPointer(event));
              const tier = tierFromPointer(event);
              if (tier !== reasoningEffort) onSelectReasoningEffort?.(tier);
            }}
            onPointerMove={event => {
              if (!effortDragging || effortSliderDisabled) return;
              setEffortDragX(dragOffsetFromPointer(event));
              const tier = tierFromPointer(event);
              if (tier !== reasoningEffort) onSelectReasoningEffort?.(tier);
            }}
            onPointerUp={() => { setEffortDragging(false); setEffortDragX(null); }}
            onPointerCancel={() => { setEffortDragging(false); setEffortDragX(null); }}
          >
            <span
              aria-hidden="true"
              className={cn(
                'absolute inset-y-0.5 left-0.5 rounded-md bg-white shadow-sm',
                // 拖动时跟手无过渡；松手用带轻微过冲的弹性曲线吸附回档位，营造流动感。
                effortDragX === null
                  ? 'transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]'
                  : 'transition-transform duration-75 ease-out',
                'motion-reduce:transition-none',
              )}
              style={{
                width: 'calc((100% - 4px) / 5)',
                transform: effortDragX === null
                  ? `translateX(${REASONING_EFFORT_TIERS.indexOf(reasoningEffort) * 100}%)`
                  : `translateX(${effortDragX}px) scaleX(1.06)`,
              }}
            />
            {REASONING_EFFORT_TIERS.map(tier => (
              <button
                key={tier}
                type="button"
                role="radio"
                aria-checked={tier === reasoningEffort}
                disabled={effortSliderDisabled}
                onClick={() => onSelectReasoningEffort?.(tier)}
                className={cn(
                  'relative z-10 flex-1 whitespace-nowrap rounded-md py-1 text-[11px] text-slate-500 transition-colors disabled:cursor-not-allowed',
                  tier === reasoningEffort && 'font-medium text-purple-700',
                )}
              >
                {t(`composer.effort.${tier}`)}
              </button>
            ))}
          </div>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
