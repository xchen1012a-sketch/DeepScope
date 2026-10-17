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

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle2, FileText, Sparkles, XCircle } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { payloadText } from '@/api/advisor';
import type { ConversationEvent, ProposalResolution } from '../useAdvisorConversation';

/* ------------------------------------------------------- 提案卡片（proposal → 挂起门） */

/**
 * F08 动态创建子智能体提案：名称 / 职责说明 / 目标 + 可展开的定义草稿全文
 * （批准前可编辑）；批准 / 拒绝调 resolve。已裁决的提案只读展示状态。
 */
export function ProposalCard({
  event,
  resolution,
  disabled,
  disabledReason,
  resolving,
  onResolve,
}: {
  event: ConversationEvent;
  resolution?: ProposalResolution;
  disabled?: boolean;
  disabledReason?: string;
  resolving?: boolean;
  onResolve?: (approved: boolean, content?: string) => void;
}) {
  const { t } = useTranslation();
  const { payload } = event;
  const [draftOpen, setDraftOpen] = useState(false);
  const [draft, setDraft] = useState(() => payloadText(payload, 'content'));

  useEffect(() => {
    setDraft(payloadText(payload, 'content'));
  }, [payload]);

  const pending = !resolution;
  const locked = pending && (disabled || !onResolve);
  const rows: Array<[string, string]> = [
    [t('chat.proposal.name'), payloadText(payload, 'name')],
    [t('chat.proposal.description'), payloadText(payload, 'description')],
    [t('chat.proposal.goal'), payloadText(payload, 'goal')],
  ];

  return (
    <div
      className={cn(
        'rounded-2xl border-2 bg-white px-4 py-3.5',
        pending ? 'border-violet-300 shadow-sm' : 'border-border',
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Sparkles className="h-4 w-4 text-violet-600" />
        <span className="text-[14px] font-semibold text-slate-900">{t('chat.proposal.title')}</span>
        {pending ? (
          <Badge tone="warning" className="text-[11.5px]">{t('chat.proposal.pending')}</Badge>
        ) : (
          <Badge tone={resolution?.approved ? 'success' : 'danger'} className="text-[11.5px]">
            {resolution?.approved ? t('chat.proposal.resolved.approved') : t('chat.proposal.resolved.rejected')}
          </Badge>
        )}
      </div>

      <p className="mt-2 text-[13px] leading-relaxed text-slate-500">{t('chat.proposal.subtitle')}</p>

      <dl className="mt-3 space-y-1.5 rounded-xl bg-muted/60 px-3.5 py-3 text-[12.5px] leading-relaxed">
        {rows.map(([label, value]) => (
          <div key={label} className="flex gap-2">
            <dt className="w-[86px] shrink-0 text-slate-400">{label}</dt>
            <dd className="text-slate-600">{value}</dd>
          </div>
        ))}
      </dl>

      <div className="mt-2.5">
        <button
          type="button"
          onClick={() => setDraftOpen((open) => !open)}
          className="text-[12px] font-medium text-violet-700 transition-colors hover:text-violet-800"
        >
          {draftOpen ? t('chat.proposal.collapse') : t('chat.proposal.expand')}
        </button>
        {draftOpen ? (
          <div className="mt-2">
            <div className="mb-1.5 text-[11.5px] text-slate-400">
              {pending && !locked ? t('chat.proposal.draftHint') : t('chat.proposal.draft')}
            </div>
            {pending && !locked ? (
              <Textarea
                value={draft}
                onChange={(changeEvent) => setDraft(changeEvent.target.value)}
                className="min-h-[220px] font-mono text-[12.5px] leading-relaxed"
              />
            ) : (
              <pre className="max-h-[320px] overflow-auto whitespace-pre-wrap break-words rounded-xl border border-border bg-muted/40 px-3.5 py-3 font-mono text-[12px] leading-relaxed text-slate-600">
                {draft}
              </pre>
            )}
          </div>
        ) : null}
      </div>

      {pending ? (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            disabled={locked || resolving}
            onClick={() => onResolve?.(true, draft)}
          >
            <CheckCircle2 className="h-4 w-4" />
            {resolving ? t('chat.proposal.resolving') : t('chat.proposal.approve')}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!onResolve || resolving}
            onClick={() => onResolve?.(false)}
          >
            <XCircle className="h-4 w-4" />
            {t('chat.proposal.reject')}
          </Button>
          {locked && disabledReason ? (
            <span className="text-[12px] leading-relaxed text-amber-700">{disabledReason}</span>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/* ------------------------------------------------------------ 提案裁决卡片（proposal_resolved） */

/** 提案裁决结果：批准显示落盘文件，拒绝说明已退回通用能力。 */
export function ProposalResolvedCard({ payload }: { payload: Record<string, unknown> }) {
  const { t } = useTranslation();
  const approved = payload.approved === true || payload.approved === 'true';
  const file = payloadText(payload, 'file');
  return (
    <div className="rounded-xl border bg-white px-3.5 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        {approved ? (
          <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-600" />
        ) : (
          <XCircle className="h-3.5 w-3.5 shrink-0 text-red-500" />
        )}
        <span className="text-[12.5px] font-medium text-slate-700">{t('chat.proposal.resolved.title')}</span>
        <Badge tone={approved ? 'success' : 'danger'} className="text-[11.5px]">
          {approved ? t('chat.proposal.resolved.approved') : t('chat.proposal.resolved.rejected')}
        </Badge>
        <span className="ml-auto truncate text-[12px] text-slate-400">{payloadText(payload, 'name')}</span>
      </div>
      {approved ? (
        <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[12px] text-slate-500">
          <FileText className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          {t('chat.proposal.resolved.file')}：
          <code className="break-all font-mono text-[11.5px] text-slate-600">{file}</code>
        </div>
      ) : (
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-slate-500">
          {t('chat.proposal.resolved.rejectedNote')}
        </p>
      )}
    </div>
  );
}
