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

import { api, apiFetch, apiResponse } from '@/lib/apiClient';

/** 会话状态：idle 空闲 / running 运行中 / awaiting_visitor 等待访客补充 / failed 失败 / interrupted 中断终态。 */
export type SessionStatus = 'idle' | 'running' | 'awaiting_visitor' | 'failed' | 'interrupted';

export type AttachmentKind = 'image' | 'archive' | 'document';

export interface AttachmentSummary {
  id: string;
  name: string;
  size: number;
  kind: AttachmentKind;
  mimeType?: string;
  dataUrl?: string;
}

export interface UploadedAttachment extends AttachmentSummary {
  mimeType: string;
  dataUrl?: string;
  workspacePath?: string;
  extractedText?: string;
  /** 本地上传/处理中占位状态，用于在卡片上直接显示加载动画。 */
  uploading?: boolean;
}

/** 主流 AI 支持的附件后缀，用于文件选择器 accept 属性。 */
export const ACCEPTED_ATTACHMENT_EXTENSIONS =
  '.png,.jpg,.jpeg,.webp,.gif,.zip,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.csv,.tsv,.md,.markdown,.txt,.log,.rtf,.html,.htm,.json,.yaml,.yml,.xml,.toml,.ini,.properties,.sql,.sh,.java,.kt,.py,.ts,.tsx,.js,.jsx,.go,.rs,.c,.cpp,.h,.hpp,.cs,.rb,.php,.swift,.css,.scss,.vue,.svelte,.gradle';

export interface SessionSummary {
  sessionId: string;
  /** 可为空（未命名会话）。 */
  title: string | null;
  /** 绑定的主智能体（自由模式下为空，每条消息由路由决定）。 */
  agentId: string | null;
  /** 绑定智能体的显示名；自由模式为空。 */
  agentName: string | null;
  status: SessionStatus;
  /** epoch 毫秒。 */
  createdAt: number;
  updatedAt: number;
}

/** 三段式事实条目：编号、事实、来源（结论仅在调查过程里出现）。 */
export interface FactItem {
  id: string;
  fact: string;
  source: string;
  conclusion?: string;
}

/** 交付物 evidence 只登记编号、事实与来源。 */
export interface EvidenceFact {
  id: string;
  fact: string;
  source: string;
}

export type SubtaskExecution = 'self' | 'dispatch';

export interface Subtask {
  id: string;
  title: string;
  question: string;
  needs: string[];
  expected: string;
  dependsOn: string[];
  execution: SubtaskExecution;
  executionReason: string;
}

export type Severity = 'blocker' | 'major' | 'minor';

export interface DeliverableItem {
  text: string;
  severity?: Severity;
  /** 依据编号，如 ['F3','F7']。 */
  citations: string[];
  /** 无依据支撑时显式降级为推测。 */
  speculative?: boolean;
  /** 多来源事实冲突时的并列说明。 */
  conflict?: string;
  recommendation?: string;
}

export interface DeliverableSection {
  key: string;
  title: string;
  /** markdown 正文，与 items 可并用。 */
  body?: string;
  items?: DeliverableItem[];
}

/** 交付物文档：deliverable 事件的 payload 即此结构（含 version）。 */
export interface DeliverableDoc {
  title: string;
  domainName: string;
  mode: string;
  sections: DeliverableSection[];
  evidence: EvidenceFact[];
  suggestedFollowUps?: string[];
  version?: number;
}

/** 历史交付物归档（detail 接口返回）。 */
export interface DeliverableArchive {
  version: number;
  title: string;
  body: DeliverableDoc;
  createdAt: number;
}

/** 时间线事件：seq 单调递增，按其顺序线性回放整个会话视图。 */
export interface EventEntry {
  seq: number;
  turn: number;
  kind: string;
  payload: Record<string, unknown>;
  createdAt?: number;
  replayed?: boolean;
}

