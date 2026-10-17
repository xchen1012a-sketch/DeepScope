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
  updateTool,
  type CatalogTool,
  type ToolAuthConfig,
  type ToolPermission,
} from '@/api/tool';
import { ToolAuthSection } from './ToolAuthSection';
import { toolFailure } from '../toolFailure';

export interface ToolEditorPanelProps {
  tool: CatalogTool | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export function ToolEditorPanel({ tool, open, onClose, onSaved }: ToolEditorPanelProps) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [httpMethod, setHttpMethod] = useState('GET');
  const [httpUrl, setHttpUrl] = useState('');
  const [inputSchema, setInputSchema] = useState('{}');
  const [permission, setPermission] = useState<ToolPermission>('ask');
  const [auth, setAuth] = useState<ToolAuthConfig>({ action: 'keep', type: 'none' });
  const [isConfirmingDiscard, setIsConfirmingDiscard] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (open && tool) {
      setName(tool.name ?? '');
      setDescription(tool.description ?? '');
      setHttpMethod(tool.httpMethod ?? 'GET');
      setHttpUrl(tool.httpUrl ?? '');
      setInputSchema(tool.inputSchema ?? '{}');
      setPermission(tool.permission ?? 'ask');
      setAuth({ action: 'keep', type: 'none' });
      setIsConfirmingDiscard(false);
      setErrorMsg(null);
    }
  }, [open, tool]);

  const isDirty = Boolean(
    tool &&
      (name !== (tool.name ?? '') ||
        description !== (tool.description ?? '') ||
        httpMethod !== (tool.httpMethod ?? 'GET') ||
        httpUrl !== (tool.httpUrl ?? '') ||
        inputSchema !== (tool.inputSchema ?? '{}') ||
        permission !== (tool.permission ?? 'ask') ||
        auth.action !== 'keep'),
  );

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!tool) return;
      setErrorMsg(null);
      return await updateTool(
        tool.toolId,
        {
          name,
          description,
          httpMethod: tool.origin === 'http' ? httpMethod : undefined,
          httpUrl: tool.origin === 'http' ? httpUrl : undefined,
          inputSchema,
          permission,
          auth: tool.origin === 'http' ? auth : undefined,
        },
        tool.revision,
      );
    },
    onSuccess: () => {
      onSaved();
      onClose();
    },
    onError: (err: unknown) => {
      setErrorMsg(toolFailure(err, t));
    },
  });

  if (!tool) return null;

  const handleRequestClose = () => {
    if (isDirty) {
      setIsConfirmingDiscard(true);
    } else {
      onClose();
    }
  };

  return (
    <>
      <Dialog open={open && !isConfirmingDiscard} onOpenChange={(next) => !next && handleRequestClose()}>
        <DialogContent size="lg">
          <DialogHeader>
            <DialogTitle>{t('tool.editor.title', '编辑工具')}: {tool.name}</DialogTitle>
            <DialogDescription>
              {tool.origin === 'mcp'
                ? t('tool.errors.remote_definition', 'MCP 工具由远端服务提供定义')
                : t('tool.editor.subtitle', '更新工具名称、调用地址、参数及权限')}
            </DialogDescription>
          </DialogHeader>

          <DialogBody className="max-h-[70vh] space-y-4 overflow-y-auto">
            {/* Tool Name */}
            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-name">
                {t('tool.editor.name', '工具名称')}
              </label>
              <input
                id="tool-edit-name"
                className="w-full rounded-lg border border-border px-3 py-2 text-sm"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={saveMutation.isPending}
              />
            </div>

            {/* Description */}
            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-desc">
                {t('tool.editor.description', '描述')}
              </label>
              <Textarea
                id="tool-edit-desc"
                rows={2}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                disabled={saveMutation.isPending}
              />
            </div>

            {/* HTTP Method and URL (Only for HTTP origin tools) */}
            {tool.origin === 'http' ? (
              <div className="grid grid-cols-4 gap-3">
                <div className="col-span-1">
                  <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-method">
                    {t('tool.editor.httpMethod', '方法')}
                  </label>
                  <select
                    id="tool-edit-method"
                    className="w-full rounded-lg border border-border px-2 py-2 text-sm"
                    value={httpMethod}
                    onChange={(e) => setHttpMethod(e.target.value)}
                    disabled={saveMutation.isPending}
                  >
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                    <option value="PATCH">PATCH</option>
                  </select>
                </div>
                <div className="col-span-3">
                  <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-url">
                    {t('tool.editor.httpUrl', '调用地址')}
                  </label>
                  <input
                    id="tool-edit-url"
                    className="w-full rounded-lg border border-border px-3 py-2 text-sm"
                    value={httpUrl}
                    onChange={(e) => setHttpUrl(e.target.value)}
                    disabled={saveMutation.isPending}
                  />
                </div>
              </div>
            ) : null}

            {/* Input Schema */}
            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-schema">
                {t('tool.editor.inputSchema', '参数定义 (JSON Schema)')}
              </label>
              <Textarea
                id="tool-edit-schema"
                rows={5}
                value={inputSchema}
                onChange={(e) => setInputSchema(e.target.value)}
                disabled={saveMutation.isPending || tool.origin === 'mcp'}
                className="font-mono text-xs"
              />
            </div>

            {/* Permission Select */}
            <div>
              <label className="mb-1.5 block text-[13px] font-medium text-slate-700" htmlFor="tool-edit-permission">
                {t('tool.permission', '权限')}
              </label>
              <select
                id="tool-edit-permission"
                className="w-full rounded-lg border border-border px-2 py-2 text-sm"
                value={permission}
                onChange={(e) => setPermission(e.target.value as ToolPermission)}
                disabled={saveMutation.isPending}
              >
                <option value="allow">{t('tool.level.allow', '允许')}</option>
                <option value="ask">{t('tool.level.ask', '询问')}</option>
                <option value="deny">{t('tool.level.deny', '禁用')}</option>
              </select>
            </div>

            {/* Authentication (for HTTP tools) */}
            {tool.origin === 'http' ? (
              <ToolAuthSection
                mode="edit"
                value={auth}
                onChange={setAuth}
                hasExistingCredential={tool.hasCredential}
              />
            ) : null}

            {/* Error Message */}
            {errorMsg ? (
              <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                {errorMsg}
              </div>
            ) : null}
          </DialogBody>

          <DialogFooter>
            <Button variant="outline" onClick={handleRequestClose} disabled={saveMutation.isPending}>
              {t('chat.cancel', '取消')}
            </Button>
            <Button
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending || !name.trim()}
            >
              {saveMutation.isPending ? t('tool.editor.saving', '保存中…') : t('tool.editor.save', '保存修改')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Discard confirmation dialog */}
      <Dialog open={isConfirmingDiscard} onOpenChange={(next) => !next && setIsConfirmingDiscard(false)}>
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>{t('tool.editor.discardTitle', '放弃未保存的修改？')}</DialogTitle>
            <DialogDescription>
              {t('tool.editor.unsavedWarning', '您有未保存的修改，确定离开并放弃修改吗？')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIsConfirmingDiscard(false)}>
              {t('chat.cancel', '返回编辑')}
            </Button>
            <Button
              variant="destructive"
              onClick={() => {
                setIsConfirmingDiscard(false);
                onClose();
              }}
            >
              {t('tool.editor.confirmDiscard', '放弃修改')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
