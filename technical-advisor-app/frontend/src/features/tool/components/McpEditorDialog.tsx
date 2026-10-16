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

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  updateMcpServer,
  type McpServer,
} from '@/api/tool';
import { ApiError } from '@/lib/apiClient';
import { toolFailure } from '../toolFailure';

export interface McpEditorDialogProps {
  server: McpServer | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export function McpEditorDialog({ server, open, onClose, onSaved }: McpEditorDialogProps) {
  const { t } = useTranslation();
  const [transport, setTransport] = useState<'stdio' | 'sse' | 'streamable-http'>('stdio');
  const [command, setCommand] = useState('');
  const [url, setUrl] = useState('');
  const [argsText, setArgsText] = useState('');
  const [envText, setEnvText] = useState('');
  const [headersText, setHeadersText] = useState('');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (open && server) {
      setTransport((server.transport as 'stdio' | 'sse' | 'streamable-http') || 'stdio');
      setCommand(server.command ?? '');
      setUrl(server.url ?? '');
      setArgsText('');
      setEnvText('');
      setHeadersText('');
      setErrorMsg(null);
    }
  }, [open, server]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!server) return;
      setErrorMsg(null);

      let parsedArgs: string[] | undefined;
      if (argsText.trim()) {
        try {
          parsedArgs = JSON.parse(argsText);
        } catch {
          parsedArgs = argsText.split(/\s+/).filter(Boolean);
        }
      }

      let parsedEnv: Record<string, string> | undefined;
      if (envText.trim()) {
        try {
          parsedEnv = JSON.parse(envText);
        } catch {
          throw new Error(t('tool.errors.invalid_mcp_config'));
        }
      }

      let parsedHeaders: Record<string, string> | undefined;
      if (headersText.trim()) {
        try {
          parsedHeaders = JSON.parse(headersText);
        } catch {
          throw new Error(t('tool.errors.invalid_mcp_config'));
        }
      }

      return await updateMcpServer(
        server.serverId,
        {
          name: server.name,
          transport,
          command: transport === 'stdio' ? command : undefined,
          url: transport !== 'stdio' ? url : undefined,
          args: parsedArgs,
          env: parsedEnv,
          headers: parsedHeaders,
          revision: server.revision,
        },
        server.revision,
      );
    },
    onSuccess: () => {
      onSaved();
      onClose();
    },
    onError: (err: unknown) => {
      setErrorMsg(err instanceof Error && !(err instanceof ApiError) ? err.message : toolFailure(err, t));
    },
  });

  if (!server) return null;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>{t('tool.mcpEditor.title', '编辑 MCP 服务连接')}: {server.name}</DialogTitle>
          <DialogDescription>
            {t('tool.mcpEditor.hint', '更新服务连接配置。系统将在保存前测试连接，测试成功后才会替换原连接并保留服务标识。')}
          </DialogDescription>
        </DialogHeader>

        <DialogBody className="max-h-[70vh] space-y-4 overflow-y-auto">
          {/* Transport Select */}
          <div>
            <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-transport">
              {t('tool.mcpEditor.transport', '传输协议')}
            </label>
            <select
              id="mcp-edit-transport"
              className="w-full rounded-lg border border-border px-2 py-2 text-sm"
              value={transport}
              onChange={(e) => setTransport(e.target.value as 'stdio' | 'sse' | 'streamable-http')}
              disabled={saveMutation.isPending}
            >
              <option value="stdio">stdio (本地命令)</option>
              <option value="sse">SSE (HTTP Server-Sent Events)</option>
              <option value="streamable-http">Streamable HTTP</option>
            </select>
          </div>

          {/* Stdio: Command and Args */}
          {transport === 'stdio' ? (
            <>
              <div>
                <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-command">
                  {t('tool.mcpEditor.command', '启动命令')}
                </label>
                <input
                  id="mcp-edit-command"
                  className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
                  value={command}
                  onChange={(e) => setCommand(e.target.value)}
                  placeholder="npx, python, uvx..."
                  disabled={saveMutation.isPending}
                />
              </div>

              <div>
                <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-args">
                  {t('tool.mcpEditor.args', '命令参数 (空格分隔或 JSON 数组)')}
                </label>
                <input
                  id="mcp-edit-args"
                  className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
                  value={argsText}
                  onChange={(e) => setArgsText(e.target.value)}
                  placeholder='-y @modelcontextprotocol/server-filesystem /tmp'
                  disabled={saveMutation.isPending}
                />
              </div>

              <div>
                <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-env">
                  {t('tool.mcpEditor.env', '环境变量 (可选 JSON 对象)')}
                </label>
                <Textarea
                  id="mcp-edit-env"
                  rows={3}
                  value={envText}
                  onChange={(e) => setEnvText(e.target.value)}
                  placeholder='{"DEBUG": "true"}'
                  disabled={saveMutation.isPending}
                  className="font-mono text-xs"
                />
              </div>
            </>
          ) : (
            /* SSE / HTTP: URL and Headers */
            <>
              <div>
                <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-url">
                  {t('tool.mcpEditor.url', '服务 URL')}
                </label>
                <input
                  id="mcp-edit-url"
                  className="w-full rounded-lg border border-border px-3 py-2 text-sm font-mono"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="http://localhost:8080/sse"
                  disabled={saveMutation.isPending}
                />
              </div>

              <div>
                <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="mcp-edit-headers">
                  {t('tool.mcpEditor.headers', '请求头 (可选 JSON 对象)')}
                </label>
                <Textarea
                  id="mcp-edit-headers"
                  rows={3}
                  value={headersText}
                  onChange={(e) => setHeadersText(e.target.value)}
                  placeholder='{"Authorization": "Bearer token"}'
                  disabled={saveMutation.isPending}
                  className="font-mono text-xs"
                />
              </div>
            </>
          )}

          {/* Error Message */}
          {errorMsg ? (
            <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {errorMsg}
            </div>
          ) : null}
        </DialogBody>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saveMutation.isPending}>
            {t('chat.cancel', '取消')}
          </Button>
          <Button
            onClick={() => saveMutation.mutate()}
            disabled={
              saveMutation.isPending ||
              (transport === 'stdio' ? !command.trim() : !url.trim())
            }
          >
            {saveMutation.isPending
              ? t('tool.mcpEditor.saving', '连接并保存中…')
              : t('tool.mcpEditor.save', '测试并保存')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