export interface QuestionOption { id: string; label: string; description?: string }
export interface QuestionAnswer { selectedOptionIds: string[]; customText: string }
export interface QuestionReplyInput extends QuestionAnswer { questionId: string; requestId: string; attachmentContext?: string }
export interface PendingQuestion {
  questionId: string;
  title?: string;
  text: string;
  answerMode: 'single' | 'multiple' | 'text';
  allowCustom: boolean;
  options: QuestionOption[];
  seq: number;
  turn: number;
}

export interface AguiInterrupt {
  id: string;
  reason: string;
  message?: string;
  toolCallId?: string;
  responseSchema?: Record<string, unknown>;
  expiresAt?: string;
  metadata?: Record<string, unknown>;
}

export interface AguiResumeInput {
  interruptId: string;
  status: 'resolved' | 'cancelled';
  payload?: { approved: boolean; editedArgs?: Record<string, unknown>; reason?: string };
}

export interface SessionRuntime {
  phase: 'running' | 'awaiting_action' | 'awaiting_visitor' | 'finalizing' | 'idle';
  runId?: string;
  agentId?: string;
  interrupts: AguiInterrupt[];
}

export interface SessionDetail {
  pendingQuestion?: PendingQuestion | null;
  pendingQuestionReply?: EventEntry | null;
  runtime?: SessionRuntime;
  session: SessionSummary;
  entries: EventEntry[];
  deliverables: DeliverableArchive[];
}

/** 一次模型调用落定后的用量；窗口与采样缺失时不写，读侧据此不显示，而不是显示 0。 */
export interface UsageInput {
  stepId: string;
  uncachedInput: number;
  cacheRead: number;
  cacheWrite: number;
  output: number;
  projectedTokens: number;
  timeSeconds: number;
  /** 提供方声明的上下文窗口；缺失即不显示上下文环。 */
  contextWindow?: number;
  /** 该步调用实际使用的模型名；应用层随 token_usage 透出，缺失时不写。 */
  modelName?: string;
  /** 首个输出 token 的等待毫秒数；没有采样就不写。 */
  firstTokenMs?: number;
  /** 输出速率；采样不足就不写。 */
  outputTokensPerSecond?: number;
}

/**
 * 一次工具调用的落库形状。
 *
 * <p>`startedAt` 必须在 `TOOL_CALL_START` 那一刻就记下来：事件行的 `createdAt` 是服务端在回合
 * 结束时追加的时间，用它当起始时间会让刷新后的耗时接近 0。
 */
export interface ToolCallInput {
  id: string;
  name?: string;
  args?: unknown;
  result?: unknown;
  status?: 'running' | 'done' | 'failed';
  startedAt?: number;
  endedAt?: number;
}

/** 子智能体自身过程的一个步骤，按发生顺序排列。 */
export interface SubagentStepInput {
  kind: 'thinking' | 'tool_call';
  text?: string;
  toolCallId?: string;
  name?: string;
  args?: unknown;
  result?: unknown;
  status?: 'running' | 'done' | 'failed';
  startedAt?: number;
  endedAt?: number;
}

export interface SubagentRunInput {
  /** 派发时写入的 subtaskId，用于把过程挂回对应派发。 */
  subtaskId: string;
  events: SubagentStepInput[];
}

interface PartIdentity {
  id: string;
  runId: string;
  status: 'running' | 'done' | 'failed';
}

/** Ordered display projection; model history remains owned by the native session log. */
export type ConversationPart =
  | (PartIdentity & { kind: 'text' | 'reasoning'; text: string; messageId?: string })
  | (PartIdentity & { kind: 'tool'; tool: ToolCallInput })
  | (PartIdentity & { kind: 'event'; event: { kind: string; payload: Record<string, unknown>; seq?: number } });

export interface RunPresentation {
  runId: string;
  runStartedAt?: number;
  finalMessageIds: string[];
  finalStartedAt?: number;
  runEndedAt?: number;
  terminationReason?: 'MAX_ITERATIONS';
  outcome: 'completed' | 'limited' | 'failed' | 'cancelled' | 'awaiting_action' | 'unknown';
}

