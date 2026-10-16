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
import { ToolImportDialog } from './ToolImportDialog';

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

const sampleOpenApi = JSON.stringify({
  openapi: '3.0.0',
  info: { title: 'User API', version: '1.0.0' },
  paths: {
    '/users': {
      get: { operationId: 'getUsers', summary: 'List users' },
      post: { operationId: 'createUser', summary: 'Create user' },
    },
  },
});

const samplePreviewItems: toolApi.ToolPreviewItem[] = [
  {
    operationId: 'getUsers',
    name: 'getUsers',
    httpMethod: 'GET',
    httpUrl: 'https://api.example.com/users',
    description: 'List users',
    definitionHash: 'hash-abc',
    inputSchema: '{}',
    origin: 'http',
    sourceId: 'src-1',
    duplicates: [],
  },
  {
    operationId: 'createUser',
    name: 'createUser',
    httpMethod: 'POST',
    httpUrl: 'https://api.example.com/users',
    description: 'Create user',
    definitionHash: 'hash-abc',
    inputSchema: '{}',
    origin: 'http',
    sourceId: 'src-1',
    duplicates: [
      {
        toolId: 'tool-dup-1',
        name: 'createUser',
        sameOperation: true,
        revision: 4,
        canEdit: true,
      },
    ],
  },
];

describe('ToolImportDialog', () => {
  it('previews OpenAPI content and renders discovered operations with duplicate choices', async () => {
    const previewSpy = vi.spyOn(toolApi, 'previewTools').mockResolvedValue(samplePreviewItems);
    const onClose = vi.fn();
    const onDone = vi.fn();

    renderWithProviders(<ToolImportDialog open={true} onClose={onClose} onDone={onDone} />);

    const textarea = screen.getByLabelText(/Definition content/i) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: sampleOpenApi } });

    const previewBtn = screen.getByRole('button', { name: /^Preview$/i });
    fireEvent.click(previewBtn);

    await waitFor(() => {
      expect(previewSpy).toHaveBeenCalledTimes(1);
    });

    expect(previewSpy).toHaveBeenCalledWith({
      kind: 'openapi',
      content: sampleOpenApi,
      address: undefined,
    });

    // Check preview items rendered
    await waitFor(() => {
      expect(screen.getByText('getUsers')).toBeTruthy();
      expect(screen.getByText('createUser')).toBeTruthy();
    });

    // Duplicate detection UI should be present for createUser
    expect(screen.getByText(/Existing tool:/i)).toBeTruthy();
    expect(screen.getByText(/Update existing/i)).toBeTruthy();
  });

  it('imports chosen tools with duplicate action decisions and idempotency key', async () => {
    vi.spyOn(toolApi, 'previewTools').mockResolvedValue(samplePreviewItems);
    const importSpy = vi.spyOn(toolApi, 'importTools').mockResolvedValue([
      {
        toolId: 'tool-new-1',
        name: 'getUsers',
        description: 'List users',
        origin: 'http',
        permission: 'ask',
        canEdit: true,
        approved: true,
        revision: 1,
        ownerUserId: 'user-1',
        mcpServerId: null,
        httpMethod: 'GET',
        httpUrl: 'https://api.example.com/users',
        inputSchema: '{}',
      },
    ]);
    const onClose = vi.fn();
    const onDone = vi.fn();

    renderWithProviders(<ToolImportDialog open={true} onClose={onClose} onDone={onDone} />);

    const textarea = screen.getByLabelText(/Definition content/i);
    fireEvent.change(textarea, { target: { value: sampleOpenApi } });

    const previewBtn = screen.getByRole('button', { name: /^Preview$/i });
    fireEvent.click(previewBtn);

    await waitFor(() => {
      expect(screen.getByText('getUsers')).toBeTruthy();
    });

    // Click Import
    const importBtn = screen.getByRole('button', { name: /Import tools/i });
    fireEvent.click(importBtn);

    await waitFor(() => {
      expect(importSpy).toHaveBeenCalledTimes(1);
    });

    expect(importSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'openapi',
        content: sampleOpenApi,
        chosenOperations: ['getUsers', 'createUser'],
        definitionHash: 'hash-abc',
        duplicateActions: expect.objectContaining({
          createUser: expect.objectContaining({
            action: 'update',
            toolId: 'tool-dup-1',
            expectedRevision: 4,
          }),
        }),
      }),
      expect.stringMatching(/^import-/),
    );

    await waitFor(() => {
      expect(onDone).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('preserves input fields and displays error message when preview or import fails', async () => {
    vi.spyOn(toolApi, 'previewTools').mockResolvedValue(samplePreviewItems);
    vi.spyOn(toolApi, 'importTools').mockRejectedValue(
      new ApiError(409, JSON.stringify({ code: 'duplicate_confirmation_required' })),
    );
    const onClose = vi.fn();
    const onDone = vi.fn();

    renderWithProviders(<ToolImportDialog open={true} onClose={onClose} onDone={onDone} />);

    const textarea = screen.getByLabelText(/Definition content/i) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: sampleOpenApi } });

    const addrInput = screen.getByLabelText(/Call address/i) as HTMLInputElement;
    fireEvent.change(addrInput, { target: { value: 'https://override.example.com' } });

    const previewBtn = screen.getByRole('button', { name: /^Preview$/i });
    fireEvent.click(previewBtn);

    await waitFor(() => {
      expect(screen.getByText('getUsers')).toBeTruthy();
    });

    const importBtn = screen.getByRole('button', { name: /Import tools/i });
    fireEvent.click(importBtn);

    await waitFor(() => {
      expect(screen.getByText(/duplicate or matching tools were found/i)).toBeTruthy();
    });

    // Inputs should still be preserved
    expect(textarea.value).toBe(sampleOpenApi);
    expect(addrInput.value).toBe('https://override.example.com');
    expect(onClose).not.toHaveBeenCalled();
    expect(onDone).not.toHaveBeenCalled();
  });
});
