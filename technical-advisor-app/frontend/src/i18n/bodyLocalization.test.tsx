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

import { describe, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { i18nResources, DEFAULT_LANGUAGE } from './resources';
import HomePage from '@/pages/HomePage';
import LoginPage from '@/pages/LoginPage';
import ChatPage from '@/features/chat/ChatPage';
import AgentPage from '@/features/agent/AgentPage';
import AgentDetailPage from '@/features/agent/AgentDetailPage';
import ComingSoonPage from '@/pages/ComingSoonPage';

// 会话页的服务端状态全部走真实接口；在 SSR 渲染测试里以空数据替代网络层。
vi.mock('@/api/auth', () => ({ can: () => false, getUsername: () => 'admin' }));

vi.mock('@/api/advisor', () => ({
  advisorKeys: {
    commands: ['advisor', 'commands'],
    modelActivation: ['advisor', 'model-current'],
    sessions: ['advisor', 'sessions'],
    session: (id: string) => ['advisor', 'sessions', id],
    timelinePage: (id: string, page: number, size: number) => ['advisor', 'sessions', id, 'timeline', page, size],
    subagentsPage: (id: string, page: number, size: number) => ['advisor', 'sessions', id, 'subagents', page, size],
  },
  listSessions: async () => [],
  fetchCommands: async () => [],
  createSession: async () => ({ sessionId: 's1', title: null, status: 'idle', createdAt: 0, updatedAt: 0 }),
  renameSession: async () => ({ sessionId: 's1', title: '', status: 'idle', createdAt: 0, updatedAt: 0 }),
  deleteSession: async () => undefined,
  getSessionDetail: async () => ({ session: { sessionId: 's1', title: null, status: 'idle', createdAt: 0, updatedAt: 0 }, entries: [], deliverables: [] }),
  getSessionTimelinePage: async () => ({ items: [], page: 1, size: 10, total: 0, totalPages: 1, hasMore: false }),
  getSessionSubagentsPage: async () => ({ items: [], page: 1, size: 10, total: 0, totalPages: 1, hasMore: false }),
  postSessionMessage: async () => ({ seq: 1, turn: 1, kind: 'user_message', payload: {} }),
  uploadAttachment: async () => ({ id: 'att1', name: 'test.md', size: 12, kind: 'document', mimeType: 'text/markdown' }),
  uploadAttachmentByPath: async () => ({ id: 'att1', name: 'test.md', size: 12, kind: 'document', mimeType: 'text/markdown' }),
  cacheAttachmentPreview: () => undefined,
  getCachedAttachmentPreview: () => undefined,
  fetchAttachmentPreviewUrl: async () => null,
  ACCEPTED_ATTACHMENT_EXTENSIONS: '.md,.pdf,.zip,.png',
  payloadText: (payload: Record<string, unknown>, key: string) => (typeof payload[key] === 'string' ? (payload[key] as string) : ''),
  payloadList: <T,>(payload: Record<string, unknown>, key: string) => (Array.isArray(payload[key]) ? (payload[key] as T[]) : []),
}));

// 智能体模块同理：SSR 渲染断言只覆盖静态骨架，接口层以空数据替代。
vi.mock('@/api/agent', () => {
  const emptyDetail = {
    agentId: 'a1',
    name: 'A',
    description: '',
    origin: 'personal',
    mine: true,
    canEdit: true,
    counts: { skills: 0, subagents: 0 },
    sections: { skills: [], tools: [], subagents: [], knowledge: [] },
  };
  return {
    listAvailableAgents: async () => [],
    agentKeys: {
      agents: ['advisor', 'agents'],
      agent: (id: string) => ['advisor', 'agents', id],
      tree: (id: string) => ['advisor', 'agents', id, 'tree'],
      file: (id: string, path: string) => ['advisor', 'agents', id, 'files', path],
      subagentTemplate: (id: string) => ['advisor', 'agents', id, 'subagents', 'template'],
    },
    listAgents: async () => [],
    createAgent: async () => emptyDetail,
    getAgent: async () => emptyDetail,
    updateAgent: async () => emptyDetail,
    deleteAgent: async () => undefined,
    getAgentTree: async () => [],
    readAgentFile: async () => ({ content: '' }),
    saveAgentFile: async () => undefined,
    createAgentFile: async () => undefined,
    deleteAgentFile: async () => undefined,
    getSubagentTemplate: async () => ({ content: '# template' }),
    routeSession: async () => ({ agentId: 'technical-advisor', name: '技术参谋', reason: '' }),
    resolveProposal: async () => undefined,
  };
});

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = join(dir, entry.name);
    return entry.isDirectory() ? sourceFiles(file) : /\.tsx?$/.test(file) && !file.includes('.test.') ? [file] : [];
  });
}

