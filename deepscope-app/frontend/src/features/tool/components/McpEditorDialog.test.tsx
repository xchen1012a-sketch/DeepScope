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
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ApiError } from '@/lib/apiClient';
import * as toolApi from '@/api/tool';
import { McpEditorDialog } from './McpEditorDialog';

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

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </I18nextProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const mockServer: toolApi.McpServer = {
  serverId: 'srv-1',
  name: 'LocalFilesystem',
  transport: 'stdio',
  command: 'npx',
  url: null,
  status: 'connected',
  statusMessage: '',
  ownerUserId: 'user-1',
  hasSecrets: false,
  toolCount: 3,
  tools: ['read_file', 'write_file', 'list_dir'],
  revision: 2,
};

describe('McpEditorDialog', () => {
  it('renders stdio server configuration and saves with revision If-Match', async () => {
    const updateSpy = vi.spyOn(toolApi, 'updateMcpServer').mockResolvedValue(mockServer);
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <McpEditorDialog server={mockServer} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    expect(screen.getByText(/LocalFilesystem/)).toBeTruthy();

    const cmdInput = screen.getByLabelText(/Command/i) as HTMLInputElement;
    expect(cmdInput.value).toBe('npx');

    const argsInput = screen.getByLabelText(/Arguments/i) as HTMLInputElement;
    fireEvent.change(argsInput, { target: { value: '-y @modelcontextprotocol/server-filesystem /tmp' } });

    const envInput = screen.getByLabelText(/Environment Variables/i) as HTMLTextAreaElement;
    fireEvent.change(envInput, { target: { value: '{"NODE_ENV":"production"}' } });

    const saveBtn = screen.getByRole('button', { name: /Test & Save/i });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });

    expect(updateSpy).toHaveBeenCalledWith(
      'srv-1',
      expect.objectContaining({
        name: 'LocalFilesystem',
        transport: 'stdio',
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-filesystem', '/tmp'],
        env: { NODE_ENV: 'production' },
      }),
      2,
    );

    await waitFor(() => {
      expect(onSaved).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('switches transport to SSE and saves URL and headers', async () => {
    const updateSpy = vi.spyOn(toolApi, 'updateMcpServer').mockResolvedValue(mockServer);
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <McpEditorDialog server={mockServer} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText(/Transport/i));
    await user.click(screen.getByRole('menuitem', { name: 'SSE (HTTP Server-Sent Events)' }));

    expect(screen.queryByLabelText(/Command/i)).toBeNull();

    const urlInput = screen.getByLabelText(/Server URL/i) as HTMLInputElement;
    fireEvent.change(urlInput, { target: { value: 'https://mcp.internal.net/sse' } });

    const headersInput = screen.getByLabelText(/Headers/i) as HTMLTextAreaElement;
    fireEvent.change(headersInput, { target: { value: '{"X-MCP-Token":"sec-123"}' } });

    const saveBtn = screen.getByRole('button', { name: /Test & Save/i });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });

    expect(updateSpy).toHaveBeenCalledWith(
      'srv-1',
      expect.objectContaining({
        name: 'LocalFilesystem',
        transport: 'sse',
        url: 'https://mcp.internal.net/sse',
        headers: { 'X-MCP-Token': 'sec-123' },
      }),
      2,
    );
  });

  it('rejects invalid JSON in environment variables without calling updateMcpServer', async () => {
    const updateSpy = vi.spyOn(toolApi, 'updateMcpServer');
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <McpEditorDialog server={mockServer} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const envInput = screen.getByLabelText(/Environment Variables/i);
    fireEvent.change(envInput, { target: { value: '{ not valid json' } });

    const saveBtn = screen.getByRole('button', { name: /Test & Save/i });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(screen.getByText(/connection configuration is invalid/i)).toBeTruthy();
    });

    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('displays error message when update fails', async () => {
    vi.spyOn(toolApi, 'updateMcpServer').mockRejectedValue(
      new ApiError(409, JSON.stringify({ code: 'revision_conflict' })),
    );
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <McpEditorDialog server={mockServer} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const saveBtn = screen.getByRole('button', { name: /Test & Save/i });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(screen.getByText(/Someone else changed this tool/i)).toBeTruthy();
    });

    expect(onClose).not.toHaveBeenCalled();
  });
});
