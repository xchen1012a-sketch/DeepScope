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
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { ConversationBlock } from '../model/blocks';
import { describeTool } from '../processPresentation';
import { formatRunDuration } from '../conversationModel';
import { ProcessText } from './ProcessText';

/** Results first; raw inputs never serve as a fallback for missing output. */
export function ProcessDetails({ block }: { block: ConversationBlock }) {
  const { t } = useTranslation();
  const [technical, setTechnical] = useState(false);
  if (block.kind === 'thinking') return <div className="space-y-3">
    <h3 className="text-sm font-medium text-slate-800">{t('chat.activity.analysisRecord')}</h3>
    <ProcessText text={block.thinkingText ?? ''} contained={false} />
  </div>;
  const tool = block.tool;
  if (!tool) return <ProcessText text={block.summary} contained={false} />;
  const description = describeTool(tool);
  return <div className="space-y-4">
    <h3 className="text-sm font-medium text-slate-800">{t(description.titleKey)}{description.target ? ` · ${description.target}` : ''}</h3>
    <section className="space-y-2" aria-label={t('chat.flow.result')}>
      <p className={tool.status === 'failed' || description.hasFailure ? 'text-sm text-red-700' : 'text-sm text-slate-600'}>{t(description.resultKey, description.resultValues)}</p>
      {description.content ? <ProcessText text={description.content} contained={false} /> : null}
    </section>
    <button type="button" aria-expanded={technical} onClick={() => setTechnical(value => !value)}
      className="flex min-h-9 items-center gap-1 text-xs text-slate-500 hover:text-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 rounded">
      {technical ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}{t('chat.activity.technical')}
    </button>
    {technical ? <div className="space-y-3 border-t border-slate-100 pt-3 text-xs text-slate-500">
      <p>{t('chat.activity.toolName')}: <span className="break-all font-mono">{tool.name}</span></p>
      {formatRunDuration(tool.durationMs ?? null) ? <p>{t('chat.activity.duration')}: {formatRunDuration(tool.durationMs ?? null)}</p> : null}
      {tool.args ? <section className="space-y-1"><h4>{t('chat.flow.args')}</h4><ProcessText text={tool.args} code contained={false} /></section> : null}
      {tool.result ? <section className="space-y-1"><h4>{t('chat.activity.rawResult')}</h4><ProcessText text={tool.result} code contained={false} /></section> : null}
    </div> : null}
  </div>;
}
