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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { createInstance } from 'i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { DataTable } from './DataTable';
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app' });
afterEach(cleanup);
const columns = [{ id: 'name', header: 'Name', cell: (row: { id: string }) => row.id }];
describe('shared table states', () => {
  it('keeps pagination available when the current page has no rows', () => {
    const change = vi.fn();
    render(<I18nextProvider i18n={i18n}><DataTable columns={columns} data={[]} keyExtractor={row => row.id} page={2} pageSize={10} total={11} onPageChange={change} /></I18nextProvider>);
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(change).toHaveBeenCalledWith(1);
  });
  it('shows a recoverable error instead of pretending the list is empty', () => {
    const retry = vi.fn();
    render(<I18nextProvider i18n={i18n}><DataTable columns={columns} data={[]} keyExtractor={row => row.id} error="Load failed" onRetry={retry} /></I18nextProvider>);
    expect(screen.getByRole('alert').textContent).toContain('Load failed');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
  it('generates accessible row actions without a page-specific action column', () => {
    const edit = vi.fn();
    render(<I18nextProvider i18n={i18n}><DataTable columns={columns} data={[{ id: 'item' }]} keyExtractor={row => row.id} actions={row => [{ id: 'edit', label: `Edit ${row.id}`, onClick: edit }]} /></I18nextProvider>);
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Edit item' }));
    expect(edit).toHaveBeenCalledTimes(1);
  });
});
