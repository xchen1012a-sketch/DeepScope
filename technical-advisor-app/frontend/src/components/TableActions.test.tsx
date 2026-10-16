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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { createInstance } from 'i18next';
import { i18nResources } from '@/i18n/resources';
import { TableActions } from './TableActions';
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);
function mount(actions: React.ComponentProps<typeof TableActions>['actions']) {
  render(<I18nextProvider i18n={i18n}><TableActions actions={actions} /></I18nextProvider>);
  return userEvent.setup();
}
describe('table actions', () => {
  it('hides unauthorized actions, blocks unavailable actions and puts dangerous actions last', async () => {
    const remove = vi.fn(); const edit = vi.fn(); const hidden = vi.fn();
    const user = mount([
      { id: 'delete', label: 'Delete', tone: 'danger', onClick: remove },
      { id: 'edit', label: 'Edit', disabled: true, onClick: edit },
      { id: 'hidden', label: 'Hidden', hidden: true, onClick: hidden },
    ]);
    expect(screen.queryByText('Hidden')).toBeNull();
    expect(screen.getAllByRole('button').map(node => node.textContent)).toEqual(['Edit', 'Delete']);
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    expect(edit).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(remove).toHaveBeenCalledTimes(1);
  });
  it('keeps icon actions named and disables all actions while a row is executing', async () => {
    const execute = vi.fn();
    const user = mount([{ id: 'test', label: 'Test', loading: true, onClick: execute }, { id: 'edit', label: 'Edit', onClick: execute }]);
    expect(screen.getByRole('button', { name: 'Test' }).getAttribute('aria-busy')).toBe('true');
    for (const button of screen.getAllByRole('button')) {
      expect((button as HTMLButtonElement).disabled).toBe(true);
      await user.click(button);
    }
    expect(execute).not.toHaveBeenCalled();
  });
  it('exposes explicitly grouped actions through the shared menu and respects their disabled state', async () => {
    const configure = vi.fn(); const disabled = vi.fn();
    const user = mount([
      { id: 'edit', label: 'Edit', onClick: vi.fn() },
      { id: 'configure', label: 'Configure', overflow: true, onClick: configure },
      { id: 'disabled', label: 'Unavailable', overflow: true, disabled: true, onClick: disabled },
    ]);
    expect(screen.queryByRole('menuitem')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'More actions' }));
    const menu = screen.getByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: 'Unavailable' }).getAttribute('aria-disabled')).toBe('true');
    await user.click(within(menu).getByRole('menuitem', { name: 'Configure' }));
    expect(configure).toHaveBeenCalledTimes(1);
    expect(disabled).not.toHaveBeenCalled();
  });
});
