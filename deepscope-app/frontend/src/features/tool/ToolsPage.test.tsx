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

// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import * as toolApi from '@/api/tool';
import { api } from '@/lib/apiClient';
import ToolsPage from './ToolsPage';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

if (typeof window !== 'undefined' && window.CustomEvent) {
  globalThis.CustomEvent = window.CustomEvent;
}

function renderToolsPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ToolsPage />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

const mockTools: toolApi.CatalogTool[] = [
  {
    toolId: 'tool-1',
    name: 'SearchTool',
    description: 'Search documentation',
    origin: 'http',
    permission: 'allow',
    canEdit: true,
    approved: true,
    revision: 2,
    ownerUserId: 'user-1',
    mcpServerId: null,
    httpMethod: 'POST',
    httpUrl: 'https://api.example.com/search',
    inputSchema: '{}',
  },
  {
    toolId: 'tool-2',
    name: 'ReadOnlyTool',
    description: 'System utility',
    origin: 'declared',
    permission: 'deny',
    canEdit: false,
    approved: false,
    revision: 1,
    ownerUserId: 'user-1',
    mcpServerId: null,
    httpMethod: null,
    httpUrl: null,
    inputSchema: '{}',
  },
];

const mockServers: toolApi.McpServer[] = [
  {
    serverId: 'srv-1',
    name: 'GitMcpServer',
    transport: 'stdio',
    command: 'npx git-mcp',
    url: null,
    status: 'connected',
    statusMessage: '',
    ownerUserId: 'user-1',
    hasSecrets: false,
    toolCount: 2,
    tools: ['git_status', 'git_diff'],
    revision: 1,
  },
];

beforeEach(() => {
  mockListRequests();
});

