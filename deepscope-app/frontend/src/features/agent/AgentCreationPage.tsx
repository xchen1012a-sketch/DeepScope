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

import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, CheckCircle2, Loader2, MoreHorizontal, Trash2 } from 'lucide-react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Button } from '@/components/ui/button';
import { Page } from '@/components/Page';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import { activateDraft, getActivation, checkDraft, deleteDraft, type CheckIssue } from '@/api/agentCreation';
import { useAgentCreationDraft } from './useAgentCreationDraft';
import { CreationPurposeForm } from './components/CreationPurposeForm';
import { CreationSettingsSection } from './components/CreationSettingsSection';
import { AgentPreviewPanel } from './components/AgentPreviewPanel';
import { useAgentEditGuard, useAgentEditor } from './agentEditGuard';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { agentKeys } from '@/api/agent';
import { agentCreationKeys } from '@/api/agentCreation';

export default function AgentCreationPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const editGuard = useAgentEditGuard();
  const queryClient = useQueryClient();
  const [savedDraftId, setSavedDraftId] = useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const { draftId: routeDraftId } = useParams<{ draftId?: string }>();
  const [searchParams] = useSearchParams();
  const copyFrom = searchParams.get('copyFrom');

  const [isActivating, setIsActivating] = useState(false);
  const [pendingActivation, setPendingActivation] = useState<{ draftId: string; operationId: string } | null>(null);
  const activationQuery = useQuery({
    queryKey: ['agent-creation-activation', pendingActivation],
    queryFn: () => getActivation(pendingActivation!.draftId, pendingActivation!.operationId),
    enabled: pendingActivation !== null,
    refetchInterval: query => ['succeeded', 'failed', 'interrupted', 'canceled'].includes(query.state.data?.state ?? '') ? false : 2000,
  });
  useEffect(() => {
    const result = activationQuery.data;
    if (!pendingActivation || !result) return;
    if (result.state === 'succeeded' && result.preallocatedAgentId) {
      setPendingActivation(null);
      isNavigatingAwayRef.current = true;
      isActivatingRef.current = false;
      setIsActivating(false);
      void queryClient.invalidateQueries({ queryKey: agentKeys.agents });
      void queryClient.invalidateQueries({ queryKey: agentCreationKeys.drafts });
      navigate('/agent/' + encodeURIComponent(result.preallocatedAgentId));
    } else if (['failed', 'interrupted', 'canceled'].includes(result.state)) {
      setPendingActivation(null);
      isActivatingRef.current = false;
      setIsActivating(false);
      setIssues([{ code:'activation_failed', severity:'error', section:'purpose', messageKey:t('agent.creation.activationFailed') }]);
    }
  }, [activationQuery.data, navigate, pendingActivation, queryClient, t]);
  const isActivatingRef = useRef(false);
  const isNavigatingAwayRef = useRef(false);
  const [issues, setIssues] = useState<CheckIssue[]>([]);

  const {
    draftId,
    activationState,
    activatedAgentId,
    activationOperationId,
    revision,
    purpose,
    name,
    description,
    behavior,
    tools,
    skills,
    resources,
    status,
    errorMessage,
    updatePurpose,
    updateName,
    updateDescription,
    updateBehavior,
    updateTools,
    updateSkills,
    applyCandidate,
    flush,
    hasUnsavedChanges,
    discardChanges,
  } = useAgentCreationDraft({
    initialDraftId: routeDraftId ?? null,
    copyFromId: copyFrom ?? null,
    deferInitialSave: true,
    onCreated: (created) => {
      setSavedDraftId(created.draftId);
      queryClient.invalidateQueries({ queryKey: agentCreationKeys.drafts });
      queryClient.invalidateQueries({ queryKey: agentKeys.agents });
    },
  });

  const effectiveDraftId = routeDraftId || savedDraftId || draftId;
  useEffect(() => {
    if (activationState === 'publishing' && activationOperationId && effectiveDraftId) {
      isActivatingRef.current = true;
      setIsActivating(true);
      setPendingActivation({ draftId: effectiveDraftId, operationId: activationOperationId });
    }
  }, [activationState, activationOperationId, effectiveDraftId]);
  useEffect(() => {
    if (activationState === 'activated' && activatedAgentId) navigate('/agent/' + encodeURIComponent(activatedAgentId), { replace: true });
  }, [activationState, activatedAgentId, navigate]);
  const removeDraftMutation = useMutation({
    mutationFn: () => deleteDraft(effectiveDraftId ?? ''),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentCreationKeys.drafts });
      queryClient.invalidateQueries({ queryKey: agentKeys.agents });
      setDeleteOpen(false);
      isNavigatingAwayRef.current = true;
      navigate('/agent');
    },
  });

  useAgentEditor('creation-draft', () => ({
    dirty: isNavigatingAwayRef.current ? false : hasUnsavedChanges,
    busy: isNavigatingAwayRef.current ? false : (status === 'saving' || isActivatingRef.current),
    save: async () => { const saved = await flush(); if (!saved) throw new Error('Draft save failed'); return saved; },
    discard: discardChanges,
  }));

  useEffect(() => {
    if (savedDraftId && savedDraftId !== routeDraftId && status === 'saved' && !hasUnsavedChanges && !isActivating) navigate(`/agent/creation/${savedDraftId}`, { replace: true });
  }, [savedDraftId, routeDraftId, status, hasUnsavedChanges, isActivating, navigate]);

  // 创建并保存智能体
  const handleCreateAndStart = async () => {
    if (isActivatingRef.current) return;
    isActivatingRef.current = true;
    setIsActivating(true);
    setIssues([]);

    try {
      // 1. Flush any pending auto-saves
      const saved = await flush();
      const currentDraftId = saved?.draftId || draftId;
      const currentRev = saved?.revision || revision;

      if (!saved || !currentDraftId) {
        const hasUserInput = Boolean(
          purpose.trim() || name.trim() || description.trim() || behavior.trim(),
        );
        setIssues([
          {
            code: hasUserInput ? 'save_failed' : 'missing_content',
            severity: 'error',
            section: 'purpose',
            messageKey: hasUserInput
              ? (errorMessage || t('agent.creation.saveFailed', '草稿保存失败，请检查服务连接后重试'))
              : t('agent.creation.missingContent'),
          },
        ]);
        isActivatingRef.current = false;
        setIsActivating(false);
        return;
      }

      // 2. Static configuration validation check
      const checkRes = await checkDraft(currentDraftId, currentRev);
      const blockingIssues = checkRes.issues.filter((i) => i.severity === 'error');
      if (blockingIssues.length > 0) {
        setIssues(blockingIssues);
        isActivatingRef.current = false;
        setIsActivating(false);
        return;
      }

      // 3. Submit activation
      const actRes = await activateDraft(currentDraftId, currentRev);
      if (actRes.state === 'running' || actRes.state === 'queued') {
        setPendingActivation({ draftId: currentDraftId, operationId: actRes.operationId });
        return;
      }
      if (actRes.state !== 'succeeded') throw new Error(t('agent.creation.activationFailed'));
      const agentId = actRes.preallocatedAgentId;
      if (!agentId) {
        throw new Error(t('agent.creation.noAgentId', '未获取到正式智能体标识'));
      }

      queryClient.invalidateQueries({ queryKey: agentKeys.agents });
      queryClient.invalidateQueries({ queryKey: agentCreationKeys.drafts });
      isNavigatingAwayRef.current = true;
      isActivatingRef.current = false;
      setIsActivating(false);
      navigate(`/agent/${encodeURIComponent(agentId)}`);
    } catch (err: any) {
      setIssues([
        {
          code: 'activation_failed',
          severity: 'error',
          section: 'root',
          messageKey: err?.message || t('agent.creation.activationFailed', '创建智能体失败，请检查后重试'),
        },
      ]);
      isActivatingRef.current = false;
      setIsActivating(false);
    }
  };

  return (
    <Page>
      {activationQuery.isError ? <p role="alert" className="text-sm text-red-600">{t('agent.creation.activationStatusUnavailable')}<Button type="button" variant="outline" onClick={() => void activationQuery.refetch()}>{t('table.retry')}</Button></p> : null}
      {/* Top Header Bar */}
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-100 pb-4">
        <div className="flex items-center gap-3">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => editGuard ? editGuard.request(() => navigate('/agent')) : navigate('/agent')}
            className="h-8 w-8 p-0 text-slate-500 hover:text-slate-800"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-lg font-bold text-slate-900">
              {t('agent.creation.pageTitle', '创建智能体')}
            </h1>
            <div className="flex items-center gap-2 text-xs text-slate-400">
              <span>{copyFrom ? t('agent.creation.copyMode', '基于现有智能体复制') : t('agent.creation.createMode', '新建智能体')}</span>
              <span>•</span>
              {status === 'saving' && (
                <span className="flex items-center gap-1 text-slate-500">
                  <Loader2 className="h-3 w-3 animate-spin" />
                  {t('agent.creation.saving', '保存中...')}
                </span>
              )}
              {status === 'saved' && (
                <span className="flex items-center gap-1 text-emerald-600">
                  <CheckCircle2 className="h-3 w-3" />
                  {t('agent.creation.saved', '已保存')}
                </span>
              )}
              {status === 'conflict' && (
                <span className="text-amber-600 font-medium">{errorMessage}</span>
              )}
              {status === 'error' && (
                <span className="text-red-500">{errorMessage || t('agent.creation.saveFailed', '保存失败')}</span>
              )}
            </div>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            disabled={isActivating}
            onClick={handleCreateAndStart}
            className="flex items-center gap-1.5 bg-violet-600 hover:bg-violet-700 text-white font-medium shadow-sm"
          >
            {isActivating ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : null}
            {isActivating
              ? t('agent.creation.activating', '创建中...')
              : t('agent.creation.createAndStart', '创建')}
          </Button>

          {effectiveDraftId ? (
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <Button
                  size="icon"
                  variant="ghost"
                  aria-label={t('agent.management.more')}
                  className="h-8 w-8 text-slate-500 hover:text-slate-800"
                >
                  <MoreHorizontal className="h-4 w-4" />
                </Button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content
                  sideOffset={6}
                  className="z-50 min-w-36 rounded-xl border border-slate-200/80 bg-white p-1 text-sm text-slate-700 shadow-xl ring-1 ring-slate-900/5 outline-none focus:outline-none focus-visible:outline-none"
                >
                  <DropdownMenu.Item
                    onSelect={() => setDeleteOpen(true)}
                    className="flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs font-medium text-red-600 outline-none transition-colors hover:bg-red-50 data-[highlighted]:bg-red-50 data-[highlighted]:text-red-700 cursor-pointer"
                  >
                    <Trash2 className="h-3.5 w-3.5 text-red-500" />
                    <span>{t('agent.delete')}</span>
                  </DropdownMenu.Item>
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          ) : null}
        </div>
      </div>

      {/* Issues / Error Banner */}
      {issues.length > 0 && (
        <div className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-xs text-red-700 space-y-1">
          <div className="font-semibold">{t('agent.creation.issueTitle', '无法完成创建：')}</div>
          {issues.map((issue, idx) => (
            <div key={idx} className="flex items-center gap-1.5">
              <span>•</span>
              <span>{t(issue.messageKey, issue.messageKey)}</span>
            </div>
          ))}
        </div>
      )}

      {/* Main Content: Permanently Two-Column Layout */}
      <div className="mt-6 grid gap-6 lg:grid-cols-12">
        {/* Left Form Area */}
        <div className="space-y-6 lg:col-span-7">
          <CreationPurposeForm
            draftId={draftId}
            revision={revision}
            purpose={purpose}
            onChangePurpose={updatePurpose}
            name={name}
            onChangeName={updateName}
            description={description}
            onChangeDescription={updateDescription}
            behavior={behavior}
            onChangeBehavior={updateBehavior}
            onApplyCandidate={applyCandidate}
            onFlush={flush}
          />

          <CreationSettingsSection
            draftId={effectiveDraftId ?? undefined}
            tools={tools}
            onChangeTools={updateTools}
            skills={skills}
            onChangeSkills={updateSkills}
            resources={resources}
            onChangeResources={() => {}}
          />

          {/* Bottom Call to Action */}
          <div className="flex items-center justify-between pt-4 border-t border-slate-100">
            <span className="text-xs text-slate-400">
              {t('agent.creation.footerHint', '创建后将自动保存并加入智能体列表。')}
            </span>
            <Button
              type="button"
              disabled={isActivating}
              onClick={handleCreateAndStart}
              className="bg-violet-600 hover:bg-violet-700 text-white flex items-center gap-1.5"
            >
              {isActivating ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : null}
              {isActivating
                ? t('agent.creation.activating', '创建中...')
                : t('agent.creation.createAndStart', '创建')}
            </Button>
          </div>
        </div>

        {/* Right Preview Sandbox Area: Permanently Mounted */}
        <div className="lg:col-span-5 h-[680px] sticky top-6">
          <AgentPreviewPanel
            draftId={draftId}
            revision={revision}
            permanent={true}
          />
        </div>
      </div>

      <ConfirmDeleteDialog
        open={deleteOpen}
        title={t('agent.deleteTitle')}
        description={t('agent.management.deleteDraftHint')}
        confirmText={t('agent.delete')}
        isDeleting={removeDraftMutation.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => removeDraftMutation.mutate()}
      >
        {removeDraftMutation.isError ? (
          <p role="alert" className="text-sm text-red-600">
            {t('agent.management.deleteFailed')}
          </p>
        ) : null}
      </ConfirmDeleteDialog>
    </Page>
  );
}
