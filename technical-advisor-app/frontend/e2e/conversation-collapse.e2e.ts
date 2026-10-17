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
import type { EventEntry } from '../src/api/advisor';

for (const scenario of ['completed', 'failed', 'long-process'] as const) {
  const failed = scenario === 'failed';
  const longProcess = scenario === 'long-process';
  test(longProcess ? 'manual expansion keeps a long process entry in the viewport' : failed ? 'failed run leaves process visible without successful elapsed entry' : 'confirmed run folds every process, replays elapsed time and locates folded content', async ({ page }) => {
    const session = { sessionId: 'collapse', title: 'Conversation test', agentId: 'agent', status: 'idle', createdAt: 0, updatedAt: 0 };
    const entries: EventEntry[] = [];
    await page.addInitScript(() => { localStorage.setItem('deepscope_token', 'fixture'); localStorage.setItem('deepscope.locale', 'zh-CN'); });
    await page.route(/^http:\/\/127\.0\.0\.1:5190\/api\//, async route => {
      const request = route.request(); const path = new URL(request.url()).pathname;
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });
      if (path === '/api/auth/me') return json({ userId: 'u', username: 'Tester', isAdmin: true, roles: ['admin'], permissions: ['chat.use', 'agent.view'] });
      if (path === '/api/v1/me/scope') return json({ user: { userId: 'u' }, permissions: ['chat.use', 'agent.view'] });
      if (path === '/api/advisor/sessions') return json([session]);
      if (path.endsWith('/route')) return json({ mode: 'locked', agentId: 'agent' });
      if (path.endsWith('/messages')) {
        const body = request.postDataJSON();
        const entry = { seq: entries.length + 1, turn: 1, kind: body.role === 'visitor' ? 'user_message' : 'advisor_message', payload: body, createdAt: 1000 };
        entries.push(entry); return json(entry, 201);
      }
      if (path === '/api/advisor/sessions/collapse') return json({ session, entries, deliverables: [] });
      if (path.endsWith('/timeline') || path.endsWith('/subagents')) return json({ items: [], page: 1, size: 10, total: 0, hasMore: false });
      if (path === '/api/advisor/models') return json({ chat: [] });
      if (path === '/api/advisor/models/active') return json({ modelName: 'fixture', contextWindow: 32000 });
      return json([]);
    });
    await page.route('**/agui/run/agent', async route => {
      const runId = route.request().postDataJSON().runId;
      const wire = [
        { type: 'RUN_STARTED' },
        { type: 'TEXT_MESSAGE_CONTENT', messageId: 'before', delta: longProcess ? '先检查配置\n\n' + '过程记录：检查当前配置与调用结果。\n\n'.repeat(100) : '先检查配置' },
        { type: 'TOOL_CALL_START', toolCallId: 't', toolCallName: 'read_file' },
        { type: 'TOOL_CALL_ARGS', toolCallId: 't', delta: '{"path":"config.yaml"}' },
        { type: 'TOOL_CALL_RESULT', toolCallId: 't', content: 'config body' },
        ...(failed ? [{ type: 'RUN_ERROR', message: 'fixture failure' }] : [
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'final-a', delta: '最终结论A' },
          { type: 'TEXT_MESSAGE_END', messageId: 'final-a' },
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'final-b', delta: '最终结论B' },
          { type: 'TEXT_MESSAGE_END', messageId: 'final-b' },
          { type: 'RUN_FINISHED' },
          { type: 'CUSTOM', name: 'advisor_presentation', value: { presentationVersion: 1, runId, runStartedAt: 1000, finalStartedAt: 83000, runEndedAt: 95000, finalMessageIds: ['final-a', 'final-b'], outcome: 'completed' } },
        ]),
      ].map(event => `data: ${JSON.stringify({ threadId: 'collapse', runId, ...event })}\n\n`).join('');
      await route.fulfill({ contentType: 'text/event-stream', body: wire });
    });
    await page.goto('/chat/collapse');
    const transcript = page.locator('[data-conversation-scroll]');
    await page.getByRole('textbox').fill('检查配置');
    await page.getByRole('textbox').press('Enter');
    if (failed) {
      await expect(page.getByText('fixture failure', { exact: true })).toBeVisible();
      await expect(transcript.getByText('先检查配置', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: /用时/ })).toHaveCount(0);
      return;
    }
    const elapsed = page.getByRole('button', { name: '用时 1分钟 22秒', exact: true });
    await expect(elapsed).toBeVisible();
    if (longProcess) {
      const before = await elapsed.boundingBox();
      await elapsed.click();
      await expect(elapsed).toBeInViewport();
      const after = await elapsed.boundingBox();
      expect(Math.abs(after!.y - before!.y)).toBeLessThan(8);
      return;
    }
    await expect(transcript.getByText('先检查配置', { exact: true })).toHaveCount(0);
    await expect(page.getByText('最终结论A', { exact: true })).toBeVisible();
    await expect(page.getByText('最终结论B', { exact: true })).toBeVisible();
    await elapsed.click();
    await expect(transcript.getByText('先检查配置', { exact: true })).toBeVisible();
    await elapsed.click();
    await page.reload();
    await expect(elapsed).toBeVisible();
    await expect(transcript.getByText('先检查配置', { exact: true })).toHaveCount(0);
    await page.screenshot({ path: '/tmp/advisor-conversation-collapse-folded.png', fullPage: true });
    await page.getByRole('button', { name: '显示过程面板', exact: true }).click();
    await page.getByRole('dialog').getByRole('button').filter({ hasText: '先检查配置' }).click();
    await expect(transcript.getByText('先检查配置', { exact: true })).toBeVisible();
    await page.screenshot({ path: '/tmp/advisor-conversation-collapse-ui.png', fullPage: true });
  });
}
