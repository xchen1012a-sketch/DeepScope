import type { EventEntry } from '../src/api/advisor';
import { test, expect } from '@playwright/test';
import zh from '../src/i18n/locales/zh-CN/app.json' with { type: 'json' };

const session = {
  sessionId: 'ui-preview', title: '评估项目的技术架构', agentId: 'technical-advisor', agentName: '技术参谋',
  status: 'idle', createdAt: 1_791_799_200_000, updatedAt: 1_791_799_201_000,
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('claw_token', 'local-ui-test');
    localStorage.setItem('controlplane.console.locale', 'zh-CN');
  });
  const models = [
    { endpointId: 'deepseek', kind: 'chat', modelName: 'DeepSeek', current: true },
    { endpointId: 'sonnet', kind: 'chat', modelName: 'Claude Sonnet', current: false },
  ];
  const entries: EventEntry[] = [
    { seq: 1, turn: 1, kind: 'user_message', payload: { text: '帮我评估一下这个项目的架构，重点看模块边界和后续维护成本。' }, createdAt: session.createdAt },
    { seq: 2, turn: 1, kind: 'tool_call', payload: { id: 'c1', name: 'read_file', args: '{"path":"README.md"}', result: '项目结构', status: 'done' }, createdAt: session.createdAt + 500 },
    { seq: 3, turn: 1, kind: 'advisor_message', payload: { text: '整体上，项目已经有清晰的分层。**现阶段更适合收紧模块边界，逐步降低维护成本。**\n\n建议优先关注三个地方：\n\n1. **统一业务入口。** 减少重复判断。\n2. **收拢跨模块调用。** 集中到明确的协作入口。\n3. **让配置跟随模块。** 避免影响无关区域。' }, createdAt: session.updatedAt },
  ];
  entries.push({ seq: 4, turn: 1, kind: 'usage', payload: { stepId: 'step1', uncachedInput: 1000, cacheRead: 1000, cacheWrite: 0, output: 100, projectedTokens: 2400, contextWindow: 16000, modelName: 'DeepSeek' }, createdAt: session.updatedAt + 1000 });
  await page.route('http://127.0.0.1:5190/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ json: body });
    if (path === '/api/auth/me') return json({ userId: 'test-user', username: '设计预览', roles: ['admin'], isAdmin: true, permissions: ['chat.use', 'settings.manage', 'agent.view', 'tool.manage', 'skill.manage', 'knowledge.view', 'rbac.manage'] });
    if (path === '/api/advisor/models') return json({ chat: models });
    if (path === '/api/advisor/models/sonnet/current') {
      models.forEach(model => { model.current = model.endpointId === 'sonnet'; });
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/advisor/sessions') {
      if (request.method() === 'POST') return json(session);
      return json([session]);
    }
    if (path === '/api/advisor/sessions/ui-preview') return json({ session, entries, deliverables: [] });
    if (path === '/api/advisor/sessions/ui-preview/messages') {
      const body = request.postDataJSON();
      const entry = { seq: entries.length + 1, turn: 2, kind: body.role === 'visitor' ? 'user_message' : 'advisor_message', payload: { text: body.text }, createdAt: Date.now() };
      entries.push(entry);
      return json(entry);
    }
    throw new Error(`Unexpected API request: ${request.method()} ${path}`);
  });
});

test('model menu, Think payload, process navigation and draft preservation', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/chat/ui-preview');
  await expect(page.getByRole('heading', { name: session.title })).toBeVisible();
  const picker = page.getByRole('button', { name: /选择模型/ });
  await picker.click();
  await expect(page.getByRole('menuitemradio', { name: 'DeepSeek', exact: true })).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('menuitemcheckbox', { name: 'Think' }).click();
  await expect(picker).toContainText('Think');
  await page.screenshot({ path: test.info().outputPath('conversation-desktop.png') });
  await page.keyboard.press('Escape');
  const draft = page.getByPlaceholder(zh.chat.composerPlaceholder);
  await draft.fill('如何逐步收紧模块边界？');
  await page.getByRole('button', { name: zh.chat.process.show }).click();
  await page.getByRole('dialog').getByRole('tab', { name: /子智能体/ }).click();
  await expect(page.getByText(zh.chat.process.noSubagents)).toBeVisible();
  await page.getByRole('dialog').getByRole('tab', { name: zh.chat.view.trajectory }).click();
  await page.getByRole('dialog').getByRole('button', { name: /帮我评估一下/ }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(draft).toHaveValue('如何逐步收紧模块边界？');

  const runRequest = page.waitForRequest('**/agui/run/technical-advisor');
  await page.route('**/agui/run/**', route => route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"TEXT_MESSAGE_CONTENT","delta":"已收到补充问题。"}\n\n' }));
  await page.getByRole('button', { name: zh.chat.send, exact: true }).click();
  expect((await runRequest).postDataJSON().forwardedProps.thinking).toBe(true);
  await expect(page.getByText('已收到补充问题。', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('model selection supports keyboard and closes after the server confirms', async ({ page }) => {
  await page.goto('/chat/ui-preview');
  const picker = page.getByRole('button', { name: /选择模型/ });
  await expect(picker).toContainText('DeepSeek');
  await picker.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menuitemradio', { name: 'DeepSeek', exact: true })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitemradio', { name: 'Claude Sonnet' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(picker).toContainText('Claude Sonnet');
  await expect(page.getByRole('menu')).toHaveCount(0);
});

test('narrow screens retain model controls and navigation without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await page.route('http://127.0.0.1:5190/api/advisor/models', route => route.fulfill({ json: {
    chat: [{ endpointId: 'long-model', kind: 'chat', modelName: 'provider/very-long-configured-model-name-2026', current: true }],
  } }));
  await page.goto('/chat/ui-preview');
  await expect(page.getByRole('heading', { name: session.title })).toBeVisible();
  await page.getByRole('button', { name: /选择模型/ }).click();
  const bounds = await page.getByRole('menu').boundingBox();
  expect(bounds?.x).toBeGreaterThanOrEqual(0);
  expect((bounds?.x ?? 0) + (bounds?.width ?? 0)).toBeLessThanOrEqual(360);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
  const sendBounds = await page.getByRole('button', { name: zh.chat.send, exact: true }).boundingBox();
  expect((sendBounds?.x ?? 0) + (sendBounds?.width ?? 0)).toBeLessThanOrEqual(360);
  await page.screenshot({ path: test.info().outputPath('conversation-mobile.png') });
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: zh.openNavigation }).click();
  await expect(page.getByRole('link', { name: zh.nav.home })).toBeVisible();
});
