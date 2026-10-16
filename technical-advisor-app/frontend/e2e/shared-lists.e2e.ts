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
  await page.addInitScript(() => {
    localStorage.setItem('claw_token', 'list-ui-test');
    localStorage.setItem('controlplane.console.locale', 'zh-CN');
  });
  await page.route('http://127.0.0.1:5190/api/**', async route => {
    const requestURL = new URL(route.request().url());
    const path = requestURL.pathname;
    const permissions = ['rbac.manage', 'tool.manage', 'settings.manage', 'settings.view'];
    if (path === '/api/auth/me') return route.fulfill({ json: { userId: 'u', username: 'Tester', roles: ['admin'], isAdmin: true, permissions } });
    if (path === '/api/v1/me/scope') return route.fulfill({ json: { user: { userId: 'u', username: 'Tester' }, permissions } });
    if (path === '/api/advisor/users') {
      const roleId = requestURL.searchParams.get('roleId');
      const users = [
        { userId: 'u', username: 'admin', nickname: '管理员', builtin: true, status: 'active', roleIds: ['r'] },
        { userId: 'u2', username: 'member', nickname: '成员', builtin: false, status: 'active', roleIds: ['r2'] },
      ].filter(user => !roleId || user.roleIds.includes(roleId));
      return route.fulfill({ json: listPageFixture(requestURL, users, {
        searchText: user => `${user.username} ${user.nickname}`, filters: ['status'],
      }) });
    }
    if (path === '/api/advisor/roles') {
      const code = requestURL.searchParams.get('code');
      const roles = [
        { roleId: 'r', name: '管理员', code: 'admin', description: '', builtin: true, userCount: 1, status: 'active', permissions: [] },
        { roleId: 'r2', name: '普通成员', code: 'member', description: '', builtin: false, userCount: 2, status: 'active', permissions: [] },
      ].filter(role => !code || role.code.includes(code));
      return route.fulfill({ json: listPageFixture(requestURL, roles, {
        searchText: role => `${role.name} ${role.code} ${role.description}`, filters: ['status'],
      }) });
    }
    return route.fulfill({ json: [] });
  });
});

test('users and roles share table actions, pagination and builtin restrictions', async ({ page }) => {
  await page.goto('/access/users');
  const adminRow = page.getByRole('row').filter({ hasText: 'admin' });
  await expect(adminRow).toBeVisible();
  await expect(adminRow.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
  const memberRow = page.getByRole('row').filter({ hasText: 'member' });
  await expect(memberRow.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  await expect(memberRow.getByRole('button', { name: '删除', exact: true })).toHaveAttribute('title', '删除');
  if (process.env.CAPTURE_LIST_SCREENSHOTS === '1') await page.screenshot({ path: '/tmp/shared-list-users.png', fullPage: true });
  await memberRow.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
  await page.goto('/access/roles');
  const role = page.getByRole('row').filter({ hasText: '普通成员' });
  await expect(role.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  const builtin = page.getByRole('row').filter({ hasText: 'admin' });
  await expect(builtin.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0);
  await expect(builtin.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
  await expect(page.getByTestId('pagination')).toBeVisible();
  if (process.env.CAPTURE_LIST_SCREENSHOTS === '1') await page.screenshot({ path: '/tmp/shared-list-roles.png', fullPage: true });
});

test('an empty result keeps headers and a clear route back through search', async ({ page }) => {
  await page.goto('/access/users');
  await expect(page.getByRole('row').filter({ hasText: 'member' })).toBeVisible();
  await page.getByRole('textbox').first().fill('no-matching-user');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('columnheader', { name: '登录名', exact: true })).toBeVisible();
  await expect(page.getByTestId('pagination')).toBeVisible();
  await page.getByRole('button', { name: '重置', exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: 'member' })).toBeVisible();
});
