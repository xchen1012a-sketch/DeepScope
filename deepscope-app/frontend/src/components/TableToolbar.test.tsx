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
import { useState } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { i18nResources } from '@/i18n/resources';
import { TableToolbar } from './TableToolbar';
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);
it('keeps typing local and submits the current draft by button or Enter', async () => {
  const submit = vi.fn();
  function Harness() {
    const [value, setValue] = useState('');
    return <TableToolbar search={value} onSearchChange={setValue} onSearch={() => submit(value)} placeholder="Search items" />;
  }
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><Harness /></I18nextProvider>);
  await user.type(screen.getByRole('searchbox'), 'term');
  expect(submit).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Search' }));
  expect(submit).toHaveBeenNthCalledWith(1, 'term');
  await user.clear(screen.getByRole('searchbox'));
  await user.type(screen.getByRole('searchbox'), '{Enter}');
  expect(submit).toHaveBeenNthCalledWith(2, '');
});
