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

import { listQuery, selectionQuery, type SelectionContext } from '@/api/listQuery';
import { api, apiFetch } from '@/lib/apiClient';

export type ToolOrigin = 'declared' | 'http' | 'mcp';
export type ToolPermission = 'allow' | 'ask' | 'deny';

export interface CatalogTool {
  toolId: string;
  name: string;
  description: string;
  inputSchema: string;
  origin: ToolOrigin;
  permission: ToolPermission;
  ownerUserId: string | null;
  mcpServerId: string | null;
  httpMethod: string | null;
  httpUrl: string | null;
  approved: boolean;
  canEdit: boolean;
  revision?: number;
  hasCredential?: boolean;
}

export type ToolAuthType = 'none' | 'apiKey' | 'bearer' | 'basic';
export type ToolAuthAction = 'keep' | 'replace' | 'clear';

export interface ToolAuthConfig {
  action?: ToolAuthAction;
  type?: ToolAuthType;
  name?: string;
  in?: 'header' | 'query';
  value?: string;
  username?: string;
  password?: string;
}

export interface DiscoveredTool {
  name: string;
  description: string;
  inputSchema: string;
}

export interface McpChangedTool {
  toolId: string;
  name: string;
  oldDescription: string;
  newDescription: string;
  oldInputSchema: string;
  newInputSchema: string;
  permission: ToolPermission;
  revision: number;
}

export interface McpRemovedTool {
  toolId: string;
  name: string;
  description: string;
  inputSchema: string;
  impactedAgentCount: number;
}

export interface McpDiff {
  serverId: string;
  fingerprint: string;
  added: DiscoveredTool[];
  changed: McpChangedTool[];
  removed: McpRemovedTool[];
  generatedAt: number;
}

export interface AcceptedAdded {
  name: string;
  permission: ToolPermission;
}

export interface AcceptedChanged {
  toolId: string;
  name: string;
  apply: boolean;
}

export interface AcceptedRemoved {
  toolId: string;
  name: string;
  confirm: boolean;
}

export interface AcceptedChanges {
  added?: AcceptedAdded[];
  changed?: AcceptedChanged[];
  removed?: AcceptedRemoved[];
}

export interface ApproveMcpChangesInput {
  expectedFingerprint: string;
  acceptedChanges: AcceptedChanges;
  tools?: Array<{ name: string; permission: ToolPermission }>;
}

export interface ImpactedAgent {
  agentId: string;
  name: string;
  via?: 'tool' | 'server';
}

export interface ToolImpact {
  toolId: string;
  name: string;
  revision: number;
  mcpServerId?: string | null;
  impactedAgents: ImpactedAgent[];
  impactedCount: number;
}

export interface McpServerImpact {
  serverId: string;
  name: string;
  revision: number;
  tools: Array<{ toolId: string; name: string; description: string }>;
  impactedAgents: ImpactedAgent[];
  impactedCount: number;
}

/** 选择器分页时，每个 MCP 服务自带其工具摘要；管理列表不下发该字段。 */
export interface McpToolSummary {
  toolId: string;
  name: string;
  description: string;
  approved: boolean;
  permission: ToolPermission;
}

export interface McpServer {
  serverId: string;
  name: string;
  transport: string;
  command: string | null;
  url: string | null;
  status: 'connected' | 'error';
  statusMessage: string;
  ownerUserId: string;
  hasSecrets: boolean;
  toolCount: number;
  tools: string[];
  toolSummaries?: McpToolSummary[];
  discovered?: DiscoveredTool[];
  pending?: DiscoveredTool[];
  pendingDiff?: McpDiff;
  diff?: McpDiff;
  revision?: number;
}

export interface ToolSelection {
  toolIds: string[];
  serverIds: string[];
}

export const toolKeys = {
  tools: ['advisor', 'tools'] as const,
  servers: ['advisor', 'mcp-servers'] as const,
  selection: (agentId: string) => ['advisor', 'agents', agentId, 'tool-selection'] as const,
};

export type DuplicateActionType = 'create' | 'update' | 'skip';

export interface DuplicateAction {
  action: DuplicateActionType;
  name?: string;
  toolId?: string;
  expectedRevision?: number;
}

export interface DuplicateCandidate {
  toolId: string;
  name: string;
  revision: number;
  sameOperation: boolean;
  canEdit: boolean;
}

export interface ToolPreviewItem {
  name: string;
  description: string;
  inputSchema: string;
  origin: ToolOrigin;
  httpMethod: string | null;
  httpUrl: string | null;
  sourceId: string;
  operationId: string;
  definitionHash: string;
  duplicates: DuplicateCandidate[];
}

export interface ImportToolsInput {
  kind?: 'openapi' | 'schema';
  content: string;
  address?: string;
  addressOverride?: string;
  permission?: ToolPermission;
  chosenOperations?: string[];
  duplicateActions?: Record<string, DuplicateAction>;
  definitionHash?: string;
  boundToolId?: string;
  mcpToolId?: string;
  auth?: ToolAuthConfig | Record<string, unknown>;
}

export interface UpdateToolInput {
  name?: string;
  description?: string;
  inputSchema?: string;
  httpMethod?: string;
  httpUrl?: string;
  permission?: ToolPermission;
  revision?: number;
  auth?: ToolAuthConfig;
}

export function listTools(query = '', selection?: SelectionContext): Promise<CatalogTool[]> {
  return api.get<CatalogTool[]>(selection ? selectionQuery('/api/advisor/tools', selection) : listQuery('/api/advisor/tools', query));
}

export function importTools(
  input: ImportToolsInput,
  idempotencyKey?: string | unknown,
): Promise<CatalogTool[]> {
  const headers: Record<string, string> = {};
  if (typeof idempotencyKey === 'string' && idempotencyKey.length > 0) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return apiFetch<CatalogTool[]>('/api/advisor/tools/import', {
    method: 'POST',
    body: JSON.stringify(input),
    headers,
  });
}

