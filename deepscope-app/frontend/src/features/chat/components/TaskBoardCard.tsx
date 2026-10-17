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
import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock,
  FastForward,
  Loader2,
  Lock,
  RotateCcw,
  Sparkles,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

export type TaskItemStatus =
  | 'PENDING'
  | 'BLOCKED'
  | 'READY'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export interface TaskBoardItem {
  taskId: string;
  title: string;
  description: string;
  assigneeRole: string;
  blockedBy: string[];
  status: TaskItemStatus;
  resultSummary?: string | null;
  createdAt?: number;
  updatedAt?: number;
  completedAt?: number;
}

export interface TaskBoardCardProps {
  sessionId?: string;
  tasks: TaskBoardItem[];
  maxTasks?: number;
  availableSlots?: number;
  defaultExpanded?: boolean;
  onRetry?: (taskId: string) => void;
  onSkip?: (taskId: string) => void;
}

export function TaskBoardCard({
  tasks,
  maxTasks = 8,
  availableSlots = 3,
  defaultExpanded = true,
  onRetry,
  onSkip,
}: TaskBoardCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const [expandedDeliverables, setExpandedDeliverables] = useState<Set<string>>(new Set());

  const toggleDeliverable = (taskId: string) => {
    setExpandedDeliverables((prev) => {
      const next = new Set(prev);
      if (next.has(taskId)) {
        next.delete(taskId);
      } else {
        next.add(taskId);
      }
      return next;
    });
  };

  const total = tasks.length;
  const succeeded = tasks.filter((t) => t.status === 'SUCCEEDED').length;
  const running = tasks.filter((t) => t.status === 'RUNNING').length;
  const ready = tasks.filter((t) => t.status === 'READY').length;
  const blocked = tasks.filter((t) => t.status === 'BLOCKED' || t.status === 'PENDING').length;
  const failed = tasks.filter((t) => t.status === 'FAILED').length;
  const cancelled = tasks.filter((t) => t.status === 'CANCELLED').length;

  const percent = total > 0 ? Math.round((succeeded / total) * 100) : 0;

  return (
    <div
      data-testid="task-board-card"
      className="my-3 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm transition-all dark:border-slate-800 dark:bg-slate-900"
    >
      {/* 头部摘要栏 */}
      <div
        className="flex cursor-pointer items-center justify-between border-b border-slate-100 bg-slate-50/75 px-4 py-3 hover:bg-slate-100/75 dark:border-slate-800/60 dark:bg-slate-800/40"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2.5">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-100 text-indigo-600 dark:bg-indigo-950/60 dark:text-indigo-400">
            <Sparkles className="h-4 w-4" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                多 Agent 协同任务看板
              </span>
              <span className="rounded-full bg-slate-200/80 px-2 py-0.5 text-[11px] font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-400">
                {succeeded}/{total} 已办结 ({percent}%)
              </span>
              {running > 0 && (
                <span className="flex items-center gap-1 rounded-full bg-indigo-50 px-2 py-0.5 text-[11px] font-medium text-indigo-600 dark:bg-indigo-950/60 dark:text-indigo-400">
                  <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-indigo-500" />
                  {running} 运行中
                </span>
              )}
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right text-xs text-slate-500">
            <span>
              槽位余量: {availableSlots} · 上限: {total}/{maxTasks}
            </span>
          </div>
          <button
            type="button"
            className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
            aria-label={expanded ? '折叠看板' : '展开看板'}
          >
            {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </button>
        </div>
      </div>

      {/* 进度条指示器 */}
      <div className="flex h-1.5 w-full bg-slate-100 dark:bg-slate-800">
        <div
          style={{ width: `${(succeeded / (total || 1)) * 100}%` }}
          className="bg-emerald-500 transition-all duration-300"
        />
        <div
          style={{ width: `${(running / (total || 1)) * 100}%` }}
          className="bg-indigo-500 transition-all duration-300"
        />
        <div
          style={{ width: `${(ready / (total || 1)) * 100}%` }}
          className="bg-amber-400 transition-all duration-300"
        />
        <div
          style={{ width: `${(failed / (total || 1)) * 100}%` }}
          className="bg-rose-500 transition-all duration-300"
        />
        <div
          style={{ width: `${(cancelled / (total || 1)) * 100}%` }}
          className="bg-zinc-300 transition-all duration-300"
        />
      </div>

      {/* 展开任务明细列表 */}
      {expanded && (
        <div className="divide-y divide-slate-100 px-4 py-2 dark:divide-slate-800">
          {tasks.length === 0 ? (
            <div className="py-4 text-center text-xs text-slate-400">
              当前暂无拆解任务单
            </div>
          ) : (
            tasks.map((task) => {
              const hasDeliverable = Boolean(task.resultSummary?.trim());
              const isDeliverableOpen = expandedDeliverables.has(task.taskId);

              return (
                <div key={task.taskId} className="py-2.5">
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex min-w-0 items-center gap-2">
                      <StatusBadge status={task.status} />
                      <span className="truncate text-xs font-semibold text-slate-800 dark:text-slate-200">
                        {task.title}
                      </span>
                      <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500 dark:bg-slate-800">
                        {task.assigneeRole}
                      </span>
                      {task.blockedBy.length > 0 && (
                        <span className="flex items-center gap-1 text-[11px] text-slate-400">
                          <Lock className="h-3 w-3" />
                          依赖: {task.blockedBy.join(', ')}
                        </span>
                      )}
                    </div>

                    <div className="flex items-center gap-2">
                      {task.status === 'FAILED' && (
                        <div className="flex items-center gap-1">
                          {onRetry && (
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-6 gap-1 px-2 text-[11px] text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40"
                              onClick={() => onRetry(task.taskId)}
                            >
                              <RotateCcw className="h-3 w-3" />
                              重试
                            </Button>
                          )}
                          {onSkip && (
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-6 gap-1 px-2 text-[11px] text-slate-500 hover:text-slate-700"
                              onClick={() => onSkip(task.taskId)}
                            >
                              <FastForward className="h-3 w-3" />
                              跳过
                            </Button>
                          )}
                        </div>
                      )}

                      {hasDeliverable && (
                        <button
                          type="button"
                          className="flex items-center gap-1 text-[11px] text-indigo-600 hover:underline dark:text-indigo-400"
                          onClick={() => toggleDeliverable(task.taskId)}
                        >
                          交付成果
                          {isDeliverableOpen ? (
                            <ChevronDown className="h-3 w-3" />
                          ) : (
                            <ChevronRight className="h-3 w-3" />
                          )}
                        </button>
                      )}
                    </div>
                  </div>

                  {/* 展开的精炼交付物 */}
                  {hasDeliverable && isDeliverableOpen && (
                    <div className="mt-2 rounded-lg bg-slate-50 p-2.5 text-xs text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
                      <div className="whitespace-pre-wrap leading-relaxed">
                        {task.resultSummary}
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: TaskItemStatus }) {
  switch (status) {
    case 'SUCCEEDED':
      return (
        <span className="flex items-center gap-1 rounded-md bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-400">
          <CheckCircle2 className="h-3 w-3" />
          已完成
        </span>
      );
    case 'RUNNING':
      return (
        <span className="flex items-center gap-1 rounded-md bg-indigo-50 px-1.5 py-0.5 text-[10px] font-medium text-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-400">
          <Loader2 className="h-3 w-3 animate-spin" />
          执行中
        </span>
      );
    case 'READY':
      return (
        <span className="flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 dark:bg-amber-950/50 dark:text-amber-400">
          <Clock className="h-3 w-3" />
          就绪待命
        </span>
      );
    case 'BLOCKED':
    case 'PENDING':
      return (
        <span className="flex items-center gap-1 rounded-md bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-400">
          <Lock className="h-3 w-3" />
          等待依赖
        </span>
      );
    case 'FAILED':
      return (
        <span className="flex items-center gap-1 rounded-md bg-rose-50 px-1.5 py-0.5 text-[10px] font-medium text-rose-700 dark:bg-rose-950/50 dark:text-rose-400">
          <AlertTriangle className="h-3 w-3" />
          执行失败
        </span>
      );
    case 'CANCELLED':
      return (
        <span className="flex items-center gap-1 rounded-md bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400">
          <Ban className="h-3 w-3" />
          已取消
        </span>
      );
  }
}
