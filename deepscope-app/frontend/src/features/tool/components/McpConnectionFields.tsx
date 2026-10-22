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

import { useTranslation } from 'react-i18next';
import { Textarea } from '@/components/ui/input';
import { AppSelect } from '@/components/ui/select';
import type { McpConnectionValues, McpTransport } from '../mcpConnection';

export interface McpConnectionFieldsProps {
  values: McpConnectionValues;
  onChange: (values: McpConnectionValues) => void;
  disabled?: boolean;
  /** Keeps the field ids apart when the same form appears in two dialogs. */
  idPrefix: string;
  /** The name is chosen when a service is created; an existing service keeps the name it has. */
  showName?: boolean;
}

/**
 * The connection fields of an MCP service, so creating and editing one ask for the same things.
 */
export function McpConnectionFields({
  values,
  onChange,
  disabled,
  idPrefix,
  showName,
}: McpConnectionFieldsProps) {
  const { t } = useTranslation();
  const set = <K extends keyof McpConnectionValues>(key: K, value: McpConnectionValues[K]) =>
    onChange({ ...values, [key]: value });

  return (
    <>
      {showName ? (
        <div>
          <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-name`}>
            {t('tool.mcpEditor.serviceName', '服务名称')}
          </label>
          <input
            id={`${idPrefix}-name`}
            className="w-full rounded-lg border border-border px-3 py-2 text-sm"
            value={values.name}
            onChange={(e) => set('name', e.target.value)}
            placeholder="mcp"
            disabled={disabled}
          />
        </div>
      ) : null}

      <div>
        <label className="mb-1.5 block text-[13px] font-medium text-slate-700">
          {t('tool.mcpEditor.transport', '传输协议')}
        </label>
        <AppSelect
          className="w-full"
          aria-label={t('tool.mcpEditor.transport', '传输协议')}
          value={values.transport}
          onChange={(value) => set('transport', value as McpTransport)}
          disabled={disabled}
          options={[
            { value: 'stdio', label: 'stdio (本地命令)' },
            { value: 'sse', label: 'SSE (HTTP Server-Sent Events)' },
            { value: 'streamable-http', label: 'Streamable HTTP' },
          ]}
        />
      </div>

      {values.transport === 'stdio' ? (
        <>
          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-command`}>
              {t('tool.mcpEditor.command', '启动命令')}
            </label>
            <input
              id={`${idPrefix}-command`}
              className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
              value={values.command}
              onChange={(e) => set('command', e.target.value)}
              placeholder="npx, python, uvx..."
              disabled={disabled}
            />
          </div>

          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-args`}>
              {t('tool.mcpEditor.args', '命令参数 (空格分隔或 JSON 数组)')}
            </label>
            <input
              id={`${idPrefix}-args`}
              className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
              value={values.argsText}
              onChange={(e) => set('argsText', e.target.value)}
              placeholder="-y @modelcontextprotocol/server-filesystem /tmp"
              disabled={disabled}
            />
          </div>

          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-env`}>
              {t('tool.mcpEditor.env', '环境变量 (可选 JSON 对象)')}
            </label>
            <Textarea
              id={`${idPrefix}-env`}
              rows={3}
              value={values.envText}
              onChange={(e) => set('envText', e.target.value)}
              placeholder='{"DEBUG": "true"}'
              disabled={disabled}
              className="font-mono text-xs"
            />
          </div>
        </>
      ) : (
        <>
          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-url`}>
              {t('tool.mcpEditor.url', '服务 URL')}
            </label>
            <input
              id={`${idPrefix}-url`}
              className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
              value={values.url}
              onChange={(e) => set('url', e.target.value)}
              placeholder="http://localhost:8080/sse"
              disabled={disabled}
            />
          </div>

          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor={`${idPrefix}-headers`}>
              {t('tool.mcpEditor.headers', '请求头 (可选 JSON 对象)')}
            </label>
            <Textarea
              id={`${idPrefix}-headers`}
              rows={3}
              value={values.headersText}
              onChange={(e) => set('headersText', e.target.value)}
              placeholder='{"Authorization": "Bearer token"}'
              disabled={disabled}
              className="font-mono text-xs"
            />
          </div>
        </>
      )}
    </>
  );
}
