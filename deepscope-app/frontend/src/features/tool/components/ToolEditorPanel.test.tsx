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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ApiError } from '@/lib/apiClient';
import * as toolApi from '@/api/tool';
import { ToolEditorPanel } from './ToolEditorPanel';

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

const mockHttpTool: toolApi.CatalogTool = {
  toolId: 'tool-http-1',
  name: 'SearchTool',
  description: 'Search web resources',
  origin: 'http',
  permission: 'ask',
  canEdit: true,
  approved: true,
  revision: 3,
  ownerUserId: 'user-1',
  mcpServerId: null,
  httpMethod: 'POST',
  httpUrl: 'https://api.example.com/search',
  inputSchema: '{"type":"object"}',
  hasCredential: true,
};

const mockMcpTool: toolApi.CatalogTool = {
  toolId: 'tool-mcp-1',
  name: 'GitCommit',
  description: 'Git commit tool',
  origin: 'mcp',
  permission: 'allow',
  canEdit: true,
  approved: true,
  revision: 1,
  ownerUserId: 'user-1',
  mcpServerId: 'srv-1',
  httpMethod: null,
  httpUrl: null,
  inputSchema: '{"type":"object"}',
};

describe('ToolEditorPanel', () => {
  it('renders HTTP tool editor fields and saves with If-Match revision', async () => {
    const updateSpy = vi
      .spyOn(toolApi, 'updateTool')
      .mockResolvedValue({ tool: mockHttpTool });
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockHttpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const nameInput = screen.getByLabelText('Tool Name') as HTMLInputElement;
    expect(nameInput.value).toBe('SearchTool');

    const descInput = screen.getByLabelText('Description') as HTMLTextAreaElement;
    expect(descInput.value).toBe('Search web resources');

    expect(screen.getByLabelText('Method').textContent).toContain('POST');

    const urlInput = screen.getByLabelText('Call URL') as HTMLInputElement;
    expect(urlInput.value).toBe('https://api.example.com/search');

    // Change description
    fireEvent.change(descInput, { target: { value: 'Updated description' } });

    const saveBtn = screen.getByRole('button', { name: 'Save Changes' });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });

    expect(updateSpy).toHaveBeenCalledWith(
      'tool-http-1',
      expect.objectContaining({
        name: 'SearchTool',
        description: 'Updated description',
        httpMethod: 'POST',
        httpUrl: 'https://api.example.com/search',
      }),
      3, // revision passed as If-Match argument
    );

    await waitFor(() => {
      expect(onSaved).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('follows an accepted save until the change is applied', async () => {
    vi.spyOn(toolApi, 'updateTool').mockResolvedValue({
      requestId: 'change-1',
      status: 'pending',
      tool: mockHttpTool,
    });
    const changeSpy = vi
      .spyOn(toolApi, 'fetchToolChange')
      .mockResolvedValue({ requestId: 'change-1', status: 'applied' });
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockHttpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    fireEvent.change(screen.getByLabelText('Description'), {
      target: { value: 'Updated description' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => {
      expect(changeSpy).toHaveBeenCalledWith('change-1');
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('renders MCP tool with remote definition hint and disabled schema', () => {
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockMcpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    expect(screen.getByText(/defined by the remote service/i)).toBeTruthy();
    expect(screen.queryByLabelText('Call URL')).toBeNull();

    const schemaInput = screen.getByLabelText('Parameters (JSON Schema)') as HTMLTextAreaElement;
    expect(schemaInput.disabled).toBe(true);
  });

  it('closes directly when clean and cancel is clicked', () => {
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockHttpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const cancelBtn = screen.getByRole('button', { name: 'Cancel' });
    fireEvent.click(cancelBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/Discard unsaved changes/i)).toBeNull();
  });

  it('prompts confirmation when dirty, allows canceling or confirming discard', async () => {
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockHttpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const nameInput = screen.getByLabelText('Tool Name');
    fireEvent.change(nameInput, { target: { value: 'NewName' } });

    const cancelBtn = screen.getByRole('button', { name: 'Cancel' });
    fireEvent.click(cancelBtn);

    // Discard warning dialog should appear
    expect(screen.getByText('Discard unsaved changes?')).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();

    // Click "Return to editing"
    const returnBtn = screen.getByRole('button', { name: 'Cancel' });
    fireEvent.click(returnBtn);

    await waitFor(() => {
      expect(screen.queryByText('Discard unsaved changes?')).toBeNull();
    });

    // Click Cancel again
    const cancelBtn2 = screen.getByRole('button', { name: 'Cancel' });
    fireEvent.click(cancelBtn2);

    // Confirm discard
    const discardBtn = screen.getByRole('button', { name: 'Discard' });
    fireEvent.click(discardBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('displays error message when update fails', async () => {
    vi.spyOn(toolApi, 'updateTool').mockRejectedValue(
      new ApiError(409, JSON.stringify({ code: 'revision_conflict' })),
    );
    const onClose = vi.fn();
    const onSaved = vi.fn();

    renderWithProviders(
      <ToolEditorPanel tool={mockHttpTool} open={true} onClose={onClose} onSaved={onSaved} />,
    );

    const descInput = screen.getByLabelText('Description');
    fireEvent.change(descInput, { target: { value: 'Trigger error' } });

    const saveBtn = screen.getByRole('button', { name: 'Save Changes' });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(screen.getByText(/Someone else changed this tool/i)).toBeTruthy();
    });

    expect(onClose).not.toHaveBeenCalled();
  });
});
