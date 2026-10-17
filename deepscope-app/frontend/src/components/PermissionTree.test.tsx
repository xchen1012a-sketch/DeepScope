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
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { useState } from 'react';
import { afterEach, expect, it } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { PermissionTree } from './PermissionTree';
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);

function Editor() {
  const [value, setValue] = useState(['agent.view']);
  return <PermissionTree value={value} onChange={setValue} nodes={[{ id: 'agent', name: 'Agents', children: [
    { id: 'agent.view', name: 'View', children: [] }, { id: 'agent.edit', name: 'Edit agent', children: [] },
  ] }]} />;
}

it('starts collapsed, preserves grants when toggled, and retains partial selection', async () => {
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><Editor /></I18nextProvider>);
  expect(screen.queryByRole('checkbox', { name: 'View' })).toBeNull();
  expect(screen.getByRole('checkbox', { name: 'Agents' }).getAttribute('aria-checked')).toBe('mixed');
  await user.click(screen.getByRole('button', { name: 'Expand Agents' }));
  expect((screen.getByRole('checkbox', { name: 'View' }) as HTMLInputElement).checked).toBe(true);
  await user.click(screen.getByRole('checkbox', { name: 'Edit agent' }));
  expect((screen.getByRole('checkbox', { name: 'Agents' }) as HTMLInputElement).checked).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Collapse Agents' }));
  expect(screen.queryByRole('checkbox', { name: 'Edit agent' })).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Expand Agents' }));
  expect((screen.getByRole('checkbox', { name: 'Edit agent' }) as HTMLInputElement).checked).toBe(true);
});
