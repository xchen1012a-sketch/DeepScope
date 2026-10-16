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

import { createSession, deleteSession } from '@/api/advisor';
import { getAvailableAgent } from '@/api/agent';
import { can } from '@/api/auth';
import { apiFetch, ApiError } from '@/lib/apiClient';

export async function createPersonalConversation(defaults: { agentId?: string; modelEndpointId?: string | null }) {
  if (defaults.agentId && !(await getAvailableAgent(defaults.agentId))) throw unavailable();
  let selectedModel = defaults.modelEndpointId;
  if (selectedModel) {
    const active = await apiFetch<{ endpointId?: string }>('/api/advisor/models/active');
    if (active.endpointId === selectedModel) selectedModel = null;
    else {
      if (!can('settings.manage')) throw unavailable();
      const models = await apiFetch<{ chat: Array<{ endpointId: string }> }>('/api/advisor/models');
      if (!models.chat.some(model => model.endpointId === selectedModel)) throw unavailable();
    }
  }
  const session = await createSession(defaults.agentId || undefined);
  if (selectedModel) {
    try {
      await apiFetch<void>('/api/advisor/sessions/' + encodeURIComponent(session.sessionId) + '/model', {
        method: 'PUT', body: JSON.stringify({ endpointId: selectedModel }),
      });
    } catch (error) {
      try { await deleteSession(session.sessionId); } catch { /* Preserve the original binding error. */ }
      throw error;
    }
  }
  return session;
}
function unavailable() {
  return new ApiError(400, '{"code":"invalid_preferences"}');
}
