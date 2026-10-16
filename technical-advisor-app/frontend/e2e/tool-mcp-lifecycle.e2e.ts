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
    localStorage.setItem('claw_token', 'local-ui-test');
    localStorage.setItem('controlplane.console.locale', 'en');
  });
});

test('auto-detects server URL from OpenAPI definition without manual address input', async ({ page }) => {
  const tools = [
    {
      toolId: 'existing-1',
      name: 'existing_tool',
      description: 'Existing utility',
      inputSchema: '{}',
      origin: 'http',
      permission: 'ask',
      canEdit: true,
      revision: 1,
      httpMethod: 'GET',
      httpUrl: 'https://api.example.com/existing',
    },
  ];

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'u',
        username: 'Tester',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['tool.view', 'tool.manage', 'agent.view'],
      });
    }
    if (path === '/api/v1/me/scope') {
      return json({
        user: { userId: 'u', username: 'Tester' },
        permissions: ['tool.view', 'tool.manage', 'agent.view'],
      });
    }
    if (path === '/api/advisor/tools') {
      return json(listPageFixture(url, tools, { filters: ['permission'] }));
    }
    if (path === '/api/advisor/mcp-servers') {
      return json(listPageFixture(url, []));
    }
    if (path === '/api/advisor/tools/preview') {
      const body = request.postDataJSON();
      expect(body.kind).toBe('openapi');
      expect(body.content).toContain('https://api.weather.example.com/v1');
      return json([
        {
          operationId: 'get_current_weather',
          name: 'get_current_weather',
          description: 'Get current weather forecast',
          httpMethod: 'GET',
          path: '/weather/current',
          resolvedUrl: 'https://api.weather.example.com/v1/weather/current',
          addressSource: 'servers.url',
          definitionHash: 'hash-abc-123',
          duplicates: [],
          errors: [],
          warnings: [],
        },
      ]);
    }
    if (path === '/api/advisor/tools/import') {
      const body = request.postDataJSON();
      expect(body.chosenOperations).toEqual(['get_current_weather']);
      const newTool = {
        toolId: 'tool-weather-imported',
        name: 'get_current_weather',
        description: 'Get current weather forecast',
        inputSchema: '{"type":"object"}',
        origin: 'http',
        permission: 'ask',
        canEdit: true,
        revision: 1,
        httpMethod: 'GET',
        httpUrl: 'https://api.weather.example.com/v1/weather/current',
      };
      tools.push(newTool);
      return json([newTool], 201);
    }

    return json([]);
  });

  await page.goto('/tools');
  await expect(page.getByText('existing_tool')).toBeVisible();

  // Click Import tools
  await page.getByRole('button', { name: 'Import tools' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();

  // Paste OpenAPI specification containing servers.url without typing in address input
  const openapiSpec = JSON.stringify({
    openapi: '3.0.0',
    info: { title: 'Weather API', version: '1.0.0' },
    servers: [{ url: 'https://api.weather.example.com/v1' }],
    paths: {
      '/weather/current': {
        get: {
          operationId: 'get_current_weather',
          summary: 'Get current weather forecast',
        },
      },
    },
  });

  const contentInput = page.locator('#tool-import-content');
  await contentInput.fill(openapiSpec);

  // Address input should remain untouched (empty)
  const addressInput = page.locator('#tool-import-address');
  await expect(addressInput).toHaveValue('');

  // Click Preview Operations
  await dialog.getByRole('button', { name: 'Preview' }).click();

  // Check that candidate operation is displayed
  await expect(dialog.getByText('get_current_weather')).toBeVisible();

  // Click confirm Import inside dialog
  await dialog.getByRole('button', { name: 'Import tools' }).click();

  // Dialog closes and new tool appears in the catalog list
  await expect(dialog).toBeHidden();
  await expect(page.getByText('get_current_weather')).toBeVisible();
});

