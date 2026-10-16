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

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { AlertCircle, Clock, Play, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
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
  testTool,
  stopToolTest,
  suggestToolTestInput,
  type CatalogTool,
  type ToolTestResult,
} from '@/api/tool';
import { ApiError } from '@/lib/apiClient';
import { toolFailure } from '../toolFailure';
import { toolTestTemplate } from '../testInputTemplate';

const PAGE_SIZE = 1500;

export interface ToolTestPanelProps {
  tool: CatalogTool | null;
  open: boolean;
  onClose: () => void;
}

export function ToolTestPanel({ tool, open, onClose }: ToolTestPanelProps) {
  const { t } = useTranslation();
  const [paramsText, setParamsText] = useState('{}');
  const [runId, setRunId] = useState('');
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [result, setResult] = useState<ToolTestResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [confirmationData, setConfirmationData] = useState<{
    confirmationId?: string;
  } | null>(null);
  const [page, setPage] = useState(1);
  const [filling, setFilling] = useState(false);
  const fillController = useRef<AbortController | null>(null);

  useEffect(() => {
    fillController.current?.abort();
    fillController.current = null;
    setFilling(false);
    if (open && tool) {
      setRunId(`run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
      setResult(null);
      setErrorMsg(null);
      setConfirmationData(null);
      setPage(1);

      setParamsText(toolTestTemplate(tool.inputSchema ?? '{}'));
    }
    return () => { fillController.current?.abort(); };
  }, [open, tool]);

  async function fillInput() {
    if (!tool || filling || testMutation.isPending) return;
    const controller = new AbortController();
    fillController.current?.abort();
    fillController.current = controller;
    setFilling(true);
    setErrorMsg(null);
    setConfirmationData(null);
    try {
      const response = await suggestToolTestInput(tool.toolId, controller.signal);
      if (controller.signal.aborted || fillController.current !== controller) return;
      setParamsText(JSON.stringify(response.input, null, 2));
      setResult(null);
    } catch (error) {
      if (!controller.signal.aborted && fillController.current === controller) setErrorMsg(toolFailure(error, t));
    } finally {
      if (fillController.current === controller) { setFilling(false); fillController.current = null; }
    }
  }

  const testMutation = useMutation({
    mutationFn: async (opts?: { confirmed?: boolean; confirmationId?: string } | void) => {
      if (!tool) return;
      let parsedParams: Record<string, unknown> = {};
      try {
        if (paramsText.trim()) {
          parsedParams = JSON.parse(paramsText);
          if (!parsedParams || typeof parsedParams !== 'object' || Array.isArray(parsedParams)) throw new Error('object_required');
        }
      } catch {
        throw new Error(t('tool.errors.invalid_parameters'));
      }

      const currentRunId = opts?.confirmed ? runId : `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      setRunId(currentRunId);
      setActiveRunId(currentRunId);
      setResult(null);
      setErrorMsg(null);
      setConfirmationData(null);

      const data = await testTool(tool.toolId, {
        runId: currentRunId,
        input: parsedParams,
        savedRevision: tool.revision,
        confirmed: opts?.confirmed ?? false,
        confirmationId: opts?.confirmationId,
      });
      if (data.errorCode || (data.httpStatus && data.httpStatus >= 400)) {
        setResult(data);
        setPage(1);
        throw new Error(data.messageKey && t(data.messageKey) !== data.messageKey ? t(data.messageKey) : t('tool.testPanel.executionFailed'));
      }
      return data;
    },
    onSuccess: (data) => {
      setActiveRunId(null);
      setResult(data ?? null);
      setPage(1);
    },
    onError: (err: unknown) => {
      setActiveRunId(null);
      if (err instanceof ApiError && err.status === 409) {
        try {
          const body = JSON.parse(err.body) as {
            code?: string;
            confirmationId?: string;
          };
          if (body.code === 'confirmation_required') {
            setConfirmationData({ confirmationId: body.confirmationId });
            return;
          }
        } catch {
          // ignore parse error
        }
      }
      setErrorMsg(err instanceof ApiError ? toolFailure(err, t) : err instanceof Error ? err.message : toolFailure(err, t));
    },
  });

  const stopMutation = useMutation({
    mutationFn: async () => {
      if (!activeRunId) return;
      return await stopToolTest(activeRunId);
    },
    onSuccess: () => {
      setActiveRunId(null);
    },
  });

  if (!tool) return null;

  const handleClose = () => {
    fillController.current?.abort();
    if (activeRunId) {
      void stopToolTest(activeRunId);
    }
    onClose();
  };

  const fullResultText = result?.result ?? '';
  const totalPages = Math.max(1, Math.ceil(fullResultText.length / PAGE_SIZE));
  const currentPageText = fullResultText.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <Dialog open={open} onOpenChange={(next) => !next && handleClose()}>
      <DialogContent size="lg">
        <DialogHeader className="min-h-0 shrink-0">
          <div className="flex items-center gap-2">
            <DialogTitle>{t('tool.testPanel.title', '测试工具')}: {tool.name}</DialogTitle>
            <Badge tone={tool.permission === 'deny' ? 'destructive' : tool.permission === 'ask' ? 'warning' : 'success'}>
              {t(`tool.level.${tool.permission}`)}
            </Badge>
          </div>
          <DialogDescription className="h-20 overflow-y-auto overscroll-contain whitespace-pre-wrap break-words pr-2">
            {[tool.httpMethod ? `${tool.httpMethod} ${tool.httpUrl ?? ''}` : '', tool.description].filter(Boolean).join('\n')}
          </DialogDescription>
        </DialogHeader>

        <DialogBody className="max-h-[70vh] space-y-4 overflow-y-auto">
          {/* Confirmation Alert */}
          {confirmationData ? (
            <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4">
              <AlertCircle className="h-5 w-5 shrink-0 text-amber-600" />
              <div className="flex-1">
                <p className="text-sm font-medium text-amber-900">
                  {t('tool.testPanel.confirmAsk', '该工具执行策略为每次询问，确认执行该操作吗？')}
                </p>
                <div className="mt-3 flex gap-2">
                  <Button
                    size="sm"
                    disabled={testMutation.isPending}
                    onClick={() =>
                      testMutation.mutate({
                        confirmed: true,
                        confirmationId: confirmationData.confirmationId,
                      })
                    }
                  >
                    {t('tool.testPanel.confirmButton', '确认并执行')}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setConfirmationData(null)}
                  >
                    {t('chat.cancel')}
                  </Button>
                </div>
              </div>
            </div>
          ) : null}

          {/* Parameters Input */}
          <div>
            <label
              className="mb-1.5 block text-[13px] font-medium text-slate-700"
              htmlFor="tool-test-params"
            >
              {t('tool.testPanel.parameters', '输入参数 (JSON)')}
            </label>
            <div className="mb-2 flex justify-end">
              <Button type="button" size="sm" variant="outline" disabled={filling || testMutation.isPending || stopMutation.isPending}
                onClick={() => void fillInput()}>
                {t(filling ? 'tool.testPanel.aiFilling' : 'tool.testPanel.aiFill')}
              </Button>
            </div>
            <Textarea
              id="tool-test-params"
              rows={6}
              value={paramsText}
              onChange={(e) => setParamsText(e.target.value)}
              placeholder={t('tool.testPanel.parametersPlaceholder', '{\n  "key": "value"\n}')}
              disabled={testMutation.isPending || filling}
              className="font-mono text-xs"
            />
          </div>

          {/* Action Button: Run / Stop */}
          <div className="flex items-center gap-2">
            {activeRunId ? (
              <Button
                variant="destructive"
                size="sm"
                onClick={() => stopMutation.mutate()}
                disabled={stopMutation.isPending}
              >
                <Square className="h-3.5 w-3.5" />
                {t('tool.testPanel.stop', '停止')}
              </Button>
            ) : (
              <Button
                size="sm"
                onClick={() => testMutation.mutate()}
                disabled={testMutation.isPending || filling}
              >
                <Play className="h-3.5 w-3.5" />
                {t('tool.testPanel.run', '运行测试')}
              </Button>
            )}
          </div>

          {/* Error Display */}
          {errorMsg ? (
            <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              {errorMsg}
            </div>
          ) : null}

          {/* Result Display */}
          {result ? (
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border pb-2 text-xs text-slate-500">
                <div className="flex items-center gap-3">
                  {result.httpStatus ? (
                    <span className="font-semibold text-slate-800">
                      HTTP {result.httpStatus}
                    </span>
                  ) : null}
                  <span className="inline-flex items-center gap-1">
                    <Clock className="h-3 w-3" />
                    {result.elapsedMs}ms
                  </span>
                  {result.truncated ? (
                    <span className="text-amber-600">
                      {t('tool.testPanel.truncated', '响应内容已截断')}
                    </span>
                  ) : null}
                </div>
                {totalPages > 1 ? (
                  <div className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-6 px-2 text-xs"
                      disabled={page <= 1}
                      onClick={() => setPage((p) => Math.max(1, p - 1))}
                    >
                      {t('tool.testPanel.pagePrev', '上一页')}
                    </Button>
                    <span>
                      {t('tool.testPanel.pageInfo', '第 {{current}} / {{total}} 页', {
                        current: page,
                        total: totalPages,
                      })}
                    </span>
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-6 px-2 text-xs"
                      disabled={page >= totalPages}
                      onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                    >
                      {t('tool.testPanel.pageNext', '下一页')}
                    </Button>
                  </div>
                ) : null}
              </div>
              <pre className="max-h-60 overflow-auto rounded-xl border border-border bg-slate-50 p-3 font-mono text-xs text-slate-800">
                {currentPageText || t('tool.testPanel.emptyResult', '(无返回内容)')}
              </pre>
            </div>
          ) : null}
        </DialogBody>

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            {t('tool.testPanel.close')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