export interface PostMessageInput {
  questionReply?: QuestionReplyInput;
  role: 'visitor' | 'advisor';
  text: string;
  partsVersion?: 1;
  parts?: ConversationPart[];
  presentationVersion?: 1;
  presentation?: RunPresentation;
  mode?: string;
  domainName?: string;
  /** 本轮思考文本（可选）：随参谋回复一起落库，回放时在气泡上方展示思考卡片。 */
  reasoning?: string;
  /** 访客消息附带的文件摘要列表（可选），用于时间线回放展示。 */
  attachments?: AttachmentSummary[];
  /** 本轮收齐的工具调用；形状见 {@link ToolCallInput}。 */
  toolCalls?: ToolCallInput[];
  /** 本轮各步用量，同一步已在前端替换过；形状见 {@link UsageInput}。 */
  usage?: UsageInput[];
  /** 各子智能体自身的过程记录，按派发归集；缺失表示该轮没有派发或没有过程。 */
  subagentRuns?: SubagentRunInput[];
}

export interface PaginatedResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  hasMore: boolean;
}

export const advisorKeys = {
  modelActivation: ['advisor', 'model-current'] as const,
  commands: ['advisor', 'commands'] as const,
  sessions: ['advisor', 'sessions'] as const,
  session: (sessionId: string) => ['advisor', 'sessions', sessionId] as const,
  timelinePage: (sessionId: string, page: number, size: number) =>
    ['advisor', 'sessions', sessionId, 'timeline', page, size] as const,
  subagentsPage: (sessionId: string, page: number, size: number) =>
    ['advisor', 'sessions', sessionId, 'subagents', page, size] as const,
};

/** payload 里的字符串字段；缺失或类型不符时返回空串。 */
export function payloadText(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === 'string' ? value : '';
}

/** payload 里的数组字段；缺失时返回空数组。 */
export function payloadList<T>(payload: Record<string, unknown>, key: string): T[] {
  const value = payload[key];
  return Array.isArray(value) ? (value as T[]) : [];
}

const sessionPath = (sessionId: string) => `/api/advisor/sessions/${encodeURIComponent(sessionId)}`;

export function listSessions(): Promise<SessionSummary[]> {
  return api.get<SessionSummary[]>('/api/advisor/sessions');
}

/** 创建会话；agentId 缺省为自由模式（每条消息由路由选择服务智能体）。 */
export function createSession(agentId?: string): Promise<SessionSummary> {
  return api.post<SessionSummary>('/api/advisor/sessions', agentId ? { agentId } : {});
}

export async function renameSession(sessionId: string, title: string): Promise<SessionSummary> {
  const response = await apiResponse(sessionPath(sessionId), {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  });
  return (await response.json()) as SessionSummary;
}

export async function deleteSession(sessionId: string): Promise<void> {
  await apiResponse(sessionPath(sessionId), { method: 'DELETE' });
}

export function getSessionDetail(sessionId: string): Promise<SessionDetail> {
  return api.get<SessionDetail>(sessionPath(sessionId));
}

/** 分页获取会话时间线卡片列表（执行过程抽屉 - 时间线 Tab）。 */
export function getSessionTimelinePage<T = Record<string, unknown>>(
  sessionId: string,
  page = 1,
  size = 10,
): Promise<PaginatedResponse<T>> {
  return api.get<PaginatedResponse<T>>(`${sessionPath(sessionId)}/timeline?page=${page}&size=${size}`);
}

/** 分页获取会话子智能体记录列表（执行过程抽屉 - 子智能体 Tab）。 */
export function getSessionSubagentsPage<T = Record<string, unknown>>(
  sessionId: string,
  page = 1,
  size = 10,
): Promise<PaginatedResponse<T>> {
  return api.get<PaginatedResponse<T>>(`${sessionPath(sessionId)}/subagents?page=${page}&size=${size}`);
}

/** 落库一条对话消息：发 AG-UI run 前存访客消息，run 结束后存助手回复。 */
export function postSessionMessage(sessionId: string, input: PostMessageInput): Promise<EventEntry> {
  return api.post<EventEntry>(`${sessionPath(sessionId)}/messages`, input);
}

