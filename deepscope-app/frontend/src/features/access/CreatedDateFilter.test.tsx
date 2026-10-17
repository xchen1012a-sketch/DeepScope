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
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { CreatedDateFilter } from './CreatedDateFilter';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(() => { cleanup(); vi.useRealTimers(); });
function Filter() {
  const [value, setValue] = useState({ start: '', end: '' });
  return <CreatedDateFilter value={value} onChange={setValue} />;
}

it('offers calendar, time columns and presets, committing a range only after confirmation', async () => {
  vi.setSystemTime(new Date(2026, 9, 16, 12, 30, 0));
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><Filter /></I18nextProvider>);
  await user.click(screen.getByRole('button', { name: 'Open calendar' }));
  const panel = screen.getByRole('dialog', { name: 'Time range' });
  expect(panel.contains(document.activeElement)).toBe(true);
  for (const name of ['Today', 'Last 7 days', 'Last 30 days', 'Yesterday', 'This week', 'This month', 'Last week'])
    expect(within(panel).getByRole('button', { name })).toBeTruthy();
  expect(within(panel).getByRole('grid')).toBeTruthy();
  await user.click(within(panel).getByRole('button', { name: 'Last 7 days' }));
  expect(within(panel).getByLabelText('Hours')).toBeTruthy();
  expect(within(panel).getByLabelText('Minutes')).toBeTruthy();
  expect(within(panel).getByLabelText('Seconds')).toBeTruthy();
  await user.click(within(panel).getByRole('button', { name: 'Confirm' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect((screen.getByLabelText('Start time') as HTMLInputElement).value).toBe('2026-10-10 00:00:00');
  expect((screen.getByLabelText('End time') as HTMLInputElement).value).toBe('2026-10-16 23:59:59');
});

it('discards an unconfirmed preset on Escape', async () => {
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><Filter /></I18nextProvider>);
  await user.click(screen.getByRole('button', { name: 'Open calendar' }));
  await user.click(screen.getByRole('button', { name: 'Today' }));
  await user.keyboard('{Escape}');
  expect((screen.getByLabelText('Start time') as HTMLInputElement).value).toBe('');
  expect((screen.getByLabelText('End time') as HTMLInputElement).value).toBe('');
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Open calendar' }));
});

it('confirms typed times with Enter without submitting the surrounding search form', async () => {
  const submit = vi.fn();
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><form onSubmit={event => { event.preventDefault(); submit(); }}><Filter /></form></I18nextProvider>);
  await user.click(screen.getByLabelText('Start time'));
  await user.type(screen.getByLabelText('Start time'), '2026-10-16 09:30:00');
  await user.keyboard('{Enter}');
  expect(submit).not.toHaveBeenCalled();
  expect(screen.queryByRole('dialog')).toBeNull();
  expect((screen.getByLabelText('Start time') as HTMLInputElement).value).toBe('2026-10-16 09:30:00');
});