export function previewTools(input: {
  kind?: 'openapi' | 'schema';
  content: string;
  address?: string;
  addressOverride?: string;
  boundToolId?: string;
  mcpToolId?: string;
}): Promise<ToolPreviewItem[]> {
  return api.post<ToolPreviewItem[]>('/api/advisor/tools/preview', input);
}

export function updateToolPermission(toolId: string, permission: ToolPermission): Promise<CatalogTool> {
  return apiFetch<CatalogTool>(`/api/advisor/tools/${encodeURIComponent(toolId)}`, {
    method: 'PATCH',
    body: JSON.stringify({ permission }),
  });
}

export function updateTool(
  toolId: string,
  input: UpdateToolInput,
  expectedRevision?: number,
): Promise<CatalogTool> {
  const headers: Record<string, string> = {};
  if (expectedRevision !== undefined) {
    headers['If-Match'] = String(expectedRevision);
  }
  return apiFetch<CatalogTool>(`/api/advisor/tools/${encodeURIComponent(toolId)}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
    headers,
  });
}

export function deleteTool(toolId: string, expectedRevision?: number): Promise<void> {
  const headers: Record<string, string> = {};
  if (expectedRevision !== undefined) {
    headers['If-Match'] = String(expectedRevision);
  }
  return apiFetch<void>(`/api/advisor/tools/${encodeURIComponent(toolId)}`, {
    method: 'DELETE',
    headers,
  });
}

export function getToolImpact(toolId: string): Promise<ToolImpact> {
  return api.get<ToolImpact>(`/api/advisor/tools/${encodeURIComponent(toolId)}/impact`);
}

export function listMcpServers(query = '', selection?: SelectionContext): Promise<McpServer[]> {
  return api.get<McpServer[]>(selection ? selectionQuery('/api/advisor/mcp-servers', selection) : listQuery('/api/advisor/mcp-servers', query));
}

export function connectMcp(text: string): Promise<McpServer[]> {
  return api.post<McpServer[]>('/api/advisor/mcp-servers', { text });
}

export function approveMcpTools(
  serverId: string,
  tools: Array<{ name: string; permission: ToolPermission }>,
): Promise<McpServer> {
  return api.post<McpServer>(`/api/advisor/mcp-servers/${encodeURIComponent(serverId)}/approve`, { tools });
}

export function confirmMcpChanges(
  serverId: string,
  input: ApproveMcpChangesInput,
): Promise<McpServer> {
  return api.post<McpServer>(
    `/api/advisor/mcp-servers/${encodeURIComponent(serverId)}/approve`,
    input,
  );
}

export function refreshMcp(serverId: string): Promise<McpServer> {
  return api.post<McpServer>(`/api/advisor/mcp-servers/${encodeURIComponent(serverId)}/refresh`);
}

export function deleteMcp(serverId: string, expectedRevision?: number): Promise<void> {
  const headers: Record<string, string> = {};
  if (expectedRevision !== undefined) {
    headers['If-Match'] = String(expectedRevision);
  }
  return apiFetch<void>(`/api/advisor/mcp-servers/${encodeURIComponent(serverId)}`, {
    method: 'DELETE',
    headers,
  });
}

export function getMcpServerImpact(serverId: string): Promise<McpServerImpact> {
  return api.get<McpServerImpact>(`/api/advisor/mcp-servers/${encodeURIComponent(serverId)}/impact`);
}

export function getToolSelection(agentId: string): Promise<ToolSelection> {
  return api.get<ToolSelection>(`/api/advisor/agents/${encodeURIComponent(agentId)}/tool-selection`);
}

export function saveToolSelection(agentId: string, selection: ToolSelection): Promise<void> {
  return apiFetch<void>(`/api/advisor/agents/${encodeURIComponent(agentId)}/tool-selection`, {
    method: 'PUT',
    body: JSON.stringify(selection),
  });
}

export interface TestToolInput {
  input?: Record<string, unknown>;
  savedRevision?: number;
  draftDefinition?: Record<string, unknown>;
  runId: string;
  confirmed?: boolean;
  confirmationId?: string;
}

export interface ToolTestResult {
  httpStatus?: number;
  result?: string;
  elapsedMs: number;
  truncated?: boolean;
  errorCode?: string;
  messageKey?: string;
}

export interface StopTestResult {
  runId: string;
  status: string;
  messageKey?: string;
}

export function testTool(toolId: string, input: TestToolInput): Promise<ToolTestResult> {
  return api.post<ToolTestResult>(`/api/advisor/tools/${encodeURIComponent(toolId)}/test`, input);
}

export function suggestToolTestInput(toolId: string, signal?: AbortSignal): Promise<{ input: Record<string, unknown> }> {
  return apiFetch(`/api/advisor/tools/${encodeURIComponent(toolId)}/test-input`, { method: 'POST', signal });
}

export function stopToolTest(runId: string): Promise<StopTestResult> {
  return api.post<StopTestResult>(`/api/advisor/tools/tests/${encodeURIComponent(runId)}/stop`);
}

export interface UpdateMcpServerInput {
  text?: string;
  name?: string;
  transport?: string;
  command?: string;
  url?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  revision?: number;
}

export function updateMcpServer(
  serverId: string,
  input: UpdateMcpServerInput,
  expectedRevision?: number,
): Promise<McpServer> {
  const headers: Record<string, string> = {};
  if (expectedRevision !== undefined) {
    headers['If-Match'] = String(expectedRevision);
  }
  return apiFetch<McpServer>(`/api/advisor/mcp-servers/${encodeURIComponent(serverId)}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
    headers,
  });
}
