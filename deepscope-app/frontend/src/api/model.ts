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
import { ApiError, api, apiFetch } from '@/lib/apiClient';

export type ModelKind = 'chat' | 'embedding';
export type ModelProvider = 'openai' | 'anthropic' | 'dashscope' | 'custom';
export interface ModelEndpoint {
  presetId?: string | null;
  providerName?: string | null;
  providerKeysUrl?: string | null;
  endpointId: string;
  kind: ModelKind;
  provider: ModelProvider;
  modelName: string;
  displayName?: string | null;
  baseUrl: string;
  secretMask: string;
  current: boolean;
  knowledgePdf?: boolean;
  dimensions?: number | null;
  lastTestStatus?: 'passed' | 'failed' | null;
  lastTestAt?: number | null;
  lastTestElapsedMs?: number | null;
  lastTestMessage?: string | null;
}
export interface ModelInput {
  kind: ModelKind;
  provider: ModelProvider;
  modelName: string;
  displayName?: string;
  baseUrl: string;
  secret?: string;
  makeCurrent?: boolean;
  knowledgePdf?: boolean;
  endpointId?: string;
}
export interface ModelTestResult { ok: boolean; message: string; elapsedMs: number; dimensions?: number | null }
export interface ModelProviderPreset { id: string; name: string; provider: ModelProvider; baseUrl: string; keysUrl?: string }
export interface ModelOptions<T> { items: T[]; total: number; source?: 'preset' | 'discovered'; complete?: boolean; hasMore?: boolean; page?: number; snapshot?: string }
export interface ModelDiscovery { items: string[]; total: number; complete: boolean; status: 'discovered' | 'empty' }
export interface ModelSettings { chat: ModelEndpoint[]; embedding: ModelEndpoint[]; defaults?: { chat: ModelEndpoint[]; embedding: ModelEndpoint[] } }
export const modelKeys = { all: ['advisor', 'models'] as const };
const base = '/api/advisor/models';
export const listModels = (query = '') => api.get<ModelSettings>(listQuery(base, query));
export const createModel = (input: ModelInput) => api.post<ModelEndpoint>(base, input);
export const updateModel = (id: string, input: ModelInput) => apiFetch<ModelEndpoint>(`${base}/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(input) });
export const testModelDraft = (input: ModelInput) => api.post<ModelTestResult>(`${base}/test`, input);
export const testModel = (id: string) => api.post<ModelTestResult>(`${base}/${encodeURIComponent(id)}/test`);
export const duplicateModel = (id: string, displayName: string) => api.post<ModelEndpoint>(`${base}/${encodeURIComponent(id)}/copy`, { displayName });
export interface ModelCopyConfig { kind: ModelKind; provider: ModelProvider; providerName: string; displayName: string; baseUrl: string; modelName: string; secret: string }
/** Direct copy requests keep plaintext out of Query and mutation caches. */
export const getModelCopyConfig = (id: string) => apiFetch<ModelCopyConfig>(`${base}/${encodeURIComponent(id)}/copy-config`, { method: 'POST', cache: 'no-store' });
export async function discoverModels(input: ModelInput): Promise<ModelDiscovery> {
  const result = await api.post<ModelDiscovery | string[]>(`${base}/discover`, { ...input, detailed: true });
  return Array.isArray(result) ? { items: result, total: result.length, complete: false, status: result.length ? 'discovered' : 'empty' } : result;
}
export const searchProviders = (kind: ModelKind, q: string, signal?: AbortSignal) => apiFetch<ModelOptions<ModelProviderPreset>>(`${base}/providers?${new URLSearchParams({ kind, q, limit: '30' })}`, { signal });
export const searchModelOptions = (input: ModelInput, presetId: string, q: string, signal?: AbortSignal, page = 1, snapshot?: string) => apiFetch<ModelOptions<string>>(`${base}/options`, { method: 'POST', body: JSON.stringify({ ...input, presetId, q, page, limit: 30, ...(snapshot ? { snapshot } : {}) }), signal });
export const activateModel = (id: string) => apiFetch<void>(`${base}/${encodeURIComponent(id)}/current`, { method: 'POST' });
export const deleteModel = (id: string) => apiFetch<void>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const modelLabel = (model: ModelEndpoint) => model.displayName || model.modelName;
export function modelError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    try {
      const body: unknown = JSON.parse(error.body);
      if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') return body.message;
    } catch { /* Use the translated fallback for non-JSON responses. */ }
  }
  return fallback;
}

export function modelDiscoveryErrorKey(error: unknown): string {
  if (error instanceof ApiError) {
    try {
      const body: unknown = JSON.parse(error.body);
      const code = body && typeof body === 'object' && 'error' in body ? body.error : '';
      const known: Record<string, string> = {
        model_discovery_auth: 'discoveryAuth', model_discovery_rate_limited: 'discoveryRateLimit',
        model_discovery_unsupported: 'discoveryUnsupported', model_discovery_endpoint_not_found: 'discoveryEndpoint',
        model_discovery_timeout: 'discoveryTimeout', model_discovery_invalid_response: 'discoveryFormat',
        model_discovery_invalid_pagination: 'discoveryPagination', model_discovery_invalid_address: 'invalidUrl',
      };
      if (typeof code === 'string' && Object.prototype.hasOwnProperty.call(known, code)) return `modelSettings.manage.${known[code]}`;
    } catch { /* Fall back without exposing provider bodies or credentials. */ }
  }
  return 'modelSettings.manage.discoverError';
}