async function render(element: React.ReactNode, language: string, location = '/') {
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({ lng: language, fallbackLng: 'en', defaultNS: 'app', resources: i18nResources, interpolation: { escapeValue: false } });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return renderToStaticMarkup(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <StaticRouter location={location}>{element}</StaticRouter>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe('body localization', () => {
  it('resolves every literal translation key used by the frontend in both languages', async () => {
    const keys = sourceFiles(join(process.cwd(), 'src')).flatMap(file => [...readFileSync(file, 'utf8').matchAll(/\b(?:t|translate|i18next\.t)\(\s*['"]([^'"]+)['"]/g)].map(match => match[1]));
    for (const language of ['zh-CN', 'en']) {
      const i18n = createInstance();
      await i18n.init({ lng: language, fallbackLng: false, defaultNS: 'app', resources: i18nResources });
      const missing = [...new Set(keys)].filter(key => !i18n.exists(key) && !i18n.exists(key, { ns: 'common' }));
      expect(missing, `${language} missing translations`).toEqual([]);
    }
  });

  it('renders the start page in Chinese by default and retains English', async () => {
    expect(DEFAULT_LANGUAGE).toBe('zh-CN');
    const zh = await render(<HomePage />, DEFAULT_LANGUAGE);
    expect(zh).toContain('它自己决定该做什么');
    expect(zh).toContain('粘贴仓库地址、描述需求，或直接提问');
    const en = await render(<HomePage />, 'en');
    expect(en).toContain('decides what to do');
    expect(en).toContain('Paste a repository URL');
  });

  it('renders the login form in both languages', async () => {
    const zh = await render(<LoginPage />, 'zh-CN');
    expect(zh).toContain('欢迎回来，继续探索');
    const en = await render(<LoginPage />, 'en');
    expect(en).toContain('Welcome back, keep exploring');
  });

  it('shows the prefilled question from the start page on the chat entry state', async () => {
    const question = '尽调这个仓库：https://github.com/spring-projects/spring-petclinic';
    const html = await render(<ChatPage />, 'zh-CN', `/chat?q=${encodeURIComponent(question)}`);
    expect(html).toContain(question);
    expect(html).not.toContain('备好的问题');
    expect(html).toContain('与 DeepScope 协作');
    const empty = await render(<ChatPage />, 'zh-CN', '/chat');
    expect(empty).not.toContain(question);
  });

  it('renders coming-soon placeholders for later-stage modules', async () => {
    const html = await render(<ComingSoonPage titleKey="nav.skills" />, 'zh-CN');
    expect(html).toContain('技能管理');
    expect(html).toContain('模块建设中');
  });

  it('renders the agent list page in both languages', async () => {
    const zh = await render(<AgentPage />, 'zh-CN', '/agent');
    expect(zh).toContain('新建主智能体');
    const en = await render(<AgentPage />, 'en', '/agent');
    expect(en).toContain('New primary agent');
  });

  it('renders the agent detail page entry state in both languages', async () => {
    const zh = await render(<AgentDetailPage />, 'zh-CN', '/agent/technical-advisor');
    expect(zh).toContain('选择一个智能体');
    const en = await render(<AgentDetailPage />, 'en', '/agent/technical-advisor');
    expect(en).toContain('Select an agent');
  });
});
