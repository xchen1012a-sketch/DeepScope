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

import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, CheckCircle2, Loader2, Wrench } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ConfirmDeleteDialog } from '@/components/ConfirmDeleteDialog';
import {
  deleteMcp,
  deleteTool,
  getMcpServerImpact,
  getToolImpact,
  type McpServerImpact,
  type ToolImpact,
} from '@/api/tool';
import { toolFailure } from '../toolFailure';

export interface ToolDeleteTarget {
  type: 'tool' | 'server';
  id: string;
  name: string;
  revision?: number;
}

export interface ToolDeleteDialogProps {
  open: boolean;
  target: ToolDeleteTarget | null;
  onClose: () => void;
  onDeleted: () => void;
}

export function ToolDeleteDialog({
  open,
  target,
  onClose,
  onDeleted,
}: ToolDeleteDialogProps) {
  const { t } = useTranslation();
  const [impact, setImpact] = useState<ToolImpact | McpServerImpact | null>(null);
  const [isLoadingImpact, setIsLoadingImpact] = useState(false);
  const [impactError, setImpactError] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !target) {
      setImpact(null);
      setIsLoadingImpact(false);
      setImpactError(null);
      setDeleteError(null);
      setIsDeleting(false);
      return;
    }

    let cancelled = false;
    setIsLoadingImpact(true);
    setImpact(null);
    setImpactError(null);
    setDeleteError(null);

    const fetchImpact = async () => {
      try {
        if (target.type === 'tool') {
          const res = await getToolImpact(target.id);
          if (!cancelled) {
            setImpact(res);
          }
        } else {
          const res = await getMcpServerImpact(target.id);
          if (!cancelled) {
            setImpact(res);
          }
        }
      } catch (err) {
        if (!cancelled) {
          setImpactError(toolFailure(err, t));
        }
      } finally {
        if (!cancelled) {
          setIsLoadingImpact(false);
        }
      }
    };

    fetchImpact();

    return () => {
      cancelled = true;
    };
  }, [open, target, t]);

  if (!target) return null;

  const isTool = target.type === 'tool';
  const title = isTool ? t('tool.deleteDialog.titleTool') : t('tool.deleteDialog.titleServer');
  const confirmDesc = isTool
    ? t('tool.deleteDialog.confirmTool', { name: target.name })
    : t('tool.deleteDialog.confirmServer', { name: target.name });

  const impactedAgents = impact?.impactedAgents ?? [];
  const serverImpact = !isTool && impact ? (impact as McpServerImpact) : null;
  const associatedTools = serverImpact?.tools ?? [];

  const handleDelete = async () => {
    setIsDeleting(true);
    setDeleteError(null);
    try {
      const expectedRev = target.revision ?? impact?.revision;
      if (isTool) {
        await deleteTool(target.id, expectedRev);
      } else {
        await deleteMcp(target.id, expectedRev);
      }
      onDeleted();
      onClose();
    } catch (err) {
      setDeleteError(toolFailure(err, t));
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <ConfirmDeleteDialog
      open={open}
      title={title}
      description={confirmDesc}
      confirmText={t('tool.deleteDialog.confirmButton')}
      cancelText={t('tool.deleteDialog.cancel')}
      confirmDisabled={isLoadingImpact}
      isDeleting={isDeleting}
      onClose={onClose}
      onConfirm={handleDelete}
    >
      <div className="space-y-3">
        {deleteError && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700">
            {deleteError}
          </div>
        )}

        {impactError && (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
            {impactError}
          </div>
        )}

        {isLoadingImpact && (
          <div className="flex items-center gap-2 py-4 text-xs text-slate-500">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('tool.deleteDialog.loadingImpact')}</span>
          </div>
        )}

        {!isLoadingImpact && impact && (
          <>
            {impactedAgents.length > 0 ? (
              <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50/70 p-3.5">
                <div className="flex items-center gap-2 text-xs font-medium text-amber-900">
                  <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600" />
                  <span>
                    {t('tool.deleteDialog.impactWarning', { count: impactedAgents.length })}
                  </span>
                </div>
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {impactedAgents.map((agent) => (
                    <span
                      key={agent.agentId}
                      className="inline-flex items-center rounded-md border border-amber-300 bg-white px-2 py-0.5 text-xs font-medium text-amber-900 shadow-xs"
                    >
                      {agent.name}
                    </span>
                  ))}
                </div>
              </div>
            ) : (
              <div className="flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50/70 p-3 text-xs text-emerald-800">
                <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" />
                <span>{t('tool.deleteDialog.noImpact')}</span>
              </div>
            )}

            {associatedTools.length > 0 && (
              <div className="space-y-2 rounded-xl border border-slate-200 bg-slate-50/70 p-3.5">
                <div className="flex items-center gap-2 text-xs font-medium text-slate-700">
                  <Wrench className="h-3.5 w-3.5 shrink-0 text-slate-500" />
                  <span>{t('tool.deleteDialog.associatedTools')}</span>
                </div>
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {associatedTools.map((st) => (
                    <Badge key={st.toolId} tone="default" className="text-xs">
                      {st.name}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </ConfirmDeleteDialog>
  );
}
