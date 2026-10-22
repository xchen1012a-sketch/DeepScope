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
import {
  parseMcpArgs,
  parseMcpJsonObject,
  type McpConnectionValues,
  type McpTransport,
} from '../mcpConnection';
import { McpConnectionFields } from './McpConnectionFields';

export interface McpEditorDialogProps {
  server: McpServer | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export function McpEditorDialog({ server, open, onClose, onSaved }: McpEditorDialogProps) {
  const { t } = useTranslation();
  const [values, setValues] = useState<McpConnectionValues>({
    name: 'mcp',
    transport: 'stdio',
    command: '',
    url: '',
    argsText: '',
    envText: '',
    headersText: '',
  });
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (open && server) {
      setValues({
        name: server.name,
        transport: (server.transport as McpTransport) || 'stdio',
        command: server.command ?? '',
        url: server.url ?? '',
        argsText: '',
        envText: '',
        headersText: '',
      });
      setErrorMsg(null);
    }
  }, [open, server]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!server) return;
      setErrorMsg(null);

      const parsedArgs = parseMcpArgs(values.argsText);

      let parsedEnv: Record<string, string> | undefined;
      let parsedHeaders: Record<string, string> | undefined;
      try {
        parsedEnv = parseMcpJsonObject(values.envText);
        parsedHeaders = parseMcpJsonObject(values.headersText);
      } catch {
        throw new Error(t('tool.errors.invalid_mcp_config'));
      }

      return await updateMcpServer(
        server.serverId,
        {
          name: server.name,
          transport: values.transport,
          command: values.transport === 'stdio' ? values.command : undefined,
          url: values.transport !== 'stdio' ? values.url : undefined,
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
          <McpConnectionFields
            values={values}
            onChange={setValues}
            disabled={saveMutation.isPending}
            idPrefix="mcp-edit"
          />

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
              (values.transport === 'stdio' ? !values.command.trim() : !values.url.trim())
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
