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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ApiError } from '@/lib/apiClient';
import type { McpDiff, McpServer } from '@/api/tool';
import { McpChangesDialog } from './McpChangesDialog';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

function renderWithI18n(ui: React.ReactElement) {
  return render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);
}

afterEach(() => {
  cleanup();
});

const mockServer: McpServer = {
  serverId: 'srv-1',
  name: 'Test MCP Service',
  transport: 'stdio',
  command: 'npx mcp-test',
  url: null,
  status: 'connected',
  statusMessage: '',
  ownerUserId: 'user-1',
  hasSecrets: false,
  toolCount: 2,
  tools: ['existing_tool'],
};

const mockDiff: McpDiff = {
  serverId: 'srv-1',
  fingerprint: 'abcd1234efgh5678',
  added: [
    {
      name: 'new_tool',
      description: 'A newly added tool',
      inputSchema: JSON.stringify({ type: 'object', properties: { q: { type: 'string' } } }),
    },
  ],
  changed: [
    {
      toolId: 'tool-existing-1',
      name: 'changed_tool',
      oldDescription: 'Old tool description',
      newDescription: 'Updated tool description',
      oldInputSchema: JSON.stringify({ type: 'object' }),
      newInputSchema: JSON.stringify({ type: 'object', properties: { extra: { type: 'number' } } }),
      permission: 'ask',
      revision: 1,
    },
  ],
  removed: [
    {
      toolId: 'tool-removed-1',
      name: 'obsolete_tool',
      description: 'An obsolete tool',
      inputSchema: '{}',
      impactedAgentCount: 2,
    },
  ],
  generatedAt: 1728900000000,
};

describe('McpChangesDialog', () => {
  it('renders empty state when diff has no changes', () => {
    const emptyDiff: McpDiff = {
      serverId: 'srv-1',
      fingerprint: '11112222',
      added: [],
      changed: [],
      removed: [],
      generatedAt: 1728900000000,
    };

    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={emptyDiff}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    expect(screen.getByText('MCP Service Changes')).toBeTruthy();
    expect(screen.getByText('Test MCP Service')).toBeTruthy();
    expect(
      screen.getByText('No changes detected. All tools are in sync with the remote service.'),
    ).toBeTruthy();
  });

  it('renders tab counts and added tool details', () => {
    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={mockDiff}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    expect(screen.getByText('Added Tools (1)')).toBeTruthy();
    expect(screen.getByText('Changed Tools (1)')).toBeTruthy();
    expect(screen.getByText('Removed Tools (1)')).toBeTruthy();

    expect(screen.getByText('new_tool')).toBeTruthy();
    expect(screen.getByText('A newly added tool')).toBeTruthy();
  });

  it('submits accepted added, changed, and removed changes with fingerprint', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined);

    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={mockDiff}
        onClose={vi.fn()}
        onApply={onApply}
      />,
    );

    // Change permission of added tool to 'allow'
    const permissionSelect = screen.getByLabelText('Permission') as HTMLSelectElement;
    fireEvent.change(permissionSelect, { target: { value: 'allow' } });

    // Click Apply button
    const applyBtn = screen.getByText('Confirm and Apply Changes');
    fireEvent.click(applyBtn);

    await waitFor(() => {
      expect(onApply).toHaveBeenCalledTimes(1);
    });

    expect(onApply).toHaveBeenCalledWith('srv-1', {
      expectedFingerprint: 'abcd1234efgh5678',
      acceptedChanges: {
        added: [{ name: 'new_tool', permission: 'allow' }],
        changed: [{ toolId: 'tool-existing-1', name: 'changed_tool', apply: true }],
        removed: [{ toolId: 'tool-removed-1', name: 'obsolete_tool', confirm: true }],
      },
      tools: [{ name: 'new_tool', permission: 'allow' }],
    });
  });

  it('switches to changed tools tab and displays old vs new description', () => {
    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={mockDiff}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Changed Tools (1)'));

    expect(screen.getByText('changed_tool')).toBeTruthy();
    expect(screen.getByText('Old tool description')).toBeTruthy();
    expect(screen.getByText('Updated tool description')).toBeTruthy();
  });

  it('switches to removed tools tab and warns about impacted agents', () => {
    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={mockDiff}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Removed Tools (1)'));

    expect(screen.getByText('obsolete_tool')).toBeTruthy();
    expect(screen.getByText('Associated with 2 agent(s)')).toBeTruthy();
  });

  it('displays error message when apply fails with 409 mcp_changed', async () => {
    const conflictError = new ApiError(
      409,
      JSON.stringify({
        code: 'mcp_changed',
        message: 'Remote MCP service definitions have changed since refresh.',
      }),
    );
    const onApply = vi.fn().mockRejectedValue(conflictError);

    renderWithI18n(
      <McpChangesDialog
        open={true}
        server={mockServer}
        diff={mockDiff}
        onClose={vi.fn()}
        onApply={onApply}
      />,
    );

    fireEvent.click(screen.getByText('Confirm and Apply Changes'));

    await waitFor(() => {
      expect(screen.getByText(i18n.t('tool.errors.mcp_changed'))).toBeTruthy();
    });
  });
});
