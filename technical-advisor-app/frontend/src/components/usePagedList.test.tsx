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
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { api } from '@/lib/apiClient';
import { usePagedList } from './usePagedList';

vi.mock('@/lib/apiClient', () => ({ api: { get: vi.fn() } }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('loads only a page and sends keyword, status, page and size to the server', async () => {
  vi.mocked(api.get).mockImplementation(async path => {
    const url = new URL(path, 'https://test.local');
    const page = Number(url.searchParams.get('page'));
    return { items: [{ name: `server-page-${page}` }], page, size: Number(url.searchParams.get('size')), total: 37, totalPages: 4 };
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['items'], ['selector-cache']);
  const { result } = renderHook(() => usePagedList<{ name: string }>(['items'], '/api/items'), {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
  await waitFor(() => expect(result.current.data?.items[0].name).toBe('server-page-1'));
  expect(api.get).toHaveBeenLastCalledWith('/api/items?page=1&size=10');
  act(() => result.current.setPage(2));
  await waitFor(() => expect(result.current.data?.items[0].name).toBe('server-page-2'));
  expect(api.get).toHaveBeenLastCalledWith('/api/items?page=2&size=10');
  act(() => result.current.submit('  query%_  '));
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/items?page=1&size=10&q=query%25_'));
  act(() => result.current.setFilters({ status: 'draft' }));
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/items?page=1&size=10&q=query%25_&status=draft'));
  const count = vi.mocked(api.get).mock.calls.length;
  act(() => result.current.submit('query%_'));
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(count + 1));
  act(() => result.current.setPageSize(20));
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/items?page=1&size=20&q=query%25_&status=draft'));
  act(() => result.current.reset());
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/items?page=1&size=20'));
  expect(client.getQueryData(['items'])).toEqual(['selector-cache']);
});

it('keeps selector context in every request and never overwrites the selector cache', async () => {
  vi.mocked(api.get).mockImplementation(async (path: string) => {
    const url = new URL(path, 'https://test.local');
    return { items: [{ name: `page-${url.searchParams.get('page')}` }], page: Number(url.searchParams.get('page')), size: 10, total: 2, totalPages: 2 };
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['advisor', 'skills'], ['selector-cache']);
  const { result } = renderHook(
    () => usePagedList<{ name: string }>(['advisor', 'skills'], '/api/advisor/skills', { extra: { purpose: 'selection', agentId: 'a1' } }),
    { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
  );
  await waitFor(() => expect(result.current.data?.items[0].name).toBe('page-1'));
  expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=1&size=10&purpose=selection&agentId=a1');
  act(() => result.current.setPage(2));
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=2&size=10&purpose=selection&agentId=a1'));
  act(() => result.current.submit('review'));
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=1&size=10&q=review&purpose=selection&agentId=a1'));
  act(() => result.current.reset());
  await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=1&size=10&purpose=selection&agentId=a1'));
  expect(client.getQueryData(['advisor', 'skills'])).toEqual(['selector-cache']);
});

it('does not fetch a disabled resource and resets filters atomically', async () => {
  vi.mocked(api.get).mockResolvedValue({ items: [], page: 1, size: 10, total: 0, totalPages: 1 });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { result, rerender } = renderHook(({ enabled }) => usePagedList(['models'], '/api/models', { filters: { kind: 'chat' }, enabled }), {
    initialProps: { enabled: false }, wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
  expect(api.get).not.toHaveBeenCalled();
  act(() => result.current.reset({ kind: 'embedding' }));
  rerender({ enabled: true });
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));
  expect(api.get).toHaveBeenLastCalledWith('/api/models?page=1&size=10&kind=embedding');
});
