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

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { AlertTriangle, Check, Eye } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  importTools,
  previewTools,
  type DuplicateAction,
  type DuplicateActionType,
  type ToolAuthConfig,
  type ToolPermission,
  type ToolPreviewItem,
} from '@/api/tool';
import { ToolAuthSection } from './ToolAuthSection';
import { toolFailure } from '../toolFailure';

export interface ToolImportDialogProps {
  open: boolean;
  onClose: () => void;
  onDone: () => void;
}

export function ToolImportDialog({ open, onClose, onDone }: ToolImportDialogProps) {
  const { t } = useTranslation();
  const [kind, setKind] = useState<'openapi' | 'schema'>('openapi');
  const [content, setContent] = useState('');
  const [address, setAddress] = useState('');
  const [permission, setPermission] = useState<ToolPermission>('ask');
  const [auth, setAuth] = useState<ToolAuthConfig>({ type: 'none' });

  // Preview state
  const [previewItems, setPreviewItems] = useState<ToolPreviewItem[] | null>(null);
  const [selectedOperations, setSelectedOperations] = useState<string[]>([]);
  const [duplicateActions, setDuplicateActions] = useState<Record<string, DuplicateAction>>({});
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const previewMutation = useMutation({
    mutationFn: async () => {
      setErrorMsg(null);
      return await previewTools({
        kind,
        content,
        address: address.trim() || undefined,
      });
    },
    onSuccess: (items) => {
      setPreviewItems(items);
      const allOps = items.map((it) => it.operationId);
      setSelectedOperations(allOps);

      // Default duplicate decisions
      const initialActions: Record<string, DuplicateAction> = {};
      for (const item of items) {
        if (item.duplicates && item.duplicates.length > 0) {
          const match = item.duplicates[0];
          initialActions[item.operationId] = {
            action: match.sameOperation ? 'update' : 'create',
            toolId: match.toolId,
            expectedRevision: match.revision,
            name: match.sameOperation ? undefined : `${item.name}_new`,
          };
        }
      }
      setDuplicateActions(initialActions);
    },
    onError: (err: unknown) => {
      setErrorMsg(toolFailure(err, t));
    },
  });

  const importMutation = useMutation({
    mutationFn: async () => {
      setErrorMsg(null);
      const authPayload = auth && auth.type && auth.type !== 'none' ? auth : undefined;
      const idempotencyKey = `import-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

      return await importTools(
        {
          kind,
          content,
          address: address.trim() || undefined,
          permission,
          chosenOperations: previewItems ? selectedOperations : undefined,
          duplicateActions: previewItems ? duplicateActions : undefined,
          definitionHash: previewItems?.[0]?.definitionHash,
          auth: authPayload,
        },
        idempotencyKey,
      );
    },
    onSuccess: () => {
      onDone();
      onClose();
      // Reset state
      setContent('');
      setAddress('');
      setPreviewItems(null);
      setSelectedOperations([]);
      setDuplicateActions({});
      setErrorMsg(null);
    },
    onError: (err: unknown) => {
      setErrorMsg(toolFailure(err, t));
    },
  });

  const toggleOperation = (opId: string) => {
    setSelectedOperations((prev) =>
      prev.includes(opId) ? prev.filter((id) => id !== opId) : [...prev, opId],
    );
  };

  const handleDuplicateActionChange = (
    opId: string,
    action: DuplicateActionType,
    candidateToolId?: string,
    revision?: number,
  ) => {
    setDuplicateActions((prev) => ({
      ...prev,
      [opId]: {
        action,
        toolId: candidateToolId,
        expectedRevision: revision,
        name: action === 'create' ? prev[opId]?.name || `${opId}_new` : undefined,
      },
    }));
  };

  const handleDuplicateNameChange = (opId: string, newName: string) => {
    setDuplicateActions((prev) => ({
      ...prev,
      [opId]: {
        ...(prev[opId] ?? { action: 'create' }),
        name: newName,
      },
    }));
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="lg" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{t('tool.importTitle', '导入工具')}</DialogTitle>
        </DialogHeader>

        <DialogBody className="max-h-[70vh] space-y-4 overflow-y-auto">
          {/* Definition Type */}
          <div>
            <label
              className="mb-1.5 block text-[13px] font-medium text-slate-700"
              htmlFor="tool-import-kind"
            >
              {t('tool.kindLabel')}
            </label>
            <select
              id="tool-import-kind"
              className="w-full rounded-lg border border-border px-2 py-2 text-sm"
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as 'openapi' | 'schema');
                setPreviewItems(null);
              }}
              disabled={importMutation.isPending || previewMutation.isPending}
            >
              <option value="openapi">{t('tool.importDialog.openapiFormat')}</option>
              <option value="schema">{t('tool.importDialog.schemaFormat')}</option>
            </select>
          </div>

          {/* Definition Content Textarea */}
          <div>
            <label
              className="mb-1.5 block text-[13px] font-medium text-slate-700"
              htmlFor="tool-import-content"
            >
              {t('tool.importDialog.content')}
            </label>
            <Textarea
              id="tool-import-content"
              rows={8}
              value={content}
              onChange={(e) => {
                setContent(e.target.value);
                setPreviewItems(null);
              }}
              placeholder={t(
                kind === 'openapi' ? 'tool.pasteOpenapi' : 'tool.pasteSchema',
              )}
              disabled={importMutation.isPending || previewMutation.isPending}
              className="font-mono text-xs"
            />
          </div>

          {/* Address */}
          <div>
            <label
              className="mb-1.5 block text-[13px] font-medium text-slate-700"
              htmlFor="tool-import-address"
            >
              {t('tool.address', '调用地址')}
            </label>
            <input
              id="tool-import-address"
              className="w-full rounded-lg border border-border px-3 py-2 text-sm"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              disabled={importMutation.isPending || previewMutation.isPending}
              placeholder={t('tool.importDialog.addressPlaceholder')}
            />
          </div>

          {/* Permission */}
          <div>
            <label
              className="mb-1.5 block text-[13px] font-medium text-slate-700"
              htmlFor="tool-import-permission"
            >
              {t('tool.permission', '权限')}
            </label>
            <select
              id="tool-import-permission"
              className="w-full rounded-lg border border-border px-2 py-2 text-sm"
              value={permission}
              onChange={(e) => setPermission(e.target.value as ToolPermission)}
              disabled={importMutation.isPending || previewMutation.isPending}
            >
              <option value="allow">{t('tool.level.allow', '允许')}</option>
              <option value="ask">{t('tool.level.ask', '询问')}</option>
              <option value="deny">{t('tool.level.deny', '禁用')}</option>
            </select>
          </div>

          {/* Authentication Section */}
          <ToolAuthSection
            mode="create"
            value={auth}
            onChange={setAuth}
          />

          {/* Preview Button */}
          <div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => previewMutation.mutate()}
              disabled={!content.trim() || previewMutation.isPending}
            >
              <Eye className="h-4 w-4" />
              {previewMutation.isPending
                ? t('tool.importDialog.previewing', '解析中…')
                : t('tool.importDialog.preview', '解析预览候选项')}
            </Button>
          </div>

          {/* Preview Items & Duplicate Handling */}
          {previewItems && previewItems.length > 0 ? (
            <div className="space-y-3 rounded-xl border border-slate-200 bg-slate-50/50 p-4">
              <div className="flex items-center justify-between">
                <span className="text-sm font-semibold text-slate-800">
                  {t('tool.importDialog.candidates', '识别到的操作 ({{count}})', {
                    count: previewItems.length,
                  })}
                </span>
              </div>

              <div className="space-y-3">
                {previewItems.map((item) => {
                  const isChecked = selectedOperations.includes(item.operationId);
                  const hasDuplicates = item.duplicates && item.duplicates.length > 0;
                  const currentDecision = duplicateActions[item.operationId];

                  return (
                    <div
                      key={item.operationId}
                      className="rounded-lg border border-border bg-white p-3 text-sm"
                    >
                      <div className="flex items-start gap-2">
                        <input
                          type="checkbox"
                          id={`op-${item.operationId}`}
                          checked={isChecked}
                          onChange={() => toggleOperation(item.operationId)}
                          className="mt-1"
                        />
                        <div className="flex-1">
                          <label
                            htmlFor={`op-${item.operationId}`}
                            className="cursor-pointer font-medium text-slate-800"
                          >
                            {item.name}
                            {item.httpMethod ? (
                              <Badge tone="default" className="ml-2 text-[10px]">
                                {item.httpMethod}
                              </Badge>
                            ) : null}
                          </label>
                          {item.description ? (
                            <p className="mt-0.5 text-xs text-slate-500">{item.description}</p>
                          ) : null}

                          {/* Duplicates Alert & Resolution */}
                          {hasDuplicates ? (
                            <div className="mt-2 rounded border border-amber-200 bg-amber-50 p-2 text-xs">
                              <div className="flex items-center gap-1.5 font-medium text-amber-800">
                                <AlertTriangle className="h-3.5 w-3.5 text-amber-600" />
                                {t(
                                  'tool.importDialog.duplicateFound',
                                  '发现同名或匹配的已存在工具: {{name}}',
                                  { name: item.duplicates[0].name },
                                )}
                              </div>
                              <div className="mt-2 flex flex-wrap items-center gap-3">
                                <label className="inline-flex items-center gap-1 cursor-pointer">
                                  <input
                                    type="radio"
                                    name={`action-${item.operationId}`}
                                    value="create"
                                    checked={currentDecision?.action === 'create'}
                                    onChange={() =>
                                      handleDuplicateActionChange(
                                        item.operationId,
                                        'create',
                                        item.duplicates[0].toolId,
                                        item.duplicates[0].revision,
                                      )
                                    }
                                  />
                                  {t('tool.importDialog.actionCreate', '换名新建')}
                                </label>
                                <label className="inline-flex items-center gap-1 cursor-pointer">
                                  <input
                                    type="radio"
                                    name={`action-${item.operationId}`}
                                    value="update"
                                    checked={currentDecision?.action === 'update'}
                                    onChange={() =>
                                      handleDuplicateActionChange(
                                        item.operationId,
                                        'update',
                                        item.duplicates[0].toolId,
                                        item.duplicates[0].revision,
                                      )
                                    }
                                  />
                                  {t('tool.importDialog.actionUpdate', '更新已有')}
                                </label>
                                <label className="inline-flex items-center gap-1 cursor-pointer">
                                  <input
                                    type="radio"
                                    name={`action-${item.operationId}`}
                                    value="skip"
                                    checked={currentDecision?.action === 'skip'}
                                    onChange={() =>
                                      handleDuplicateActionChange(
                                        item.operationId,
                                        'skip',
                                        item.duplicates[0].toolId,
                                        item.duplicates[0].revision,
                                      )
                                    }
                                  />
                                  {t('tool.importDialog.actionSkip', '跳过')}
                                </label>
                              </div>

                              {currentDecision?.action === 'create' ? (
                                <div className="mt-2 flex items-center gap-2">
                                  <span className="text-slate-600">
                                    {t('tool.importDialog.newName', '新名称')}:
                                  </span>
                                  <input
                                    type="text"
                                    className="rounded border border-border px-2 py-0.5 text-xs"
                                    value={currentDecision.name ?? ''}
                                    onChange={(e) =>
                                      handleDuplicateNameChange(item.operationId, e.target.value)
                                    }
                                    placeholder={`${item.name}_new`}
                                  />
                                </div>
                              ) : null}
                            </div>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : null}

          {/* Error Message */}
          {errorMsg ? (
            <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {errorMsg}
            </div>
          ) : null}
        </DialogBody>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={importMutation.isPending}>
            {t('chat.cancel', '取消')}
          </Button>
          <Button
            onClick={() => importMutation.mutate()}
            disabled={
              !content.trim() ||
              importMutation.isPending ||
              (previewItems !== null && selectedOperations.length === 0)
            }
          >
            <Check className="h-4 w-4" />
            {importMutation.isPending ? t('tool.importing', '导入中…') : t('tool.import', '确认导入')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
