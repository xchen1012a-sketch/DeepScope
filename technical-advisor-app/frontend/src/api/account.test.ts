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

import { afterEach, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/apiClient';
import { accountErrorKey, deleteAccountSessions, getAvatar, updatePreferences, uploadAvatar } from './account';
afterEach(() => vi.unstubAllGlobals());
it('fetches avatar bytes with a Bearer header and never puts credentials in the URL', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-only-token' });
  const fetcher = vi.fn(async () => new Response('pixels', { headers: { 'Content-Type': 'image/png' } }));
  vi.stubGlobal('fetch', fetcher);
  await getAvatar(123);
  const [path, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
  expect(path).toBe('/api/me/avatar?v=123');
  expect(new Headers(init.headers).get('Authorization')).toBe('Bearer test-only-token');
});
it('uses multipart without forcing a JSON content type and supports conditional migration', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-only-token' });
  const fetcher = vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetcher);
  await uploadAvatar(new File(['pixels'], 'test.png', { type: 'image/png' }));
  const first = fetcher.mock.calls[0] as unknown as [string, RequestInit];
  expect(first[1].body).toBeInstanceOf(FormData);
  expect(new Headers(first[1].headers).has('Content-Type')).toBe(false);
  await updatePreferences({ thinkEnabled: false }, true);
  const second = fetcher.mock.calls[1] as unknown as [string, RequestInit];
  expect(new Headers(second[1].headers).get('If-None-Match')).toBe('*');
});
it('translates known error codes without rendering arbitrary backend bodies', () => {
  expect(accountErrorKey(new ApiError(400, '{"code":"weak_password"}'))).toBe('account.errors.weak_password');
  expect(accountErrorKey(new ApiError(500, '{"message":"sensitive-internal-detail"}'))).toBe('account.errors.request');
});
it('deletes only confirmed conversations even when another conversation appears before confirmation', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-only-token' });
  const deleted: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') { deleted.push(path); return new Response(null, { status: 204 }); }
    return new Response(JSON.stringify([{ sessionId: 'confirmed', status: 'idle' }, { sessionId: 'new-one', status: 'idle' }]), { headers: { 'Content-Type': 'application/json' } });
  });
  await deleteAccountSessions(['confirmed']);
  expect(deleted).toEqual(['/api/me/sessions/confirmed']);
});
it('does not delete any conversations when a confirmed conversation has started running', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-only-token' });
  const deleted: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') deleted.push(path);
    return new Response(JSON.stringify([{ sessionId: 'idle', status: 'idle' }, { sessionId: 'running', status: 'running' }]), { headers: { 'Content-Type': 'application/json' } });
  });
  await expect(deleteAccountSessions(['idle', 'running'])).rejects.toMatchObject({ body: '{"code":"sessions_running"}' });
  expect(deleted).toEqual([]);
});
it('reports partial failures while still attempting the remaining confirmed conversations', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-only-token' });
  const deleted: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') { deleted.push(path); return new Response(null, { status: path.endsWith('/failed') ? 500 : 204 }); }
    return new Response(JSON.stringify([{ sessionId: 'failed', status: 'idle' }, { sessionId: 'ok', status: 'idle' }]), { headers: { 'Content-Type': 'application/json' } });
  });
  await expect(deleteAccountSessions(['failed', 'ok'])).rejects.toMatchObject({ body: '{"code":"partial_session_delete"}' });
  expect(deleted).toEqual(['/api/me/sessions/failed', '/api/me/sessions/ok']);
});
it('refuses to continue bulk deletion after the account changes during list refresh', async () => {
  let token = 'alice-token';
  vi.stubGlobal('localStorage', { getItem: () => token });
  const deleted: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') deleted.push(path);
    token = 'bob-token';
    return new Response(JSON.stringify([{ sessionId: 'alice-session', status: 'idle' }]), { headers: { 'Content-Type': 'application/json' } });
  });
  await expect(deleteAccountSessions(['alice-session'])).rejects.toMatchObject({ body: '{"code":"account_changed"}' });
  expect(deleted).toEqual([]);
});
it('stops subsequent deletions when the account changes during the first delete', async () => {
  let token = 'alice-token';
  vi.stubGlobal('localStorage', { getItem: () => token });
  const deleted: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') {
      deleted.push(path); token = 'bob-token';
      return new Response(null, { status: 204 });
    }
    return new Response(JSON.stringify([{ sessionId: 'one', status: 'idle' }, { sessionId: 'two', status: 'idle' }]), { headers: { 'Content-Type': 'application/json' } });
  });
  await expect(deleteAccountSessions(['one', 'two'])).rejects.toMatchObject({ body: '{"code":"account_changed"}' });
  expect(deleted).toEqual(['/api/me/sessions/one']);
});
