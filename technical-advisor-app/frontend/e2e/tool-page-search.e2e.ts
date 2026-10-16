import { test, expect } from '@playwright/test';
import { listPageFixture } from './listPageFixture';

test('searches tools and MCP on submission and shows a connection example', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('claw_token', 'local-ui-test');
    localStorage.setItem('controlplane.console.locale', 'zh-CN');
  });
  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/me') {
      return route.fulfill({ json: { userId: 'u', username: 'Tester', roles: ['admin'], isAdmin: true, permissions: ['tool.view', 'tool.manage'] } });
    }
    if (path === '/api/v1/me/scope') {
      return route.fulfill({ json: { user: { userId: 'u', username: 'Tester' }, permissions: ['tool.view', 'tool.manage'] } });
    }
    if (path === '/api/advisor/tools') {
      return route.fulfill({ json: listPageFixture(route.request().url(), ['search_docs', 'read_file'].map((name, i) => ({
        toolId: `tool-${i}`, name, description: name, origin: 'http', permission: 'ask',
        canEdit: true, approved: true, revision: 1, inputSchema: '{}',
        httpMethod: 'GET', httpUrl: `https://api.example.com/${name}`,
      })), { filters: ['permission'] }) });
    }
    if (path === '/api/advisor/mcp-servers') {
      return route.fulfill({ json: listPageFixture(route.request().url(), [{
        serverId: 'server-1', name: 'ExampleMCP', transport: 'http',
        url: 'https://mcp.example.com/mcp', status: 'connected',
        toolCount: 2, tools: ['git_status', 'git_diff'], revision: 1,
      }]) });
    }
    return route.fulfill({ json: [] });
  });
  await page.goto('/tools');
  await page.getByRole('button', { name: '导入工具', exact: true }).click();
  const importDialog = page.getByRole('dialog');
  await expect(importDialog.getByLabel('定义格式')).toBeVisible();
  await expect(importDialog.getByLabel('调用地址（可选）')).toBeVisible();
  await expect(importDialog.getByRole('button', { name: '预览', exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/tool-import-compact.png', fullPage: true });
  await importDialog.getByRole('button', { name: '取消', exact: true }).click();
  const search = page.getByRole('searchbox');
  await expect(page.getByRole('row').filter({ hasText: 'search_docs' })).toBeVisible();
  await search.fill('read_file');
  await expect(page.getByRole('row').filter({ hasText: 'search_docs' })).toBeVisible();
  const toolSearch = page.waitForRequest(request => new URL(request.url()).pathname === '/api/advisor/tools' && new URL(request.url()).searchParams.get('q') === 'read_file');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  expect(new URL((await toolSearch).url()).searchParams.get('page')).toBe('1');
  await expect(page.getByRole('row').filter({ hasText: 'search_docs' })).toHaveCount(0);
  await search.fill('');
  await search.press('Enter');
  await expect(page.getByRole('row').filter({ hasText: 'search_docs' })).toBeVisible();
  await page.getByRole('tab', { name: 'MCP', exact: true }).click();
  await expect(page.getByText('已批准 2 件', { exact: true })).toBeVisible();
  await expect(page.getByText('git_status、git_diff', { exact: true })).toBeVisible();
  await expect(page.getByText('https://mcp.example.com/mcp', { exact: true })).toBeVisible();
  await expect(page.getByText('http', { exact: true })).toHaveCount(0);
  await search.fill('missing');
  await expect(page.getByText('ExampleMCP', { exact: true })).toBeVisible();
  const mcpSearch = page.waitForRequest(request => new URL(request.url()).pathname === '/api/advisor/mcp-servers' && new URL(request.url()).searchParams.get('q') === 'missing');
  await search.press('Enter');
  expect(new URL((await mcpSearch).url()).searchParams.get('page')).toBe('1');
  await expect(page.getByText('ExampleMCP', { exact: true })).toHaveCount(0);
  await search.fill('');
  await search.press('Enter');
  await expect(page.getByText('ExampleMCP', { exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/tool-page-mcp.png', fullPage: true });
  await page.getByRole('button', { name: '接入 MCP', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('textbox')).toHaveAttribute('placeholder', /mcpServers/);
  await page.screenshot({ path: '/tmp/tool-page-examples.png', fullPage: true });
});
