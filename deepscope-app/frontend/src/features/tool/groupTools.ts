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

import type { CatalogTool, McpServer } from '@/api/tool';

export interface ToolGroup {
  serverId: string;
  name: string;
  tools: CatalogTool[];
}

/** MCP tools stay under the service they were born in. Imported tools have no service. */
export function groupExternalTools(tools: CatalogTool[], servers: McpServer[]): {
  groups: ToolGroup[];
  imported: CatalogTool[];
} {
  const imported = tools.filter((tool) => tool.origin === 'http' || tool.origin === 'declared');
  const groups = servers.map((server) => ({
    serverId: server.serverId,
    name: server.name,
    tools: tools.filter((tool) => tool.origin === 'mcp' && tool.mcpServerId === server.serverId),
  }));
  return { groups, imported };
}

/** 页签数字只统计这里真正列出来的工具：这位智能体勾选的外部工具。未选用的不计数。 */
export function countVisibleTools(
  tools: CatalogTool[] | undefined,
  selection: { toolIds: string[]; serverIds: string[] } | undefined,
): number {
  if (!tools) return 0;
  const selected = new Set(selection?.toolIds ?? []);
  const servers = new Set(selection?.serverIds ?? []);
  return tools.filter((tool) => {
    if (tool.origin === 'declared') return false;
    if (tool.mcpServerId && servers.has(tool.mcpServerId)) return true;
    return selected.has(tool.toolId);
  }).length;
}
