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

// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import type { PendingQuestion, QuestionAnswer } from '@/api/advisor';
import { i18nResources } from '@/i18n/resources';
import { QuestionCard } from './QuestionCard';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);

const question: PendingQuestion = {
  questionId: 'question-1', text: 'What should we investigate?', answerMode: 'single',
  allowCustom: true, seq: 3, turn: 1,
  options: [
    { id: 'architecture', label: 'Architecture', description: 'Review module boundaries' },
    { id: 'performance', label: 'Performance' },
    { id: 'delivery', label: 'Delivery' },
  ],
};

function show(overrides: Partial<PendingQuestion> = {}, onAnswer = vi.fn<(answer: QuestionAnswer) => Promise<void>>().mockResolvedValue(undefined), busy = false, error?: string) {
  return {
    onAnswer,
    ...render(<I18nextProvider i18n={i18n}><QuestionCard question={{ ...question, ...overrides }} busy={busy} error={error} onAnswer={onAnswer} /></I18nextProvider>),
  };
}

it('renders the current title and actual options with continuing circular numbers and submits a single selection', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show();
  expect(screen.getByRole('heading', { name: question.text })).toBeTruthy();
  const choices = screen.getAllByRole('radio');
  expect(choices).toHaveLength(4);
  for (let index = 0; index < choices.length; index += 1) {
    const number = within(choices[index].closest('label')!).getByText(String(index + 1));
    expect(number.className).toContain('rounded-full');
  }
  expect(screen.getByText('Review module boundaries')).toBeTruthy();
  expect(onAnswer).not.toHaveBeenCalled();
  await user.click(screen.getByRole('radio', { name: 'Performance' }));
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: ['performance'], customText: '' });
});

it('shows a different question and any actual number of options', () => {
  show({ text: 'Which constraint matters?', options: question.options.slice(0, 2) });
  expect(screen.getByRole('heading', { name: 'Which constraint matters?' })).toBeTruthy();
  expect(screen.getAllByRole('radio')).toHaveLength(3);
  expect(screen.queryByText('Delivery')).toBeNull();
  expect(within(screen.getByRole('radio', { name: 'Other' }).closest('label')!).getByText('3')).toBeTruthy();
});

it('keeps a long legacy question out of the heading and preserves its complete explanation', async () => {
  const text = 'Which area should we investigate? ' + 'Detailed background that must remain available. '.repeat(8);
  show({ text });
  expect(screen.getByRole('heading').textContent).toBe('Which area should we investigate?');
  await userEvent.setup().click(screen.getByText('Question details'));
  expect(screen.getByText(text.trim()).textContent).toBe(text);
});

it('uses the concise dynamic title separately from the complete question', () => {
  show({ title: 'Choose a review scope', text: 'Which areas and constraints should this review cover?' });
  expect(screen.getByRole('heading', { name: 'Choose a review scope' })).toBeTruthy();
  expect(screen.getByText('Which areas and constraints should this review cover?')).toBeTruthy();
});

it('waits for confirmation on multiple choices and allows adding a custom answer', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show({ answerMode: 'multiple' });
  const submit = screen.getByRole('button', { name: 'Confirm answer' });
  expect((submit as HTMLButtonElement).disabled).toBe(true);
  await user.click(screen.getByRole('checkbox', { name: /Architecture/ }));
  await user.click(screen.getByRole('checkbox', { name: 'Delivery' }));
  await user.click(screen.getByRole('checkbox', { name: 'Other' }));
  await user.type(screen.getByRole('textbox', { name: 'Your answer' }), '  Security  ');
  expect(onAnswer).not.toHaveBeenCalled();
  await user.click(submit);
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: ['architecture', 'delivery'], customText: 'Security' });
});

it('opens a custom single answer without submitting until the user confirms', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show();
  await user.click(screen.getByRole('radio', { name: 'Other' }));
  const answer = screen.getByRole('textbox', { name: 'Your answer' });
  const submit = screen.getByRole('button', { name: 'Confirm answer' });
  expect((submit as HTMLButtonElement).disabled).toBe(true);
  await user.type(answer, '  Security  ');
  expect(onAnswer).not.toHaveBeenCalled();
  await user.click(submit);
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: [], customText: 'Security' });
});

it('accepts free text when there are no options and supports keyboard submission', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show({ options: [] });
  await user.type(screen.getByRole('textbox', { name: 'Your answer' }), 'Investigate memory usage');
  await user.tab();
  await user.keyboard('{Enter}');
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: [], customText: 'Investigate memory usage' });
});

