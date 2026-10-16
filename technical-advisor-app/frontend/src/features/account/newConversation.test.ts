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
import { createPersonalConversation } from './newConversation';
import { createSession, deleteSession } from '@/api/advisor';
import { getAvailableAgent } from '@/api/agent';
import { can } from '@/api/auth';
import { apiFetch } from '@/lib/apiClient';
vi.mock('@/api/advisor', () => ({ createSession: vi.fn(async () => ({ sessionId: 'new-session' })), deleteSession: vi.fn(async () => undefined) }));
vi.mock('@/api/agent', () => ({ getAvailableAgent: vi.fn(async () => ({ agentId: 'chosen-agent' })) }));
vi.mock('@/api/auth', () => ({ can: vi.fn(() => true) }));
vi.mock('@/lib/apiClient', async importOriginal => ({ ...await importOriginal<typeof import('@/lib/apiClient')>(), apiFetch: vi.fn(async (path: string) => path === '/api/advisor/models/active' ? { endpointId: 'current' } : { chat: [{ endpointId: 'chosen-model' }] }) }));
afterEach(() => vi.clearAllMocks());
it('binds the personal agent and selects the saved model only on the new session', async () => {
  await createPersonalConversation({ agentId: 'chosen-agent', modelEndpointId: 'chosen-model' });
  expect(createSession).toHaveBeenCalledWith('chosen-agent');
  expect(apiFetch).toHaveBeenCalledWith('/api/advisor/sessions/new-session/model', expect.objectContaining({ method: 'PUT', body: '{"endpointId":"chosen-model"}' }));
});
it('does not call the management-only model selection endpoint for the current model', async () => {
  vi.mocked(can).mockReturnValue(false);
  await createPersonalConversation({ modelEndpointId: 'current' });
  expect(apiFetch).not.toHaveBeenCalledWith(expect.stringContaining('/sessions/'), expect.anything());
});
it('rejects a removed default agent before creating an unrelated free-mode session', async () => {
  vi.mocked(getAvailableAgent).mockResolvedValueOnce(null);
  await expect(createPersonalConversation({ agentId: 'removed' })).rejects.toThrow();
  expect(createSession).not.toHaveBeenCalled();
});
it('cleans up the session when applying the saved model fails', async () => {
  vi.mocked(can).mockReturnValue(true);
  vi.mocked(apiFetch).mockImplementationOnce(async () => ({ endpointId: 'current' }) as never)
    .mockImplementationOnce(async () => ({ chat: [{ endpointId: 'chosen-model' }] }) as never)
    .mockImplementationOnce(async () => { throw new Error('model binding failed'); });
  await expect(createPersonalConversation({ modelEndpointId: 'chosen-model' })).rejects.toThrow('model binding failed');
  expect(deleteSession).toHaveBeenCalledWith('new-session');
});
