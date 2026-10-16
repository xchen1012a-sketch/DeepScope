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
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import * as agentApi from '@/api/agent';
import type { CatalogTool, McpServer } from '@/api/tool';
import type { SkillSummary } from '@/features/skill/SkillsPage';
import type { LibrarySummary } from '@/features/knowledge/types';
import { api, apiFetch } from '@/lib/apiClient';
import { BehaviorSection } from './BehaviorSection';
import { ToolSelectionSection } from './ToolSelectionSection';
import { SkillSelectionSection } from './SkillSelectionSection';
import { SubagentsSection } from './SubagentsSection';
import { CreationSettingsSection } from './CreationSettingsSection';
import { KnowledgeSelection } from '@/features/knowledge/KnowledgeSelection';
import { AgentEditProvider } from '../AgentEditProvider';

vi.mock('@/api/agent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/agent')>()),
  generateBehavior: vi.fn(),
  saveAgentFile: vi.fn(),
  createAgentFile: vi.fn(),
  deleteAgentFile: vi.fn(),
  getSubagentTemplate: vi.fn(),
  draftSubagent: vi.fn(),
  readAgentFile: vi.fn(),
}));

vi.mock('@/lib/apiClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/apiClient')>()),
  api: { get: vi.fn(), post: vi.fn() },
  apiFetch: vi.fn(),
}));

const toolRows: CatalogTool[] = [
  {
    toolId: 't1',
    name: 'Weather Lookup',
    description: 'Fetch current weather',
    inputSchema: '{}',
    origin: 'http',
    permission: 'allow',
    ownerUserId: 'u',
    mcpServerId: null,
    httpMethod: 'GET',
    httpUrl: 'https://api.test',
    approved: true,
    canEdit: true,
  },
];

const serverRows: McpServer[] = [
  {
    serverId: 's1',
    name: 'GitHub Server',
    command: 'npx -y github-mcp',
    url: null,
    transport: 'stdio',
    status: 'connected',
    statusMessage: '',
    ownerUserId: 'u1',
    hasSecrets: false,
    toolCount: 1,
    tools: ['gh_search'],
    toolSummaries: [{ toolId: 'm1', name: 'gh_search', description: 'Search issues', approved: true, permission: 'ask' }],
  },
];

const skillRows: SkillSummary[] = [
  {
    skillId: 'sk1',
    name: 'Code Reviewer',
    description: 'Automated pull request review guidelines',
    status: 'published',
    origin: 'personal',
    canEdit: true,
    agents: [],
  },
];

const libraryRows: LibrarySummary[] = [
  {
    libraryId: 'lib1',
    name: 'Architecture RFCs',
    description: 'System design standards and RFCs',
    chunkSize: 500,
    overlap: 50,
    recallLimit: 5,
    scoreThreshold: 0.7,
    readyCount: 12,
    canEdit: true,
  },
];

/** 服务端分页替身：只回当前页，并如实带上 q 过滤后的总数。 */
function pageOf<T extends { name: string; description?: string }>(rows: T[], url: URL) {
  const q = (url.searchParams.get('q') ?? '').toLowerCase();
  const page = Number(url.searchParams.get('page') ?? 1);
  const size = Number(url.searchParams.get('size') ?? 10);
  const matched = rows.filter((row) => `${row.name} ${row.description ?? ''}`.toLowerCase().includes(q));
  return {
    items: matched.slice((page - 1) * size, page * size),
    page,
    size,
    total: matched.length,
    totalPages: Math.max(1, Math.ceil(matched.length / size)),
  };
}

function renderWithProviders(ui: React.ReactNode, lng = 'zh-CN') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const i18n = createInstance();
  void i18n.use(initReactI18next).init({
    resources: i18nResources,
    lng,
    defaultNS: 'app',
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
  });

  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AgentEditProvider>{ui}</AgentEditProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.get).mockImplementation(async (path: string) => {
    const url = new URL(path, 'https://test.local');
    if (url.pathname === '/api/advisor/tools') return pageOf(toolRows, url);
    if (url.pathname === '/api/advisor/mcp-servers') return pageOf(serverRows, url);
    if (url.pathname === '/api/advisor/skills') return pageOf(skillRows, url);
    if (url.pathname === '/api/advisor/knowledge') return pageOf(libraryRows, url);
    if (url.pathname === '/api/advisor/agents/a1/tool-selection') return { toolIds: [], serverIds: [] };
    throw new Error(`Unexpected list request: ${path}`);
  });
  vi.mocked(apiFetch).mockImplementation(async (url: string) => {
    if (url.includes('skill-selection')) return { skillIds: [] };
    if (url.includes('knowledge-selection')) return { libraryIds: [] };
    if (url.includes('tool-selection')) return { toolIds: [], serverIds: [] };
    return {};
  });
  vi.mocked(agentApi.getSubagentTemplate).mockResolvedValue({ content: 'template content' });
  vi.mocked(agentApi.draftSubagent).mockResolvedValue({ content: '---\ndescription: 合同审查\n---\n\n# 角色定位\n你是合同审查子智能体。' });
});