function mockListRequests(tools = mockTools, servers = mockServers) {
  return vi.spyOn(api, 'get').mockImplementation(async path => {
    const url = new URL(String(path), 'http://localhost');
    const q = url.searchParams.get('q') ?? '';
    const permission = url.searchParams.get('permission');
    const page = Number(url.searchParams.get('page') ?? 1);
    const size = Number(url.searchParams.get('size') ?? 10);
    const filtered = url.pathname.endsWith('mcp-servers')
      ? servers.filter(server => JSON.stringify(server).toLowerCase().includes(q.toLowerCase()))
      : tools.filter(tool => JSON.stringify(tool).toLowerCase().includes(q.toLowerCase()) && (!permission || tool.permission === permission));
    return { items: filtered.slice((page - 1) * size, page * size), total: filtered.length, page, size, totalPages: Math.max(1, Math.ceil(filtered.length / size)) };
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ToolsPage', () => {
  it('renders tools list with Test, Edit, and Delete action buttons', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
      expect(screen.getByText('ReadOnlyTool')).toBeTruthy();
    });

    // Check Test and Edit buttons for editable tool
    const testButtons = screen.getAllByRole('button', { name: 'Test' });
    expect(testButtons.length).toBe(2);

    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    expect(editButtons.length).toBe(1); // Only tool-1 is editable
  });

  it('opens ToolTestPanel when Test button is clicked', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
    });

    const testButtons = screen.getAllByRole('button', { name: 'Test' });
    fireEvent.click(testButtons[0]);

    await waitFor(() => {
      expect(screen.getByText('Test Tool: SearchTool')).toBeTruthy();
    });
  });

  it('opens ToolEditorPanel when Edit button is clicked on a tool', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
    });

    const editBtn = screen.getByRole('button', { name: 'Edit' });
    fireEvent.click(editBtn);

    await waitFor(() => {
      expect(screen.getByText('Edit Tool: SearchTool')).toBeTruthy();
    });
  });

  it('opens ToolImportDialog when Import button is clicked', async () => {
    renderToolsPage();

    const importBtn = screen.getByRole('button', { name: /Import tools/i });
    fireEvent.click(importBtn);

    await waitFor(() => {
      expect(screen.getByText('Import a tool')).toBeTruthy();
    });
  });

  it('switches to MCP tab and opens McpEditorDialog when Edit is clicked on a server', async () => {
    renderToolsPage();

    const mcpTab = screen.getByRole('tab', { name: 'MCP' });
    fireEvent.click(mcpTab);

    await waitFor(() => {
      expect(screen.getByText('GitMcpServer')).toBeTruthy();
    });

    const editBtn = screen.getByRole('button', { name: 'Edit' });
    fireEvent.click(editBtn);

    await waitFor(() => {
      expect(screen.getByText('Edit MCP Service: GitMcpServer')).toBeTruthy();
    });
  });

  it('applies tool search only after submission and restores all tools on an empty search', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
      expect(screen.getByText('ReadOnlyTool')).toBeTruthy();
    });

    const searchInput = screen.getByPlaceholderText(/^Search/);
    fireEvent.change(searchInput, { target: { value: 'ReadOnly' } });

    expect(screen.getByText('SearchTool')).toBeTruthy();
    expect(api.get).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /^Search$/ }));
    await waitFor(() => expect(screen.queryByText('SearchTool')).toBeNull());
    expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/q=ReadOnly/));
    expect(await screen.findByText('ReadOnlyTool')).toBeTruthy();

    const count = vi.mocked(api.get).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /^Search$/ }));
    await waitFor(() => expect(vi.mocked(api.get).mock.calls.length).toBeGreaterThan(count));

    fireEvent.change(searchInput, { target: { value: '   ' } });
    expect(screen.queryByText('SearchTool')).toBeNull();
    fireEvent.submit(screen.getByRole('search'));
    expect(await screen.findByText('SearchTool')).toBeTruthy();
  });

  it('submits MCP search and clears applied search when switching tabs', async () => {
    mockListRequests(mockTools, [
      ...mockServers,
      { ...mockServers[0], serverId: 'srv-2', name: 'OtherServer', tools: [] },
    ]);
    renderToolsPage();
    fireEvent.click(screen.getByRole('tab', { name: 'MCP' }));
    await screen.findByText('OtherServer');

    fireEvent.change(screen.getByPlaceholderText(/^Search/), { target: { value: 'git_diff' } });
    expect(screen.getByText('OtherServer')).toBeTruthy();
    fireEvent.submit(screen.getByRole('search'));
    await waitFor(() => expect(screen.queryByText('OtherServer')).toBeNull());
    expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/mcp-servers\?.*q=git_diff/));
    expect(await screen.findByText('GitMcpServer')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'Tools' }));
    expect(await screen.findByText('SearchTool')).toBeTruthy();
    expect((screen.getByPlaceholderText(/^Search/) as HTMLInputElement).value).toBe('');
    fireEvent.click(screen.getByRole('tab', { name: 'MCP' }));
    expect(await screen.findByText('OtherServer')).toBeTruthy();
  });

  it('supports pagination when there are more than 10 tools', async () => {
    const manyTools: toolApi.CatalogTool[] = Array.from({ length: 15 }, (_, i) => ({
      toolId: `tool-${i + 1}`,
      name: `ToolItem_${i + 1}`,
      description: `Description ${i + 1}`,
      origin: 'http',
      permission: 'allow',
      canEdit: true,
      approved: true,
      revision: 1,
      ownerUserId: 'user-1',
      mcpServerId: null,
      httpMethod: 'GET',
      httpUrl: `https://api.example.com/${i + 1}`,
      inputSchema: '{}',
    }));

    mockListRequests(manyTools);

    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('ToolItem_1')).toBeTruthy();
      expect(screen.getByText('ToolItem_10')).toBeTruthy();
      // Item 11 should be on page 2
      expect(screen.queryByText('ToolItem_11')).toBeNull();
    });

    // Click next page
    const nextBtn = screen.getByRole('button', { name: /Next/i });
    fireEvent.click(nextBtn);

    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/page=2/)));

    await waitFor(() => {
      expect(screen.queryByText('ToolItem_1')).toBeNull();
      expect(screen.getByText('ToolItem_11')).toBeTruthy();
      expect(screen.getByText('ToolItem_15')).toBeTruthy();
    });
  });

  it('filters tools by permission dropdown in catalog tab', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
      expect(screen.getByText('ReadOnlyTool')).toBeTruthy();
    });

    const permissionSelect = screen.getAllByRole('button', { name: 'Permission' })[0];
    fireEvent.pointerDown(permissionSelect, { button: 0 });
    fireEvent.keyDown(permissionSelect, { key: 'ArrowDown' });

    const denyOption = await screen.findByRole('menuitem', { name: 'Deny' });
    fireEvent.click(denyOption);

    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/permission=deny/)));

    await waitFor(() => {
      expect(screen.queryByText('SearchTool')).toBeNull();
      expect(screen.getByText('ReadOnlyTool')).toBeTruthy();
    });

    // Clear filter
    const clearBtn = screen.getByRole('button', { name: 'Clear' });
    fireEvent.click(clearBtn);

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
      expect(screen.getByText('ReadOnlyTool')).toBeTruthy();
    });
  });

  it('opens delete confirmation dialog when Delete button is clicked', async () => {
    renderToolsPage();

    await waitFor(() => {
      expect(screen.getByText('SearchTool')).toBeTruthy();
    });

    const deleteBtn = screen.getByRole('button', { name: 'Delete' });
    fireEvent.click(deleteBtn);

    await waitFor(() => {
      expect(screen.getByText('Delete Tool')).toBeTruthy();
      expect(
        screen.getByText(/Are you sure you want to delete tool "SearchTool"\?/i),
      ).toBeTruthy();
    });
  });
});
