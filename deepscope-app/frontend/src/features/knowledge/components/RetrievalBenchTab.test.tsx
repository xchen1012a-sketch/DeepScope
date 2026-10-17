// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { api } from '@/lib/apiClient';
import { RetrievalBenchTab } from './RetrievalBenchTab';
import type { SearchHit } from '../types';

vi.mock('@/lib/apiClient', () => ({ api: { post: vi.fn() }, ApiError: class extends Error {} }));

const i18n = createInstance();
void i18n.use(initReactI18next).init({ lng: 'zh-CN', resources: {}, react: { useSuspense: false } });

function setup(props: Partial<React.ComponentProps<typeof RetrievalBenchTab>> = {}) {
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
        <RetrievalBenchTab libraryId="library one" readyCount={1} scoreThreshold={0.2} {...props} />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

function submit(query = 'Token') {
  fireEvent.change(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容'), { target: { value: query } });
  fireEvent.submit(screen.getByRole('form', { name: '检索测试' }));
}

describe('RetrievalBenchTab', () => {
  beforeEach(() => vi.resetAllMocks());
  afterEach(cleanup);

  it('submits the form and blocks duplicate requests while pending', async () => {
    let resolve!: (hits: SearchHit[]) => void;
    vi.mocked(api.post).mockReturnValue(new Promise<SearchHit[]>(r => { resolve = r; }));
    setup();
    submit(' Token ');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/advisor/knowledge/library%20one/search', { query: 'Token' }));
    expect((screen.getByRole('button', { name: '测试中…' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.submit(screen.getByRole('form', { name: '检索测试' }));
    expect(api.post).toHaveBeenCalledTimes(1);
    await act(async () => resolve([]));
    expect(await screen.findByText('未召回相关片段，试试调整检索词或检索设置。')).toBeTruthy();
  });

  it('offers direct upload and cannot test when no document is ready', () => {
    const onUpload = vi.fn();
    setup({ readyCount: 0, onUpload });
    expect(screen.getByText('请先上传文档。')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '上传文档' }));
    expect(onUpload).toHaveBeenCalledTimes(1);
    submit();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('submits the same test action by pressing Enter', async () => {
    vi.mocked(api.post).mockResolvedValue([]);
    setup();
    await userEvent.setup().type(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容'), 'Token{Enter}');
    expect(await screen.findByText('未召回相关片段，试试调整检索词或检索设置。')).toBeTruthy();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/api/advisor/knowledge/library%20one/search', { query: 'Token' });
  });

  it('shows raw similarity and highlights the submitted query while editing a new draft', async () => {
    vi.mocked(api.post).mockResolvedValue([{ document: 'auth.md', passage: 'Token authentication', score: 0.876 }]);
    setup();
    submit();
    expect(await screen.findByText('0.88')).toBeTruthy();
    expect(screen.getByText('auth.md')).toBeTruthy();
    expect(screen.getByText(/召回 1 个片段/)).toBeTruthy();
    fireEvent.change(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容'), { target: { value: 'authentication' } });
    expect(screen.getByText('Token').tagName).toBe('MARK');
    expect(screen.queryByText('高匹配')).toBeNull();
    expect(screen.queryByText(/%/)).toBeNull();
  });

  it('omits bulk toggles, separate expand actions and passage copy controls', async () => {
    vi.mocked(api.post).mockResolvedValue([{ document: 'auth.md', passage: 'Token authentication', score: 0.88 }]);
    setup();
    submit();
    await screen.findByText('auth.md');
    for (const name of ['复制', '全部展开', '全部收起', '展开片段']) expect(screen.queryByRole('button', { name })).toBeNull();
  });

  it('reports search failure without rendering a decorative initial empty state', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('offline'));
    setup();
    expect(screen.queryByRole('heading')).toBeNull();
    submit();
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', '检索失败，请重试。');
  });

  it('collapses results by default, renders a real table on expansion, and preserves raw source and copy', async () => {
    const passage = '# 审批规则\n\n| 金额 | 批准人 |\n| --- | --- |\n| 30000 | 财务 |';
    vi.mocked(api.post).mockResolvedValue([{ document: 'rules.md', passage, score: .0325, rankingMethod: 'rrf' }]);
    setup(); submit('30000');
    await screen.findByText('rules.md');
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByText('审批规则')).toBeTruthy();
    expect(screen.queryByText(/\| --- \|/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /rules.md/ }));
    expect(screen.getByRole('table')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '原始文本' }));
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByTestId('raw-passage').textContent).toBe(passage);
    fireEvent.click(screen.getByRole('button', { name: /rules.md/ }));
    expect(screen.queryByTestId('raw-passage')).toBeNull();
  });

  it('keeps only the selected result open and resets expansion after a new search', async () => {
    vi.mocked(api.post).mockResolvedValue([
      { document: 'one.md', passage: '# First heading\n\nToken evidence', score: .03 },
      { document: 'two.md', passage: '# Second heading\n\nToken evidence', score: .02 },
    ]);
    setup(); submit();
    await screen.findByText('one.md');
    fireEvent.click(screen.getByRole('button', { name: /one.md/ }));
    expect(screen.getByRole('heading', { name: 'First heading' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /two.md/ }));
    expect(screen.queryByRole('heading', { name: 'First heading' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Second heading' })).toBeTruthy();
    submit('next query');
    await waitFor(() => expect(screen.queryAllByRole('heading')).toHaveLength(0));
  });
});