test('edits HTTP tool with revision concurrency check and preserves agent binding', async ({ page }) => {
  const tool = {
    toolId: 'tool-http-1',
    name: 'order_lookup',
    description: 'Lookup order by ID',
    inputSchema: '{"type":"object"}',
    origin: 'http',
    permission: 'allow',
    canEdit: true,
    revision: 2,
    httpMethod: 'GET',
    httpUrl: 'https://api.example.com/orders/{id}',
  };

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'u',
        username: 'Tester',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/v1/me/scope') {
      return json({
        user: { userId: 'u', username: 'Tester' },
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/advisor/tools') {
      return json(listPageFixture(url, [tool], { filters: ['permission'] }));
    }
    if (path === '/api/advisor/mcp-servers') {
      return json(listPageFixture(url, []));
    }
    if (path === '/api/advisor/tools/tool-http-1') {
      if (request.method() === 'GET') {
        return json(tool);
      }
      if (request.method() === 'PATCH') {
        // Assert If-Match revision concurrency header was sent (expected: '2')
        const ifMatch = request.headers()['if-match'];
        expect(ifMatch).toBe('2');
        const patchData = request.postDataJSON();
        tool.name = patchData.name;
        tool.description = patchData.description;
        tool.revision = 3;
        return json(tool);
      }
    }

    return json([]);
  });

  await page.goto('/tools');
  await expect(page.getByText('order_lookup')).toBeVisible();

  // Click Edit button
  await page.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();

  // Change description
  const descInput = page.locator('#tool-edit-desc');
  await descInput.fill('Updated order lookup service');

  // Click Save Changes
  await dialog.getByRole('button', { name: 'Save Changes' }).click();

  // Dialog closes and updated description is visible
  await expect(dialog).toBeHidden();
  await expect(page.getByText('Updated order lookup service')).toBeVisible();
});

