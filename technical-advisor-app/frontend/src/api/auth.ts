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

import { getAccountIdentity, setAccountIdentity } from '@/lib/accountIdentity';
import { api, clearToken, getToken, saveToken } from '@/lib/apiClient';

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  username: string;
  nickname?: string;
  avatarUrl?: string | null;
  avatarUpdatedAt?: number;
  createdAt?: number;
  roles: string[];
  permissions: string[];
  roleNames?: string[];
  isAdmin: boolean;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await api.post<LoginResponse>('/api/auth/login', { username, password });
  saveToken(res.token);
  return res;
}

export async function me(): Promise<MeResponse> {
  const token = getToken();
  const account = await api.get<MeResponse>('/api/auth/me');
  if (token && token === getToken()) setAccountIdentity(token, account);
  return account;
}

/** Revokes the login session server-side, then drops the local token. */
export async function logout() {
  try {
    await api.post('/api/auth/logout');
  } finally {
    clearToken();
  }
}

export { getToken, clearToken };

export function getUsername(): string {
  const account = getAccountIdentity(getToken());
  return account?.username || '';
}

export function getRoles(): string[] {
  return getAccountIdentity(getToken())?.roles || [];
}

export function isAdmin(): boolean {
  return getRoles().some((role) => role.toLowerCase() === 'admin');
}

export function can(permission: string): boolean {
  const permissions = getAccountIdentity(getToken())?.permissions;
  if (!permissions) {
    return permission === 'settings.manage' || permission === 'rbac.manage'
      || /^(model|user|role)\./.test(permission) ? isAdmin() : true;
  }
  return permissions.includes(permission);
}
