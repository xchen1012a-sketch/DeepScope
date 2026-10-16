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
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { getUsername } from '@/api/auth';
import { shanghaiGreeting } from './greeting';
import { ComposerDock } from './components/ComposerDock';
import { useTabDraft } from '@/hooks/useTabDraft';
import { draftKey, writeDraft } from '@/lib/tabDraftStorage';
import { advisorKeys, type UploadedAttachment } from '@/api/advisor';
import { useAccountPreferences } from '@/features/account/useAccount';
import { createPersonalConversation } from '@/features/account/newConversation';
import { RequestError } from '@/features/account/AccountShared';
import { ConversationPanel } from './ConversationPanel';
import {
  useComposerAttachments,
} from './components/ComposerToolbar';
import {
  getStoredReasoningEffortPreference,
  getStoredThinkingPreference,
  setStoredReasoningEffortPreference,
  setStoredThinkingPreference,
} from './conversationModel';
import type { ReasoningEffort } from './aguiClient';

interface ChatLaunchState {
  modelEndpointId?: string | null;
  agentId?: string | null;
  autoStart?: boolean;
  q?: string;
  thinking?: boolean;
  reasoningEffort?: ReasoningEffort;
  attachments?: UploadedAttachment[];
}

/** 会话页只放对话。会话列表挂在最左栏的「会话」下面。 */
export default function ChatPage() {
  const { t } = useTranslation();
  const identity = useAccountIdentity();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const timer = setInterval(() => setNow(new Date()), 60000); return () => clearInterval(timer); }, []);
  const displayName = identity?.nickname?.trim() || identity?.username || getUsername();
  const changingModel = useIsMutating({ mutationKey: advisorKeys.modelActivation }) > 0;
  const navigate = useNavigate();
  const location = useLocation();
  const { sessionId } = useParams<{ sessionId: string }>();
  const [searchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const preferences = useAccountPreferences();

  const launchState = (location.state as ChatLaunchState | null) ?? null;
  const queryQuestion = (searchParams.get('q') ?? '').trim();

  const [pendingQuestion, setPendingQuestion] = useState(() => (launchState?.q ?? queryQuestion).trim());
  const [pendingThinking, setPendingThinking] = useState(
    () => (launchState?.thinking !== undefined ? Boolean(launchState.thinking) : getStoredThinkingPreference()),
  );
  const [pendingEffort, setPendingEffort] = useState<ReasoningEffort>(
    () => launchState?.reasoningEffort ?? getStoredReasoningEffortPreference() ?? 'high',
  );
  const [pendingAttachments, setPendingAttachments] = useState<UploadedAttachment[]>(
    () => launchState?.attachments ?? [],
  );
  const [modelOverride, setModelEndpointId] = useState<string | null | undefined>(launchState?.modelEndpointId);
  const modelEndpointId = modelOverride !== undefined ? modelOverride : preferences.data?.defaultModelId || null;
  const [agentOverride, setAgentOverride] = useState<string | null | undefined>(launchState?.agentId);
  const selectedAgentId = agentOverride !== undefined ? agentOverride : preferences.data?.defaultAgentId || null;
  const [draft, setDraft, clearDraft] = useTabDraft('chat:new', queryQuestion);
  const [thinkingOverride, setThinking] = useState<boolean | undefined>(launchState?.thinking);
  const thinking = thinkingOverride ?? getStoredThinkingPreference();
  const [effortOverride, setEffort] = useState<ReasoningEffort | undefined>(launchState?.reasoningEffort);
  const reasoningEffort = effortOverride ?? preferences.data?.reasoningEffort ?? getStoredReasoningEffortPreference() ?? 'high';
  const {
    attachments,
    uploading,
    uploadError,
    fileInputRef,
    openFilePicker,
    addFiles,
    removeAttachment,
    clearAttachments,
    handlePaste,
    handleDrop,
  } = useComposerAttachments(launchState?.attachments ?? []);

  const autoStartRef = useRef(false);

  const handleToggleThinking = () => {
    const next = !thinking;
    setThinking(next);
    setStoredThinkingPreference(next);
  };

  const handleSelectEffort = (tier: ReasoningEffort) => {
    setEffort(tier);
    setStoredReasoningEffortPreference(tier);
    // 账号偏好是跨设备的增强持久化；保存失败不打断本地体验。
    preferences.save({ reasoningEffort: tier });
  };

  const createMutation = useMutation({
    mutationFn: () => createPersonalConversation({ agentId: selectedAgentId || undefined, modelEndpointId }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: advisorKeys.sessions }),
  });
  /** 从空输入框开始一次任务：建会话 → 跳转 → 把问题、思考开关与附件交给对话面板发出（自由模式）。 */
  const startSession = async (
    text: string,
    useDeepThinking: boolean,
    attachedFiles: UploadedAttachment[],
    effort: ReasoningEffort = reasoningEffort,
  ) => {
    const trimmed = text.trim();
    if (createMutation.isPending) return;
    const session = await createMutation.mutateAsync();
    writeDraft(draftKey(identity?.userId, `chat:${session.sessionId}`), trimmed);
    setPendingQuestion(trimmed);
    setPendingThinking(useDeepThinking);
    setPendingEffort(effort);
    setPendingAttachments(attachedFiles);
    setDraft(current => current.trim() === trimmed ? '' : current);
    clearAttachments();
    navigate(`/chat/${session.sessionId}`, { replace: Boolean(launchState?.autoStart) });
  };

  useEffect(() => {
    if (sessionId || !launchState?.autoStart || autoStartRef.current || (identity && preferences.isPending) || preferences.isError) return;
    const initialText = (launchState.q ?? queryQuestion).trim();
    const initialFiles = launchState.attachments ?? [];
    if (!initialText && initialFiles.length === 0) return;
    autoStartRef.current = true;
    void startSession(initialText, launchState.thinking ?? thinking, initialFiles).catch(() => { autoStartRef.current = false; });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, launchState, identity, preferences.isPending, preferences.isError]);

  const canSendDraft = (draft.trim().length > 0 || attachments.length > 0) && !createMutation.isPending && !uploading && !changingModel && !(identity && preferences.isPending) && !preferences.isError;

  const submitEmptyDraft = () => {
    if (!canSendDraft) return;
    const text = draft;
    const currentAttachments = attachments;
    const currentThinking = thinking;
    void startSession(text, currentThinking, currentAttachments).catch(() => undefined);
  };

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        {sessionId ? (
          <ConversationPanel
            key={sessionId}
            sessionId={sessionId}
            initialQuestion={pendingQuestion || undefined}
            initialThinking={pendingQuestion || pendingAttachments.length ? pendingThinking : undefined}
            initialReasoningEffort={pendingQuestion || pendingAttachments.length ? pendingEffort : undefined}
            initialAttachments={pendingAttachments.length > 0 ? pendingAttachments : undefined}
            onQuestionConsumed={() => {
              setPendingQuestion('');
              setPendingAttachments([]);
            }}
          />
        ) : (
          <div className="flex h-full flex-col items-center justify-center px-6">
            <div className="w-full max-w-2xl">
              <h1 className="mb-8 text-center text-[24px] font-semibold tracking-tight text-slate-900">{t(`chat.greeting.${shanghaiGreeting(now)}`)}{displayName ? t('chat.greeting.name', { name: displayName }) : ''}</h1>
              {preferences.isError ? <RequestError error={preferences.error} retry={() => void preferences.refetch()} /> : null}
              {createMutation.isError ? <RequestError error={createMutation.error} /> : null}
              <ComposerDock variant="welcome" draft={draft} setDraft={setDraft} running={false} canSend={canSendDraft}
                agentPicker={{ value: selectedAgentId, onChange: setAgentOverride, disabled: createMutation.isPending }}
                onSend={submitEmptyDraft} onStop={() => {}}
                turns={0} samples={[]} toolCallCount={0} thinking={thinking} onToggleThinking={handleToggleThinking}
                reasoningEffort={reasoningEffort} onSelectReasoningEffort={handleSelectEffort}
                attachments={attachments} uploading={uploading} uploadError={uploadError} fileInputRef={fileInputRef}
                onPickFiles={openFilePicker} onFilesSelected={files => void addFiles(files)} onRemoveAttachment={removeAttachment}
                onPaste={handlePaste} onDrop={handleDrop} selectedModelEndpointId={modelEndpointId} onSelectModel={setModelEndpointId}
                onClearSession={() => { clearDraft(); clearAttachments(); }} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