it('preserves the draft across collapse and expand', async () => {
  const user = userEvent.setup();
  show({ answerMode: 'text', options: [] });
  await user.type(screen.getByRole('textbox', { name: 'Your answer' }), 'Keep this draft');
  await user.click(screen.getByRole('button', { name: 'Collapse question' }));
  expect(screen.queryByRole('textbox')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Expand question' }));
  expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('Keep this draft');
});

it('retains a failed custom answer and retries the same answer on explicit confirmation', async () => {
  const user = userEvent.setup();
  const onAnswer = vi.fn<(answer: QuestionAnswer) => Promise<void>>()
    .mockRejectedValueOnce(new Error('Network problem')).mockResolvedValue(undefined);
  show({ answerMode: 'text', options: [] }, onAnswer);
  await user.type(screen.getByRole('textbox'), 'Investigate latency');
  await user.click(screen.getByRole('button', { name: 'Confirm answer' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('Investigate latency');
  await user.click(screen.getByRole('button', { name: 'Retry answer' }));
  expect(onAnswer).toHaveBeenCalledTimes(2);
  expect(onAnswer.mock.calls[1]).toEqual(onAnswer.mock.calls[0]);
});

it('retains a failed single selection and allows explicit retry without changing the answer', async () => {
  const user = userEvent.setup();
  const onAnswer = vi.fn<(answer: QuestionAnswer) => Promise<void>>()
    .mockRejectedValueOnce(new Error('Network problem')).mockResolvedValue(undefined);
  show({}, onAnswer);
  await user.click(screen.getByRole('radio', { name: 'Performance' }));
  await screen.findByRole('alert');
  expect((screen.getByRole('radio', { name: 'Performance' }) as HTMLInputElement).checked).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Retry answer' }));
  expect(onAnswer).toHaveBeenCalledTimes(2);
  expect(onAnswer.mock.calls[1]).toEqual(onAnswer.mock.calls[0]);
});

it('guards immediate duplicate clicks while submission is pending', async () => {
  let resolve: () => void = () => {};
  const onAnswer = vi.fn<(answer: QuestionAnswer) => Promise<void>>(() => new Promise<void>(done => { resolve = done; }));
  show({}, onAnswer);
  const option = screen.getByRole('radio', { name: 'Performance' });
  act(() => { fireEvent.click(option); fireEvent.click(option); });
  expect(onAnswer).toHaveBeenCalledTimes(1);
  expect(option.matches(':disabled')).toBe(true);
  await act(async () => resolve());
  await waitFor(() => expect(option.matches(':disabled')).toBe(false));
});

it('disables answer controls while the parent is busy and displays its error', () => {
  show({ answerMode: 'text', options: [] }, undefined, true, 'Wait for the current run to finish.');
  expect((screen.getByRole('textbox') as HTMLTextAreaElement).disabled).toBe(true);
  expect((screen.getByRole('button', { name: 'Retry answer' }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByRole('alert').textContent).toBe('Wait for the current run to finish.');
});

it('omits Other when custom answers are disabled', () => {
  show({ allowCustom: false });
  expect(screen.getAllByRole('radio')).toHaveLength(3);
  expect(screen.queryByRole('radio', { name: 'Other' })).toBeNull();
});

it('uses Chinese card prompts for a Chinese question even when the interface language is English', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show({ text: '本次需要分析哪个方向？' });
  expect(screen.queryByRole('radio', { name: 'Other' })).toBeNull();
  await user.click(screen.getByRole('radio', { name: '其他' }));
  const input = screen.getByRole('textbox', { name: '你的回答' });
  expect(document.activeElement).toBe(input);
  expect(onAnswer).not.toHaveBeenCalled();
  await user.type(input, '分析安全边界');
  await user.click(screen.getByRole('button', { name: '确认回答' }));
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: [], customText: '分析安全边界' });
});

it.each(['Other', '其他', 'Other (please specify)', '其它（请说明）'])('treats a model-provided %s option as text input rather than an immediately submitted answer', async label => {
  const user = userEvent.setup();
  const { onAnswer } = show({ options: [...question.options, { id: 'model-other', label }] });
  const choices = screen.getAllByRole('radio');
  expect(choices).toHaveLength(4);
  const other = screen.getByRole('radio', { name: 'Other' });
  await user.click(other);
  expect(onAnswer).not.toHaveBeenCalled();
  const input = screen.getByRole('textbox', { name: 'Your answer' });
  expect(document.activeElement).toBe(input);
  await user.type(input, '  Another scope  ');
  await user.click(screen.getByRole('button', { name: 'Confirm answer' }));
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: [], customText: 'Another scope' });
});

it('requires custom text when Other is checked together with business options', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show({ answerMode: 'multiple' });
  await user.click(screen.getByRole('checkbox', { name: 'Architecture' }));
  await user.click(screen.getByRole('checkbox', { name: 'Other' }));
  const submit = screen.getByRole('button', { name: 'Confirm answer' }) as HTMLButtonElement;
  expect(submit.disabled).toBe(true);
  await user.type(screen.getByRole('textbox'), '   ');
  expect(submit.disabled).toBe(true);
  expect(onAnswer).not.toHaveBeenCalled();
  await user.type(screen.getByRole('textbox'), 'Security');
  await user.click(submit);
  expect(onAnswer).toHaveBeenCalledExactlyOnceWith({ selectedOptionIds: ['architecture'], customText: 'Security' });
});

it('keeps an explicit Other option editable even on a legacy card without allowCustom', async () => {
  const user = userEvent.setup();
  const { onAnswer } = show({ allowCustom: false, options: [...question.options, { id: 'custom', label: 'Other' }] });
  await user.click(screen.getByRole('radio', { name: 'Other' }));
  expect(screen.getByRole('textbox')).toBeTruthy();
  expect(onAnswer).not.toHaveBeenCalled();
});

it('displays the mode badge and toggles Select all and Clear all in multiple mode', async () => {
  const user = userEvent.setup();
  show({ answerMode: 'multiple' });
  expect(screen.getByText('Multi-select')).toBeTruthy();
  const selectAllBtn = screen.getByRole('button', { name: 'Select all' });
  await user.click(selectAllBtn);
  const checkboxes = screen.getAllByRole('checkbox') as HTMLInputElement[];
  expect(checkboxes[0].checked).toBe(true);
  expect(checkboxes[1].checked).toBe(true);
  expect(checkboxes[2].checked).toBe(true);
  expect(checkboxes[3].checked).toBe(false);

  const clearBtn = screen.getByRole('button', { name: 'Clear all' });
  await user.click(clearBtn);
  expect(checkboxes[0].checked).toBe(false);
  expect(checkboxes[1].checked).toBe(false);
  expect(checkboxes[2].checked).toBe(false);
});

it('displays the single choice badge in single mode', () => {
  show({ answerMode: 'single' });
  expect(screen.getByText('Single choice')).toBeTruthy();
});
