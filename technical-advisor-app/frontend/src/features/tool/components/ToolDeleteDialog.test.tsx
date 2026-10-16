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
import * as toolApi from '@/api/tool';
import { ToolDeleteDialog, type ToolDeleteTarget } from './ToolDeleteDialog';

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
  vi.restoreAllMocks();
});

describe('ToolDeleteDialog', () => {
  it('renders tool impact with 0 agents and performs delete', async () => {
    let resolveImpact!: (val: toolApi.ToolImpact) => void;
    const impactPromise = new Promise<toolApi.ToolImpact>((resolve) => {
      resolveImpact = resolve;
    });
    const getImpactSpy = vi.spyOn(toolApi, 'getToolImpact').mockReturnValue(impactPromise);
    const deleteToolSpy = vi.spyOn(toolApi, 'deleteTool').mockResolvedValue(undefined);
    const onClose = vi.fn();
    const onDeleted = vi.fn();

    const target: ToolDeleteTarget = {
      type: 'tool',
      id: 'tool-1',
      name: 'Calculator',
      revision: 3,
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={onClose}
        onDeleted={onDeleted}
      />,
    );

    expect(screen.getByText('Delete Tool')).toBeTruthy();
    expect(screen.getByText('Checking impact…')).toBeTruthy();

    resolveImpact({
      toolId: 'tool-1',
      name: 'Calculator',
      revision: 3,
      impactedAgents: [],
      impactedCount: 0,
    });

    await waitFor(() => {
      expect(screen.queryByText('Checking impact…')).toBeNull();
    });

    expect(
      screen.getByText('No agents are currently using this item. It can be safely deleted.'),
    ).toBeTruthy();
    expect(getImpactSpy).toHaveBeenCalledWith('tool-1');

    const confirmBtn = screen.getByRole('button', { name: 'Confirm Delete' });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(deleteToolSpy).toHaveBeenCalledWith('tool-1', 3);
      expect(onDeleted).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });

  it('renders tool impact with impacted agents', async () => {
    vi.spyOn(toolApi, 'getToolImpact').mockResolvedValue({
      toolId: 'tool-2',
      name: 'Weather API',
      revision: 1,
      impactedAgents: [
        { agentId: 'agent-1', name: 'Weather Bot' },
        { agentId: 'agent-2', name: 'Travel Assistant' },
      ],
      impactedCount: 2,
    });

    const target: ToolDeleteTarget = {
      type: 'tool',
      id: 'tool-2',
      name: 'Weather API',
      revision: 1,
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Weather Bot')).toBeTruthy();
      expect(screen.getByText('Travel Assistant')).toBeTruthy();
    });

    expect(
      screen.getByText(/The following 2 agent\(s\) currently use this item/),
    ).toBeTruthy();
  });

  it('renders MCP server impact with associated tools and agents', async () => {
    const getMcpImpactSpy = vi.spyOn(toolApi, 'getMcpServerImpact').mockResolvedValue({
      serverId: 'srv-1',
      name: 'GitHub MCP',
      revision: 5,
      tools: [
        { toolId: 't-gh-1', name: 'search_repos', description: 'Search GitHub repos' },
        { toolId: 't-gh-2', name: 'get_issue', description: 'Get issue details' },
      ],
      impactedAgents: [{ agentId: 'agent-code', name: 'DevBot' }],
      impactedCount: 1,
    });
    const deleteMcpSpy = vi.spyOn(toolApi, 'deleteMcp').mockResolvedValue(undefined);
    const onClose = vi.fn();
    const onDeleted = vi.fn();

    const target: ToolDeleteTarget = {
      type: 'server',
      id: 'srv-1',
      name: 'GitHub MCP',
      revision: 5,
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={onClose}
        onDeleted={onDeleted}
      />,
    );

    expect(screen.getByText('Delete MCP Service')).toBeTruthy();

    await waitFor(() => {
      expect(screen.getByText('search_repos')).toBeTruthy();
      expect(screen.getByText('get_issue')).toBeTruthy();
      expect(screen.getByText('DevBot')).toBeTruthy();
    });

    expect(getMcpImpactSpy).toHaveBeenCalledWith('srv-1');

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Delete' }));

    await waitFor(() => {
      expect(deleteMcpSpy).toHaveBeenCalledWith('srv-1', 5);
      expect(onDeleted).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });

  it('handles impact query failure gracefully', async () => {
    vi.spyOn(toolApi, 'getToolImpact').mockRejectedValue(
      new ApiError(
        404,
        JSON.stringify({ code: 'tool_not_found', messageKey: 'tool.errors.tool_not_found' }),
      ),
    );

    const target: ToolDeleteTarget = {
      type: 'tool',
      id: 'tool-nonexistent',
      name: 'Missing Tool',
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={vi.fn()}
        onDeleted={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('This tool is no longer available.')).toBeTruthy();
    });
  });

  it('handles delete revision conflict error display', async () => {
    vi.spyOn(toolApi, 'getToolImpact').mockResolvedValue({
      toolId: 'tool-conflict',
      name: 'Fast Math',
      revision: 1,
      impactedAgents: [],
      impactedCount: 0,
    });
    vi.spyOn(toolApi, 'deleteTool').mockRejectedValue(
      new ApiError(
        409,
        JSON.stringify({
          code: 'revision_conflict',
          messageKey: 'tool.errors.revision_conflict',
        }),
      ),
    );

    const onDeleted = vi.fn();
    const target: ToolDeleteTarget = {
      type: 'tool',
      id: 'tool-conflict',
      name: 'Fast Math',
      revision: 1,
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={vi.fn()}
        onDeleted={onDeleted}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByText('No agents are currently using this item. It can be safely deleted.'),
      ).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Delete' }));

    await waitFor(() => {
      expect(
        screen.getByText('Someone else changed this tool first. Refresh and try again.'),
      ).toBeTruthy();
      expect(onDeleted).not.toHaveBeenCalled();
    });
  });

  it('closes when Cancel is clicked', () => {
    const onClose = vi.fn();
    const target: ToolDeleteTarget = {
      type: 'tool',
      id: 'tool-1',
      name: 'Calculator',
    };

    renderWithI18n(
      <ToolDeleteDialog
        open={true}
        target={target}
        onClose={onClose}
        onDeleted={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
  });
});
