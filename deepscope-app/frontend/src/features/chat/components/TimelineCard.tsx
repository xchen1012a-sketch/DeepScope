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
import { MessageSquare } from 'lucide-react';
import { formatClockTime } from '../conversationModel';
import type { TimelineCardView } from '../model/blocks';
import { KIND_ICON } from './kindIcons';
import { ProcessCardShell, StatusChip } from './ProcessCardShell';

const KIND_TONE: Record<string, string> = {
  user_message: 'text-slate-400',
  thinking: 'text-slate-400',
  advisor_message: 'text-slate-500',
  tool_call: 'text-emerald-600',
  load_skill: 'text-violet-600',
  dispatch: 'text-indigo-600',
  self_task: 'text-slate-500',
  subagent_settled: 'text-emerald-600',
  advisor_note: 'text-slate-400',
  question: 'text-amber-600',
  proposal: 'text-violet-600',
  proposal_resolved: 'text-emerald-600',
  error: 'text-red-600',
};

/**
 * 时间线卡片：一个可见块一张，左图标 + 标题 + 一行摘要 + 右侧时间；
 * 进行中只由卡片自己的状态表示，卡片上不写单独的「思考中」「调查中」状态句，
 * 也不放令牌与缓存数字。点卡片带着锚点回到主对话对应位置。
 */
export function TimelineCard({
  card,
  onLocate,
}: {
  card: TimelineCardView;
  onLocate: (anchor: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <ProcessCardShell
      icon={KIND_ICON[card.kind] ?? MessageSquare}
      tone={KIND_TONE[card.kind] ?? 'text-slate-400'}
      title={t(`chat.timelineKind.${card.kind}`)}
      status={card.status === 'done' ? undefined : <StatusChip status={card.status} />}
      right={formatClockTime(card.createdAt)}
      summary={card.summary}
      onClick={() => onLocate(card.anchor)}
    />
  );
}
