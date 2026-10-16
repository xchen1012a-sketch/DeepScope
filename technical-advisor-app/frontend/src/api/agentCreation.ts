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

import { listQuery } from '@/api/listQuery';
import { apiFetch } from '@/lib/apiClient';

export interface DraftDto {
  activationState?: 'draft' | 'publishing' | 'activated' | 'needs_review';
  activatedAgentId?: string | null;
  activationOperationId?: string | null;
  draftId: string;
  ownerUserId: string;
  mode: string;
  sourceAgentId: string | null;
  purpose: string;
  name: string;
  description: string;
  behavior: string;
  tools: string[];
  skills: string[];
  resources: Array<Record<string, unknown>>;
  revision: number;
  createdAt: number;
  updatedAt: number;
}

export interface CreateDraftRequest {
  mode?: string;
  sourceAgentId?: string | null;
  purpose?: string;
  name?: string;
  description?: string;
  behavior?: string;
  tools?: string[];
  skills?: string[];
  resources?: Array<Record<string, unknown>>;
}

export interface UpdateDraftRequest {
  purpose?: string;
  name?: string;
  description?: string;
  behavior?: string;
  tools?: string[];
  skills?: string[];
  resources?: Array<Record<string, unknown>>;
}

export interface CandidateGenerationResult {
  operationId: string;
  draftId: string;
  operationType: string;
  requestKey: string;
  baseRevision: number;
  state: 'queued' | 'running' | 'succeeded' | 'failed' | 'interrupted' | 'canceled';
  stage: string;
  candidate?: {
    name?: string;
    description?: string;
    behavior?: string;
  };
  preallocatedAgentId?: string;
  errorCode?: string;
  errorMessage?: string;
  startedAt: number;
  finishedAt?: number;
}

export interface CheckIssue {
  code: string;
  severity: 'error' | 'warning';
  section: string;
  messageKey: string;
  resourceId?: string | null;
}

export interface CheckResult {
  issues: CheckIssue[];
}

export interface PreviewDto {
  previewId: string;
  draftId: string;
  draftRevision: number;
  ownerUserId: string;
  state: string;
  history: Array<{ role: string; content: string }>;
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

export const agentCreationKeys = {
  drafts: ['agent-creation-drafts'] as const,
  draft: (draftId: string) => ['agent-creation-drafts', draftId] as const,
  generation: (draftId: string, opId: string) =>
    ['agent-creation-generations', draftId, opId] as const,
  preview: (draftId: string, previewId: string) =>
    ['agent-creation-previews', draftId, previewId] as const,
};

export function createDraft(
  body: CreateDraftRequest,
  idempotencyKey?: string,
): Promise<DraftDto> {
  const headers: Record<string, string> = {};
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  return apiFetch<DraftDto>('/api/advisor/agent-creation-drafts', {
    method: 'POST',
    body: JSON.stringify(body),
    headers,
  });
}

export function listDrafts(query = ''): Promise<DraftDto[]> {
  return apiFetch<DraftDto[]>(listQuery('/api/advisor/agent-creation-drafts', query));
}

export function getDraft(draftId: string): Promise<DraftDto> {
  return apiFetch<DraftDto>(`/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}`);
}

export function updateDraft(
  draftId: string,
  revision: number,
  body: UpdateDraftRequest,
): Promise<DraftDto> {
  return apiFetch<DraftDto>(`/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
    headers: { 'If-Match': String(revision) },
  });
}

export function deleteDraft(draftId: string): Promise<void> {
  return apiFetch<void>(`/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}`, {
    method: 'DELETE',
  });
}

export function submitGeneration(
  draftId: string,
  baseRevision: number,
  idempotencyKey?: string,
): Promise<CandidateGenerationResult> {
  const headers: Record<string, string> = {};
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  return apiFetch<CandidateGenerationResult>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/generations`,
    {
      method: 'POST',
      body: JSON.stringify({ baseRevision }),
      headers,
    },
  );
}

export function getGeneration(
  draftId: string,
  operationId: string,
): Promise<CandidateGenerationResult> {
  return apiFetch<CandidateGenerationResult>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/generations/${encodeURIComponent(operationId)}`,
  );
}

export function cancelGeneration(
  draftId: string,
  operationId: string,
): Promise<CandidateGenerationResult> {
  return apiFetch<CandidateGenerationResult>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/generations/${encodeURIComponent(operationId)}/cancel`,
    { method: 'POST' },
  );
}

export function applyGeneration(
  draftId: string,
  operationId: string,
  baseRevision: number,
  candidateFields?: { name?: string; description?: string; behavior?: string },
): Promise<DraftDto> {
  return apiFetch<DraftDto>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/generations/${encodeURIComponent(operationId)}/apply`,
    {
      method: 'POST',
      body: JSON.stringify(candidateFields ?? {}),
      headers: { 'If-Match': String(baseRevision) },
    },
  );
}

export function checkDraft(draftId: string, revision?: number): Promise<CheckResult> {
  return apiFetch<CheckResult>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/checks`,
    {
      method: 'POST',
      body: JSON.stringify({ revision }),
    },
  );
}

export function activateDraft(
  draftId: string,
  revision: number,
  idempotencyKey?: string,
): Promise<CandidateGenerationResult> {
  const headers: Record<string, string> = {};
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  return apiFetch<CandidateGenerationResult>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/activations`,
    {
      method: 'POST',
      body: JSON.stringify({ revision }),
      headers,
    },
  );
}

export function getActivation(draftId: string, operationId: string): Promise<CandidateGenerationResult> {
  return apiFetch('/api/advisor/agent-creation-drafts/' + encodeURIComponent(draftId) + '/activations/' + encodeURIComponent(operationId));
}

export function createPreview(draftId: string, revision: number): Promise<PreviewDto> {
  return apiFetch<PreviewDto>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/previews`,
    {
      method: 'POST',
      body: JSON.stringify({ revision }),
    },
  );
}

export function getPreview(draftId: string, previewId: string): Promise<PreviewDto> {
  return apiFetch<PreviewDto>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/previews/${encodeURIComponent(previewId)}`,
  );
}

export function runPreviewTurn(
  draftId: string,
  previewId: string,
  input: string,
): Promise<{ previewId: string; reply: string }> {
  return apiFetch<{ previewId: string; reply: string }>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/previews/${encodeURIComponent(previewId)}/runs`,
    {
      method: 'POST',
      body: JSON.stringify({ input }),
    },
  );
}

export function deletePreview(draftId: string, previewId: string): Promise<void> {
  return apiFetch<void>(
    `/api/advisor/agent-creation-drafts/${encodeURIComponent(draftId)}/previews/${encodeURIComponent(previewId)}`,
    { method: 'DELETE' },
  );
}
