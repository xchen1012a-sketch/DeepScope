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

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Bot } from 'lucide-react';
import type { FactItem } from '@/api/advisor';
import { formatClockTime, formatRunDuration, subagentDurationMs } from '../conversationModel';
import { buildSubagentTurn, type ConversationBlock } from '../model/blocks';
import type { SubagentRecord } from '../useAdvisorConversation';
import { StatusChip } from './ProcessCardShell';
import { ThinkingLine } from './ThinkingLine';
import { ToolLine } from './ToolLine';
import { TurnGroup } from './TurnGroup';
import { useNowTicker } from './useNowTicker';
import { Markdown } from './Markdown';

function FactList({ facts }: { facts: FactItem[] }) {
  const { t } = useTranslation();
  const internalIds = facts.map(fact => fact.id);
  return (
    <ul className="space-y-1.5">
      {facts.map((fact, index) => (
        <li key={fact.id || `${fact.source}:${index}`} className="rounded-lg border border-border bg-white px-2.5 py-2 text-[12.5px] leading-relaxed">
          <Markdown text={fact.fact} internalIds={internalIds} className="text-slate-700" />
          <div className="mt-1 break-all font-mono text-[11px] text-slate-400">
            {t('chat.source')}：{fact.source}
          </div>
          {fact.conclusion ? (
            <div className="mt-1 text-[12px] text-slate-500">
              <span>{t('chat.conclusion')}：</span><Markdown text={fact.conclusion} internalIds={internalIds} />
            </div>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

/**
 * 子智能体自己的视图：与主对话同一套思考行、工具行和紧凑回合，顶部可返回列表。
 * 一次性任务只读——这里没有输入区；进行中的思考与工具随父运行转发的子事件即时更新，
 * 刷新后按落下来的记录回放任务、事实、总结、起止时间与耗时。
 */
export function SubagentView({ record, onBack }: { record: SubagentRecord; onBack: () => void }) {
  const { t } = useTranslation();
  const now = useNowTicker(record.status === 'running');
  const duration = formatRunDuration(subagentDurationMs(record, now));
  const internalIds = record.facts.map(fact => fact.id);
  const turn = useMemo(
    () => buildSubagentTurn({ steps: record.steps, summary: record.summary }),
    [record.steps, record.summary],
  );
  const renderBlock = (block: ConversationBlock) => {
    if (block.kind === 'thinking') {
      const isCurrentThinking = record.steps[record.steps.length - 1]?.kind === 'thinking' && block === turn.process[turn.process.length - 1];
      return <ThinkingLine text={block.thinkingText ?? ''} streaming={record.status === 'running' && isCurrentThinking} />;
    }
    if (block.kind === 'tool_call') return <ToolLine block={block} />;
    return <Markdown text={block.text ?? ''} internalIds={internalIds} className="text-[13.5px] leading-relaxed text-slate-700" />;
  };
  return (
    <div className="space-y-3">
      <button type="button" onClick={onBack} className="text-[12.5px] text-slate-500 hover:text-slate-800">
        {t('chat.flow.back')}
      </button>
      <div className="rounded-xl border border-border bg-white px-3.5 py-3">
        <div className="flex items-center gap-2">
          <Bot className="h-3.5 w-3.5 shrink-0 text-indigo-600" />
          <span className="min-w-0 truncate text-[13px] font-medium text-slate-800">{record.name}</span>
          <StatusChip status={record.status} />
          {duration ? <span className="ml-auto shrink-0 font-mono text-[11px] text-slate-400">{duration}</span> : null}
        </div>
        {record.task ? <p className="mt-1.5 text-[12.5px] leading-relaxed text-slate-500">{record.task}</p> : null}
        <dl className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11.5px] text-slate-400">
          <div className="flex gap-1">
            <dt>{t('chat.subagent.started')}</dt>
            <dd className="font-mono">{record.startedAt !== undefined ? formatClockTime(record.startedAt) : '—'}</dd>
          </div>
          <div className="flex gap-1">
            <dt>{t('chat.subagent.ended')}</dt>
            <dd className="font-mono">{record.endedAt !== undefined ? formatClockTime(record.endedAt) : '—'}</dd>
          </div>
          {duration ? (
            <div className="flex gap-1">
              <dt>{t('chat.subagent.duration')}</dt>
              <dd className="font-mono">{duration}</dd>
            </div>
          ) : null}
        </dl>
      </div>
      {record.facts.length > 0 ? <FactList facts={record.facts} /> : null}
      {turn.process.length === 0 && !turn.finalText ? (
        <p className="text-[12.5px] leading-relaxed text-slate-400">{t('chat.process.noSteps')}</p>
      ) : (
        <TurnGroup turn={turn} forcedOpen highlight={null} renderBlock={renderBlock} />
      )}
    </div>
  );
}
