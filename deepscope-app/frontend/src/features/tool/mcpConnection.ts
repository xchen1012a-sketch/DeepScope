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

/** The connection fields an MCP service is reached by, shared by creating and editing one. */
export type McpTransport = 'stdio' | 'sse' | 'streamable-http';

export interface McpConnectionValues {
  name: string;
  transport: McpTransport;
  command: string;
  url: string;
  argsText: string;
  envText: string;
  headersText: string;
}

export const emptyMcpConnection: McpConnectionValues = {
  name: 'mcp',
  transport: 'stdio',
  command: '',
  url: '',
  argsText: '',
  envText: '',
  headersText: '',
};

/**
 * Reads a JSON object the user typed. A field that does not hold an object is refused, so the
 * caller can point at the field instead of sending a configuration the server cannot read.
 */
export function parseMcpJsonObject(text: string): Record<string, string> | undefined {
  if (!text.trim()) return undefined;
  const parsed: unknown = JSON.parse(text);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('invalid_mcp_object');
  }
  return Object.fromEntries(
    Object.entries(parsed as Record<string, unknown>).map(([key, value]) => [key, String(value)]),
  );
}

/**
 * Reads command arguments, accepting either a JSON array or a space separated list, because both
 * are how these commands are written down.
 */
export function parseMcpArgs(text: string): string[] | undefined {
  if (!text.trim()) return undefined;
  try {
    const parsed: unknown = JSON.parse(text);
    if (Array.isArray(parsed)) return parsed.map(String);
  } catch {
    // A plain command line is the common case.
  }
  return text.split(/\s+/).filter(Boolean);
}

/**
 * Builds the configuration text the endpoint parser reads: a command with its arguments and
 * environment, or a URL with its headers.
 */
export function toMcpConfigText(values: McpConnectionValues): string {
  const config: Record<string, unknown> = { name: values.name.trim() || 'mcp' };
  if (values.transport === 'stdio') {
    config.command = values.command.trim();
    const args = parseMcpArgs(values.argsText);
    if (args) config.args = args;
    const env = parseMcpJsonObject(values.envText);
    if (env) config.env = env;
  } else {
    config.url = values.url.trim();
    config.transport = values.transport === 'sse' ? 'sse' : 'http';
    const headers = parseMcpJsonObject(values.headersText);
    if (headers) config.headers = headers;
  }
  return JSON.stringify(config);
}
