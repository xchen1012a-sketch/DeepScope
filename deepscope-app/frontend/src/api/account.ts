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

import { apiFetch, apiResponse, ApiError, getToken } from '@/lib/apiClient';
import { setAccountIdentity } from '@/lib/accountIdentity';
import type { MeResponse } from './auth';
import { listSessions } from './advisor';

export interface AccountPreferences {
  /** Display-only preference; model thinking is controlled by the composer separately. */
  thinkEnabled: boolean;
  reasoningEffort: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  defaultModelId: string;
  defaultAgentId: string;
  initialized: boolean;
}
export type PreferenceUpdate = Partial<Omit<AccountPreferences, 'initialized'>>;
export interface CredentialSummary { toolId: string; name: string; origin: string; configured: boolean }
export interface AccountOverview {
  agents: number | null; skills: number | null; knowledge: number | null; tools: number | null;
  credentials: CredentialSummary[];
}
export interface AvatarInfo { avatarUrl: string; avatarUpdatedAt: number }
export const accountKeys = {
  root: (userId: string) => ['account', userId] as const,
  profile: (userId: string) => ['account', userId, 'profile'] as const,
  preferences: (userId: string) => ['account', userId, 'preferences'] as const,
};
export async function updateNickname(nickname: string): Promise<MeResponse> {
  const token = getToken();
  const value = await apiFetch<MeResponse>('/api/me/profile', { method: 'PUT', body: JSON.stringify({ nickname }) });
  if (token && token === getToken()) setAccountIdentity(token, value);
  return value;
}
export const changePassword = (currentPassword: string, newPassword: string) =>
  apiFetch<void>('/api/me/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) });
export const revokeOtherSessions = () => apiFetch<void>('/api/me/sessions/revoke-others', { method: 'POST' });
export async function uploadAvatar(file: File): Promise<AvatarInfo> {
  const body = new FormData(); body.append('file', file);
  return apiFetch<AvatarInfo>('/api/me/avatar', { method: 'POST', body });
}
export const deleteAvatar = () => apiFetch<void>('/api/me/avatar', { method: 'DELETE' });
export const getAvatar = async (updatedAt: number, signal?: AbortSignal): Promise<Blob> =>
  (await apiResponse('/api/me/avatar?' + new URLSearchParams({ v: String(updatedAt) }), { signal })).blob();
export const getPreferences = (signal?: AbortSignal) => apiFetch<AccountPreferences>('/api/me/preferences', { signal });
export const updatePreferences = (value: PreferenceUpdate, initializeOnly = false, signal?: AbortSignal) =>
  apiFetch<AccountPreferences>('/api/me/preferences', { method: 'PUT', body: JSON.stringify(value), signal,
    ...(initializeOnly ? { headers: { 'If-None-Match': '*' } } : {}) });
export const getAccountOverview = () => apiFetch<AccountOverview>('/api/me/overview');

/** Delete only the confirmed list, refreshing status before touching any session. */
export async function deleteAccountSessions(sessionIds: string[]): Promise<void> {
  const token = getToken();
  const current = await listSessions();
  const confirmed = new Set(sessionIds);
  const targets = current.filter(session => confirmed.has(session.sessionId));
  const sameAccount = () => token !== null && token === getToken();
  if (!sameAccount()) throw new ApiError(409, '{"code":"account_changed"}');
  if (targets.some(session => session.status === 'running'))
    throw new ApiError(409, '{"code":"sessions_running"}');
  let failed = false;
  for (const session of targets) {
    if (!sameAccount()) throw new ApiError(409, '{"code":"account_changed"}');
    try { await apiResponse('/api/me/sessions/' + encodeURIComponent(session.sessionId), { method: 'DELETE' }); }
    catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) failed = true;
    }
  }
  if (failed) throw new ApiError(409, '{"code":"partial_session_delete"}');
}

/** Only known codes are translated; raw server bodies can contain implementation details. */
export function accountErrorKey(error: unknown): string {
  const known = new Set(['invalid_nickname', 'current_password_mismatch', 'weak_password', 'invalid_avatar', 'invalid_preferences', 'sessions_running', 'partial_session_delete', 'account_changed']);
  if (error instanceof ApiError) {
    try {
      const body: unknown = JSON.parse(error.body);
      if (body && typeof body === 'object' && 'code' in body && typeof body.code === 'string' && known.has(body.code))
        return ['account', 'errors', body.code].join('.');
    } catch { /* Non-JSON responses use the localized fallback. */ }
  }
  return 'account.errors.request';
}
