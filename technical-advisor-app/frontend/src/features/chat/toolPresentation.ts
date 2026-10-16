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

export type ToolAction = 'read' | 'list' | 'search' | 'fetch' | 'run' | 'write' | 'edit' | 'spawn';
interface ToolDefinition { action: ToolAction; targets: readonly string[]; scope?: readonly string[] }
const FILE_TARGETS = ['path', 'file_path', 'filePath'];
const DEFINITIONS = new Map<string, ToolDefinition>([
  ['read_file', { action: 'read', targets: FILE_TARGETS }],
  ['list_files', { action: 'list', targets: FILE_TARGETS }],
  ['write_file', { action: 'write', targets: FILE_TARGETS }],
  ['edit_file', { action: 'edit', targets: FILE_TARGETS }],
  ['apply_patch', { action: 'edit', targets: FILE_TARGETS }],
  ['execute', { action: 'run', targets: ['command', 'cmd'], scope: ['working_directory', 'cwd'] }],
  ['grep_files', { action: 'search', targets: ['pattern', 'query'], scope: ['path', 'glob'] }],
  ['grep', { action: 'search', targets: ['pattern', 'query'], scope: ['path', 'glob'] }],
  ['glob_files', { action: 'search', targets: ['pattern', 'glob'], scope: ['path'] }],
  ['web_search', { action: 'search', targets: ['query'] }],
  ['web_fetch', { action: 'fetch', targets: ['url', 'uri'] }],
  ['agent_spawn', { action: 'spawn', targets: ['agent_id', 'agent_key', 'name'] }],
]);
const FALLBACK_TARGETS = ['path', 'file_path', 'filePath', 'query', 'question', 'command', 'cmd', 'pattern', 'glob', 'url', 'uri', 'skill_name', 'skillName', 'agent_id', 'agent_key', 'name', 'id'];
const MAX_ARGUMENTS = 16_384;
const MAX_PREVIEW = 180;

export function toolAction(name: string): ToolAction | undefined {
  return DEFINITIONS.get(name)?.action;
}

function preview(record: Record<string, unknown>, keys: readonly string[]): string {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === 'string' && value.trim()) return value.trim().slice(0, MAX_PREVIEW).replace(/\s+/g, ' ');
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  }
  return '';
}

/** Describe recorded arguments only; never infer an executed action from reasoning or output. */
export function toolPresentation(name: string, rawArgs: string): { action?: ToolAction; target: string } {
  const definition = DEFINITIONS.get(name);
  const action = definition?.action;
  if (rawArgs.length > MAX_ARGUMENTS) return { action, target: '' };
  const trimmed = rawArgs.trim();
  if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return { action, target: '' };
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { action, target: '' };
    const record = parsed as Record<string, unknown>;
    const target = preview(record, definition?.targets ?? FALLBACK_TARGETS);
    const scope = definition?.scope && target ? preview(record, definition.scope) : '';
    return { action, target: (scope && scope !== target ? `${target} · ${scope}` : target).slice(0, MAX_PREVIEW) };
  } catch {
    // Partial streamed JSON is not an available target yet.
    return { action, target: '' };
  }
}

export function countToolActions(tools: readonly ToolCall[]): [ToolAction | 'other', number][] {
  const counts = new Map<ToolAction | 'other', number>();
  for (const tool of tools) {
    const action = toolAction(tool.name) ?? 'other';
    counts.set(action, (counts.get(action) ?? 0) + 1);
  }
  return [...counts];
}