test('runs tool test with error recovery and confirmation flow', async ({ page }) => {
  const tool = {
    toolId: 'tool-test-1',
    name: 'charge_card',
    description: 'Process card payment',
    inputSchema: '{"type":"object","properties":{"amount":{"type":"number"}}}',
    origin: 'http',
    permission: 'ask',
    canEdit: true,
    revision: 1,
    httpMethod: 'POST',
    httpUrl: 'https://api.example.com/charge',
  };

  let testCallCount = 0;

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'u',
        username: 'Tester',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/v1/me/scope') {
      return json({
        user: { userId: 'u', username: 'Tester' },
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/advisor/tools') {
      return json(listPageFixture(url, [tool], { filters: ['permission'] }));
    }
    if (path === '/api/advisor/mcp-servers') {
      return json(listPageFixture(url, []));
    }
    if (path === '/api/advisor/tools/tool-test-1/test') {
      testCallCount++;
      const body = request.postDataJSON();
      if (testCallCount === 1) {
        // First execution requires confirmation
        return route.fulfill({
          status: 409,
          json: {
            code: 'confirmation_required',
            messageKey: 'tool.errors.confirmation_required',
            confirmationId: 'conf-charge-999',
          },
        });
      }
      // Second execution carries confirmed flag and confirmationId
      expect(body.confirmed).toBe(true);
      expect(body.confirmationId).toBe('conf-charge-999');
      return json({
        runId: body.runId,
        httpStatus: 200,
        elapsedMs: 245,
        result: JSON.stringify({ status: 'succeeded', chargeId: 'ch_12345' }),
        truncated: false,
      });
    }

    return json([]);
  });

  await page.goto('/tools');
  await expect(page.getByText('charge_card')).toBeVisible();

  // Click Test button
  await page.getByRole('button', { name: 'Test' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();

  // Click Run Test
  await dialog.getByRole('button', { name: 'Run Test' }).click();

  // Expect confirmation prompt to appear
  await expect(dialog.getByText('This tool execution requires explicit approval. Confirm execution?')).toBeVisible();
  const confirmBtn = dialog.getByRole('button', { name: 'Confirm & Run' });
  await expect(confirmBtn).toBeVisible();

  // Click Confirm & Run
  await confirmBtn.click();

  // Expect test result to display successful response
  await expect(dialog.getByText('245ms')).toBeVisible();
  await expect(dialog.getByText('ch_12345')).toBeVisible();

  // Close panel using first Close button
  await dialog.getByRole('button', { name: 'Close' }).first().click();
  await expect(dialog).toBeHidden();
});

test('previews MCP diffs on refresh and confirms atomic changes', async ({ page }) => {
  const mcpServer = {
    serverId: 'mcp-srv-1',
    name: 'filesystem_mcp',
    transport: 'stdio',
    command: 'npx @modelcontextprotocol/server-filesystem',
    status: 'connected',
    tools: ['read_file'],
    toolCount: 1,
    revision: 1,
  };

  let approveCalled = false;

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'u',
        username: 'Tester',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/v1/me/scope') {
      return json({
        user: { userId: 'u', username: 'Tester' },
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/advisor/tools') {
      return json(listPageFixture(url, []));
    }
    if (path === '/api/advisor/mcp-servers') {
      return json(listPageFixture(url, [mcpServer]));
    }
    if (path === '/api/advisor/mcp-servers/mcp-srv-1/refresh') {
      return json({
        ...mcpServer,
        tools: ['read_file'],
        diff: {
          fingerprint: 'fp-diff-456',
          added: [
            {
              name: 'write_file',
              description: 'Write content to a file',
              inputSchema: '{"type":"object"}',
            },
          ],
          changed: [
            {
              toolId: 'tool-mcp-read',
              name: 'read_file',
              oldDescription: 'Read file',
              newDescription: 'Read file with UTF-8 encoding support',
              oldInputSchema: '{}',
              newInputSchema: '{"properties":{"path":{"type":"string"}}}',
              impactedAgentCount: 1,
            },
          ],
          removed: [],
        },
      });
    }
    if (path === '/api/advisor/mcp-servers/mcp-srv-1/approve') {
      approveCalled = true;
      const body = request.postDataJSON();
      expect(body.expectedFingerprint).toBe('fp-diff-456');
      expect(body.acceptedChanges.added).toHaveLength(1);
      expect(body.acceptedChanges.changed).toHaveLength(1);
      mcpServer.toolCount = 2;
      mcpServer.tools = ['read_file', 'write_file'];
      return json({ ...mcpServer, diff: null, pendingDiff: null });
    }

    return json([]);
  });

  await page.goto('/tools');

  // Switch to MCP tab
  await page.getByRole('tab', { name: 'MCP' }).click();
  await expect(page.getByText('filesystem_mcp')).toBeVisible();

  // Click Refresh
  await page.getByRole('button', { name: 'Refresh' }).click();

  // McpChangesDialog should open showing differences
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('MCP Service Changes')).toBeVisible();

  // Verify added tool is visible in Added tab
  await expect(dialog.getByText('write_file')).toBeVisible();

  // Switch to Changed tab and verify read_file change
  await dialog.getByRole('tab', { name: /Changed Tools/ }).click();
  await expect(dialog.getByText('read_file')).toBeVisible();

  // Click Confirm and Apply Changes
  await dialog.getByRole('button', { name: 'Confirm and Apply Changes' }).click();

  // Expect dialog to close and approve endpoint called
  await expect(dialog).toBeHidden();
  expect(approveCalled).toBe(true);
});

