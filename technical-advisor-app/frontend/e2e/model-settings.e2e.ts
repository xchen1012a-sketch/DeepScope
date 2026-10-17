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

import { test, expect, type Page } from '@playwright/test';
import { listPageFixture } from './listPageFixture';

async function mockModels(page: Page, language = 'en') {
  await page.addInitScript(language => {
    localStorage.setItem('deepscope_token', 'model-ui-test');
    localStorage.setItem('deepscope.locale', language);
  }, language);
  const models = [
    { endpointId: 'one', kind: 'chat', provider: 'dashscope', modelName: 'qwen-plus', displayName: 'Qwen Plus', current: true, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', secretMask: '••••', lastTestStatus: 'passed', lastTestAt: Date.now(), lastTestElapsedMs: 820 },
    { endpointId: 'two', kind: 'chat', provider: 'anthropic', modelName: 'claude-sonnet-4-5', displayName: 'Claude Sonnet', current: false, baseUrl: 'https://api.anthropic.com', secretMask: '••••', lastTestStatus: 'failed', lastTestAt: Date.now(), lastTestElapsedMs: 210, lastTestMessage: language === 'en' ? 'Invalid API key' : '密钥无效' },
  ];
  const calls: { path: string; body?: Record<string, unknown> }[] = [];
  await page.route('http://127.0.0.1:5190/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ json: body });
    if (path === '/api/auth/me') return json({ userId: 'u', username: 'Tester', roles: ['admin'], isAdmin: true, permissions: ['settings.view', 'settings.manage'] });
    if (path === '/api/v1/me/scope') return json({ user: { userId: 'u', username: 'Tester' }, permissions: ['settings.view', 'settings.manage'] });
    if (path === '/api/advisor/models' && request.method() === 'GET') {
      const url = new URL(request.url());
      if (!url.searchParams.has('page')) return json({ chat: models, embedding: [] });
      const kind = url.searchParams.get('kind') || 'chat';
      return json(listPageFixture(url, models.filter(model => model.kind === kind), {
        filters: ['provider'], defaultModel: models.find(model => model.kind === kind && model.current) ?? null,
      }));
    }
    if (path.startsWith('/api/advisor/models') && request.method() !== 'GET') {
      const body = request.postData() ? request.postDataJSON() as Record<string, unknown> : undefined;
      calls.push({ path, body });
      if (path.endsWith('/test')) return json({ ok: true, elapsedMs: 420, message: '' });
      if (path.endsWith('/discover')) return json(['gpt-4.1', 'gpt-4.1-mini']);
      if (path.endsWith('/current')) {
        models.forEach(model => { model.current = path.includes(`/${model.endpointId}/`); });
        return route.fulfill({ status: 204 });
      }
      if (request.method() === 'DELETE') {
        models.splice(models.findIndex(model => path.endsWith(`/${model.endpointId}`)), 1);
        return route.fulfill({ status: 204 });
      }
      return json({ endpointId: 'new', ...body });
    }
    return json([]);
  });
  return calls;
}

test('connects, validates, edits and changes defaults with explicit feedback', async ({ page }) => {
  const calls = await mockModels(page);
  await page.goto('/settings');
  const row = page.getByRole('row').filter({ hasText: 'Claude Sonnet' });
  await expect(row).toBeVisible();
  await page.getByRole('searchbox').fill('Claude');
  await expect(page.getByRole('row').filter({ hasText: 'qwen-plus' })).toBeVisible();
  const searchRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/api/advisor/models' && new URL(request.url()).searchParams.get('q') === 'Claude');
  await page.getByRole('searchbox').press('Enter');
  expect(new URL((await searchRequest).url()).searchParams.get('page')).toBe('1');
  await expect(page.getByRole('row').filter({ hasText: 'qwen-plus' })).toHaveCount(0);
  await page.getByRole('searchbox').fill('');
  await page.getByRole('searchbox').press('Enter');
  await page.getByRole('button', { name: 'Connect model', exact: true }).click();
  let drawer = page.getByRole('dialog');
  await drawer.getByLabel('API Key', { exact: true }).fill('sk-test-only');
  await drawer.getByLabel('Model ID', { exact: true }).fill('gpt-4.1');
  await drawer.getByRole('button', { name: 'Test connection', exact: true }).click();
  await expect(drawer.getByRole('status')).toHaveText('Test passed · 420 ms');
  expect(calls.filter(call => call.path === '/api/advisor/models')).toHaveLength(0);
  await drawer.getByLabel('Model ID', { exact: true }).fill('gpt-4.1-mini');
  await expect(drawer.getByText(/Test passed/)).toHaveCount(0);
  await drawer.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(drawer).toBeHidden();
  expect(calls.find(call => call.path === '/api/advisor/models')?.body?.makeCurrent).toBe(false);
  await row.getByRole('button', { name: 'Configure' }).click();
  drawer = page.getByRole('dialog');
  await expect(drawer.getByLabel('API Key', { exact: true })).toHaveCount(0);
  await drawer.getByLabel('Display name').fill('Sonnet updated');
  await drawer.getByRole('button', { name: 'Save changes' }).click();
  await expect(drawer).toBeHidden();
  expect(calls.find(call => call.path === '/api/advisor/models/two')?.body?.secret).toBeUndefined();
  await row.getByRole('button', { name: /More actions/ }).click();
  await page.getByRole('menuitem', { name: 'Set as default' }).click();
  expect(calls.filter(call => call.path.endsWith('/current'))).toHaveLength(0);
  await page.getByRole('dialog').getByRole('button', { name: 'Set as default' }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  expect(calls.filter(call => call.path.endsWith('/current'))).toHaveLength(1);
});

test('Chinese desktop and mobile drawers remain readable and support discard confirmation', async ({ page }) => {
  await mockModels(page, 'zh-CN');
  await page.goto('/settings');
  await expect(page.getByRole('row').filter({ hasText: 'qwen-plus' })).toBeVisible();
  await page.screenshot({ path: '/tmp/model-settings-list.png', fullPage: true });
  await page.getByRole('button', { name: '接入模型', exact: true }).click();
  let drawer = page.getByRole('dialog');
  await drawer.getByLabel('API Key', { exact: true }).fill('sk-hidden');
  await drawer.getByLabel('模型 ID', { exact: true }).fill('gpt-4.1');
  await drawer.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(drawer.getByRole('status')).toContainText('测试通过');
  await page.screenshot({ path: '/tmp/model-settings-drawer.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(drawer.getByRole('button', { name: '保存', exact: true })).toBeInViewport();
  const box = await drawer.boundingBox();
  expect(box?.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: '/tmp/model-settings-mobile.png', fullPage: true });
  await drawer.getByRole('button', { name: '取消', exact: true }).click();
  const confirm = page.getByRole('dialog', { name: '放弃修改？' });
  await confirm.getByRole('button', { name: '继续编辑' }).click();
  drawer = page.getByRole('dialog');
  await expect(drawer.getByLabel('模型 ID', { exact: true })).toHaveValue('gpt-4.1');
});