afterEach(cleanup);

describe('Agent Management Optimization Points', () => {
  it('Point 1 & 2: BehaviorSection save button says "保存" and generate uses established dialog', async () => {
    const fakeQuery = {
      data: { content: 'Original content', path: 'AGENTS.md' },
      isPending: false,
      isError: false,
    } as unknown as ReturnType<typeof import('@tanstack/react-query').useQuery>;

    renderWithProviders(
      <BehaviorSection agentId="a1" canEdit={true} readOnlyReason="" fileQuery={fakeQuery as any} />,
      'zh-CN',
    );

    // Point 2: save button text is '保存'
    const saveButton = screen.getByRole('button', { name: '保存' });
    expect(saveButton).toBeTruthy();

    // Point 1: generate dialog uses established dialog component
    const generateBtn = screen.getByRole('button', { name: '根据简介生成' });
    fireEvent.click(generateBtn);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeTruthy();
    expect(screen.getByText('生成行为文档')).toBeTruthy();
    expect(screen.getByText('取消')).toBeTruthy();
  });

  it('Point 3: ToolSelectionSection keeps the 工具 / MCP tabs on the standard table', async () => {
    renderWithProviders(<ToolSelectionSection agentId="a1" canEdit={true} />, 'zh-CN');

    expect(screen.getByRole('button', { name: /工具/ })).toBeTruthy();
    const mcpTab = screen.getByRole('button', { name: /MCP/ });
    expect(mcpTab).toBeTruthy();

    const table = await screen.findByRole('table');
    expect(Array.from(table.querySelectorAll('th')).map((header) => header.textContent)).toEqual(['工具名称', '功能说明']);
    expect(await screen.findByText('Weather Lookup')).toBeTruthy();
    expect(screen.getByText('Fetch current weather')).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: 'Weather Lookup' })).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/tools?page=1&size=10&purpose=selection&agentId=a1&origin=http%2Cdeclared');

    fireEvent.click(mcpTab);
    expect(await screen.findByText('GitHub Server')).toBeTruthy();
    expect(screen.getByText('gh_search')).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: 'GitHub Server' })).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: 'gh_search' })).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/mcp-servers?page=1&size=10&purpose=selection&agentId=a1');
  });

  it('Point 4: SkillSelectionSection lists skills in the standard table and keeps 保存技能 below it', async () => {
    renderWithProviders(<SkillSelectionSection agentId="a1" canEdit={true} />, 'zh-CN');

    const table = await screen.findByRole('table');
    expect(Array.from(table.querySelectorAll('th')).map((header) => header.textContent)).toEqual(['技能名称', '用途说明']);
    expect(await screen.findByText('Code Reviewer')).toBeTruthy();
    expect(screen.getByText('Automated pull request review guidelines')).toBeTruthy();
    expect(screen.getByText('已发布')).toBeTruthy();
    expect(screen.getByRole('button', { name: '保存技能' })).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/skills?page=1&size=10&purpose=selection&agentId=a1');

    fireEvent.click(screen.getByRole('checkbox', { name: 'Code Reviewer' }));
    expect(await screen.findByText('已选用 1 项')).toBeTruthy();
  });

  it('Point 5: KnowledgeSelection lists libraries in the standard table and keeps 保存知识库 below it', async () => {
    renderWithProviders(<KnowledgeSelection agentId="a1" canEdit={true} />, 'zh-CN');

    const table = await screen.findByRole('table');
    expect(Array.from(table.querySelectorAll('th')).map((header) => header.textContent)).toEqual(['知识库名称与描述', '文档数量']);
    expect(await screen.findByText('Architecture RFCs')).toBeTruthy();
    expect(screen.getByText('已就绪 12 份')).toBeTruthy();
    expect(screen.getByRole('button', { name: '保存知识库' })).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/knowledge?page=1&size=10&purpose=selection&agentId=a1');
  });

  it('Point 6 & 7: SubagentsSection uses card form and opens a blank definition dialog', async () => {
    const subagents = [
      {
        name: 'Database Specialist',
        description: 'Analyzes SQL performance and migrations',
        file: 'subagents/database.md',
      },
    ];

    renderWithProviders(
      <SubagentsSection agentId="a1" subagents={subagents} canEdit={true} readOnlyReason="" />,
      'zh-CN',
    );

    // Point 6: Subagent is rendered in card format (no file path displayed)
    expect(screen.getByText('Database Specialist')).toBeTruthy();
    expect(screen.getByText('Analyzes SQL performance and migrations')).toBeTruthy();
    expect(screen.queryByText(/subagents\/database\.md/)).toBeNull();

    // Point 7: 新建不预填名称与定义内容，也没有文件路径输入
    fireEvent.click(screen.getByRole('button', { name: '新建子智能体' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeTruthy();
    expect(screen.queryByLabelText('文件路径')).toBeNull();
    expect((screen.getByLabelText('名称') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('定义内容') as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByDisplayValue(/专项调查智能体/)).toBeNull();
  });

  it('keeps AI completion disabled until a name is filled and shows the privacy note', async () => {
    renderWithProviders(<SubagentsSection agentId="a1" subagents={[]} canEdit={true} readOnlyReason="" />, 'zh-CN');
    fireEvent.click(screen.getByRole('button', { name: '新建子智能体' }));
    await screen.findByRole('dialog');

    const fillButton = screen.getByRole('button', { name: 'AI 补齐' }) as HTMLButtonElement;
    expect(fillButton.disabled).toBe(true);
    expect(fillButton.getAttribute('title')).toBe('先填写名称再补齐');
    expect(screen.getByText(/发送给已配置的对话模型/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '合同审查' } });
    expect((screen.getByRole('button', { name: 'AI 补齐' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'AI 补齐' }));

    await waitFor(() => expect(agentApi.draftSubagent).toHaveBeenCalledWith('a1', { name: '合同审查' }));
    expect((await screen.findByLabelText('定义内容') as HTMLTextAreaElement).value).toContain('# 角色定位');
  });

  it('asks before replacing typed definition content and keeps it when the model fails', async () => {
    renderWithProviders(<SubagentsSection agentId="a1" subagents={[]} canEdit={true} readOnlyReason="" />, 'zh-CN');
    fireEvent.click(screen.getByRole('button', { name: '新建子智能体' }));
    await screen.findByRole('dialog');

    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '合同审查' } });
    fireEvent.change(screen.getByLabelText('定义内容'), { target: { value: '# 手写内容' } });
    fireEvent.click(screen.getByRole('button', { name: 'AI 补齐' }));

    // 已有内容先确认再整段替换
    expect(await screen.findByText('覆盖当前定义内容？')).toBeTruthy();
    expect(agentApi.draftSubagent).not.toHaveBeenCalled();

    vi.mocked(agentApi.draftSubagent).mockRejectedValueOnce(new Error('offline'));
    fireEvent.click(screen.getByRole('button', { name: '覆盖生成' }));

    expect(await screen.findByText('模型没有写出定义。请检查模型设置后重试。')).toBeTruthy();
    expect((screen.getByLabelText('定义内容') as HTMLTextAreaElement).value).toBe('# 手写内容');
  });

  it('submits selection searches and paging to the server instead of slicing locally', async () => {
    const manySkills: SkillSummary[] = Array.from({ length: 12 }, (_, i) => ({
      ...skillRows[0],
      skillId: `sk-${i + 1}`,
      name: i === 0 ? 'Unique Python Analysis' : `Skill ${i + 1}`,
      description: i === 5 ? 'Special Keyword In Description' : `Description ${i + 1}`,
    }));
    vi.mocked(api.get).mockImplementation(async (path: string) => {
      const url = new URL(path, 'https://test.local');
      if (url.pathname === '/api/advisor/skills') return pageOf(manySkills, url);
      throw new Error(`Unexpected list request: ${path}`);
    });

    renderWithProviders(<SkillSelectionSection agentId="a1" canEdit={true} />, 'zh-CN');

    expect(await screen.findByText('Skill 10')).toBeTruthy();
    expect(screen.queryByText('Skill 11')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=2&size=10&purpose=selection&agentId=a1'));
    expect(await screen.findByText('Skill 11')).toBeTruthy();

    const searchInput = screen.getByRole('searchbox', { name: '搜索技能' });
    fireEvent.change(searchInput, { target: { value: 'Special Keyword' } });
    fireEvent.click(screen.getByRole('button', { name: '搜索' }));
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/api/advisor/skills?page=1&size=10&q=Special+Keyword&purpose=selection&agentId=a1'));
    expect(await screen.findByText('Skill 6')).toBeTruthy();
    expect(screen.queryByText('Skill 11')).toBeNull();
  });

  it('keeps MCP tool checkboxes under the server they belong to and saves the server selection', async () => {
    renderWithProviders(<ToolSelectionSection agentId="a1" canEdit={true} />, 'zh-CN');

    fireEvent.click(screen.getByRole('button', { name: /MCP/ }));
    const serverCheckbox = await screen.findByRole('checkbox', { name: 'GitHub Server' });
    const toolCheckbox = screen.getByRole('checkbox', { name: 'gh_search' });
    expect((toolCheckbox as HTMLInputElement).disabled).toBe(false);

    fireEvent.click(serverCheckbox);
    expect((toolCheckbox as HTMLInputElement).disabled).toBe(true);
    expect((toolCheckbox as HTMLInputElement).checked).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: '保存选用' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/api/advisor/agents/a1/tool-selection',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ toolIds: [], serverIds: ['s1'] }) }),
    ));
  });

  it('locks the name and definition inputs while AI completion runs and unlocks them after', async () => {
    let finish!: (value: { content: string }) => void;
    vi.mocked(agentApi.draftSubagent).mockImplementationOnce(
      () => new Promise<{ content: string }>((resolve) => { finish = resolve; }),
    );
    renderWithProviders(<SubagentsSection agentId="a1" subagents={[]} canEdit={true} readOnlyReason="" />, 'zh-CN');
    fireEvent.click(screen.getByRole('button', { name: '新建子智能体' }));
    await screen.findByRole('dialog');

    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '合同审查' } });
    fireEvent.click(screen.getByRole('button', { name: 'AI 补齐' }));

    await screen.findByRole('button', { name: '正在生成…' });
    expect((screen.getByLabelText('名称') as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText('定义内容') as HTMLTextAreaElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: '保存' }) as HTMLButtonElement).disabled).toBe(true);

    finish({ content: '---\ndescription: 合同审查\n---\n\n# 角色定位\n生成内容' });
    await waitFor(() => expect((screen.getByLabelText('定义内容') as HTMLTextAreaElement).disabled).toBe(false));
    expect((screen.getByLabelText('名称') as HTMLInputElement).disabled).toBe(false);
    expect((screen.getByLabelText('定义内容') as HTMLTextAreaElement).value).toContain('生成内容');
  });

  it('offers a clear action when the selection search has no results', async () => {
    renderWithProviders(<ToolSelectionSection agentId="a1" canEdit={true} />, 'zh-CN');

    const searchInput = screen.getByRole('searchbox', { name: '搜索工具' });
    fireEvent.change(searchInput, { target: { value: 'nothing matches' } });
    fireEvent.click(screen.getByRole('button', { name: '搜索' }));

    expect(await screen.findByText('未找到匹配的记录')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '清除搜索' }));
    expect(await screen.findByText('Weather Lookup')).toBeTruthy();
  });

  it('creation settings reuse the same tables and carry the draft selection context', async () => {
    const onChangeTools = vi.fn();
    const onChangeSkills = vi.fn();
    renderWithProviders(
      <CreationSettingsSection draftId="d1" tools={[]} onChangeTools={onChangeTools} skills={[]} onChangeSkills={onChangeSkills} />,
      'zh-CN',
    );

    fireEvent.click(screen.getByRole('button', { name: /高级能力配置/ }));

    expect(await screen.findByText('Weather Lookup')).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/tools?page=1&size=10&purpose=selection&draftId=d1&origin=http%2Cdeclared');
    expect(await screen.findByText('Code Reviewer')).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/api/advisor/skills?page=1&size=10&purpose=selection&draftId=d1');

    fireEvent.click(screen.getByRole('checkbox', { name: 'Weather Lookup' }));
    expect(onChangeTools).toHaveBeenCalledWith(['t1']);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Code Reviewer' }));
    expect(onChangeSkills).toHaveBeenCalledWith(['sk1']);
  });
});
