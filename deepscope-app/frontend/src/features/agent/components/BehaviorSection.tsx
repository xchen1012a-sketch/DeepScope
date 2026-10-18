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
import { useMutation, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ConfirmActionDialog } from '@/components/ConfirmDeleteDialog';
import { agentKeys, generateBehavior, saveAgentFile, type AgentFileContent } from '@/api/agent';
import { useAgentEditor } from '../agentEditGuard';

/** 行为页签：AGENTS.md 在线编辑；保存需确认"影响该智能体所有会话"。 */
export function BehaviorSection({
  agentId,
  canEdit,
  readOnlyReason,
  fileQuery,
  compact = false,
  extraDirty = false,
  extraValid = true,
  onSaveExtra,
}: {
  agentId: string;
  compact?: boolean;
  canEdit: boolean;
  readOnlyReason: string;
  /** 页面级查询：与详情请求并行发出，仅在行为页签激活时启用。 */
  fileQuery: UseQueryResult<AgentFileContent, Error>;
  extraDirty?: boolean;
  extraValid?: boolean;
  onSaveExtra?: () => Promise<unknown>;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [content, setContent] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [generateOpen, setGenerateOpen] = useState(false);
  const [extraSaved, setExtraSaved] = useState(false);
  const [extraSaving, setExtraSaving] = useState(false);

  useEffect(() => {
    if (fileQuery.data) setContent(fileQuery.data.content);
  }, [fileQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (value: string) => saveAgentFile(agentId, 'AGENTS.md', value),
    onSuccess: (_result, value) => { queryClient.setQueryData(agentKeys.file(agentId, 'AGENTS.md'), { content: value }); },
  });
  const generateMutation = useMutation({
    mutationFn: () => generateBehavior(agentId),
    onSuccess: (draft) => {
      setContent(draft.content);
      queryClient.invalidateQueries({ queryKey: agentKeys.file(agentId, 'AGENTS.md') });
      setGenerateOpen(false);
    },
  });

  const dirty = canEdit && fileQuery.data !== undefined && content !== fileQuery.data.content;
  useAgentEditor('behavior', { dirty, busy: saveMutation.isPending || generateMutation.isPending, save: () => saveMutation.mutateAsync(content), discard: () => setContent(fileQuery.data?.content ?? '') });

  const isDirty = dirty || extraDirty;
  const canSave = isDirty && extraValid && !saveMutation.isPending && !extraSaving;

  const handleSave = async () => {
    if (dirty) {
      setConfirmOpen(true);
    } else if (extraDirty && onSaveExtra) {
      setExtraSaving(true);
      try {
        await onSaveExtra();
        setExtraSaved(true);
        setTimeout(() => setExtraSaved(false), 3000);
      } finally {
        setExtraSaving(false);
      }
    }
  };

  return (
    <section>
      {!compact ? (
        <div className="mb-4">
          <h2 className="text-[18px] font-semibold tracking-tight text-slate-900">{t('agent.sections.behavior')}</h2>
          <p className="mt-1.5 text-[13px] leading-relaxed text-slate-500">{t('agent.behavior.hint')}</p>
        </div>
      ) : null}
      {!canEdit ? <p className="mb-3 text-[12.5px] text-amber-700">{readOnlyReason}</p> : null}

      {fileQuery.isPending ? (
        <Skeleton className="h-[520px] w-full rounded-xl" />
      ) : fileQuery.isError ? (
        <p className="rounded-2xl border border-red-200 bg-red-50/60 px-4 py-3 text-[13px] text-red-700">
          {t('agent.behavior.loadError')}
        </p>
      ) : (
        <>
          <Textarea
            aria-label={t(compact ? 'agent.management.instructions' : 'agent.sections.behavior')}
            value={content}
            readOnly={!canEdit || saveMutation.isPending || generateMutation.isPending}
            onChange={(event) => setContent(event.target.value)}
            placeholder={t(
              'agent.behavior.placeholder',
              '先写清这位智能体负责什么、按什么流程工作、输出要满足什么要求。留空则它没有固定的行为规范。',
            )}            className={compact ? 'min-h-[420px] text-sm leading-6' : 'min-h-[520px] font-mono text-[12.5px] leading-relaxed'}
          />
          {canEdit ? (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button size="sm" variant="outline" disabled={generateMutation.isPending} onClick={() => setGenerateOpen(true)}>
                {t('agent.behavior.generate')}
              </Button>
              <Button size="sm" disabled={!canSave} onClick={handleSave}>
                {t('agent.behavior.save')}
              </Button>
              {(saveMutation.isSuccess && !dirty) || (extraSaved && !extraDirty) ? (
                <span className="text-[12px] text-emerald-600">{t('agent.infoSaved')}</span>
              ) : null}
              {generateMutation.isError ? (
                <span className="text-[12px] text-red-700">{t('agent.behavior.generateError')}</span>
              ) : null}
            </div>
          ) : null}
        </>
      )}

      <ConfirmActionDialog
        open={confirmOpen}
        title={t('agent.behavior.confirmTitle')}
        description={
          <div className="space-y-2">
            <p>{t('agent.behavior.confirmBody')}</p>
            {!compact ? (
              <code className="inline-block rounded-md bg-muted px-2 py-0.5 font-mono text-[11.5px] text-slate-500">
                AGENTS.md
              </code>
            ) : null}
          </div>
        }
        confirmText={t('agent.behavior.save')}
        isConfirming={saveMutation.isPending}
        onClose={() => setConfirmOpen(false)}
        onConfirm={async () => {
          setConfirmOpen(false);
          if (extraDirty && onSaveExtra) {
            try {
              await onSaveExtra();
            } catch {
              /* Ignore failure from extra save callback. */
            }
          }
          saveMutation.mutate(content);
        }}
      />

      <ConfirmActionDialog
        open={generateOpen}
        title={t('agent.behavior.generateTitle')}
        description={t('agent.behavior.generateBody')}
        confirmText={t('agent.behavior.generate')}
        isConfirming={generateMutation.isPending}
        onClose={() => setGenerateOpen(false)}
        onConfirm={() => {
          setGenerateOpen(false);
          generateMutation.mutate();
        }}
      />
    </section>
  );
}