/** 显式打断会话：立即终止后端 Agent 与子智能体执行，强制释放运行锁并保存已产出的中间结果。 */
export function interruptSession(sessionId: string, expectedRunId?: string): Promise<{ outcome: string; sessionId: string }> {
  return api.post<{ outcome: string; sessionId: string }>(`${sessionPath(sessionId)}:interrupt`, expectedRunId ? { expectedRunId } : undefined);
}

const attachmentPreviewCache = new Map<string, string>();
const attachmentPreviewInflight = new Map<string, Promise<string | null>>();

export function cacheAttachmentPreview(id: string, url?: string): void {
  if (id && url) {
    attachmentPreviewCache.set(id, url);
  }
}

export function getCachedAttachmentPreview(id: string): string | undefined {
  return attachmentPreviewCache.get(id);
}

/** 按附件 ID 获取图片预览地址（优先命中内存缓存，未命中则向后端请求原图二进制流并生成本地 URL）。 */
export function fetchAttachmentPreviewUrl(id: string): Promise<string | null> {
  if (!id) return Promise.resolve(null);
  const cached = attachmentPreviewCache.get(id);
  if (cached) return Promise.resolve(cached);
  const existing = attachmentPreviewInflight.get(id);
  if (existing) return existing;
  const request = (async () => {
    try {
      const response = await apiResponse(
        `/api/advisor/sessions/attachments/${encodeURIComponent(id)}/content`,
      );
      const blob = await response.blob();
      if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
        const objectUrl = URL.createObjectURL(blob);
        attachmentPreviewCache.set(id, objectUrl);
        return objectUrl;
      }
      return null;
    } catch {
      return null;
    } finally {
      attachmentPreviewInflight.delete(id);
    }
  })();
  attachmentPreviewInflight.set(id, request);
  return request;
}

/** 上传并解析一个附件（图片、ZIP、Office、PDF、Markdown、代码文件等）。 */
export async function uploadAttachment(file: File): Promise<UploadedAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  const item = await apiFetch<UploadedAttachment>('/api/advisor/sessions/attachments', {
    method: 'POST',
    body: formData,
  });
  if (item?.id && item?.dataUrl) {
    cacheAttachmentPreview(item.id, item.dataUrl);
  }
  return item;
}

/** 按本地路径或 file:// URI 解析并导入一个附件。 */
export async function uploadAttachmentByPath(path: string): Promise<UploadedAttachment> {
  const item = await api.post<UploadedAttachment>('/api/advisor/sessions/attachments/by-path', { path });
  if (item?.id && item?.dataUrl) {
    cacheAttachmentPreview(item.id, item.dataUrl);
  }
  return item;
}

export interface SlashCommand {
  commandId: string;
  code: string;
  name: string;
  description: string;
  origin: 'builtin' | 'custom';
  targetType: 'action' | 'skill' | 'tool' | 'mcp' | 'prompt';
  targetId: string | null;
  paramsJson: string;
  sortOrder: number;
}

export interface ExecuteCommandResult {
  outcome: string;
  targetType: string;
  targetId: string;
  reason?: string;
  data?: Record<string, unknown>;
}

/** 获取当前用户可用的斜杠命令列表（内置 + 自建）。 */
export async function fetchCommands(): Promise<SlashCommand[]> {
  const result = await api.get<SlashCommand[]>('/api/advisor/commands');
  return result ?? [];
}

/** 执行斜杠受控指令。 */
export function executeCommand(
  code: string,
  sessionId?: string,
  args?: string,
): Promise<ExecuteCommandResult> {
  return api.post<ExecuteCommandResult>(`/api/advisor/commands/${encodeURIComponent(code)}:execute`, {
    sessionId,
    args,
  });
}

/** 清空当前会话上下文并保留会话本身。 */
export function clearSessionContext(sessionId: string): Promise<{ outcome: string }> {
  return api.post<{ outcome: string }>(`/api/advisor/sessions/${encodeURIComponent(sessionId)}:clear`);
}
