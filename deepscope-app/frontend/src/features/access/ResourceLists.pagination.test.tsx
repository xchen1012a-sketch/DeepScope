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
import { MemoryRouter } from 'react-router-dom';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { createInstance } from 'i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { api, apiFetch } from '@/lib/apiClient';
import SkillsPage from '../skill/SkillsPage';
import { KnowledgeListPage } from '../knowledge/components/KnowledgeListPage';
import { DocumentManagerTab } from '../knowledge/components/DocumentManagerTab';
import UsersPage from './UsersPage';
import RolesPage from './RolesPage';
vi.mock('@/api/auth', () => ({ can: () => true }));

vi.mock('@/lib/apiClient', () => ({ api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn(), ApiError: class extends Error {} }));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'zh-CN', defaultNS: 'app', react: { useSuspense: false } });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('resource lists request server pages', () => {
  for (const [kind, Component] of [['skills', SkillsPage], ['knowledge', KnowledgeListPage], ['users', UsersPage], ['roles', RolesPage]] as const) {
    it(`${kind}: loads a bounded page, submits searches repeatedly and requests next page`, async () => {
      const requests: URL[] = [];
      vi.mocked(api.get).mockImplementation(async path => {
        const url = new URL(path, 'http://localhost');
        requests.push(url);
        if (url.pathname.endsWith('/roles') && !url.searchParams.has('page')) return [];
        const size = Number(url.searchParams.get('size'));
        return { items: [], page: Number(url.searchParams.get('page')), size, total: 21, totalPages: Math.ceil(21 / size) };
      });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><MemoryRouter><Component /></MemoryRouter></QueryClientProvider></I18nextProvider>);
      const matching = () => requests.filter(url => url.pathname === `/api/advisor/${kind}` && url.searchParams.has('page'));
      const latest = () => matching()[matching().length - 1];
      await waitFor(() => expect(matching().length).toBe(1));
      expect(matching()[0].searchParams.get('size')).toBe('10');
      const input = screen.queryByRole('searchbox') ?? screen.getAllByRole('textbox')[0];
      const search = screen.getByRole('button', { name: /^(搜索|查询)$/ });
      fireEvent.change(input, { target: { value: 'needle' } });
      expect(matching()).toHaveLength(1);
      fireEvent.click(search);
      await waitFor(() => expect(latest()?.searchParams.get('q')).toBe('needle'));
      const count = matching().length;
      fireEvent.click(search);
      await waitFor(() => expect(matching().length).toBe(count + 1));
      fireEvent.click(screen.getByRole('button', { name: '下一页' }));
      await waitFor(() => expect(latest()?.searchParams.get('page')).toBe('2'));
      expect(latest()?.searchParams.get('q')).toBe('needle');
      await waitFor(() => expect(screen.queryByTestId('data-table-loading')).toBeNull());
      const sizeSelect = screen.getByRole('button', { name: '10 条/页' });
      fireEvent.pointerDown(sizeSelect, { button: 0 });
      fireEvent.keyDown(sizeSelect, { key: 'ArrowDown' });
      fireEvent.click(await screen.findByRole('menuitem', { name: '20 条/页' }));
      await waitFor(() => expect(latest()?.searchParams.get('size')).toBe('20'));
      expect(latest()?.searchParams.get('page')).toBe('1');
      if (kind === 'users' || kind === 'roles') {
        const statusSelect = screen.getByRole('button', { name: '状态' });
        fireEvent.pointerDown(statusSelect, { button: 0 });
        fireEvent.keyDown(statusSelect, { key: 'ArrowDown' });
        fireEvent.click(await screen.findByRole('menuitem', { name: '停用' }));
        expect(latest()?.searchParams.get('status')).toBeNull();
        fireEvent.click(search);
        await waitFor(() => expect(latest()?.searchParams.get('status')).toBe('disabled'));
      }
      const beforeEnter = matching().length;
      fireEvent.submit(screen.getByRole('search'));
      await waitFor(() => expect(matching().length).toBe(beforeEnter + 1));
    });
  }

  it('documents use their own server page and search without loading library documents', async () => {
    vi.mocked(api.get).mockResolvedValue({ items: [], page: 1, size: 10, total: 21, totalPages: 3 });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><DocumentManagerTab library={{ libraryId: 'lib', name: 'Library', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: 0, canEdit: false, documents: [] }} onChanged={() => {}} /></QueryClientProvider></I18nextProvider>);
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(vi.mocked(api.get).mock.calls[0][0]).toContain('/knowledge/lib/documents?page=1&size=10');
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'manual' } });
    expect(api.get).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /^搜索$/ }));
    await waitFor(() => expect(vi.mocked(api.get).mock.lastCall?.[0]).toContain('q=manual'));
    const statusSelect = screen.getByRole('button', { name: '全部' });
    fireEvent.pointerDown(statusSelect, { button: 0 });
    fireEvent.keyDown(statusSelect, { key: 'ArrowDown' });
    fireEvent.click(await screen.findByRole('menuitem', { name: '已就绪' }));
    await waitFor(() => expect(vi.mocked(api.get).mock.lastCall?.[0]).toContain('status=ready'));
  });

  it('refreshes the current document query after reprocessing without dropping search conditions', async () => {
    const onChanged = vi.fn();
    vi.mocked(api.get).mockResolvedValue({ items: [{ documentId: 'doc', filename: 'manual.md', sizeBytes: 20, status: 'ready', failure: '', createdAt: 0 }], page: 1, size: 10, total: 1, totalPages: 1 });
    vi.mocked(apiFetch).mockResolvedValue(undefined);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><DocumentManagerTab library={{ libraryId: 'lib', name: 'Library', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: 1, canEdit: true, documents: [] }} onChanged={onChanged} /></QueryClientProvider></I18nextProvider>);
    await screen.findByText('manual.md');
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'manual' } });
    fireEvent.click(screen.getByRole('button', { name: '搜索' }));
    await waitFor(() => expect(vi.mocked(api.get).mock.lastCall?.[0]).toContain('q=manual'));
    await screen.findByText('manual.md');
    const previousRequests = vi.mocked(api.get).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: i18n.t('knowledge.reprocess') }));
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(previousRequests + 1));
    expect(vi.mocked(api.get).mock.lastCall?.[0]).toContain('q=manual');
    expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib/documents/doc/reprocess', { method: 'POST' });
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('polls while documents outside the current result are parsing and refreshes parent metadata on completion', async () => {
    const onChanged = vi.fn();
    vi.mocked(api.get)
      .mockResolvedValueOnce({ items: [], page: 1, size: 10, total: 0, totalPages: 0, parsingCount: 1 })
      .mockResolvedValueOnce({ items: [], page: 1, size: 10, total: 0, totalPages: 0, parsingCount: 1 })
      .mockResolvedValue({ items: [{ documentId: 'doc', filename: 'completed.md', sizeBytes: 20, status: 'ready', failure: '', createdAt: 0 }], page: 1, size: 10, total: 1, totalPages: 1, parsingCount: 0 });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><DocumentManagerTab library={{ libraryId: 'lib', name: 'Library', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: 0, canEdit: false, documents: [] }} onChanged={onChanged} /></QueryClientProvider></I18nextProvider>);
    await waitFor(() => expect(screen.queryByTestId('data-table-loading')).toBeNull());
    const statusSelect = screen.getByRole('button', { name: '全部' });
    fireEvent.pointerDown(statusSelect, { button: 0 });
    fireEvent.keyDown(statusSelect, { key: 'ArrowDown' });
    fireEvent.click(await screen.findByRole('menuitem', { name: '已就绪' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
    expect(onChanged).not.toHaveBeenCalled();
    expect(await screen.findByText('completed.md', {}, { timeout: 3500 })).toBeTruthy();
    await waitFor(() => expect(onChanged).toHaveBeenCalledOnce());
    expect(api.get).toHaveBeenCalledTimes(3);
    expect(vi.mocked(api.get).mock.calls[1][0]).toBe(vi.mocked(api.get).mock.calls[2][0]);
    expect(vi.mocked(api.get).mock.lastCall?.[0]).toContain('status=ready');
  });
});
