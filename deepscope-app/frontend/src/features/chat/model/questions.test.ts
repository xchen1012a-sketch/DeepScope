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
import { describe, expect, it } from 'vitest';
import type { EventEntry } from '@/api/advisor';
import { pendingQuestionFromEvents, questionPrompt, questionFromEvent, unfinishedQuestionReply, questionErrorKey, questionTitle } from './questions';
import { ApiError } from '@/lib/apiClient';

function event(seq: number, kind: string, payload: Record<string, unknown> = {}, turn = 1): EventEntry {
  return { seq, kind, payload, turn };
}
describe('question event projection', () => {
  it('preserves explicit titles and bounds historical headings without rewriting question text', () => {
    const text = 'Which scope? ' + 'Additional context. '.repeat(12);
    const question = questionFromEvent(event(1, 'question', { title: 'Choose a scope', text }))!;
    expect(question.title).toBe('Choose a scope');
    expect(question.text).toBe(text);
    expect(questionTitle(question)).toBe('Choose a scope');
    expect(questionTitle({ text })).toBe('Which scope?');
    expect(Array.from(questionTitle({ text: '范围'.repeat(40) }))).toHaveLength(40);
  });
  it('keeps a question pending after explanatory assistant text', () => {
    const question = event(2, 'question', { text: 'Choose deployment', suggestions: ['Local', 'Cloud'] });
    expect(pendingQuestionFromEvents([question, event(3, 'advisor_message', { text: 'Waiting' })]))
      .toMatchObject({ questionId: 'legacy-2', text: 'Choose deployment', answerMode: 'single', options: [{ id: 'opt-1', label: 'Local' }, { id: 'opt-2', label: 'Cloud' }] });
    expect(pendingQuestionFromEvents([question, event(4, 'user_message', { text: 'Cloud' }, 2)])).toBeNull();
  });
  it('preserves server identifiers and supports optionless questions', () => {
    expect(questionFromEvent(event(5, 'question', { questionId: 'q-5', text: 'Targets?', answerMode: 'multiple', options: [{ id: 'stable', label: 'Cloud', description: 'Hosted' }] })))
      .toMatchObject({ questionId: 'q-5', answerMode: 'multiple', options: [{ id: 'stable', label: 'Cloud', description: 'Hosted' }] });
    expect(questionFromEvent(event(6, 'question', { text: 'What topic?' }))).toMatchObject({ answerMode: 'text', options: [] });
    expect(questionFromEvent(event(7, 'question', { text: '  ' }))).toBeNull();
  });
  it('dynamically adapts answerMode from snake_case, boolean flags, and semantic cues', () => {
    const opts = [{ id: 'opt-1', label: 'Item 1' }];
    expect(questionFromEvent(event(10, 'question', { text: 'Select options', answer_mode: 'multiple', options: opts })))
      .toMatchObject({ answerMode: 'multiple' });
    expect(questionFromEvent(event(11, 'question', { text: 'Select options', is_multi_select: true, options: opts })))
      .toMatchObject({ answerMode: 'multiple' });
    expect(questionFromEvent(event(12, 'question', { text: '请选择排查模块（可多选）', options: opts })))
      .toMatchObject({ answerMode: 'multiple' });
    expect(questionFromEvent(event(13, 'question', { text: '涉及哪些方面？', options: opts })))
      .toMatchObject({ answerMode: 'multiple' });
    expect(questionFromEvent(event(14, 'question', { text: '请确认优先方案', options: opts })))
      .toMatchObject({ answerMode: 'single' });
  });
  it('does not let an answer for another question close the current question', () => {
    const question = event(2, 'question', { questionId: 'current', text: 'Topic?' });
    expect(pendingQuestionFromEvents([question, event(3, 'user_message', { text: 'Other', questionReply: { questionId: 'old' } }, 2)])?.questionId).toBe('current');
  });
  it('recovers an unprocessed saved answer but never completed or active work', () => {
    const answer = event(3, 'user_message', { text: 'Cloud', questionReply: { questionId: 'q-2', contextText: 'Question: deployment\nAnswer: Cloud' } }, 2);
    expect(unfinishedQuestionReply([answer], { phase: 'idle', interrupts: [] })).toBe(answer);
    expect(unfinishedQuestionReply([answer], { phase: 'running', interrupts: [] })).toBeNull();
    expect(unfinishedQuestionReply([answer], { phase: 'finalizing', interrupts: [] })).toBeNull();
    expect(unfinishedQuestionReply([answer, event(4, 'advisor_message', { text: 'Done' }, 2)])).toBeNull();
    expect(unfinishedQuestionReply([answer, event(4, 'tool_call', { name: 'read' }, 2)])).toBeNull();
    expect(questionPrompt(answer)).toBe('Question: deployment\nAnswer: Cloud');
  });
  it('maps server reply errors without exposing response details as UI text', () => {
    expect(questionErrorKey(new ApiError(409, '{"error":"session_running"}'))).toBe('questionCard.waiting');
    expect(questionErrorKey(new ApiError(400, '{"error":"question_reply_invalid"}'))).toBe('questionCard.invalidAnswer');
    expect(questionErrorKey(new ApiError(409, '{"error":"question_already_answered"}'))).toBe('questionCard.staleQuestion');
    expect(questionErrorKey(new ApiError(404, '{"error":"question_not_found"}'))).toBe('questionCard.staleQuestion');
    expect(questionErrorKey(new ApiError(409, '{"error":"question_request_conflict"}'))).toBe('questionCard.requestConflict');
    expect(questionErrorKey(new Error('private technical error'))).toBe('questionCard.submitError');
  });
});
