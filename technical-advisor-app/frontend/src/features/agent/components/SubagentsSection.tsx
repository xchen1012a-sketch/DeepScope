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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Plus, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useAgentEditor } from '../agentEditGuard';
import { Input, Textarea } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  agentKeys,
  createAgentFile,
  deleteAgentFile,
  draftSubagent,
  readAgentFile,
  saveAgentFile,
  type AgentSubagentRef,
} from '@/api/agent';

/** 新建/编辑子智能体的对话框状态：editFile 为空 = 新建（template 起点）。 */
interface SubagentDialogState {
  mode: 'new' | 'edit';
  file: string;
}

/**
 * 子智能体页签：卡片列表（名称/描述/文件名）+ 新建（模板为起点）/ 编辑 / 删除（确认）。
 * 定义即工作区文件，全部走文件接口读写。
 */
export function SubagentsSection({
  agentId,
  subagents,
  canEdit,
  readOnlyReason,
}: {
  agentId: string;
  subagents: AgentSubagentRef[];
  canEdit: boolean;
  readOnlyReason: string;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<SubagentDialogState | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AgentSubagentRef | null>(null);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: agentKeys.agent(agentId) });
    queryClient.invalidateQueries({ queryKey: agentKeys.agents });
    queryClient.invalidateQueries({ queryKey: agentKeys.tree(agentId) });
  };

  const deleteMutation = useMutation({
    mutationFn: (file: string) => deleteAgentFile(agentId, file),
    onSuccess: invalidate,
  });

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        {!canEdit ? <p className="text-[12.5px] text-amber-700">{readOnlyReason}</p> : <span />}
        {canEdit ? (
          <Button variant="outline" size="sm" onClick={() => setDialog({ mode: 'new', file: '' })}>
            <Plus className="h-4 w-4" />
            {t('agent.subagents.new')}
          </Button>
        ) : null}
      </div>

      {subagents.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-slate-300 bg-muted/40 px-4 py-8 text-center text-[13px] text-slate-500">
          {t('agent.subagents.empty')}
        </p>
      ) : (
        <div className="grid gap-3.5 sm:grid-cols-2">
          {subagents.map((subagent) => (
            <div
              key={subagent.file}
              className="flex flex-col justify-between rounded-xl border border-border bg-white p-4 shadow-2xs transition-shadow hover:shadow-xs"
            >
              <div>
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-2.5">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
                      <Bot className="h-4 w-4" />
                    </span>
                    <span className="text-sm font-semibold text-slate-900">{subagent.name}</span>
                  </div>
                  {canEdit ? (
                    <div className="flex shrink-0 items-center gap-1">
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-7 px-2 text-xs"
                        onClick={() => setDialog({ mode: 'edit', file: subagent.file })}
                      >
                        {t('agent.edit')}
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={t('agent.delete')}
                        className="h-7 w-7 p-0 text-slate-400 hover:text-red-600"
                        onClick={() => setDeleteTarget(subagent)}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  ) : null}
                </div>
                <p className="mt-2.5 line-clamp-2 text-xs leading-relaxed text-slate-500">
                  {subagent.description || '暂无描述'}
                </p>
              </div>
            </div>
          ))}
        </div>
      )}

      <SubagentDialog
        agentId={agentId}
        state={dialog}
        subagents={subagents}
        onClose={() => setDialog(null)}
        onSaved={() => {
          setDialog(null);
          invalidate();
        }}
      />

      <ConfirmDeleteDialog
        open={deleteTarget !== null}
        title={t('agent.subagents.deleteTitle')}
        description={
          <div className="space-y-2">
            <span className="text-sm font-medium text-slate-800 block">{deleteTarget?.name}</span>
            <p className="text-sm text-slate-600">{t('agent.subagents.deleteBody')}</p>
          </div>
        }
        confirmText={t('agent.delete')}
        isDeleting={deleteMutation.isPending}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget) deleteMutation.mutate(deleteTarget.file);
          setDeleteTarget(null);
        }}
      />
    </section>
  );
}

