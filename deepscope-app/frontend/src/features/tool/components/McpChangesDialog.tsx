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
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, FileCode } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { AppSelect } from '@/components/ui/select';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { toolFailure } from '../toolFailure';
import { cn } from '@/lib/utils';
import type {
  ApproveMcpChangesInput,
  McpDiff,
  McpServer,
  ToolPermission,
} from '@/api/tool';

export interface McpChangesDialogProps {
  open: boolean;
  server: McpServer | null;
  diff: McpDiff | null;
  onClose: () => void;
  onApply: (serverId: string, input: ApproveMcpChangesInput) => Promise<void>;
  isApplying?: boolean;
}

type TabType = 'added' | 'changed' | 'removed';

function formatJson(raw: string | undefined): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function ExpandableToolDescription({
  description,
  className = 'mt-1.5 pl-7 text-[12.5px] text-slate-600',
  prefix,
}: {
  description?: string;
  className?: string;
  prefix?: React.ReactNode;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  if (!description) return null;

  return (
    <div className={className}>
      <p className={cn(!expanded && 'line-clamp-2', 'break-words')}>
        {prefix}
        {description}
      </p>
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className="mt-0.5 inline-flex items-center text-[11px] font-medium text-primary hover:underline"
      >
        {expanded ? t('tool.mcpChanges.collapse') : t('tool.mcpChanges.expand')}
      </button>
    </div>
  );
}

export function McpChangesDialog({
  open,
  server,
  diff,
  onClose,
  onApply,
  isApplying = false,
}: McpChangesDialogProps) {
  const { t } = useTranslation();

  const [activeTab, setActiveTab] = useState<TabType>('added');
  const [selectedAdded, setSelectedAdded] = useState<
    Record<string, { selected: boolean; permission: ToolPermission }>
  >({});
  const [selectedChanged, setSelectedChanged] = useState<Record<string, boolean>>({});
  const [selectedRemoved, setSelectedRemoved] = useState<Record<string, boolean>>({});
  const [expandedSchemas, setExpandedSchemas] = useState<Record<string, boolean>>({});
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const added = diff?.added ?? [];
  const changed = diff?.changed ?? [];
  const removed = diff?.removed ?? [];
  const hasChanges = added.length > 0 || changed.length > 0 || removed.length > 0;

  useEffect(() => {
    if (diff) {
      const nextAdded: Record<string, { selected: boolean; permission: ToolPermission }> = {};
      diff.added?.forEach((item) => {
        nextAdded[item.name] = { selected: true, permission: 'ask' };
      });
      setSelectedAdded(nextAdded);

      const nextChanged: Record<string, boolean> = {};
      diff.changed?.forEach((item) => {
        nextChanged[item.toolId] = true;
      });
      setSelectedChanged(nextChanged);

      const nextRemoved: Record<string, boolean> = {};
      diff.removed?.forEach((item) => {
        nextRemoved[item.toolId] = true;
      });
      setSelectedRemoved(nextRemoved);

      setErrorMessage(null);

      // Default active tab to first non-empty section
      if (diff.added?.length) {
        setActiveTab('added');
      } else if (diff.changed?.length) {
        setActiveTab('changed');
      } else if (diff.removed?.length) {
        setActiveTab('removed');
      } else {
        setActiveTab('added');
      }
    }
  }, [diff]);

  const toggleSchema = (key: string) => {
    setExpandedSchemas((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const handleApply = async () => {
    if (!server || !diff) return;
    setErrorMessage(null);

    const acceptedAdded = added
      .filter((tool) => selectedAdded[tool.name]?.selected)
      .map((tool) => ({
        name: tool.name,
        permission: selectedAdded[tool.name]?.permission ?? 'ask',
      }));

    const acceptedChanged = changed.map((tool) => ({
      toolId: tool.toolId,
      name: tool.name,
      apply: Boolean(selectedChanged[tool.toolId]),
    }));

    const acceptedRemoved = removed.map((tool) => ({
      toolId: tool.toolId,
      name: tool.name,
      confirm: Boolean(selectedRemoved[tool.toolId]),
    }));

    const payload: ApproveMcpChangesInput = {
      expectedFingerprint: diff.fingerprint,
      acceptedChanges: {
        added: acceptedAdded,
        changed: acceptedChanged,
        removed: acceptedRemoved,
      },
      tools: acceptedAdded,
    };

    try {
      await onApply(server.serverId, payload);
    } catch (err) {
      setErrorMessage(toolFailure(err, t));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent size="lg" className="flex max-h-[85vh] flex-col">
        <DialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            <DialogTitle>{t('tool.mcpChanges.title')}</DialogTitle>
            {server ? <Badge tone="default">{server.name}</Badge> : null}
          </div>
          <DialogDescription className="mt-1">
            {t('tool.mcpChanges.hint')}
          </DialogDescription>
          {diff?.fingerprint ? (
            <p className="mt-1 font-mono text-[11px] text-slate-400">
              {t('tool.mcpChanges.fingerprint', {
                fingerprint: diff.fingerprint.slice(0, 16) + '...',
              })}
            </p>
          ) : null}
        </DialogHeader>

        <DialogBody className="min-h-0 flex-1 space-y-4 overflow-y-auto">
          {errorMessage ? (
            <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              <AlertTriangle className="h-4 w-4 shrink-0 text-red-600" />
              <span>{errorMessage}</span>
            </div>
          ) : null}

          {!hasChanges ? (
            <div className="flex flex-col items-center justify-center py-10 text-center">
              <CheckCircle2 className="h-10 w-10 text-emerald-500" />
              <p className="mt-3 text-sm text-slate-600">
                {t('tool.mcpChanges.noChanges')}
              </p>
            </div>
          ) : (
            <>
              {/* Tab navigation */}
              <div className="flex border-b border-border" role="tablist">
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === 'added'}
                  onClick={() => setActiveTab('added')}
                  className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
                    activeTab === 'added'
                      ? 'border-primary text-primary'
                      : 'border-transparent text-slate-500 hover:text-slate-700'
                  }`}
                >
                  {t('tool.mcpChanges.tabAdded', { count: added.length })}
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === 'changed'}
                  onClick={() => setActiveTab('changed')}
                  className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
                    activeTab === 'changed'
                      ? 'border-primary text-primary'
                      : 'border-transparent text-slate-500 hover:text-slate-700'
                  }`}
                >
                  {t('tool.mcpChanges.tabChanged', { count: changed.length })}
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === 'removed'}
                  onClick={() => setActiveTab('removed')}
                  className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
                    activeTab === 'removed'
                      ? 'border-primary text-primary'
                      : 'border-transparent text-slate-500 hover:text-slate-700'
                  }`}
                >
                  {t('tool.mcpChanges.tabRemoved', { count: removed.length })}
                </button>
              </div>

              {/* Tab 1: Added Tools */}
              {activeTab === 'added' ? (
                <div className="space-y-3 pt-2">
                  {added.length === 0 ? (
                    <p className="py-6 text-center text-sm text-slate-400">
                      {t('tool.mcpChanges.noChanges')}
                    </p>
                  ) : (
                    added.map((tool) => {
                      const isSelected = selectedAdded[tool.name]?.selected ?? true;
                      const permission = selectedAdded[tool.name]?.permission ?? 'ask';
                      const schemaExpanded = expandedSchemas[`added:${tool.name}`];

                      return (
                        <div
                          key={tool.name}
                          className="rounded-xl border border-border bg-white p-3 shadow-xs"
                        >
                          <div className="flex flex-wrap items-center gap-3">
                            <input
                              type="checkbox"
                              id={`added-${tool.name}`}
                              checked={isSelected}
                              onChange={(e) =>
                                setSelectedAdded((prev) => ({
                                  ...prev,
                                  [tool.name]: {
                                    selected: e.target.checked,
                                    permission: prev[tool.name]?.permission ?? 'ask',
                                  },
                                }))
                              }
                              className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                            />
                            <label
                              htmlFor={`added-${tool.name}`}
                              className="font-mono text-sm font-semibold text-slate-800"
                            >
                              {tool.name}
                            </label>
                            <Badge tone="success" className="text-[11px]">
                              {t('tool.origin.mcp')}
                            </Badge>

                            <div className="ml-auto flex items-center gap-2">
                              <span className="text-[12px] text-slate-500">
                                {t('tool.permission')}
                              </span>
                              <AppSelect
                                size="sm"
                                aria-label={t('tool.permission')}
                                disabled={!isSelected}
                                value={permission}
                                onChange={(value) =>
                                  setSelectedAdded((prev) => ({
                                    ...prev,
                                    [tool.name]: {
                                      selected: prev[tool.name]?.selected ?? true,
                                      permission: value as ToolPermission,
                                    },
                                  }))
                                }
                                options={[
                                  { value: 'allow', label: t('tool.level.allow') },
                                  { value: 'ask', label: t('tool.level.ask') },
                                  { value: 'deny', label: t('tool.level.deny') },
                                ]}
                              />
                            </div>
                          </div>

                          <ExpandableToolDescription description={tool.description} />

                          {tool.inputSchema ? (
                            <div className="mt-2 pl-7">
                              <button
                                type="button"
                                onClick={() => toggleSchema(`added:${tool.name}`)}
                                className="inline-flex items-center gap-1 text-[11.5px] text-slate-500 hover:text-slate-800"
                              >
                                {schemaExpanded ? (
                                  <ChevronDown className="h-3.5 w-3.5" />
                                ) : (
                                  <ChevronRight className="h-3.5 w-3.5" />
                                )}
                                <FileCode className="h-3.5 w-3.5" />
                                <span>{t('tool.kind.schema')}</span>
                              </button>
                              {schemaExpanded ? (
                                <pre className="mt-1 max-h-40 overflow-auto rounded-lg bg-slate-50 p-2 font-mono text-[11px] text-slate-700">
                                  {formatJson(tool.inputSchema)}
                                </pre>
                              ) : null}
                            </div>
                          ) : null}
                        </div>
                      );
                    })
                  )}
                </div>
              ) : null}

              {/* Tab 2: Changed Tools */}
              {activeTab === 'changed' ? (
                <div className="space-y-3 pt-2">
                  {changed.length === 0 ? (
                    <p className="py-6 text-center text-sm text-slate-400">
                      {t('tool.mcpChanges.noChanges')}
                    </p>
                  ) : (
                    changed.map((tool) => {
                      const isApplied = selectedChanged[tool.toolId] ?? true;
                      const descChanged = tool.oldDescription !== tool.newDescription;
                      const schemaChanged = tool.oldInputSchema !== tool.newInputSchema;

                      return (
                        <div
                          key={tool.toolId}
                          className="rounded-xl border border-border bg-white p-3 shadow-xs"
                        >
                          <div className="flex flex-wrap items-center gap-3">
                            <input
                              type="checkbox"
                              id={`changed-${tool.toolId}`}
                              checked={isApplied}
                              onChange={(e) =>
                                setSelectedChanged((prev) => ({
                                  ...prev,
                                  [tool.toolId]: e.target.checked,
                                }))
                              }
                              className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                            />
                            <label
                              htmlFor={`changed-${tool.toolId}`}
                              className="font-mono text-sm font-semibold text-slate-800"
                            >
                              {tool.name}
                            </label>
                            <span className="text-xs text-slate-500">
                              {t('tool.mcpChanges.applyChange')}
                            </span>
                          </div>

                          <div className="mt-2 space-y-2 pl-7 text-[12.5px]">
                            {descChanged ? (
                              <div className="rounded-lg border border-slate-100 bg-slate-50 p-2">
                                <ExpandableToolDescription
                                  className="text-[12.5px] text-slate-500"
                                  prefix={
                                    <span className="font-medium text-slate-600">
                                      {t('tool.mcpChanges.oldDesc')}
                                    </span>
                                  }
                                  description={tool.oldDescription || '—'}
                                />
                                <ExpandableToolDescription
                                  className="mt-1 text-[12.5px] text-slate-800"
                                  prefix={
                                    <span className="font-medium text-primary">
                                      {t('tool.mcpChanges.newDesc')}
                                    </span>
                                  }
                                  description={tool.newDescription || '—'}
                                />
                              </div>
                            ) : (
                              <ExpandableToolDescription
                                className="text-slate-500"
                                description={tool.newDescription}
                              />
                            )}

                            {schemaChanged ? (
                              <div className="space-y-1">
                                <button
                                  type="button"
                                  onClick={() => toggleSchema(`changed:${tool.toolId}`)}
                                  className="inline-flex items-center gap-1 text-[11.5px] text-slate-500 hover:text-slate-800"
                                >
                                  {expandedSchemas[`changed:${tool.toolId}`] ? (
                                    <ChevronDown className="h-3.5 w-3.5" />
                                  ) : (
                                    <ChevronRight className="h-3.5 w-3.5" />
                                  )}
                                  <FileCode className="h-3.5 w-3.5" />
                                  <span>{t('tool.kind.schema')}</span>
                                </button>
                                {expandedSchemas[`changed:${tool.toolId}`] ? (
                                  <div className="grid grid-cols-1 gap-2 pt-1 sm:grid-cols-2">
                                    <div>
                                      <p className="mb-1 text-[11px] font-medium text-slate-500">
                                        {t('tool.mcpChanges.oldSchema')}
                                      </p>
                                      <pre className="max-h-40 overflow-auto rounded-lg bg-slate-50 p-2 font-mono text-[11px] text-slate-700">
                                        {formatJson(tool.oldInputSchema)}
                                      </pre>
                                    </div>
                                    <div>
                                      <p className="mb-1 text-[11px] font-medium text-primary">
                                        {t('tool.mcpChanges.newSchema')}
                                      </p>
                                      <pre className="max-h-40 overflow-auto rounded-lg bg-emerald-50/50 p-2 font-mono text-[11px] text-slate-800">
                                        {formatJson(tool.newInputSchema)}
                                      </pre>
                                    </div>
                                  </div>
                                ) : null}
                              </div>
                            ) : null}
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              ) : null}

              {/* Tab 3: Removed Tools */}
              {activeTab === 'removed' ? (
                <div className="space-y-3 pt-2">
                  {removed.length === 0 ? (
                    <p className="py-6 text-center text-sm text-slate-400">
                      {t('tool.mcpChanges.noChanges')}
                    </p>
                  ) : (
                    removed.map((tool) => {
                      const isConfirmed = selectedRemoved[tool.toolId] ?? true;
                      const hasImpact = tool.impactedAgentCount > 0;

                      return (
                        <div
                          key={tool.toolId}
                          className={`rounded-xl border p-3 shadow-xs ${
                            hasImpact
                              ? 'border-amber-200 bg-amber-50/30'
                              : 'border-border bg-white'
                          }`}
                        >
                          <div className="flex flex-wrap items-center gap-3">
                            <input
                              type="checkbox"
                              id={`removed-${tool.toolId}`}
                              checked={isConfirmed}
                              onChange={(e) =>
                                setSelectedRemoved((prev) => ({
                                  ...prev,
                                  [tool.toolId]: e.target.checked,
                                }))
                              }
                              className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                            />
                            <label
                              htmlFor={`removed-${tool.toolId}`}
                              className="font-mono text-sm font-semibold text-slate-800"
                            >
                              {tool.name}
                            </label>
                            <span className="text-xs text-slate-500">
                              {t('tool.mcpChanges.confirmRemove')}
                            </span>

                            <div className="ml-auto">
                              {hasImpact ? (
                                <span className="inline-flex items-center gap-1 rounded-md bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                                  <AlertTriangle className="h-3 w-3" />
                                  {t('tool.mcpChanges.impactedAgents', {
                                    count: tool.impactedAgentCount,
                                  })}
                                </span>
                              ) : (
                                <span className="text-xs text-slate-400">
                                  {t('tool.mcpChanges.noImpact')}
                                </span>
                              )}
                            </div>
                          </div>

                          <ExpandableToolDescription
                            className="mt-1.5 pl-7 text-[12.5px] text-slate-500"
                            description={tool.description}
                          />
                        </div>
                      );
                    })
                  )}
                </div>
              ) : null}
            </>
          )}
        </DialogBody>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isApplying}>
            {t('chat.cancel')}
          </Button>
          <Button
            disabled={!hasChanges || isApplying}
            onClick={handleApply}
          >
            {isApplying ? t('shell.loading') : t('tool.mcpChanges.apply')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
