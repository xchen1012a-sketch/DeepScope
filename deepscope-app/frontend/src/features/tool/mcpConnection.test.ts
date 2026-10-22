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
import {
  emptyMcpConnection,
  parseMcpArgs,
  parseMcpJsonObject,
  toMcpConfigText,
} from './mcpConnection';

describe('mcpConnection', () => {
  it('builds a command configuration from the fields', () => {
    const text = toMcpConfigText({
      ...emptyMcpConnection,
      name: 'docs_mcp',
      command: 'npx',
      argsText: '-y @modelcontextprotocol/server-filesystem /tmp',
    });
    expect(JSON.parse(text)).toEqual({
      name: 'docs_mcp',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-filesystem', '/tmp'],
    });
  });

  it('reads arguments given as a JSON array', () => {
    expect(parseMcpArgs('["-y","pkg"]')).toEqual(['-y', 'pkg']);
  });

  it('builds a url configuration and maps streamable http onto http', () => {
    const text = toMcpConfigText({
      ...emptyMcpConnection,
      transport: 'streamable-http',
      url: 'https://mcp.example.com/sse',
      headersText: '{"Authorization":"Bearer token"}',
    });
    expect(JSON.parse(text)).toEqual({
      name: 'mcp',
      url: 'https://mcp.example.com/sse',
      transport: 'http',
      headers: { Authorization: 'Bearer token' },
    });
  });

  it('keeps the sse transport', () => {
    const text = toMcpConfigText({
      ...emptyMcpConnection,
      transport: 'sse',
      url: 'https://mcp.example.com/sse',
    });
    expect(JSON.parse(text).transport).toBe('sse');
  });

  it('leaves out the fields that were left empty', () => {
    const text = toMcpConfigText({ ...emptyMcpConnection, command: 'npx' });
    expect(JSON.parse(text)).not.toHaveProperty('env');
    expect(JSON.parse(text)).not.toHaveProperty('args');
  });

  it('refuses a field that does not hold an object', () => {
    expect(() => parseMcpJsonObject('["a"]')).toThrow();
    expect(parseMcpJsonObject('  ')).toBeUndefined();
  });
});
