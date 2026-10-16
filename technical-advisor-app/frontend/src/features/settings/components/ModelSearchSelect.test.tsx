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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ModelSearchSelect } from './ModelSearchSelect';
import { ApiError } from '@/lib/apiClient';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);
function mount(freeText = false, load = vi.fn().mockResolvedValue([{ value: 'deepseek', label: 'DeepSeek' }, { value: 'openai', label: 'OpenAI' }])) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onChange = vi.fn();
  const view = render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}>
    <ModelSearchSelect id="choice" label="Choice" value="" scope="test" freeText={freeText} load={load} onChange={onChange} />
  </QueryClientProvider></I18nextProvider>);
  return { ...view, client, load, onChange };
}
describe('server searched model inputs', () => {
  it('loads on focus, sends typed queries to the server and renders server results without local filtering', async () => {
    const { load, onChange } = mount();
    const input = screen.getByRole('combobox', { name: 'Choice' });
    fireEvent.focus(input);
    await screen.findByRole('option', { name: 'DeepSeek' });
    expect(load).toHaveBeenCalledWith('', expect.any(AbortSignal), 1);
    fireEvent.change(input, { target: { value: 'glm' } });
    await waitFor(() => expect(load).toHaveBeenCalledWith('glm', expect.any(AbortSignal), 1));
    expect(await screen.findByRole('option', { name: 'DeepSeek' })).toBeTruthy();
    fireEvent.click(screen.getByRole('option', { name: 'DeepSeek' }));
    expect(onChange).toHaveBeenCalledWith('deepseek');
  });
  it('allows a custom model ID and keyboard selection without a native datalist', async () => {
    const { container, onChange } = mount(true);
    const input = screen.getByRole('combobox');
    fireEvent.focus(input);
    await screen.findByRole('option', { name: 'DeepSeek' });
    fireEvent.change(input, { target: { value: 'my-private-model' } });
    expect(onChange).toHaveBeenCalledWith('my-private-model');
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenLastCalledWith('deepseek');
    expect(container.querySelector('datalist')).toBeNull();
  });
  it('shows errors and retries without discarding typed input', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue([{ value: 'ok', label: 'Available' }]);
    mount(true, load);
    const input = screen.getByRole('combobox');
    fireEvent.focus(input);
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('option', { name: 'Available' })).toBeTruthy();
  });
  it('labels preset suggestions and limited server results rather than presenting a complete remote list', async () => {
    mount(true, vi.fn().mockResolvedValue({ items: [{ value: 'one', label: 'one' }], total: 80, source: 'preset', complete: false, hasMore: true }));
    fireEvent.focus(screen.getByRole('combobox'));
    expect(await screen.findByText('Built-in suggestions, not your account’s model list.')).toBeTruthy();
    expect(screen.getByText('Showing 1 of 80 matches. Type to narrow the search.')).toBeTruthy();
  });
  it('loads another server page on scroll and restarts at page one for a new search', async () => {
    const load = vi.fn().mockImplementation(async (query: string, _signal: AbortSignal, page: number) => ({
      items: [{ value: `${query || 'all'}-${page}`, label: `${query || 'all'} page ${page}` }],
      total: query ? 1 : 3, hasMore: !query && page < 3, source: 'discovered', complete: true,
    }));
    const { onChange } = mount(true, load);
    const input = screen.getByRole('combobox');
    fireEvent.focus(input);
    await screen.findByRole('option', { name: 'all page 1' });
    fireEvent.scroll(screen.getByRole('listbox'));
    await screen.findByRole('option', { name: 'all page 2' });
    expect(screen.getByRole('option', { name: 'all page 1' })).toBeTruthy();
    fireEvent.scroll(screen.getByRole('listbox'));
    await screen.findByRole('option', { name: 'all page 3' });
    fireEvent.click(screen.getByRole('option', { name: 'all page 3' }));
    expect(onChange).toHaveBeenCalledWith('all-3');
    fireEvent.click(input);
    fireEvent.change(input, { target: { value: 'specific' } });
    await screen.findByRole('option', { name: 'specific page 1' });
    expect(load).toHaveBeenCalledWith('specific', expect.any(AbortSignal), 1);
    expect(screen.queryByRole('option', { name: 'all page 3' })).toBeNull();
  });
  it('pins page requests to one snapshot and refreshes from page one if it expires', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce({ items: [{ value: 'old', label: 'Old first page' }], total: 50, hasMore: true, snapshot: 'snapshot-1' })
      .mockRejectedValueOnce(new ApiError(409, '{"error":"model_options_changed"}'))
      .mockResolvedValueOnce({ items: [{ value: 'new', label: 'New first page' }], total: 1, hasMore: false, snapshot: 'snapshot-2' });
    mount(true, load);
    fireEvent.focus(screen.getByRole('combobox'));
    await screen.findByRole('option', { name: 'Old first page' });
    fireEvent.scroll(screen.getByRole('listbox'));
    await screen.findByRole('button', { name: 'Retry' });
    expect(load).toHaveBeenNthCalledWith(2, '', expect.any(AbortSignal), 2, 'snapshot-1');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('option', { name: 'New first page' });
    expect(screen.queryByRole('option', { name: 'Old first page' })).toBeNull();
    expect(load).toHaveBeenNthCalledWith(3, '', expect.any(AbortSignal), 1);
  });
  it('keeps keyboard navigation visible in a long list', async () => {
    const scrollIntoView = vi.fn();
    const previous = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    try {
      mount(false, vi.fn().mockResolvedValue(Array.from({ length: 20 }, (_, i) => ({ value: String(i), label: `Provider ${i}` }))));
      const input = screen.getByRole('combobox');
      fireEvent.focus(input);
      await screen.findByRole('option', { name: 'Provider 0' });
      fireEvent.keyDown(input, { key: 'ArrowUp' });
      expect(input.getAttribute('aria-activedescendant')).toBe(screen.getByRole('option', { name: 'Provider 19' }).id);
      fireEvent.keyDown(input, { key: 'ArrowDown' });
      for (let i = 0; i < 11; i++) fireEvent.keyDown(input, { key: 'ArrowDown' });
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
      expect(scrollIntoView.mock.instances[scrollIntoView.mock.instances.length - 1]).toBe(screen.getByRole('option', { name: 'Provider 11' }));
    } finally { HTMLElement.prototype.scrollIntoView = previous; }
  });
});
