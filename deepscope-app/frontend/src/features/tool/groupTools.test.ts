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

import { describe, expect, it } from 'vitest';
import type { CatalogTool, McpServer } from '@/api/tool';
import { groupExternalTools, countVisibleTools } from './groupTools';

function tool(partial: Partial<CatalogTool> & Pick<CatalogTool, 'toolId' | 'origin'>): CatalogTool {
  return {
    name: partial.toolId,
    description: '',
    inputSchema: '{}',
    permission: 'ask',
    ownerUserId: 'u',
    mcpServerId: null,
    httpMethod: null,
    httpUrl: null,
    approved: true,
    canEdit: true,
    ...partial,
  };
}

describe('groupExternalTools', () => {
  it('keeps MCP tools with the service they came from and leaves imported tools loose', () => {
    const servers = [{ serverId: 's1', name: 'files' } as McpServer];
    const grouped = groupExternalTools(
      [
        tool({ toolId: 'm1', origin: 'mcp', mcpServerId: 's1', name: 'list_dir' }),
        tool({ toolId: 'h1', origin: 'http', name: 'lookup' }),
      ],
      servers,
    );
    expect(grouped.groups).toEqual([{ serverId: 's1', name: 'files', tools: [expect.objectContaining({ name: 'list_dir' })] }]);
    expect(grouped.imported.map((item) => item.name)).toEqual(['lookup']);
  });

  it('counts tools reachable through the selection, and ignores the unselected ones', () => {
    const tools = [
      tool({ toolId: 'm1', origin: 'mcp', mcpServerId: 's1' }),
      tool({ toolId: 'm2', origin: 'mcp', mcpServerId: 's2' }),
      tool({ toolId: 'h1', origin: 'http' }),
      tool({ toolId: 'd1', origin: 'declared' }),
    ];
    expect(countVisibleTools(undefined, undefined)).toBe(0);
    expect(countVisibleTools(tools, { toolIds: ['h1'], serverIds: ['s1'] })).toBe(2);
  });
});
