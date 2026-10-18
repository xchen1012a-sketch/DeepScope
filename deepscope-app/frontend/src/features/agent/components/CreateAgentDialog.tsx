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

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { Bot, Copy, FilePlus2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { can } from '@/api/auth';
import { createAgent, generateBehavior, type AgentDetail, type AgentSummary } from '@/api/agent';

type StartPoint = 'blank' | 'copy';

/**
 * 分步式新建主智能体对话框：
 * 第一步选择创建方式（空白 / 从现有智能体复制，复制时展示可选列表）；
 * 第二步填写名称与简介（复制方式预填“{源智能体名} 副本”）。
 * copyFrom 快捷入口（卡片上的“复制”按钮）直接进入第二步并预填来源。
 */
export function CreateAgentDialog({
  open,
  agents,
  copyFrom,
  onClose,
  onCreated,
}: {
  open: boolean;
  agents: AgentSummary[];
  /** 卡片“复制”快捷入口预选的来源智能体 id；为空时从第一步开始。 */
  copyFrom: string | null;
  onClose: () => void;
  onCreated: (agent: AgentDetail) => void;
}) {
  const { t } = useTranslation();
  const [step, setStep] = useState<1 | 2>(copyFrom ? 2 : 1);
  const [startPoint, setStartPoint] = useState<StartPoint>(copyFrom ? 'copy' : 'blank');
  const [copySource, setCopySource] = useState(copyFrom ?? '');
  const [name, setName] = useState(() => {
    const source = copyFrom ? agents.find((agent) => agent.agentId === copyFrom) : null;
    return source ? t('agent.create.namePrefill', { name: source.name }) : '';
  });
  const [description, setDescription] = useState(
    () => (copyFrom ? agents.find((agent) => agent.agentId === copyFrom)?.description ?? '' : ''),
  );
  const [isPublic, setIsPublic] = useState(false);
  const canShare = can('agent.share') || can('agent.manage');

  // 复制方式默认选中第一个候选；列表晚于对话框打开到达时补上默认值。
  useEffect(() => {
    if (startPoint !== 'copy' || copySource || agents.length === 0) return;
    setCopySource(agents[0].agentId);
  }, [startPoint, copySource, agents]);

  const createMutation = useMutation({
    mutationFn: createAgent,
    onSuccess: (agent) => onCreated(agent),
  });

  const source = startPoint === 'copy' ? agents.find((agent) => agent.agentId === copySource) : null;

  /** 进入第二步时按当前方式预填：复制 → “{源名} 副本” + 源简介；空白 → 清空。 */
  const goNext = () => {
    if (startPoint === 'copy') {
      if (!source) return;
      setName(t('agent.create.namePrefill', { name: source.name }));
      setDescription(source.description);
    } else {
      setName('');
      setDescription('');
    }
    setIsPublic(false);
    setStep(2);
  };

  const submit = async () => {
    const trimmedName = name.trim();
    if (!trimmedName || createMutation.isPending) return;
    const trimmedDescription = description.trim();
    const agent = await createMutation.mutateAsync({
      name: trimmedName,
      description: trimmedDescription,
      ...(startPoint === 'copy' && copySource ? { copyFromAgentId: copySource } : {}),
      ...(canShare ? { isPublic } : {}),
    });
    if (startPoint === 'blank' && trimmedDescription) {
      try {
        await generateBehavior(agent.agentId);
      } catch {
        /* 生成失败时保留创建时的基础行为文档，不阻断进入编辑页 */
      }
    }
    onCreated(agent);
  };

  const canGoNext = startPoint !== 'copy' || copySource !== '';
  const canSubmit = name.trim().length > 0 && (startPoint !== 'copy' || copySource !== '');

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>{t('agent.create.title')}</DialogTitle>
          <DialogDescription>{t('agent.create.step', { current: step, total: 2 })}</DialogDescription>
        </DialogHeader>

        {step === 1 ? (
          <DialogBody className="space-y-4">
            <div>
              <div className="mb-1.5 text-[13px] font-medium text-slate-600">{t('agent.create.method')}</div>
              <div className="grid gap-3 sm:grid-cols-2">
                {(['blank', 'copy'] as StartPoint[]).map((point) => (
                  <button
                    key={point}
                    type="button"
                    onClick={() => setStartPoint(point)}
                    aria-pressed={startPoint === point}
                    className={cn(
                      'rounded-xl border p-4 text-left transition-colors',
                      startPoint === point
                        ? 'border-indigo-400 bg-indigo-50/60 ring-1 ring-indigo-300'
                        : 'border-border bg-white hover:border-slate-300',
                    )}
                  >
                    <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white shadow-sm">
                      {point === 'blank' ? (
                        <FilePlus2 className="h-4 w-4 text-indigo-600" />
                      ) : (
                        <Copy className="h-4 w-4 text-violet-600" />
                      )}
                    </span>
                    <span className="mt-2.5 block text-[14px] font-semibold text-slate-900">
                      {point === 'blank' ? t('agent.create.blank') : t('agent.create.copy')}
                    </span>
                    <span className="mt-1 block text-[12.5px] leading-relaxed text-slate-500">
                      {point === 'blank' ? t('agent.create.blankDesc') : t('agent.create.copyDesc')}
                    </span>
                  </button>
                ))}
              </div>
            </div>

            {startPoint === 'copy' ? (
              <div>
                <div className="mb-1.5 text-[13px] font-medium text-slate-600">{t('agent.create.pickSource')}</div>
                <div className="max-h-64 space-y-2 overflow-auto rounded-xl border border-border bg-white p-2">
                  {agents.map((agent) => (
                    <button
                      key={agent.agentId}
                      type="button"
                      onClick={() => setCopySource(agent.agentId)}
                      aria-pressed={copySource === agent.agentId}
                      className={cn(
                        'flex w-full items-start gap-3 rounded-lg border px-3 py-2.5 text-left transition-colors',
                        copySource === agent.agentId
                          ? 'border-indigo-400 bg-indigo-50/60'
                          : 'border-transparent hover:bg-muted/60',
                      )}
                    >
                      <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-white shadow-sm">
                        <Bot className="h-3.5 w-3.5 text-violet-600" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-[13.5px] font-medium text-slate-800">{agent.name}</span>
                          <Badge className="text-[11px]">{t('agent.personal')}</Badge>
                        </span>
                        {agent.description ? (
                          <span className="mt-0.5 line-clamp-2 block text-[12px] leading-relaxed text-slate-500">
                            {agent.description}
                          </span>
                        ) : null}
                      </span>
                      <span
                        className={cn(
                          'mt-1 h-4 w-4 shrink-0 rounded-full border',
                          copySource === agent.agentId
                            ? 'border-indigo-500 bg-indigo-500 ring-2 ring-indigo-200'
                            : 'border-slate-300',
                        )}
                      />
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
          </DialogBody>
        ) : (
          <DialogBody className="space-y-4">
            {startPoint === 'copy' && source ? (
              <div className="flex items-center gap-2 rounded-lg border border-violet-200 bg-violet-50/60 px-3 py-2">
                <Copy className="h-3.5 w-3.5 shrink-0 text-violet-600" />
                <span className="text-[12.5px] text-slate-600">{t('agent.create.copySource')}</span>
                <span className="truncate text-[12.5px] font-medium text-slate-800">{source.name}</span>
                <Badge className="ml-auto text-[11px]">{t('agent.personal')}</Badge>
              </div>
            ) : null}

            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-600" htmlFor="agent-create-name">
                {t('agent.field.name')}
              </label>
              <Input
                id="agent-create-name"
                value={name}
                maxLength={120}
                placeholder={t('agent.create.namePlaceholder')}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-600" htmlFor="agent-create-description">
                {t('agent.field.description')}
              </label>
              <Textarea
                id="agent-create-description"
                value={description}
                maxLength={400}
                rows={2}
                className="min-h-[60px] py-2"
                placeholder={t('agent.field.descriptionPlaceholder')}
                onChange={(event) => setDescription(event.target.value)}
              />
            </div>
            {canShare ? (
              <div className="space-y-1 pt-1">
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={isPublic}
                    disabled={createMutation.isPending}
                    onChange={(e) => setIsPublic(e.target.checked)}
                  />
                  {t('agent.shareWithAll')}
                </label>
                <p className="text-xs leading-5 text-slate-500">{t('agent.shareWithAllHelp')}</p>
              </div>
            ) : null}
          </DialogBody>
        )}

        {createMutation.isError ? (
          <p role="alert" className="px-6 pb-3 text-sm text-red-700">{t('agent.create.error')}</p>
        ) : null}
        <DialogFooter className="items-center justify-between">
          {step === 2 ? (
            <Button variant="ghost" onClick={() => setStep(1)}>
              {t('agent.create.back')}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose}>
              {t('chat.cancel')}
            </Button>
            {step === 1 ? (
              <Button disabled={!canGoNext} onClick={goNext}>
                {t('agent.create.next')}
              </Button>
            ) : (
              <Button disabled={!canSubmit || createMutation.isPending} onClick={submit}>
                {t('agent.create.confirm')}
              </Button>
            )}
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