function SubagentDialog({
  agentId,
  state,
  subagents,
  onClose,
  onSaved,
}: {
  agentId: string;
  state: SubagentDialogState | null;
  subagents: AgentSubagentRef[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [content, setContent] = useState('');
  const [confirmFillOpen, setConfirmFillOpen] = useState(false);

  const currentSubagent = subagents.find((s) => s.file === state?.file);

  // 新建从空白开始（不预填名称与模板），编辑读原文件；对话框关闭时清空本地草稿。
  const sourceQuery = useQuery({
    queryKey: agentKeys.file(agentId, state?.file ?? ''),
    queryFn: () => readAgentFile(agentId, state?.file ?? ''),
    enabled: state !== null && state.mode === 'edit',
  });

  useEffect(() => {
    if (state?.mode === 'new') {
      setName('');
      setContent('');
    } else if (state?.mode === 'edit' && sourceQuery.data) {
      setName(currentSubagent?.name || state.file.replace(/^subagents\//, '').replace(/\.md$/, ''));
      setContent(sourceQuery.data.content);
    }
    if (!state) {
      setName('');
      setContent('');
      setConfirmFillOpen(false);
    }
  }, [state, sourceQuery.data, currentSubagent]);

  const baselineContent = state?.mode === 'new' ? '' : (sourceQuery.data?.content ?? '');
  const baselineName = state?.mode === 'new' ? '' : (currentSubagent?.name ?? '');

  const fill = useMutation({
    mutationFn: () => draftSubagent(agentId, { name: name.trim() }),
    onSuccess: (data) => {
      setContent(data.content);
      setConfirmFillOpen(false);
    },
  });

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!state) return Promise.resolve();
      if (state.mode === 'new') {
        const safeSlug =
          name
            .trim()
            .toLowerCase()
            .replace(/[^a-z0-9_-]+/g, '-')
            .replace(/^-+|-+$/g, '') || `subagent-${Date.now().toString().slice(-6)}`;
        let filePath = `subagents/${safeSlug}.md`;
        let counter = 1;
        const existing = new Set(subagents.map((s) => s.file));
        while (existing.has(filePath)) {
          filePath = `subagents/${safeSlug}-${counter}.md`;
          counter++;
        }
        return createAgentFile(agentId, filePath, content);
      }
      return saveAgentFile(agentId, state.file, content);
    },
    onSuccess: onSaved,
  });

  useAgentEditor('subagent-definition', {
    dirty: state !== null && (content !== baselineContent || name.trim() !== baselineName),
    busy: saveMutation.isPending,
    save: async () => {
      if (!content.trim() || (state?.mode === 'new' && !name.trim())) throw new Error('Invalid subagent');
      return saveMutation.mutateAsync();
    },
    discard: () => {
      setContent(baselineContent);
      setName(baselineName);
    },
  });

  const isNew = state?.mode === 'new';
  const canSubmit = content.trim().length > 0 && name.trim().length > 0;
  // 补齐进行中锁定名称与定义内容，避免生成结果落回时覆盖正在输入的文字。
  const filling = fill.isPending;
  const canFill = name.trim().length > 0 && !filling && !saveMutation.isPending;

  const requestFill = () => {
    if (!canFill) return;
    if (content.trim().length > 0) {
      setConfirmFillOpen(true);
      return;
    }
    fill.mutate();
  };

  return (
    <Dialog open={state !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle className="text-base font-semibold text-slate-900">
            {isNew ? t('agent.subagents.newTitle') : t('agent.subagents.editTitle')}
          </DialogTitle>
          <DialogDescription>{t('agent.subagents.hint')}</DialogDescription>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-slate-700" htmlFor="subagent-name">
              {t('agent.field.name', '子智能体名称')}
            </label>
            <Input
              id="subagent-name"
              value={name}
              placeholder={t('agent.subagents.namePlaceholder')}
              disabled={filling}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div>
            <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
              <label className="text-sm font-medium text-slate-700" htmlFor="subagent-content">
                {t('agent.subagents.content', '定义内容与工作流')}
              </label>
              <div className="flex items-center gap-2">
                {!isNew ? (
                  <code className="font-mono text-[11px] text-slate-400">{state?.file}</code>
                ) : null}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-7 px-2.5 text-xs"
                  title={canFill ? undefined : t('agent.subagents.fillNeedsName')}
                  disabled={!canFill}
                  onClick={requestFill}
                >
                  {fill.isPending ? t('agent.subagents.fillGenerating') : t('agent.subagents.fillAi')}
                </Button>
              </div>
            </div>
            {sourceQuery.isPending && state?.mode === 'edit' ? (
              <Skeleton className="h-[300px] w-full rounded-xl" />
            ) : (
              <Textarea
                id="subagent-content"
                value={content}
                disabled={filling}
                aria-busy={filling || undefined}
                onChange={(event) => setContent(event.target.value)}
                className="min-h-[300px] font-mono text-[12.5px] leading-relaxed"
              />
            )}
            <p className="mt-2 text-[11.5px] leading-relaxed text-slate-400">
              {t('agent.subagents.fillAiHint')}
            </p>
            {fill.isError ? (
              <p role="alert" className="mt-2 text-sm text-red-600">
                {t('agent.subagents.fillFailed')}
              </p>
            ) : null}
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t('chat.cancel')}
          </Button>
          <Button disabled={!canSubmit || saveMutation.isPending || filling} onClick={() => saveMutation.mutate()}>
            {t('agent.workspace.save')}
          </Button>
        </DialogFooter>
      </DialogContent>

      <ConfirmDeleteDialog
        open={confirmFillOpen}
        confirmVariant="default"
        title={t('agent.subagents.fillConfirmTitle')}
        description={t('agent.subagents.fillConfirmBody')}
        confirmText={t('agent.subagents.fillConfirm')}
        isConfirming={fill.isPending}
        onClose={() => setConfirmFillOpen(false)}
        onConfirm={() => fill.mutate()}
      />
    </Dialog>
  );
}
