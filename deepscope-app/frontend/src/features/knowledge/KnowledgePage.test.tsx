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
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import userEvent from '@testing-library/user-event';
import { api, apiFetch } from '@/lib/apiClient';
import KnowledgePage from './KnowledgePage';

vi.mock('@/lib/apiClient', () => {
  class MockApiError extends Error {
    status: number;
    body: string;
    constructor(status: number, body: string) {
      super(body);
      this.status = status;
      this.body = body;
    }
  }

  return {
    api: {
      get: vi.fn(),
      post: vi.fn(),
    },
    apiFetch: vi.fn(),
    ApiError: MockApiError,
  };
});

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'zh-CN',
  fallbackLng: 'zh-CN',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

function renderKnowledge(initialPath = '/knowledge') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const [pathname, search] = initialPath.split('?');
  const entry = search ? { pathname, search: `?${search}` } : pathname;

  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path="/knowledge" element={<KnowledgePage />} />
            <Route path="/knowledge/:libraryId" element={<KnowledgePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe('KnowledgePage', () => {
  it('uploads deletes and uploads the same file again without leaving the library', async () => {
    let documents: Array<{ documentId: string; libraryId: string; filename: string; sizeBytes: number; status: string; createdAt: number }> = [];
    let uploaded = 0;
    vi.mocked(api.get).mockImplementation(async url => {
      if (url.includes('/documents?')) return { items: documents, page: 1, size: 10, total: documents.length, totalPages: 1, parsingCount: 0 };
      return { libraryId: 'repeat', name: 'Repeat upload', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: documents.length, parsingCount: 0, canEdit: true, documents: [] };
    });
    vi.mocked(apiFetch).mockImplementation(async (url, init) => {
      if (init?.method === 'DELETE') { documents = []; return undefined; }
      if (init?.method === 'POST' && String(url).endsWith('/documents')) {
        uploaded++;
        documents = [{ documentId: `doc-${uploaded}`, libraryId: 'repeat', filename: 'same.md', sizeBytes: 4, status: 'ready', createdAt: Date.now() }];
        return documents[0];
      }
      return undefined;
    });
    renderKnowledge('/knowledge/repeat');
    const user = userEvent.setup();
    const file = new File(['text'], 'same.md', { type: 'text/markdown' });
    for (let iteration = 0; iteration < 2; iteration++) {
      fireEvent.click(await screen.findByRole('button', { name: '上传文档' }));
      const area = await screen.findByRole('region', { name: '文档上传区域' });
      const picker = screen.getByLabelText('选择文档') as HTMLInputElement;
      const select = vi.spyOn(picker, 'click');
      await user.click(area);
      expect(select).toHaveBeenCalled();
      fireEvent.change(picker, { target: { files: [file] } });
      await user.click(await screen.findByRole('button', { name: '上传 1 个文件' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      const row = await screen.findByRole('row', { name: /same.md/ });
      if (iteration === 0) {
        await user.click(within(row).getByRole('button', { name: '删除' }));
        const dialog = await screen.findByRole('dialog');
        await user.click(within(dialog).getByRole('button', { name: '删除' }));
        await waitFor(() => expect(screen.queryByRole('row', { name: /same.md/ })).toBeNull());
      }
      select.mockRestore();
    }
    expect(uploaded).toBe(2);
  });
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
    vi.mocked(apiFetch).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders library list with DataTable and filters by search query', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url.startsWith('/api/advisor/knowledge?')) {
        const items = [
          {
            libraryId: 'lib-1',
            name: '智能客服 FAQ',
            description: '高频客户咨询标准化回答',
            chunkSize: 256,
            overlap: 30,
            recallLimit: 3,
            scoreThreshold: 0.4,
            readyCount: 12,
            canEdit: true,
          },
          {
            libraryId: 'lib-2',
            name: '架构规范文档',
            description: '系统接口与数据安全设计规范',
            chunkSize: 512,
            overlap: 50,
            recallLimit: 5,
            scoreThreshold: 0.2,
            readyCount: 5,
            canEdit: true,
          },
        ];
        const q = new URL(url, 'http://localhost').searchParams.get('q') ?? '';
        const matches = items.filter(item => `${item.name} ${item.description}`.includes(q));
        return { items: matches, page: 1, size: 10, total: matches.length, totalPages: 1 };
      }
      return [];
    });

    renderKnowledge();

    await waitFor(() => {
      expect(screen.getByText('智能客服 FAQ')).toBeTruthy();
      expect(screen.getByText('架构规范文档')).toBeTruthy();
    });

    expect(screen.getByText(/精准问答/)).toBeTruthy();
    expect(screen.getByText(/通用平衡/)).toBeTruthy();

    // 搜索过滤
    const searchInput = screen.getByRole('searchbox');
    fireEvent.change(searchInput, { target: { value: '接口' } });

    expect(screen.getByText('智能客服 FAQ')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /^搜索$/ }));
    await waitFor(() => expect(screen.queryByText('智能客服 FAQ')).toBeNull());
    expect(await screen.findByText('架构规范文档')).toBeTruthy();
    fireEvent.change(searchInput, { target: { value: '' } });
    expect(screen.queryByText('智能客服 FAQ')).toBeNull();
    fireEvent.submit(screen.getByRole('search'));
    expect(await screen.findByText('智能客服 FAQ')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: /^知识库$/ })).toBeNull();
    const table = screen.getByRole('table');
    expect(within(table).getByRole('link', { name: '智能客服 FAQ' }).closest('td')?.querySelector('svg')).toBeNull();
  });

  it('triggers delete confirmation dialog and calls delete mutation', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url.startsWith('/api/advisor/knowledge?')) {
        return { items: [
          {
            libraryId: 'lib-del',
            name: '待删除知识库',
            description: '用于测试删除',
            chunkSize: 512,
            overlap: 50,
            recallLimit: 5,
            scoreThreshold: 0.2,
            readyCount: 2,
            canEdit: true,
          },
        ], page: 1, size: 10, total: 1, totalPages: 1 };
      }
      return [];
    });
    vi.mocked(apiFetch).mockResolvedValue(undefined);

    renderKnowledge();

    await waitFor(() => {
      expect(screen.getByText('待删除知识库')).toBeTruthy();
    });

    const deleteBtn = screen.getByLabelText('删除知识库');
    fireEvent.click(deleteBtn);

    // 校验弹窗已呼出
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '删除知识库' })).toBeTruthy();
      expect(screen.getByText(/这将永久删除知识库“待删除知识库”/)).toBeTruthy();
    });

    const confirmBtn = screen.getByRole('button', { name: '删除' });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        '/api/advisor/knowledge/lib-del',
        expect.objectContaining({ method: 'DELETE' }),
      );
    });
  });

  it('renders library detail page with three tabs and allows switching', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url.endsWith('/embedding-models')) return [];
      if (url.startsWith('/api/advisor/knowledge/lib-detail')) {
        const library = {
          libraryId: 'lib-detail',
          name: '微服务架构知识库',
          description: '核心技术文档沉淀',
          chunkSize: 512,
          overlap: 50,
          recallLimit: 5,
          scoreThreshold: 0.2,
          readyCount: 2,
          canEdit: true,
          documents: [
            {
              documentId: 'doc-1',
              filename: 'api-gateway.md',
              sizeBytes: 10240,
              status: 'ready',
              failure: '',
              createdAt: Date.now() - 60000,
            },
            {
              documentId: 'doc-2',
              filename: 'error-handling.pdf',
              sizeBytes: 204800,
              status: 'failed',
              failure: 'PDF 提取错误：文本为空',
              createdAt: Date.now() - 120000,
            },
          ],
        };
        if (url.includes('/documents?')) return { items: library.documents, page: 1, size: 10, total: 2, totalPages: 1 };
        return { ...library, documents: [] };
      }
      return [];
    });

    renderKnowledge('/knowledge/lib-detail');

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: '文档' })).toBeTruthy();
    });

    // 校验三大 Tab
    expect(screen.getByRole('tab', { name: '文档' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '微服务架构知识库' })).toBeNull();
    expect(screen.getByRole('tab', { name: '检索测试' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: '设置' })).toBeTruthy();

    // 默认文档列表可见
    expect(await screen.findByText('api-gateway.md')).toBeTruthy();
    expect(screen.getByText('error-handling.pdf')).toBeTruthy();

    const user = userEvent.setup();

    // 切换到命中测试 Tab
    await user.click(screen.getByRole('tab', { name: '检索测试' }));

    await waitFor(() => {
      expect(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容')).toBeTruthy();
    });

    // 切换到配置 Tab
    await user.click(screen.getByRole('tab', { name: '设置' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '处理方案' })).toBeTruthy();
      expect(screen.getByRole('button', { name: '展开' })).toBeTruthy();
      expect(screen.queryByRole('spinbutton')).toBeNull();
    });
  });

  it('refreshes lightweight metadata while waiting on the retrieval tab for documents to finish parsing', async () => {
    const summary = { libraryId: 'lib-pending', name: '解析中知识库', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, canEdit: true, documents: [] };
    vi.mocked(api.get)
      .mockResolvedValueOnce({ ...summary, readyCount: 0, parsingCount: 1 })
      .mockResolvedValue({ ...summary, readyCount: 1, parsingCount: 0 });
    renderKnowledge('/knowledge/lib-pending?tab=search');
    const search = await screen.findByRole('button', { name: '检索' });
    fireEvent.change(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容'), { target: { value: '架构' } });
    expect((search as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect((search as HTMLButtonElement).disabled).toBe(false), { timeout: 3500 });
    expect(api.get).toHaveBeenCalledTimes(2);
    for (const [path] of vi.mocked(api.get).mock.calls) {
      expect(path).toBe('/api/advisor/knowledge/lib-pending?includeDocuments=false');
    }
  });

  it('uploads directly from an empty retrieval tab without navigating to documents', async () => {
    vi.mocked(api.get).mockResolvedValue({ libraryId: 'lib-upload', name: 'Empty', description: '', chunkSize: 512, overlap: 50, recallLimit: 5, scoreThreshold: 0.2, readyCount: 0, parsingCount: 0, canEdit: true, documents: [] });
    vi.mocked(apiFetch).mockResolvedValue(undefined);
    renderKnowledge('/knowledge/lib-upload?tab=search');
    await screen.findByText('请先上传文档。');
    fireEvent.click(screen.getByRole('button', { name: '上传文档' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    const chooser = screen.getByLabelText('选择文档') as HTMLInputElement;
    const select = vi.spyOn(chooser, 'click');
    expect(select).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '拖入文件，或点击选择' }));
    expect(select).toHaveBeenCalledOnce();
    fireEvent.change(chooser, { target: { files: [new File(['text'], 'notes.md')] } });
    expect(apiFetch).not.toHaveBeenCalledWith('/api/advisor/knowledge/lib-upload/documents', expect.anything());
    fireEvent.click(await screen.findByRole('button', { name: '上传 1 个文件' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib-upload/documents', expect.objectContaining({ method: 'POST', body: expect.any(FormData) })));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.includes('/documents?'))).toBe(false);
  });

  it('runs retrieval search in test bench and renders passage card with score', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url.startsWith('/api/advisor/knowledge/lib-bench')) {
        return {
          libraryId: 'lib-bench',
          name: '测试命中库',
          description: '',
          chunkSize: 512,
          overlap: 50,
          recallLimit: 5,
          scoreThreshold: 0.2,
          readyCount: 1,
          canEdit: true,
          documents: [
            {
              documentId: 'doc-1',
              filename: 'auth.md',
              sizeBytes: 4096,
              status: 'ready',
              failure: '',
              createdAt: Date.now(),
            },
          ],
        };
      }
      return [];
    });

    vi.mocked(api.post).mockResolvedValueOnce([
      {
        library: '测试命中库',
        document: 'auth.md',
        passage: '用户登录后获得 Bearer Token 进行网关鉴权认证。',
        score: 0.88,
      },
    ]);

    renderKnowledge('/knowledge/lib-bench?tab=search');

    await waitFor(() => {
      expect(screen.getByPlaceholderText('输入检索关键词或描述要查找的内容')).toBeTruthy();
    });

    const searchInput = screen.getByPlaceholderText('输入检索关键词或描述要查找的内容');
    fireEvent.change(searchInput, { target: { value: 'Token 鉴权' } });

    const searchBtn = screen.getByRole('button', { name: '检索' });
    fireEvent.click(searchBtn);

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/api/advisor/knowledge/lib-bench/search',
        { query: 'Token 鉴权' },
      );
    });

    await waitFor(() => {
      expect(screen.getByText('0.88')).toBeTruthy();
      expect(screen.getByText(/用户登录后获得/)).toBeTruthy();
      expect(screen.getByText('Token')).toBeTruthy();
      expect(screen.queryByRole('button', { name: '复制' })).toBeNull();
    });
  });
});
