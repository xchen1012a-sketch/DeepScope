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

import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Square } from 'lucide-react';
import type { PendingQuestion, QuestionAnswer } from '@/api/advisor';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { questionTitle } from '../model/questions';

export interface QuestionCardProps {
  question: PendingQuestion;
  busy: boolean;
  onAnswer: (answer: QuestionAnswer) => Promise<void>;
  error?: string;
  onStop?: () => void;
}

function isCustomOption(label: string): boolean {
  return /^(?:others?|其他|其它)(?:\s*\(\s*(?:please\s+(?:specify|describe)|specify|custom answer|请说明|请填写|请输入|请补充|自定义|自行填写)\s*\))?[.:：。…]*$/iu
    .test(label.normalize('NFKC').trim());
}

export function QuestionCard({ question, busy, onAnswer, error, onStop }: QuestionCardProps) {
  const { i18n } = useTranslation();
  const language = /\p{Script=Han}/u.test(`${question.title ?? ''}\n${question.text}`) ? 'zh-CN' : i18n.resolvedLanguage ?? i18n.language;
  const t = i18n.getFixedT(language, 'app');
  const id = useId();
  const [collapsed, setCollapsed] = useState(false);
  const [selectedOptionIds, setSelectedOptionIds] = useState<string[]>([]);
  const [customText, setCustomText] = useState('');
  const [customOpen, setCustomOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);
  const inFlight = useRef(false);
  const answerRef = useRef<HTMLTextAreaElement>(null);
  // Historical/model-generated Other options use the same free-text path as the system entry.
  const options = question.options.filter(option => !isCustomOption(option.label));
  const allowCustom = question.allowCustom || options.length !== question.options.length;
  const textOnly = question.answerMode === 'text' || options.length === 0;
  const multiple = question.answerMode === 'multiple';
  const showText = textOnly || customOpen;
  const locked = busy || submitting;
  const message = error || (failed ? t('questionCard.submitError') : '');
  const answer: QuestionAnswer = {
    selectedOptionIds: textOnly ? [] : selectedOptionIds,
    customText: showText ? customText.trim() : '',
  };
  const valid = (answer.selectedOptionIds.length > 0 || answer.customText.length > 0)
    && (!customOpen || answer.customText.length > 0);
  const title = questionTitle(question);
  const explanation = question.text.trim();

  useEffect(() => {
    if (customOpen && !collapsed && !locked) answerRef.current?.focus();
  }, [customOpen, collapsed, locked]);

  async function submit(value: QuestionAnswer): Promise<void> {
    if (busy || inFlight.current || (!value.selectedOptionIds.length && !value.customText)) return;
    inFlight.current = true;
    setSubmitting(true);
    setFailed(false);
    try {
      await onAnswer(value);
    } catch {
      setFailed(true);
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function selectOption(optionId: string): void {
    if (locked || inFlight.current) return;
    setFailed(false);
    if (multiple) {
      setSelectedOptionIds(previous => options
        .filter(option => option.id === optionId ? !previous.includes(option.id) : previous.includes(option.id))
        .map(option => option.id));
    } else {
      setCustomOpen(false);
      setSelectedOptionIds([optionId]);
      void submit({ selectedOptionIds: [optionId], customText: '' });
    }
  }

  return <Card aria-labelledby={`${id}-title`} aria-busy={locked} className="min-w-0 shrink-0 overflow-hidden border-indigo-200">
    <CardHeader className="flex-row items-start justify-between gap-3 px-4 py-3">
      <div className="flex min-w-0 flex-1 items-center gap-2 pt-1">
        <CardTitle id={`${id}-title`} className="min-w-0 flex-1 break-words text-sm leading-6 line-clamp-2">{title}</CardTitle>
        <Badge tone={multiple ? 'info' : 'neutral'} className="shrink-0 text-xs font-normal">
          {t(multiple ? 'questionCard.multipleBadge' : 'questionCard.singleBadge')}
        </Badge>
      </div>
      {onStop ? <Button type="button" variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label={t('chat.composer.stop')} onClick={onStop}>
        <Square size={14} aria-hidden="true" />
      </Button> : null}
      <Button type="button" variant="ghost" size="icon" className="h-8 w-8 shrink-0"
        aria-label={t(collapsed ? 'questionCard.expand' : 'questionCard.collapse')}
        aria-expanded={!collapsed} aria-controls={`${id}-body`} onClick={() => setCollapsed(previous => !previous)}>
        {collapsed ? <ChevronDown size={16} aria-hidden="true" /> : <ChevronUp size={16} aria-hidden="true" />}
      </Button>
    </CardHeader>
    {!collapsed ? <CardContent id={`${id}-body`} className="max-h-[min(50dvh,28rem)] overflow-y-auto overscroll-contain px-4 pb-4">
      <form className="space-y-3" onSubmit={event => { event.preventDefault(); if (valid) void submit(answer); }}>
        {explanation !== title ? explanation.length > 120 ? <details className="text-xs text-slate-500">
          <summary className="cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">{t('chat.questionDetails')}</summary>
          <p className="mt-2 whitespace-pre-wrap break-words leading-5">{question.text}</p>
        </details> : <p className="whitespace-pre-wrap break-words text-sm leading-6 text-slate-500">{question.text}</p> : null}
        {!textOnly ? <fieldset disabled={locked} className="space-y-2" aria-label={title}>
          <legend className="sr-only">{t(multiple ? 'questionCard.multipleHint' : 'questionCard.singleHint')}</legend>
          {multiple && options.length > 2 ? <div className="flex items-center justify-between text-xs text-slate-500">
            <span>{t('questionCard.multipleHint')}</span>
            <button type="button" className="font-medium text-indigo-600 hover:text-indigo-800 hover:underline"
              onClick={() => {
                setFailed(false);
                setSelectedOptionIds(selectedOptionIds.length === options.length ? [] : options.map(option => option.id));
              }}>
              {t(selectedOptionIds.length === options.length ? 'questionCard.clearAll' : 'questionCard.selectAll')}
            </button>
          </div> : <p className="text-xs text-slate-500">{t(multiple ? 'questionCard.multipleHint' : 'questionCard.singleHint')}</p>}
          {options.map((option, index) => <label key={option.id} className={cn(
            'flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition-colors focus-within:ring-2 focus-within:ring-ring',
            selectedOptionIds.includes(option.id) ? 'border-indigo-300 bg-indigo-50/70' : 'border-slate-200 hover:bg-slate-50',
            locked && 'cursor-wait opacity-60',
          )}>
            <input type={multiple ? 'checkbox' : 'radio'} name={`${id}-choice`} value={option.id}
              aria-label={option.label} aria-describedby={option.description ? `${id}-option-${index}` : undefined}
              checked={selectedOptionIds.includes(option.id)} className="h-4 w-4 shrink-0 accent-indigo-600"
              onChange={() => selectOption(option.id)} />
            <Badge aria-hidden="true" tone="info" className="h-7 w-7 justify-center rounded-full p-0">{index + 1}</Badge>
            <span className="min-w-0 flex-1 break-words text-sm text-slate-800">
              <span className="font-medium">{option.label}</span>
              {option.description ? <span id={`${id}-option-${index}`} className="mt-0.5 block text-xs text-slate-500">{option.description}</span> : null}
            </span>
          </label>)}
          {allowCustom ? <label className={cn(
            'flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition-colors focus-within:ring-2 focus-within:ring-ring',
            customOpen ? 'border-indigo-300 bg-indigo-50/70' : 'border-slate-200 hover:bg-slate-50',
            locked && 'cursor-wait opacity-60',
          )}>
            <input type={multiple ? 'checkbox' : 'radio'} name={`${id}-choice`} checked={customOpen}
              aria-label={t('questionCard.other')} className="h-4 w-4 shrink-0 accent-indigo-600"
              onChange={() => {
                setFailed(false);
                setCustomOpen(previous => !previous);
                if (!multiple) setSelectedOptionIds([]);
              }} />
            <Badge aria-hidden="true" tone="info" className="h-7 w-7 justify-center rounded-full p-0">{options.length + 1}</Badge>
            <span className="text-sm font-medium text-slate-800">{t('questionCard.other')}</span>
          </label> : null}
        </fieldset> : null}
        {showText ? <div className="space-y-1.5">
          <label htmlFor={`${id}-answer`} className="text-sm font-medium text-slate-700">{t('questionCard.answerLabel')}</label>
          <Textarea ref={answerRef} id={`${id}-answer`} value={customText} disabled={locked} maxLength={4000} rows={3}
            placeholder={t('questionCard.placeholder')}
            onChange={event => { setCustomText(event.target.value); setFailed(false); }} />
        </div> : null}
        {message ? <p role="alert" className="break-words text-sm text-red-700">{message}</p> : null}
        {multiple || showText || message ? <div className="flex justify-end">
          <Button type="submit" size="sm" disabled={locked || !valid}>
            {t(submitting ? 'questionCard.submitting' : message ? 'questionCard.retry' : 'questionCard.confirm')}
          </Button>
        </div> : null}
      </form>
    </CardContent> : null}
  </Card>;
}
