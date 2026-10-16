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

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AgentCreationPage from './AgentCreationPage';
import * as api from '@/api/agentCreation';
import * as advisorApi from '@/api/advisor';

const mockedNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockedNavigate,
    useParams: () => ({}),
    useSearchParams: () => [new URLSearchParams(), vi.fn()],
  };
});

vi.mock('@/api/agentCreation', async () => {
  const actual = await vi.importActual<typeof import('@/api/agentCreation')>('@/api/agentCreation');
  return {
    ...actual,
    createDraft: vi.fn(),
    getDraft: vi.fn(),
    updateDraft: vi.fn(),
    checkDraft: vi.fn(),
    activateDraft: vi.fn(),
    getActivation: vi.fn(),
    createPreview: vi.fn(),
    runPreviewTurn: vi.fn(),
    submitGeneration: vi.fn(),
    getGeneration: vi.fn(),
    deleteDraft: vi.fn(),
  };
});

vi.mock('@/api/advisor', async () => {
  const actual = await vi.importActual<typeof import('@/api/advisor')>('@/api/advisor');
  return {
    ...actual,
    createSession: vi.fn(),
  };
});

vi.mock('@/lib/apiClient', async () => {
  const actual = await vi.importActual<typeof import('@/lib/apiClient')>('@/lib/apiClient');
  return {
    ...actual,
    apiFetch: vi.fn().mockImplementation((path: string) => {
      if (path === '/api/advisor/models/active') {
        return Promise.resolve({ modelName: 'DeepSeek', contextWindow: 64000 });
      }
      return Promise.resolve({});
    }),
  };
});

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>{ui}</BrowserRouter>
    </QueryClientProvider>,
  );
}

