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
import { listAgents, pageAvailableAgents, routeSession } from './agent';

afterEach(() => vi.unstubAllGlobals());

it('keeps the server agent catalog available for selection and permission checks', async () => {
  vi.stubGlobal('localStorage', { getItem: () => null });
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify([
    { agentId: 'diligence-helper', name: '尽调助手', description: '', origin: 'personal',
      mine: false, canEdit: false, counts: { skills: 3, subagents: 2 } },
  ]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  const agents = await listAgents();
  expect(agents).toHaveLength(1);
  expect(agents[0].agentId).toBe('diligence-helper');
  expect(agents[0].canEdit).toBe(false);
});
it('sends server pagination and keyword requests without downloading or filtering the full catalog', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-token' });
  const requests: string[] = [];
  vi.stubGlobal('fetch', async (path: string) => {
    requests.push(path);
    return new Response(JSON.stringify({ items: [], page: 2, size: 5, total: 7, totalPages: 2 }), { headers: { 'Content-Type': 'application/json' } });
  });
  await pageAvailableAgents('contracts', 2, 5);
  const url = new URL(requests[0], 'https://local.test');
  expect(url.pathname).toBe('/api/advisor/agents/available');
  expect(Object.fromEntries(url.searchParams)).toEqual({ page: '2', size: '5', q: 'contracts' });
});
it('distinguishes explicit free mode from an omitted selection in routing requests', async () => {
  vi.stubGlobal('localStorage', { getItem: () => 'test-token' });
  const bodies: unknown[] = [];
  vi.stubGlobal('fetch', async (_path: string, init: RequestInit) => {
    bodies.push(JSON.parse(String(init.body)));
    return new Response('{}', { headers: { 'Content-Type': 'application/json' } });
  });
  await routeSession('session', 'hello', false, null);
  await routeSession('session', 'hello');
  expect(bodies).toEqual([{ text: 'hello', selectedAgentId: null }, { text: 'hello' }]);
});
