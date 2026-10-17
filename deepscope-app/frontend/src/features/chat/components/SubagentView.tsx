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

import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Bot, ChevronDown } from 'lucide-react';
import type { FactItem } from '@/api/advisor';
import { cn } from '@/lib/utils';
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
 * 子智能体自己的视图：完整内聚在单一可折叠卡片内，符合 Claude Code 大厂规范。
 * 点击卡片头部可折叠/展开；所有思考、工具调用以及文字回复交付物均严格封存在卡片容器内。
 */
export function SubagentView({ record, onBack }: { record: SubagentRecord; onBack: () => void }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);
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
    return (
      <div className="mt-2.5 rounded-lg border border-slate-100 bg-slate-50/60 p-3 text-[13px] leading-relaxed text-slate-700">
        <Markdown text={block.text ?? ''} internalIds={internalIds} />
      </div>
    );
  };
  return (
    <div className="space-y-3">
      <button type="button" onClick={onBack} className="text-[12.5px] text-slate-500 hover:text-slate-800">
        {t('chat.flow.back')}
      </button>
      <div className="rounded-xl border border-border/80 bg-white shadow-xs overflow-hidden">
        <button
          type="button"
          onClick={() => setExpanded(prev => !prev)}
          className="w-full px-3.5 py-3 text-left transition-colors hover:bg-slate-50/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
          aria-expanded={expanded}
        >
          <div className="flex items-center gap-2">
            <Bot className="h-3.5 w-3.5 shrink-0 text-indigo-600" />
            <span className="min-w-0 truncate text-[13px] font-medium text-slate-800">{record.name}</span>
            <StatusChip status={record.status} />
            {duration ? <span className="ml-auto shrink-0 font-mono text-[11px] text-slate-400">{duration}</span> : null}
            <ChevronDown className={cn("h-4 w-4 shrink-0 text-slate-400 transition-transform duration-200", expanded ? "rotate-180" : "")} />
          </div>
          {record.task ? <p className="mt-1.5 text-[12.5px] leading-relaxed text-slate-500 line-clamp-2">{record.task}</p> : null}
        </button>

        {expanded && (
          <div className="border-t border-border/60 px-3.5 py-3 space-y-3 bg-white">
            <dl className="flex flex-wrap gap-x-4 gap-y-1 text-[11.5px] text-slate-400">
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
            {record.facts.length > 0 ? <FactList facts={record.facts} /> : null}
            {record.status === 'running' && turn.process.length === 0 ? (
              <div className="space-y-2 py-1">
                <ThinkingLine text={record.task ? `正在执行与深度分析: ${record.task}...` : t('chat.activity.analyzing')} streaming={true} />
              </div>
            ) : turn.process.length === 0 && !turn.finalText ? (
              <p className="py-2 text-[12.5px] leading-relaxed text-slate-400">{t('chat.process.noSteps')}</p>
            ) : (
              <div className="space-y-2">
                <TurnGroup turn={turn} forcedOpen highlight={null} renderBlock={renderBlock} />
                {record.status === 'running' && record.steps[record.steps.length - 1]?.kind !== 'thinking' ? (
                  <ThinkingLine text={record.task ? `正在执行与深度分析: ${record.task}...` : t('chat.activity.analyzing')} streaming={true} />
                ) : null}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