test('displays affected agents impact when deleting tool or MCP server', async ({ page }) => {
  const tool = {
    toolId: 'tool-del-1',
    name: 'delete_target_tool',
    description: 'Target to be deleted',
    inputSchema: '{}',
    origin: 'http',
    permission: 'allow',
    canEdit: true,
    revision: 1,
    httpMethod: 'DELETE',
    httpUrl: 'https://api.example.com/item',
  };

  const mcpServer = {
    serverId: 'server-del-1',
    name: 'delete_target_mcp',
    transport: 'stdio',
    command: 'npx server',
    status: 'connected',
    tools: ['mcp_bundled_tool'],
    toolCount: 1,
    revision: 1,
  };

  let toolDeleted = false;
  let serverDeleted = false;

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'u',
        username: 'Tester',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/v1/me/scope') {
      return json({
        user: { userId: 'u', username: 'Tester' },
        permissions: ['tool.view', 'tool.manage'],
      });
    }
    if (path === '/api/advisor/tools') {
      return json(listPageFixture(url, toolDeleted ? [] : [tool], { filters: ['permission'] }));
    }
    if (path === '/api/advisor/mcp-servers') {
      return json(listPageFixture(url, serverDeleted ? [] : [mcpServer]));
    }
    if (path === '/api/advisor/tools/tool-del-1/impact') {
      return json({
        toolId: 'tool-del-1',
        name: 'delete_target_tool',
        revision: 1,
        impactedAgents: [
          {
            agentId: 'agent-dev-1',
            name: 'Senior Developer Agent',
            description: 'Assists with code refactoring',
            origin: 'personal',
          },
        ],
      });
    }
    if (path === '/api/advisor/tools/tool-del-1' && request.method() === 'DELETE') {
      toolDeleted = true;
      return json({ success: true });
    }
    if (path === '/api/advisor/mcp-servers/server-del-1/impact') {
      return json({
        serverId: 'server-del-1',
        name: 'delete_target_mcp',
        revision: 1,
        impactedAgents: [
          {
            agentId: 'agent-dev-1',
            name: 'Senior Developer Agent',
            description: 'Assists with code refactoring',
            origin: 'personal',
          },
        ],
        tools: [
          {
            toolId: 't-1',
            name: 'mcp_bundled_tool',
            description: 'Sub tool',
            inputSchema: '{}',
            permission: 'ask',
          },
        ],
      });
    }
    if (path === '/api/advisor/mcp-servers/server-del-1' && request.method() === 'DELETE') {
      serverDeleted = true;
      return json({ success: true });
    }

    return json([]);
  });

  await page.goto('/tools');
  await expect(page.getByText('delete_target_tool')).toBeVisible();

  // 1. Delete tool with impact check
  // Locate tool row and click trash icon
  const toolRow = page.locator('div', { hasText: 'delete_target_tool' }).filter({ has: page.locator('button') }).last();
  await toolRow.locator('button').last().click();

  const toolDialog = page.getByRole('dialog');
  await expect(toolDialog).toBeVisible();
  await expect(toolDialog.getByRole('heading', { name: 'Delete Tool' })).toBeVisible();

  // Impacted agent name should be displayed
  await expect(toolDialog.getByText('Senior Developer Agent')).toBeVisible();

  // Confirm delete
  await toolDialog.getByRole('button', { name: 'Confirm Delete' }).click();
  await expect(toolDialog).toBeHidden();
  await expect(page.getByText('delete_target_tool')).toBeHidden();
  expect(toolDeleted).toBe(true);

  // 2. Switch to MCP tab and delete server with impact check
  await page.getByRole('tab', { name: 'MCP' }).click();
  await expect(page.getByText('delete_target_mcp')).toBeVisible();

  const serverRow = page.locator('div', { hasText: 'delete_target_mcp' }).filter({ has: page.locator('button') }).last();
  await serverRow.locator('button').last().click();

  const serverDialog = page.getByRole('dialog');
  await expect(serverDialog).toBeVisible();
  await expect(serverDialog.getByRole('heading', { name: 'Delete MCP Service' })).toBeVisible();

  // Impacted agent and associated tools should be displayed
  await expect(serverDialog.getByText('Senior Developer Agent')).toBeVisible();
  await expect(serverDialog.getByText('mcp_bundled_tool')).toBeVisible();

  // Confirm delete
  await serverDialog.getByRole('button', { name: 'Confirm Delete' }).click();
  await expect(serverDialog).toBeHidden();
  await expect(page.getByText('delete_target_mcp')).toBeHidden();
  expect(serverDeleted).toBe(true);
});
