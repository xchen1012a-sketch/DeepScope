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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api, apiFetch } from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import { DocumentManagerTab } from './DocumentManagerTab';

vi.mock('@/lib/apiClient', () => ({ api: { get: vi.fn() }, apiFetch: vi.fn() }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
const library = { libraryId: 'lib', name: 'Library', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: 0, canEdit: true, documents: [] };
const failed = { documentId: 'doc', filename: 'manual.md', status: 'failed', failure: 'Invalid encoding', sizeBytes: 12, createdAt: 0 };
it('shows completed and total embedding passages while processing', async () => {
  vi.mocked(api.get).mockResolvedValue({ items: [{ ...failed, status: 'parsing', stage: 'embedding', embeddedChunks: 3, totalChunks: 12 }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 1 });
  mount();
  expect(await screen.findByText('3 / 12 passages embedded')).toBeTruthy();
});
it('keeps HTML tables while dropping executable tags and source attributes', async () => {
  vi.mocked(api.get).mockImplementation(async path => path.endsWith('/content')
    ? { format: 'html', text: '<h1 onclick="alert(1)">资产清单</h1><script>alert(1)</script><iframe src="https://example.com"></iframe><img src="https://example.com/a"><table><tr><th>金额</th></tr><tr><td>12.50</td></tr></table><a href="javascript:alert(1)">链接文字</a>' }
    : { items: [{ ...failed, filename: 'assets.html', status: 'ready' }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  const { user } = mount();
  await user.click(await screen.findByRole('button', { name: 'View content' }));
  expect(await screen.findByRole('heading', { name: '资产清单' })).toBeTruthy();
  expect(screen.getByRole('cell', { name: '12.50' })).toBeTruthy();
  expect(screen.getByRole('dialog').querySelector('script, iframe, img, [onclick], [href]')).toBeNull();
});

it('preserves TXT line breaks and loads actual published passages on demand', async () => {
  vi.mocked(api.get).mockImplementation(async path => path.endsWith('/content')
    ? { format: 'text', text: '第一行\n第二行' }
    : path.includes('/chunks?') ? { items: [{ chunkId: '0', text: '实际入库文本' }], total: 1 }
    : { items: [{ ...failed, filename: 'audit.txt', status: 'ready' }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  const { user } = mount();
  await user.click(await screen.findByRole('button', { name: 'View content' }));
  await waitFor(() => expect(screen.getByRole('dialog').querySelector('pre')?.textContent).toBe('第一行\n第二行'));
  await user.click(screen.getByRole('tab', { name: 'Indexed passages' }));
  expect(await screen.findByText('实际入库文本')).toBeTruthy();
  expect(api.get).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/chunks?page=1&size=10');
});
it('opens a formatted non-PDF preview from the shared view-content action', async () => {
  vi.mocked(api.get).mockImplementation(async path => path.endsWith('/content')
    ? { format: 'markdown', text: '# 采购规范\n\n|项目|金额|\n|---|---|\n|A|12.50|\n\n[F1] 原文标记 [E1]\n\n![外部图片](https://example.com/track)' }
    : { items: [{ ...failed, status: 'ready' }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  const { user } = mount();
  await user.click(await screen.findByRole('button', { name: 'View content' }));
  expect(await screen.findByRole('heading', { name: '采购规范' })).toBeTruthy();
  expect(screen.getByRole('cell', { name: '12.50' })).toBeTruthy();
  expect(screen.getByText('[F1] 原文标记 [E1]')).toBeTruthy();
  expect(screen.getByRole('dialog').querySelector('img')).toBeNull();
});
it('offers one whole-document reprocess action and page inspection after a failed page', async () => {
  vi.mocked(api.get).mockResolvedValue({ items: [{ ...failed, filename: 'mixed.pdf', status: 'failed', pageCount: 3, processedPages: 3, failedPages: 1 }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  mount();
  expect(await screen.findByRole('button', { name: 'Process again' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'View content' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Retry incomplete pages' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
});
function mount(canEdit = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const changed = vi.fn();
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><DocumentManagerTab library={{ ...library, canEdit }} onChanged={changed} /></QueryClientProvider></I18nextProvider>);
  return { user: userEvent.setup(), changed };
}
beforeEach(() => {
  vi.mocked(api.get).mockResolvedValue({ items: [], page: 1, size: 10, total: 0, totalPages: 0, parsingCount: 0 });
  vi.mocked(apiFetch).mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('keeps polling when a visible row is parsing even if the separately queried count is zero', async () => {
  vi.mocked(api.get)
    .mockResolvedValueOnce({ items: [{ ...failed, status: 'parsing' }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 })
    .mockResolvedValue({ items: [{ ...failed, status: 'ready' }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  mount();
  await screen.findByText(i18n.t('knowledge.pdf.stage.parsing'));
  await waitFor(() => expect(screen.queryByText(i18n.t('knowledge.pdf.stage.parsing'))).toBeNull(), { timeout: 3500 });
  expect(api.get).toHaveBeenCalledTimes(2);
});

it('offers one upload action and uploads staged files only after confirmation', async () => {
  const { user, changed } = mount();
  await screen.findByText(i18n.t('knowledge.noDocuments'));
  expect(screen.getAllByRole('button', { name: 'Upload documents' })).toHaveLength(1);
  await user.click(screen.getByRole('button', { name: 'Upload documents' }));
  await user.upload(screen.getByLabelText('Select documents'), new File(['text'], 'manual.md', { type: 'text/markdown' }));
  expect(apiFetch).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Upload 1 files' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents', expect.objectContaining({ method: 'POST', body: expect.any(FormData) })));
  expect(changed).toHaveBeenCalledTimes(1);
});

it('shows concise failed status with focusable details and retries using the existing endpoint', async () => {
  vi.mocked(api.get).mockResolvedValue({ items: [failed], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
  const { user } = mount();
  await screen.findByText('manual.md');
  const row = screen.getByRole('row', { name: /manual.md/ });
  expect(within(row).getByText('manual.md').closest('td')?.querySelector('svg')).toBeNull();
  const help = within(row).getByRole('button', { name: 'manual.md: failure details' });
  help.focus();
  expect(await screen.findByRole('tooltip')).toHaveProperty('textContent', 'Invalid encoding');
  await user.click(within(row).getByRole('button', { name: 'Process again' }));
  expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/reprocess', { method: 'POST' });
});

it('rejects drop uploads when readonly and preserves drag upload for editors', async () => {
  mount(false);
  await screen.findByText(i18n.t('knowledge.noDocuments'));
  expect(screen.queryByRole('button', { name: 'Upload documents' })).toBeNull();
  fireEvent.drop(screen.getByRole('table').parentElement!.parentElement!.parentElement!, { dataTransfer: { files: [new File(['text'], 'readonly.md')], types: ['Files'] } });
  expect(apiFetch).not.toHaveBeenCalled();
  cleanup();
  mount();
  await screen.findByText(i18n.t('knowledge.noDocuments'));
  fireEvent.drop(screen.getByRole('table').parentElement!.parentElement!.parentElement!, { dataTransfer: { files: [new File(['text'], 'drop.md')], types: ['Files'] } });
  expect(await screen.findByRole('dialog')).toBeTruthy();
  expect(apiFetch).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('button', { name: 'Upload 1 files' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledTimes(1));
});
