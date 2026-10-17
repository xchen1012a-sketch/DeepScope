// @vitest-environment jsdom
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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { createInstance } from 'i18next';
import { i18nResources } from '@/i18n/resources';
import { Markdown } from './Markdown';
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('copies the exact code without the language label and exposes a persistent button', async () => {
  const user = userEvent.setup();
  const copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
  render(<I18nextProvider i18n={i18n}><Markdown text={'```java\nSystem.out.println("hi");\n```'} /></I18nextProvider>);
  await user.click(screen.getByRole('button', { name: 'Copy' }));
  expect(copy).toHaveBeenCalledWith('System.out.println("hi");\n');
  expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy();
});

it('offers a retry when clipboard access fails', async () => {
  const user = userEvent.setup();
  vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined);
  render(<I18nextProvider i18n={i18n}><Markdown text={'```text\nhello\n```'} /></I18nextProvider>);
  await user.click(screen.getByRole('button', { name: 'Copy' }));
  await user.click(await screen.findByRole('button', { name: i18n.t('chat.copy.retry') }));
  expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy();
});
