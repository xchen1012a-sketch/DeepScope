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

import { listQuery, listPageQuery, type PageResult } from '@/api/listQuery';
import { api, apiFetch, apiResponse } from '@/lib/apiClient';

/** 智能体来源：全部为自建（仅创建者与管理员可见）。 */
export type AgentOrigin = 'personal';

export interface AgentCounts {
  skills: number;
  subagents: number;
}

export interface AgentSummary {
  agentId: string;
  name: string;
  description: string;
  origin: AgentOrigin;
  mine: boolean;
  canEdit: boolean;
  enabled?: boolean;
  isPublic?: boolean;
  isOwner?: boolean;
  ownerUserId?: string | null;
  counts: AgentCounts;
}

export interface AgentSkillRef {
  name: string;
  description: string;
}

export interface AgentToolRef {
  name: string;
  description: string;
}

export interface AgentSubagentRef {
  name: string;
  description: string;
  file: string;
}

export interface AgentKnowledgeRef {
  file: string;
}

export interface AgentSections {
  skills: AgentSkillRef[];
  tools: AgentToolRef[];
  subagents: AgentSubagentRef[];
  knowledge: AgentKnowledgeRef[];
}

export interface AgentDetail extends AgentSummary {
  sections: AgentSections;
}

export interface CreateAgentInput {
  name: string;
  description: string;
  /** 复制起点的智能体 id；缺省为空白开始。 */
  copyFromAgentId?: string;
  /** 是否共享给全体人员 */
  isPublic?: boolean;
}

/** 工作区文件树（扁平路径清单，前端组树）。 */
export interface AgentTreeFile {
  path: string;
}

export interface AgentFileContent {
  content: string;
}

/** 自由模式下每条消息的路由结果：决定本轮用哪个智能体运行。 */
export interface AgentRouteResult {
  isLocked?: boolean;
  mode?: 'agent' | 'basic' | 'unavailable';
  reasonCode?: string;
  agentId: string;
  name: string;
  reason: string;
}

export const agentKeys = {
  agents: ['advisor', 'agents'] as const,
  agent: (agentId: string) => ['advisor', 'agents', agentId] as const,
  tree: (agentId: string) => ['advisor', 'agents', agentId, 'tree'] as const,
  file: (agentId: string, path: string) => ['advisor', 'agents', agentId, 'files', path] as const,
  subagentTemplate: (agentId: string) => ['advisor', 'agents', agentId, 'subagents', 'template'] as const,
};

const agentPath = (agentId: string) => `/api/advisor/agents/${encodeURIComponent(agentId)}`;

export async function listAgents(query = ''): Promise<AgentSummary[]> {
  return api.get<AgentSummary[]>(listQuery('/api/advisor/agents', query));
}

export function listAvailableAgents(): Promise<Pick<AgentSummary, 'agentId' | 'name' | 'description' | 'origin'>[]> {
  return api.get('/api/advisor/agents/available');
}

export type AvailableAgent = Pick<AgentSummary, 'agentId' | 'name' | 'description' | 'origin'>;
export function pageAvailableAgents(q: string, page: number, size = 5, signal?: AbortSignal): Promise<PageResult<AvailableAgent>> {
  return apiFetch(listPageQuery('/api/advisor/agents/available', { q, page, size }), { signal });
}
export async function getAvailableAgent(id: string): Promise<AvailableAgent | null> {
  const items = await api.get<AvailableAgent[]>('/api/advisor/agents/available?' + new URLSearchParams({ agentId: id }));
  return items[0] ?? null;
}

export function createAgent(input: CreateAgentInput): Promise<AgentDetail> {
  return api.post<AgentDetail>('/api/advisor/agents', input);
}

export function getAgent(agentId: string): Promise<AgentDetail> {
  return api.get<AgentDetail>(agentPath(agentId));
}

export async function updateAgent(
  agentId: string,
  input: { name?: string; description?: string; enabled?: boolean; isPublic?: boolean },
): Promise<AgentDetail> {
  const response = await apiResponse(agentPath(agentId), {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
  return (await response.json()) as AgentDetail;
}

export async function deleteAgent(agentId: string): Promise<void> {
  await apiResponse(agentPath(agentId), { method: 'DELETE' });
}

export function getAgentTree(agentId: string): Promise<AgentTreeFile[]> {
  return api.get<{ files: AgentTreeFile[] }>(`${agentPath(agentId)}/tree`).then((result) => result.files ?? []);
}

export function readAgentFile(agentId: string, path: string): Promise<AgentFileContent> {
  return api.get<AgentFileContent>(`${agentPath(agentId)}/files?path=${encodeURIComponent(path)}`);
}

/** 覆盖已有文件（200）。 */
export async function saveAgentFile(agentId: string, path: string, content: string): Promise<void> {
  await apiResponse(agentPath(agentId) + '/files', {
    method: 'PUT',
    body: JSON.stringify({ path, content }),
  });
}

/** 新建文件（201）；路径已存在时后端报错。 */
export async function createAgentFile(agentId: string, path: string, content: string): Promise<void> {
  await apiResponse(agentPath(agentId) + '/files', {
    method: 'POST',
    body: JSON.stringify({ path, content }),
  });
}

export async function deleteAgentFile(agentId: string, path: string): Promise<void> {
  await apiResponse(`${agentPath(agentId)}/files?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
}

/** 让模型按名称和简介写行为文档，并覆盖当前行为文件。模型没有给出正文时请求失败，原文件保留。 */
export function generateBehavior(agentId: string): Promise<AgentFileContent> {
  return api.post<AgentFileContent>(`${agentPath(agentId)}/behavior/draft`);
}
export function getSubagentTemplate(agentId: string): Promise<AgentFileContent> {
  return api.get<AgentFileContent>(`${agentPath(agentId)}/subagents/template`);
}

/** 让模型按名称（可选用途）写出子智能体定义供编辑器补齐；失败时编辑器原内容保留。 */
export function draftSubagent(
  agentId: string,
  input: { name: string; purpose?: string },
): Promise<AgentFileContent> {
  return api.post<AgentFileContent>(`${agentPath(agentId)}/subagents/draft`, input);
}

const sessionPath = (sessionId: string) => `/api/advisor/sessions/${encodeURIComponent(sessionId)}`;

/** 自由模式：把访客输入交给路由，得到本轮服务的主智能体。 */
export function routeSession(sessionId: string, text: string, allowBasic = false, selectedAgentId?: string | null): Promise<AgentRouteResult> {
  return api.post<AgentRouteResult>(`${sessionPath(sessionId)}/route`, { text, ...(allowBasic ? { allowBasic: true } : {}), ...(selectedAgentId !== undefined ? { selectedAgentId } : {}) });
}

/** 提案裁决：批准（可携带修改后的草稿）或拒绝（退回通用能力）。 */
export function resolveProposal(
  sessionId: string,
  seq: number,
  input: { approved: boolean; content?: string },
): Promise<void> {
  return api.post(`${sessionPath(sessionId)}/proposals/${encodeURIComponent(String(seq))}/resolve`, input);
}
