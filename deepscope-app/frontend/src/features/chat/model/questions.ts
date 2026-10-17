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
import type { EventEntry, PendingQuestion, QuestionOption, SessionDetail, SessionRuntime } from '@/api/advisor';
import { ApiError } from '@/lib/apiClient';

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

/** Older records keep their full text; only their heading receives a bounded first-sentence view. */
export function questionTitle(question: Pick<PendingQuestion, 'title' | 'text'>): string {
  const explicit = question.title?.trim();
  const firstLine = (explicit || question.text).trim().split(/[\r\n]/u)[0];
  const end = explicit ? -1 : firstLine.search(/[。！？?!]/u);
  const short = (end < 0 ? firstLine : firstLine.slice(0, end + 1)).replace(/\s+/g, ' ').trim();
  const characters = Array.from(short);
  return characters.length > 40 ? characters.slice(0, 39).join('') + '…' : short;
}

export function questionFromEvent(event: EventEntry): PendingQuestion | null {
  if (event.kind !== 'question' || typeof event.payload.text !== 'string' || !event.payload.text.trim()) return null;
  const payload = event.payload;
  const options: QuestionOption[] = Array.isArray(payload.options) ? payload.options.flatMap(value => {
    const option = record(value);
    return option && typeof option.id === 'string' && typeof option.label === 'string'
      ? [{ id: option.id, label: option.label, ...(typeof option.description === 'string' ? { description: option.description } : {}) }] : [];
  }) : Array.isArray(payload.suggestions) ? payload.suggestions.flatMap((label, index) =>
    typeof label === 'string' && label.trim() ? [{ id: `opt-${index + 1}`, label }] : []) : [];
  const rawMode = (typeof payload.answerMode === 'string' ? payload.answerMode : typeof payload.answer_mode === 'string' ? payload.answer_mode : '').toLowerCase();
  const isMultiHint = /(?:多选|可多选|复选|勾选所有|选择所有|哪些|multiple|select all)/iu.test(`${payload.title ?? ''}\n${payload.text}`);
  const answerMode = rawMode === 'multiple' || rawMode === 'multi' || rawMode === 'checkbox' || payload.is_multi_select === true || (isMultiHint && rawMode !== 'single' && rawMode !== 'radio' && rawMode !== 'text')
    ? 'multiple'
    : rawMode === 'text' || (!options.length && !rawMode)
      ? 'text'
      : 'single';
  return {
    questionId: typeof payload.questionId === 'string' ? payload.questionId : `legacy-${event.seq}`,
    text: event.payload.text,
    ...(typeof payload.title === 'string' && payload.title.trim() ? { title: payload.title.trim() } : {}),
    answerMode,
    allowCustom: payload.allowCustom !== false, options, seq: event.seq, turn: event.turn,
  };
}

/** Compatibility projection only; the server remains authoritative for submissions. */
export function pendingQuestionFromEvents(events: EventEntry[]): PendingQuestion | null {
  let pending: PendingQuestion | null = null;
  for (const event of events) {
    if (event.kind === 'question') pending = questionFromEvent(event);
    else if (event.kind === 'user_message' && pending) {
      const reply = record(event.payload.questionReply);
      if (!reply || reply.questionId === pending.questionId) pending = null;
    }
  }
  return pending;
}

export function resolvePendingQuestion(detail: SessionDetail | undefined, events: EventEntry[]): PendingQuestion | null {
  const projected = pendingQuestionFromEvents(events);
  const saved = detail?.pendingQuestion;
  if (saved === undefined) return projected;
  if (saved && !events.some(event => event.kind === 'user_message' && event.seq > saved.seq)) return projected ?? saved;
  const lastSavedSeq = (detail?.entries ?? []).reduce((max, event) => Math.max(max, event.seq), 0);
  return projected && projected.seq > lastSavedSeq ? projected : null;
}

/** Only an answer with no observed execution is safe to offer after refresh. */
export function unfinishedQuestionReply(events: EventEntry[], runtime?: SessionRuntime): EventEntry | null {
  if (runtime && runtime.phase !== 'idle') return null;
  const latestVisitor = [...events].reverse().find(event => event.kind === 'user_message');
  if (!latestVisitor || !record(latestVisitor.payload.questionReply)) return null;
  return events.some(event => event.seq > latestVisitor.seq && event.turn >= latestVisitor.turn
    && event.kind !== 'agent_route') ? null : latestVisitor;
}

export function questionPrompt(entry: EventEntry, includeAttachments = true): string {
  const reply = record(entry.payload.questionReply);
  const context = typeof reply?.contextText === 'string' ? reply.contextText : typeof entry.payload.text === 'string' ? entry.payload.text : '';
  return includeAttachments && typeof reply?.attachmentContext === 'string' && reply.attachmentContext.trim() ? `${context}\n\n${reply.attachmentContext}` : context;
}

export function questionErrorKey(error: unknown): string {
  let code = error instanceof Error ? error.message : '';
  if (error instanceof ApiError) {
    try { code = String(record(JSON.parse(error.body))?.error ?? ''); } catch { code = ''; }
  }
  if (code === 'session_running') return 'questionCard.waiting';
  if (code === 'question_reply_invalid') return 'questionCard.invalidAnswer';
  if (code === 'question_not_found' || code === 'question_already_answered') return 'questionCard.staleQuestion';
  if (code === 'question_request_conflict') return 'questionCard.requestConflict';
  return 'questionCard.submitError';
}
