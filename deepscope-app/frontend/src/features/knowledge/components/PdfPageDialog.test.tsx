/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api, apiResponse } from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import { PdfPageDialog } from './PdfPageDialog';

vi.mock('@/lib/apiClient', () => ({ api: { get: vi.fn(), post: vi.fn() }, apiResponse: vi.fn() }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });

beforeEach(() => {
  vi.resetAllMocks();
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn().mockReturnValueOnce('blob:one').mockReturnValue('blob:two') });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
  vi.mocked(api.get).mockImplementation(async path => path.endsWith('/pdf-model') ? { configured: true, modelName: 'Configured vision model' } : ({ pageNumber: path.endsWith('/2') ? 2 : 1, pageLabel: null, width: 600, height: 800, status: 'ready', method: 'native', nativeText: '',
    warnings: [], blocks: [{ id: 'b0', type: 'paragraph', text: path.endsWith('/2') ? 'Second page evidence' : 'First page evidence', box: { x: .1, y: .2, width: .4, height: .1 }, interpretation: false }] }));
  vi.mocked(apiResponse).mockResolvedValue({ blob: async () => new Blob(['image']) } as Response);
});
afterEach(cleanup);

function mount() {
  return render(<I18nextProvider i18n={i18n}><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
    <PdfPageDialog preview={{ libraryId: 'lib', documentId: 'doc', filename: 'evidence.pdf', pageCount: 2 }} onClose={() => {}} />
  </QueryClientProvider></I18nextProvider>);
}

it('fetches each page from the server and releases authenticated image blobs on navigation and close', async () => {
  const view = mount();
  expect(await screen.findByText('First page evidence')).toBeTruthy();
  await waitFor(() => expect(screen.getByAltText('Original PDF page 1').getAttribute('src')).toBe('blob:one'));
  fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
  expect(await screen.findByText('Second page evidence')).toBeTruthy();
  await waitFor(() => expect(screen.getByAltText('Original PDF page 2').getAttribute('src')).toBe('blob:two'));
  expect(api.get).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/pages/2');
  expect(apiResponse).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/pages/2/image', expect.objectContaining({ signal: expect.anything() }));
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:one');
  expect(api.post).not.toHaveBeenCalled();
  view.unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:two');
});

it('reviews only the currently displayed page after an explicit request and reports failure', async () => {
  vi.mocked(api.post).mockRejectedValue(new Error('not configured'));
  mount();
  expect(await screen.findByText('First page evidence')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
  expect(await screen.findByText('Second page evidence')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Question about this page'), { target: { value: 'What amount?' } });
  fireEvent.click(screen.getByRole('button', { name: 'Review page with model' }));
  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/pages/2/verify', { query: 'What amount?' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
});

it('renders parsed table blocks as tables and lets users inspect the unchanged raw Markdown', async () => {
  const markdown = '| Item | Amount |\n| --- | --- |\n| Gateway | 30000 |';
  vi.mocked(api.get).mockImplementation(async path => path.endsWith('/pdf-model') ? { configured: false } : {
    pageNumber: 1, width: 600, height: 800, status: 'ready', method: 'vision', nativeText: '', warnings: [],
    blocks: [{ id: 'b0', type: 'table', text: markdown, interpretation: false }],
  });
  mount();
  expect(await screen.findByRole('table')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: /Locate on page/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Raw text' }));
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.getByText(markdown, { normalizer: value => value }).textContent).toBe(markdown);
  fireEvent.click(screen.getByRole('button', { name: 'Formatted' }));
  expect(screen.getByRole('table')).toBeTruthy();
});
