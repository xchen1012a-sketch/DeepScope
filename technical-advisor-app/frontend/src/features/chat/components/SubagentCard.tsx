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

import { Bot } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { formatRunDuration, subagentDurationMs } from '../conversationModel';
import type { SubagentRecord } from '../useAdvisorConversation';
import { ProcessCardShell, StatusChip } from './ProcessCardShell';
import { useNowTicker } from './useNowTicker';
import { describeTool } from '../processPresentation';

/**
 * 子智能体卡片：外壳与时间线卡片一致，内容只有四样——名称、任务一行摘要、状态、耗时。
 * 进行中展示当前步骤；内部任务号不进入用户界面。
 */
export function SubagentCard({
  record,
  onOpen,
}: {
  record: SubagentRecord;
  onOpen: (id: string) => void;
}) {
  const { t } = useTranslation();
  const now = useNowTicker(record.status === 'running');
  const duration = formatRunDuration(subagentDurationMs(record, now));
  const current = record.status === 'running' ? record.steps[record.steps.length - 1] : undefined;
  const activity = current?.kind === 'thinking' ? t('chat.activity.analyzing')
    : current?.tool ? t(describeTool(current.tool).titleKey) : '';
  return (
    <ProcessCardShell
      icon={Bot}
      tone="text-indigo-600"
      title={record.name}
      status={<StatusChip status={record.status} />}
      right={duration ?? undefined}
      summary={activity ? `${record.task} · ${activity}` : record.task}
      onClick={() => onOpen(record.id)}
    />
  );
}
