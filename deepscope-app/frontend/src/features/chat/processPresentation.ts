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

import type { ToolCall } from './conversationModel';
import { toolAction, type ToolAction } from './toolPresentation';

export type ActivityAction = ToolAction | 'test' | 'collect' | 'tasks' | 'other';
export interface ToolDescription {
  action: ActivityAction;
  titleKey: string;
  target: string;
  resultKey: string;
  resultValues?: Record<string, number>;
  content?: string;
  hasFailure?: boolean;
}

function object(raw: string, limit: number): Record<string, unknown> | undefined {
  if (raw.length > limit) return;
  try {
    const value: unknown = JSON.parse(raw);
    if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  } catch { /* Partial input and plain text are valid recorded payloads. */ }
}
function stringValue(record: Record<string, unknown> | undefined, keys: string[]): string {
  for (const key of keys) if (typeof record?.[key] === 'string') return (record[key] as string).trim();
  return '';
}
const TEST_COMMANDS = new Set(['npm test', 'npm run test', 'pytest', 'python -m pytest', 'python3 -m pytest', 'mvn test', './mvnw test']);

function returnedText(value: string | undefined): string {
  const raw = value?.trim() ?? '';
  // String-returning harness tools can be serialized once more in persisted records.
  if (raw.startsWith('"') && raw.endsWith('"') && raw.length <= 262_144) {
    try { const decoded: unknown = JSON.parse(raw); if (typeof decoded === 'string') return decoded.trim(); }
    catch { /* Keep an incomplete or non-JSON return verbatim. */ }
  }
  return raw;
}

/** Derive captions only from known actions and explicit results, never from inferred intent. */
export function describeTool(tool: ToolCall): ToolDescription {
  const args = object(tool.args, 16_384);
  let action: ActivityAction = toolAction(tool.name) ?? 'other';
  if (tool.name === 'task_output' || tool.name === 'wait_async_results') action = 'collect';
  if (tool.name === 'task_list' || tool.name === 'task_get' || tool.name === 'task_update') action = 'tasks';
  if (tool.name === 'task_dispatch' || tool.name === 'task_create') action = 'spawn';
  if (tool.name === 'retrieve_knowledge') action = 'search';
  if (action === 'run' && TEST_COMMANDS.has(stringValue(args, ['command', 'cmd']))) action = 'test';
  let target = '';
  if (['read', 'write', 'edit', 'list'].includes(action)) {
    target = stringValue(args, ['path', 'file_path', 'filePath']).replace(/[/\\]+$/, '').split(/[/\\]/).pop() ?? '';
  } else if (action === 'fetch') {
    try { target = new URL(stringValue(args, ['url', 'uri'])).hostname; } catch { /* No complete URL yet. */ }
  } else if (action === 'spawn') {
    target = stringValue(args, ['label', 'title', 'task', 'name', 'task_id', 'taskId', 'agent_id']);
    if (!target && tool.name === 'task_dispatch' && tool.result) {
      const match = /\[([a-zA-Z0-9_-]+)\]\s*([^,\n\]]+)/.exec(tool.result);
      if (match) target = `[${match[1]}] ${match[2].trim()}`;
    }
  }
  target = target.replace(/\s+/g, ' ').slice(0, 80);
  const result: ToolDescription = { action, titleKey: `chat.activity.actions.${action}.${tool.status}`, target, resultKey: 'chat.activity.result.returned' };
  if (tool.status === 'running') return { ...result, resultKey: 'chat.activity.result.waiting' };
  const raw = returnedText(tool.result);
  if (tool.status === 'failed') return { ...result, resultKey: 'chat.activity.result.failed', content: raw && raw !== tool.args.trim() ? raw : undefined };
  if (!raw) return { ...result, resultKey: 'chat.activity.result.empty' };
  if (raw === tool.args.trim()) return { ...result, resultKey: 'chat.activity.result.echo' };
  if (action === 'spawn') {
    // AgentSpawnTool distinguishes synchronous reply text from an async receipt.
    const replyAt = raw.indexOf('\nreply:\n');
    const header = raw.slice(0, Math.min(replyAt >= 0 ? replyAt : raw.length, 2048));
    const state = raw.startsWith('agent_key: ') ? /^status: (error|cancelled|timeout)$/mi.exec(header)?.[1].toLowerCase() : undefined;
    if (state) return {
      ...result,
      titleKey: `chat.activity.${state === 'error' ? 'taskFailedTitle' : state === 'timeout' ? 'taskTimeoutTitle' : 'taskCancelledTitle'}`,
      resultKey: `chat.activity.result.${state === 'error' ? 'taskFailed' : state === 'timeout' ? 'taskTimeout' : 'taskCancelled'}`,
      hasFailure: state !== 'cancelled',
      content: raw.replace(/^(?:(?:agent_key|agent_id|session_id|subagent_id|status|task_id):[^\n]*(?:\n|$))+/, '').trim() || undefined,
    };
    if (raw.startsWith('agent_key: ') && replyAt >= 0 && replyAt < 2048) return {
      ...result, resultKey: 'chat.activity.result.taskReply', content: raw.slice(replyAt + 8).trim() || undefined,
    };
    return { ...result, resultKey: 'chat.activity.result.dispatched' };
  }
  // ShellExecuteTool's first line is a transport outcome, not proof of task correctness.
  const exit = (action === 'run' || action === 'test') && /^Exit code: (-?\d+)(?:\n\n?|$)/.exec(raw);
  if (exit && Number.isSafeInteger(Number(exit[1]))) return {
    ...result, resultKey: 'chat.activity.result.exit', resultValues: { code: Number(exit[1]) },
    content: raw.slice(exit[0].length).trim() || undefined,
  };
  // TaskTool.formatTaskDetail has a bounded metadata header before the blank line.
  // Only that header owns task status; quoted result text must never override it.
  if (tool.name === 'task_output' && raw.startsWith('task_id: ')) {
    const boundary = raw.indexOf('\n\n');
    const header = raw.slice(0, boundary < 0 ? 1024 : Math.min(boundary, 1024));
    const state = /^status: (running|completed|failed|cancelled)$/mi.exec(header)?.[1].toLowerCase();
    const keys: Record<string, string> = { running: 'taskRunning', completed: 'taskCompleted', failed: 'taskFailed', cancelled: 'taskCancelled' };
    if (state) return {
      ...result, resultKey: `chat.activity.result.${keys[state]}`,
      hasFailure: state === 'failed',
      content: boundary < 0 ? undefined : raw.slice(boundary + 2).replace(/^(Result|Error):\n/, '').trim() || undefined,
    };
  }
  const data = object(raw, 32_768);
  if (action === 'test' && data?.tests && typeof data.tests === 'object' && !Array.isArray(data.tests)) {
    const counts = data.tests as Record<string, unknown>;
    const { passed, failed, skipped, total } = counts;
    if ([passed, failed, skipped, total].every(value => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)
      && (passed as number) + (failed as number) + (skipped as number) === total) {
      result.resultKey = 'chat.activity.result.tests';
      result.resultValues = { passed: passed as number, failed: failed as number, skipped: skipped as number, total: total as number };
    }
  }
  if (action === 'search' && Array.isArray(data?.matches)) {
    result.resultKey = 'chat.activity.result.matches';
    result.resultValues = { count: data.matches.length };
  }
  // Unknown structured output remains available verbatim in advanced details.
  result.content = data ? stringValue(data, ['stdout', 'output', 'text']) || undefined : raw;
  return result;
}
