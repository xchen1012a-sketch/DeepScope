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

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createDraft,
  getDraft,
  updateDraft,
  type CreateDraftRequest,
  type DraftDto,
} from '@/api/agentCreation';

export interface UseAgentCreationDraftOptions {
  initialDraftId?: string | null;
  copyFromId?: string | null;
  onCreated?: (draft: DraftDto) => void;
  deferInitialSave?: boolean;
}

function hasDraftContent(value: CreateDraftRequest): boolean {
  return Boolean(value.purpose?.trim() || value.name?.trim() || value.description?.trim() || value.behavior?.trim()
    || value.tools?.length || value.skills?.length || value.resources?.length);
}

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'conflict' | 'error';

export function useAgentCreationDraft({
  initialDraftId,
  copyFromId,
  onCreated,
  deferInitialSave = false,
}: UseAgentCreationDraftOptions) {
  const [draftId, setDraftId] = useState<string | null>(initialDraftId ?? null);
  const [activation, setActivation] = useState<Pick<DraftDto, 'activationState' | 'activatedAgentId' | 'activationOperationId'>>({});
  const [revision, setRevision] = useState<number>(1);
  const [purpose, setPurpose] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [behavior, setBehavior] = useState('');
  const [tools, setTools] = useState<string[]>([]);
  const [skills, setSkills] = useState<string[]>([]);
  const [resources, setResources] = useState<Array<Record<string, unknown>>>([]);
  const [status, setStatus] = useState<SaveStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const isDirtyRef = useRef(false);
  const editVersion = useRef(0);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inFlightPromiseRef = useRef<Promise<DraftDto | null> | null>(null);
  const latestDraftRef = useRef<DraftDto | null>(null);
  const latestDataRef = useRef({
    purpose,
    name,
    description,
    behavior,
    tools,
    skills,
    resources,
    revision,
    draftId,
  });

  latestDataRef.current = {
    purpose,
    name,
    description,
    behavior,
    tools,
    skills,
    resources,
    revision,
    draftId,
  };

  // Initial load if draftId is supplied
  useEffect(() => {
    if (!initialDraftId) return;
    let active = true;
    getDraft(initialDraftId)
      .then((data) => {
        if (!active) return;
        setDraftId(data.draftId);
        setActivation({ activationState: data.activationState, activatedAgentId: data.activatedAgentId, activationOperationId: data.activationOperationId });
        setRevision(data.revision);
        setPurpose(data.purpose || '');
        setName(data.name || '');
        setDescription(data.description || '');
        setBehavior(data.behavior || '');
        setTools(data.tools || []);
        setSkills(data.skills || []);
        setResources(data.resources || []);
        latestDraftRef.current = data;
        setStatus('saved');
      })
      .catch((err) => {
        if (!active) return;
        setStatus('error');
        setErrorMessage(err.message || '加载草稿失败');
      });
    return () => {
      active = false;
    };
  }, [initialDraftId]);

  // Serial save execution
  const executeSave = useCallback(async (): Promise<DraftDto | null> => {
    while (inFlightPromiseRef.current) {
      try {
        await inFlightPromiseRef.current;
      } catch {
        // ignore in-flight error and retry if still dirty
      }
      if (!isDirtyRef.current && latestDataRef.current.draftId) {
        return latestDraftRef.current;
      }
    }

    const current = latestDataRef.current;
    if (!current.draftId && !copyFromId && !hasDraftContent(current)) {
      isDirtyRef.current = false;
      setStatus('idle');
      return null;
    }
    if (!isDirtyRef.current && current.draftId) {
      return latestDraftRef.current;
    }

    setStatus('saving');
    const sentVersion = editVersion.current;
    const savePromise = (async (): Promise<DraftDto | null> => {
      try {
        if (!current.draftId) {
          // First save creates the draft
          const createReq: CreateDraftRequest = {
            mode: copyFromId ? 'copy' : 'describe',
            sourceAgentId: copyFromId ?? null,
            purpose: current.purpose,
            name: current.name,
            description: current.description,
            behavior: current.behavior,
            tools: current.tools,
            skills: current.skills,
            resources: current.resources,
          };
          const created = await createDraft(createReq);
          setDraftId(created.draftId);
          setRevision(created.revision);
          latestDataRef.current = { ...latestDataRef.current, draftId: created.draftId, revision: created.revision };
          latestDraftRef.current = created;
          isDirtyRef.current = editVersion.current !== sentVersion;
          setStatus(isDirtyRef.current ? 'idle' : 'saved');
          onCreated?.(created);
          return created;
        } else {
          // Subsequent saves update the draft
          const updated = await updateDraft(current.draftId, current.revision, {
            purpose: current.purpose,
            name: current.name,
            description: current.description,
            behavior: current.behavior,
            tools: current.tools,
            skills: current.skills,
            resources: current.resources,
          });
          setRevision(updated.revision);
          latestDataRef.current = { ...latestDataRef.current, revision: updated.revision };
          latestDraftRef.current = updated;
          isDirtyRef.current = editVersion.current !== sentVersion;
          setStatus(isDirtyRef.current ? 'idle' : 'saved');
          return updated;
        }
      } catch (err: any) {
        if (err?.status === 409) {
          setStatus('conflict');
          setErrorMessage('草稿已被并发修改，请刷新最新版本');
        } else {
          setStatus('error');
          setErrorMessage(err?.message || '自动保存失败');
        }
        return null;
      } finally {
        inFlightPromiseRef.current = null;
      }
    })();

    inFlightPromiseRef.current = savePromise;
    return savePromise;
  }, [copyFromId, onCreated]);

  // Trigger 800ms debounce
  const scheduleSave = useCallback(() => {
    editVersion.current += 1;
    isDirtyRef.current = true;
    setStatus('idle');
    if (saveTimerRef.current) {
      clearTimeout(saveTimerRef.current);
    }
    if (deferInitialSave && !latestDataRef.current.draftId) return;
    saveTimerRef.current = setTimeout(() => {
      executeSave();
    }, 800);
  }, [executeSave, deferInitialSave]);

  const updatePurpose = (val: string) => {
    setPurpose(val);
    scheduleSave();
  };

  const updateName = (val: string) => {
    setName(val);
    scheduleSave();
  };

  const updateDescription = (val: string) => {
    setDescription(val);
    scheduleSave();
  };

  const updateBehavior = (val: string) => {
    setBehavior(val);
    scheduleSave();
  };

  const updateTools = (val: string[]) => {
    setTools(val);
    scheduleSave();
  };

  const updateSkills = (val: string[]) => {
    setSkills(val);
    scheduleSave();
  };

  const applyCandidate = (candidate: {
    name?: string;
    description?: string;
    behavior?: string;
  }) => {
    if (candidate.name) setName(candidate.name);
    if (candidate.description) setDescription(candidate.description);
    if (candidate.behavior) setBehavior(candidate.behavior);
    scheduleSave();
  };

  // Immediate flush (e.g. before submit / activate)
  const flush = async (): Promise<DraftDto | null> => {
    if (saveTimerRef.current) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    let saved = await executeSave();
    while (saved && isDirtyRef.current) saved = await executeSave();
    return saved;
  };

  useEffect(() => {
    return () => {
      if (saveTimerRef.current) {
        clearTimeout(saveTimerRef.current);
      }
    };
  }, []);

  const discardChanges = () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = null;
    isDirtyRef.current = false;
    const saved = latestDraftRef.current;
    setPurpose(saved?.purpose ?? ''); setName(saved?.name ?? '');
    setDescription(saved?.description ?? ''); setBehavior(saved?.behavior ?? '');
    setTools(saved?.tools ?? []); setSkills(saved?.skills ?? []); setResources(saved?.resources ?? []);
    setStatus(saved ? 'saved' : 'idle'); setErrorMessage(null);
  };

  return {
    ...activation,
    draftId,
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
    hasUnsavedChanges: isDirtyRef.current && Boolean(draftId || copyFromId || hasDraftContent(latestDataRef.current)),
    discardChanges,
  };
}