describe('AgentCreationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    for (const method of [api.createDraft, api.updateDraft, api.checkDraft, api.activateDraft, api.submitGeneration, api.getGeneration]) vi.mocked(method).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders purpose formulation, settings section, permanent sandbox panel and action buttons', () => {
    renderWithProviders(<AgentCreationPage />);
    expect(screen.getByText('创建智能体')).toBeDefined();
    expect(screen.getByPlaceholderText(/描述智能体的应用场景/)).toBeDefined();
    expect(screen.getByText('高级能力配置')).toBeDefined();
    // Preview panel is permanently displayed
    expect(screen.getByText('沙箱试运行')).toBeDefined();
    expect(screen.getAllByRole('button', { name: '创建' }).length).toBeGreaterThan(0);
  });

  it.each([0, 1])('creates from manually entered fields without a purpose using create button %s', async buttonIndex => {
    const saved = { draftId: 'manual-empty-purpose', ownerUserId: 'u1', mode: 'describe', sourceAgentId: null, purpose: '', name: '手动助手', description: '', behavior: '仅根据资料回答', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(api.createDraft).mockResolvedValueOnce(saved);
    vi.mocked(api.checkDraft).mockResolvedValueOnce({ issues: [] });
    vi.mocked(api.activateDraft).mockResolvedValueOnce({ operationId: 'manual-op', draftId: saved.draftId, operationType: 'activation', requestKey: 'manual-key', baseRevision: 1, state: 'succeeded', stage: 'completed', preallocatedAgentId: 'manual-agent', startedAt: 0 });
    renderWithProviders(<AgentCreationPage />);
    expect(screen.getByText('描述需求，AI 帮你生成')).toBeTruthy();
    expect(screen.getByText('可选；仅用于 AI 辅助生成，不填写也可手动创建')).toBeTruthy();
    fireEvent.change(screen.getByPlaceholderText('例如：架构评审助手'), { target: { value: saved.name } });
    const requirement = screen.getAllByRole('textbox').find(input => input.tagName === 'TEXTAREA' && !input.getAttribute('placeholder')?.includes('描述智能体的应用场景'))!;
    fireEvent.change(requirement, { target: { value: saved.behavior } });
    const create = screen.getAllByRole('button', { name: '创建' })[buttonIndex] as HTMLButtonElement;
    expect(create.disabled).toBe(false);
    fireEvent.click(create);
    await waitFor(() => expect(api.activateDraft).toHaveBeenCalled());
    expect(api.createDraft).toHaveBeenCalledWith(expect.objectContaining({ purpose: '', name: saved.name, behavior: saved.behavior }));
    expect(api.submitGeneration).not.toHaveBeenCalled();
    await waitFor(() => expect(mockedNavigate).toHaveBeenCalledWith('/agent/manual-agent'));
  });

  it('shows AI 生成 button when purpose has value and populates behavior on click', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({
      draftId: 'draft-ai-1',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '审核技术文档',
      name: '',
      description: '',
      behavior: '',
      tools: [],
      skills: [],
      resources: [],
      revision: 1,
      createdAt: 1000,
      updatedAt: 1000,
    });

    vi.mocked(api.submitGeneration).mockResolvedValueOnce({
      operationId: 'op-gen-1',
      draftId: 'draft-ai-1',
      operationType: 'generation',
      requestKey: 'k-gen',
      baseRevision: 1,
      state: 'queued',
      stage: 'calling_model',
      startedAt: 1000,
    });

    vi.mocked(api.getGeneration).mockResolvedValueOnce({
      operationId: 'op-gen-1',
      draftId: 'draft-ai-1',
      operationType: 'generation',
      requestKey: 'k-gen',
      baseRevision: 1,
      state: 'succeeded',
      stage: 'completed',
      candidate: {
        name: '技术文档审核员',
        description: '负责严格审核技术方案',
        behavior: '请按规范审查架构和技术依赖',
      },
      startedAt: 1000,
      finishedAt: 1050,
    });

    renderWithProviders(<AgentCreationPage />);

    // Initially purpose is empty, AI 生成 button not displayed
    expect(screen.queryByText('AI 生成')).toBeNull();

    // Type purpose
    const purposeInput = screen.getByPlaceholderText(/描述智能体的应用场景/);
    fireEvent.change(purposeInput, { target: { value: '审核技术文档' } });

    // Now AI 生成 button appears
    const aiBtn = screen.getByText('AI 生成');
    expect(aiBtn).toBeDefined();

    // Fast-forward flush/draft creation
    const createBtn = screen.getAllByRole('button', { name: '创建' })[0];
    // Trigger creation to obtain draftId
    fireEvent.click(createBtn);

    await waitFor(() => {
      expect(api.createDraft).toHaveBeenCalled();
    });

    // Click AI 生成 button
    fireEvent.click(aiBtn);

    await waitFor(() => {
      expect(api.submitGeneration).toHaveBeenCalledWith('draft-ai-1', 1);
      expect(api.getGeneration).toHaveBeenCalledWith('draft-ai-1', 'op-gen-1');
      expect(screen.getByText('已自动填充')).toBeDefined();
    });
  });

  it('performs one-click flush -> check -> activate -> navigate flow', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({
      draftId: 'draft-abc',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '提取待办',
      name: '纪要助手',
      description: '',
      behavior: '输出待办列表',
      tools: [],
      skills: [],
      resources: [],
      revision: 1,
      createdAt: 1000,
      updatedAt: 1000,
    });

    vi.mocked(api.checkDraft).mockResolvedValueOnce({
      issues: [],
    });

    vi.mocked(api.activateDraft).mockResolvedValueOnce({
      operationId: 'op-act-1',
      draftId: 'draft-abc',
      operationType: 'activation',
      requestKey: 'k1',
      baseRevision: 1,
      state: 'succeeded',
      stage: 'registered',
      preallocatedAgentId: 'agent-new-777',
      startedAt: 1000,
      finishedAt: 1050,
    });

    renderWithProviders(<AgentCreationPage />);

    const purposeInput = screen.getByPlaceholderText(/描述智能体的应用场景/);
    fireEvent.change(purposeInput, { target: { value: '提取待办' } });

    const createBtn = screen.getAllByRole('button', { name: '创建' })[0];
    fireEvent.click(createBtn);

    await waitFor(() => {
      expect(api.createDraft).toHaveBeenCalled();
      expect(api.checkDraft).toHaveBeenCalledWith('draft-abc', 1);
      expect(api.activateDraft).toHaveBeenCalledWith('draft-abc', 1);
      expect(advisorApi.createSession).not.toHaveBeenCalled();
      expect(mockedNavigate).toHaveBeenCalledWith('/agent/agent-new-777');
    });
  });

  it('waits for an in-progress activation instead of opening a hidden staging agent', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({ draftId: 'pending-activation', ownerUserId:'u1', mode:'describe', sourceAgentId:null, purpose:'audit', name:'Audit', description:'',behavior:'Audit',tools:[],skills:[],resources:[],revision:1,createdAt:0,updatedAt:0 });
    vi.mocked(api.checkDraft).mockResolvedValueOnce({issues:[]});
    vi.mocked(api.activateDraft).mockResolvedValueOnce({ operationId:'pending-op', draftId:'pending-activation', operationType:'activation',requestKey:'pending-key',baseRevision:1,state:'running',stage:'staging',preallocatedAgentId:'staging-agent',startedAt:0 });
    vi.mocked(api.getActivation).mockResolvedValue({ operationId:'pending-op', draftId:'pending-activation', operationType:'activation',requestKey:'pending-key',baseRevision:1,state:'running',stage:'staging',preallocatedAgentId:'staging-agent',startedAt:0 });
    renderWithProviders(<AgentCreationPage />);
    fireEvent.change(screen.getByPlaceholderText(/描述智能体的应用场景/),{target:{value:'audit'}});
    fireEvent.click(screen.getAllByRole('button',{name:'创建'})[0]);
    await waitFor(()=>expect(api.activateDraft).toHaveBeenCalled());
    expect(mockedNavigate).not.toHaveBeenCalledWith('/agent/staging-agent');
  });

  it('does not activate an older saved draft after the latest flush fails', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({ draftId: 'flush-failure', ownerUserId: 'u1', mode: 'describe', sourceAgentId: null, purpose: 'first', name: 'Old', description: '', behavior: 'Old instructions', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 });
    vi.mocked(api.updateDraft).mockRejectedValue(new Error('save unavailable'));
    vi.mocked(api.checkDraft).mockResolvedValue({ issues: [{ code: 'review', severity: 'error', section: 'purpose', messageKey: 'Review the saved draft' }] });
    renderWithProviders(<AgentCreationPage />);
    const input = screen.getByPlaceholderText(/描述智能体的应用场景/);
    fireEvent.change(input, { target: { value: 'first' } });
    fireEvent.click(screen.getAllByRole('button', { name: '创建' })[0]);
    await waitFor(() => expect(api.createDraft).toHaveBeenCalled(), { timeout: 2000 });
    await waitFor(() => expect(api.checkDraft).toHaveBeenCalled());
    vi.mocked(api.checkDraft).mockClear();
    fireEvent.change(input, { target: { value: 'latest unsaved' } });
    fireEvent.click(screen.getAllByRole('button', { name: '创建' })[0]);
    await waitFor(() => expect(api.updateDraft).toHaveBeenCalled());
    expect(api.checkDraft).not.toHaveBeenCalled();
    expect(api.activateDraft).not.toHaveBeenCalled();
  });

  it('displays blocking error issues if checkDraft returns errors', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({
      draftId: 'draft-err',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '简单用途',
      name: '',
      description: '',
      behavior: '',
      tools: [],
      skills: [],
      resources: [],
      revision: 1,
      createdAt: 1000,
      updatedAt: 1000,
    });

    vi.mocked(api.checkDraft).mockResolvedValueOnce({
      issues: [
        {
          code: 'behavior_missing',
          severity: 'error',
          section: 'behavior',
          messageKey: '工作要求 / 系统设定不可为空',
        },
      ],
    });

    renderWithProviders(<AgentCreationPage />);

    const purposeInput = screen.getByPlaceholderText(/描述智能体的应用场景/);
    fireEvent.change(purposeInput, { target: { value: '简单用途' } });

    const createBtn = screen.getAllByRole('button', { name: '创建' })[0];
    fireEvent.click(createBtn);

    await waitFor(() => {
      expect(screen.getByText('无法完成创建：')).toBeDefined();
      expect(screen.getByText('工作要求 / 系统设定不可为空')).toBeDefined();
    });

    expect(api.activateDraft).not.toHaveBeenCalled();
  });
});
