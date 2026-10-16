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

import { test, expect } from '@playwright/test';
import { listPageFixture } from './listPageFixture';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { localStorage.setItem('claw_token', 'local-ui-test'); localStorage.setItem('controlplane.console.locale', 'en'); });
  const agents = [{ agentId: 'a', name: 'Alpha', description: 'Review code', origin: 'personal', mine: true, canEdit: true, counts: { skills: 0, subagents: 0 } }, { agentId: 'b', name: 'Beta', description: 'Meeting notes', origin: 'personal', mine: true, canEdit: true, counts: { skills: 0, subagents: 0 } }];
  const files: Record<string, string> = { a: 'Alpha instructions', b: 'Beta instructions' };
  await page.route('http://127.0.0.1:5190/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });
    if (path === '/api/auth/me') return json({ userId: 'u', username: 'Tester', roles: ['admin'], isAdmin: true, permissions: ['agent.view', 'agent.manage', 'chat.use'] });
    if (path === '/api/advisor/agents') return json(agents);
    if (path === '/api/advisor/agent-overview') return json(listPageFixture(request.url(), agents.map(agent => ({ id: agent.agentId, name: agent.name, description: agent.description, draft: false }))));
    if (path === '/api/advisor/agent-creation-drafts') return json([]);
    const match = path.match(/^\/api\/advisor\/agents\/(a|b)(\/files)?$/);
    if (match) {
      const id = match[1];
      if (match[2]) {
        if (request.method() === 'PUT') { files[id] = request.postDataJSON().content; return route.fulfill({ status: 200 }); }
        return json({ content: files[id] });
      }
      const agent = agents.find(value => value.agentId === id)!;
      if (request.method() === 'PATCH') Object.assign(agent, request.postDataJSON());
      return json({ ...agent, sections: { tools: [], skills: [], knowledge: [], subagents: [] } });
    }
    if (path === '/api/v1/me/scope') return json({ user: { userId: 'u', username: 'Tester' }, permissions: ['agent.view', 'agent.manage', 'chat.use'] });
    return json([]);
  });
});

test('desktop split view edits in place and protects browser history navigation', async ({ page }) => {
  await page.goto('/agent');
  const list = page.getByRole('complementary', { name: 'Agents', exact: true });
  await list.getByRole('button', { name: /Alpha/ }).click();
  await expect(page.getByRole('textbox', { name: 'Work instructions' })).toHaveValue('Alpha instructions');
  await list.getByRole('button', { name: /Beta/ }).click();
  await page.getByRole('textbox', { name: 'Work instructions' }).fill('Keep Beta edits');
  await page.goBack();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Keep editing' }).click();
  await expect(page.getByRole('textbox', { name: 'Work instructions' })).toHaveValue('Keep Beta edits');
  await expect(page).toHaveURL(/\/agent\/b$/);
  await page.goBack();
  await page.getByRole('button', { name: 'Save and switch' }).click();
  await expect(page).toHaveURL(/\/agent\/a$/);
  await expect(page.getByRole('textbox', { name: 'Work instructions' })).toHaveValue('Alpha instructions');
  await page.goForward();
  await expect(page.getByRole('textbox', { name: 'Work instructions' })).toHaveValue('Keep Beta edits');
});

test('mobile selection has a clear return to the list', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/agent');
  const row = page.getByRole('row').filter({ hasText: 'Alpha' });
  await row.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('table')).toBeHidden();
  await expect(page.getByRole('textbox', { name: 'Work instructions' })).toBeVisible();
  await page.getByRole('button', { name: 'Back to agents' }).click();
  await expect(page.getByRole('table')).toBeVisible();
});
