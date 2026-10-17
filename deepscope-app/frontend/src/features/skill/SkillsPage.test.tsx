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
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ApiError, api, apiFetch } from '@/lib/apiClient';
import SkillsPage, { type SkillSummary } from './SkillsPage';

function mockSkillPages(skills: SkillSummary[]) {
  vi.mocked(api.get).mockImplementation(async path => {
    const params = new URL(path, 'http://localhost').searchParams;
    const query = params.get('q') ?? '';
    const status = params.get('status');
    const matches = skills.filter(skill => (!status || skill.status === status) && `${skill.name} ${skill.description}`.includes(query));
    const page = Number(params.get('page') ?? 1);
    const size = Number(params.get('size') ?? 10);
    return { items: matches.slice((page - 1) * size, page * size), page, size, total: matches.length, totalPages: Math.ceil(matches.length / size) };
  });
}

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

function renderSkillsPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <SkillsPage />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe('SkillsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockSkillPages([]);
  });

  afterEach(() => {
    cleanup();
  });

  it('renders skills list in table layout and filters by search query', async () => {
    mockSkillPages([
      {
        skillId: 's-1',
        name: 'code_review',
        description: '审查代码逻辑规范',
        status: 'published',
        origin: 'personal',
        canEdit: true,
        agents: ['agent-1', 'agent-2'],
      },
      {
        skillId: 's-2',
        name: 'sql_debugger',
        description: '排查数据库死锁',
        status: 'draft',
        origin: 'personal',
        canEdit: true,
        agents: [],
      },
    ]);

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('code_review')).toBeTruthy();
      expect(screen.getByText('sql_debugger')).toBeTruthy();
      expect(screen.getByText('agent-1、agent-2')).toBeTruthy();
      expect(screen.getByText('暂无引用')).toBeTruthy();
      expect(screen.getByText('已发布')).toBeTruthy();
      expect(screen.getByText('草稿')).toBeTruthy();
    });

    // Check table headers
    expect(screen.getByText('技能名称')).toBeTruthy();
    expect(screen.getByText('用途说明')).toBeTruthy();
    expect(screen.getByText('关联智能体')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: '状态' })).toBeTruthy();
    expect(screen.getByText('操作')).toBeTruthy();

    // Search filter
    const searchInput = screen.getByRole('searchbox');
    fireEvent.change(searchInput, { target: { value: 'sql' } });

    expect(screen.getByText('code_review')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /^搜索$/ }));
    await waitFor(() => expect(screen.queryByText('code_review')).toBeNull());
    expect(await screen.findByText('sql_debugger')).toBeTruthy();
    fireEvent.change(searchInput, { target: { value: '' } });
    expect(screen.queryByText('code_review')).toBeNull();
    fireEvent.submit(screen.getByRole('search'));
    expect(await screen.findByText('code_review')).toBeTruthy();
  });

  it('displays SkillFileCard with formatted size and remove action when file is dropped', async () => {
    renderSkillsPage();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /新建技能/i })).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: /新建技能/i }));

    const dropZone = screen.getByText('把文件拖到这里，或直接粘贴。也可以点这里选择。');
    const dummyZip = new File(['mock content inside zip'], 'my_assistant_skill.zip', {
      type: 'application/zip',
    });

    fireEvent.drop(dropZone, {
      dataTransfer: {
        files: [dummyZip],
      },
    });

    expect(screen.getByText('my_assistant_skill.zip')).toBeTruthy();
    expect(screen.getByText('已就绪')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重新选择' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '移除' })).toBeTruthy();

    // Clicking remove removes the file card and restores drop zone
    fireEvent.click(screen.getByRole('button', { name: '移除' }));
    expect(screen.queryByText('my_assistant_skill.zip')).toBeNull();
    expect(screen.getByText('把文件拖到这里，或直接粘贴。也可以点这里选择。')).toBeTruthy();
  });

  it('displays detailed error message when parse fails', async () => {
    renderSkillsPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建技能/i }));

    const dropZone = screen.getByText('把文件拖到这里，或直接粘贴。也可以点这里选择。');
    const dummyZip = new File(['bad zip'], 'broken.zip', { type: 'application/zip' });
    fireEvent.drop(dropZone, {
      dataTransfer: { files: [dummyZip] },
    });

    vi.mocked(apiFetch).mockRejectedValueOnce(
      new ApiError(
        400,
        JSON.stringify({
          code: 'invalid_package',
          message: '压缩包内未找到 SKILL.md，请确保包含技能定义主文件',
        }),
      ),
    );

    const parseButton = screen.getByTestId('parse-button');
    fireEvent.click(parseButton);

    await waitFor(() => {
      expect(
        screen.getByText('压缩包内未找到 SKILL.md，请确保包含技能定义主文件'),
      ).toBeTruthy();
    });
  });

  it('parses single skill and displays preview allowing edits before final import', async () => {
    renderSkillsPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建技能/i }));

    const dropZone = screen.getByText('把文件拖到这里，或直接粘贴。也可以点这里选择。');
    const dummyZip = new File(['mock zip'], 'single.zip', { type: 'application/zip' });
    fireEvent.drop(dropZone, {
      dataTransfer: { files: [dummyZip] },
    });
    expect(screen.getByText('single.zip')).toBeTruthy();

    vi.mocked(apiFetch).mockResolvedValueOnce({
      batch: false,
      count: 1,
      skills: [
        {
          name: 'extracted_analyzer',
          description: '从压缩包提取出的代码分析器',
          markdown: '# 指令\n1. 语法树分析',
        },
      ],
    });

    fireEvent.click(screen.getByTestId('parse-button'));

    await waitFor(() => {
      expect(screen.getByText('解析结果预览')).toBeTruthy();
      expect(screen.getByDisplayValue('extracted_analyzer')).toBeTruthy();
      expect(screen.getByDisplayValue('从压缩包提取出的代码分析器')).toBeTruthy();
      expect(screen.getByDisplayValue(/语法树分析/)).toBeTruthy();
    });

    // Modify name in preview
    const nameInput = screen.getByDisplayValue('extracted_analyzer');
    fireEvent.change(nameInput, { target: { value: 'custom_analyzer' } });

    vi.mocked(api.post).mockResolvedValueOnce({ skillId: 's-new', name: 'custom_analyzer' });

    fireEvent.click(screen.getByTestId('confirm-import-button'));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/api/advisor/skills',
        expect.objectContaining({
          name: 'custom_analyzer',
          description: '从压缩包提取出的代码分析器',
        }),
      );
    });
  });

  it('parses multi-skill pack and displays selection list with batch import', async () => {
    renderSkillsPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建技能/i }));

    const dropZone = screen.getByText('把文件拖到这里，或直接粘贴。也可以点这里选择。');
    const dummyZip = new File(['mock zip'], 'pack.zip', { type: 'application/zip' });
    fireEvent.drop(dropZone, {
      dataTransfer: { files: [dummyZip] },
    });

    vi.mocked(apiFetch).mockResolvedValueOnce({
      batch: true,
      count: 2,
      skills: [
        { name: 'skill_alpha', description: '技能 A', markdown: '# A' },
        { name: 'skill_beta', description: '技能 B', markdown: '# B' },
      ],
    });

    fireEvent.click(screen.getByTestId('parse-button'));

    await waitFor(() => {
      expect(screen.getByText('skill_alpha')).toBeTruthy();
      expect(screen.getByText('skill_beta')).toBeTruthy();
      expect(screen.getByText('确认导入 (2)')).toBeTruthy();
    });

    vi.mocked(api.post).mockResolvedValueOnce({
      batch: true,
      count: 2,
      skills: [{ skillId: 's-1' }, { skillId: 's-2' }],
    });

    fireEvent.click(screen.getByTestId('confirm-import-button'));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/api/advisor/skills',
        expect.objectContaining({
          skills: [
            expect.objectContaining({ name: 'skill_alpha' }),
            expect.objectContaining({ name: 'skill_beta' }),
          ],
        }),
      );
    });
  });

  it('opens edit skill dialog and saves modifications', async () => {
    vi.mocked(api.get)
      .mockResolvedValueOnce({ items: [
        {
          skillId: 's-edit',
          name: 'old_skill',
          description: '旧技能描述',
          status: 'draft',
          origin: 'personal',
          canEdit: true,
          agents: [],
        },
      ], page: 1, size: 10, total: 1, totalPages: 1 })
      // For getSkill detail call
      .mockResolvedValueOnce({
        skillId: 's-edit',
        name: 'old_skill',
        description: '旧技能描述',
        status: 'draft',
        origin: 'personal',
        canEdit: true,
        agents: [],
        markdown: '# 原始指令\n执行初始检查',
      });

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('old_skill')).toBeTruthy();
    });

    // Click edit
    fireEvent.click(screen.getByRole('button', { name: /编辑/i }));

    await waitFor(() => {
      expect(screen.getByText('编辑技能')).toBeTruthy();
      expect(screen.getByDisplayValue('old_skill')).toBeTruthy();
      expect(screen.getByDisplayValue('旧技能描述')).toBeTruthy();
      expect(screen.getByDisplayValue(/执行初始检查/)).toBeTruthy();
    });

    // Edit fields
    fireEvent.change(screen.getByDisplayValue('old_skill'), {
      target: { value: 'renamed_skill' },
    });
    fireEvent.change(screen.getByDisplayValue('旧技能描述'), {
      target: { value: '更新后的描述' },
    });

    vi.mocked(apiFetch).mockResolvedValueOnce({
      skillId: 's-edit',
      name: 'renamed_skill',
      description: '更新后的描述',
    });

    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        '/api/advisor/skills/s-edit',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            name: 'renamed_skill',
            description: '更新后的描述',
            content: '# 原始指令\n执行初始检查',
          }),
        }),
      );
    });
  });

  it('supports manual mode with template insertion and saving content', async () => {
    renderSkillsPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建技能/i }));

    // Switch to manual mode
    fireEvent.click(screen.getByRole('button', { name: '手动填写' }));

    const nameInput = screen.getByPlaceholderText('e.g. code_review');
    const briefTextarea = screen.getByPlaceholderText('写清什么时候用、要做到什么');
    const contentTextarea = screen.getByPlaceholderText(
      '输入 Markdown 格式的执行规则、执行步骤或约束规范…',
    );

    fireEvent.change(nameInput, { target: { value: 'code_reviewer' } });
    fireEvent.change(briefTextarea, { target: { value: '用于审查代码风险' } });

    // Click insert template
    fireEvent.click(screen.getByRole('button', { name: '插入标准模版' }));
    expect((contentTextarea as HTMLTextAreaElement).value).toContain('# code_reviewer');
    expect((contentTextarea as HTMLTextAreaElement).value).toContain('用于审查代码风险');
    expect((contentTextarea as HTMLTextAreaElement).value).toContain('## 执行步骤');

    // Click save
    vi.mocked(api.post).mockResolvedValueOnce({ skillId: 's-1', name: 'code_reviewer' });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/api/advisor/skills',
        expect.objectContaining({
          name: 'code_reviewer',
          description: '用于审查代码风险',
          brief: '用于审查代码风险',
          content: expect.stringContaining('## 执行步骤'),
        }),
      );
    });
  });

  it('supports manual mode AI draft generation to fill content and suggested name', async () => {
    renderSkillsPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建技能/i }));

    fireEvent.click(screen.getByRole('button', { name: '手动填写' }));

    const briefTextarea = screen.getByPlaceholderText('写清什么时候用、要做到什么');
    fireEvent.change(briefTextarea, { target: { value: '审计 SQL 注入' } });

    vi.mocked(apiFetch).mockResolvedValueOnce({
      name: 'sql_security_guard',
      description: '审计 SQL 注入',
      instructions: '## 执行步骤\n1. 检查动态拼接 SQL 语句\n2. 推荐使用预编译参数化',
    });

    fireEvent.click(screen.getByRole('button', { name: 'AI 规划初稿' }));

    await waitFor(() => {
      const nameInput = screen.getByPlaceholderText('e.g. code_review') as HTMLInputElement;
      expect(nameInput.value).toBe('sql_security_guard');
      const contentTextarea = screen.getByPlaceholderText(
        '输入 Markdown 格式的执行规则、执行步骤或约束规范…',
      ) as HTMLTextAreaElement;
      expect(contentTextarea.value).toContain('检查动态拼接 SQL 语句');
    });
  });

  it('supports pagination when there are more than 10 skills', async () => {
    const manySkills = Array.from({ length: 12 }, (_, i) => ({
      skillId: `s-${i + 1}`,
      name: `Skill_${i + 1}`,
      description: `Description ${i + 1}`,
      status: 'published' as const,
      origin: 'personal' as const,
      canEdit: true,
      agents: [],
    }));

    mockSkillPages(manySkills);

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('Skill_1')).toBeTruthy();
      expect(screen.getByText('Skill_10')).toBeTruthy();
      expect(screen.queryByText('Skill_11')).toBeNull();
    });

    const nextBtn = screen.getByRole('button', { name: /下一页/i });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(screen.queryByText('Skill_1')).toBeNull();
      expect(screen.getByText('Skill_11')).toBeTruthy();
      expect(screen.getByText('Skill_12')).toBeTruthy();
    });
  });

  it('filters skills by status and clears filter', async () => {
    mockSkillPages([
      {
        skillId: 's-pub',
        name: 'Published Skill',
        description: 'desc pub',
        status: 'published',
        origin: 'personal',
        canEdit: true,
        agents: [],
      },
      {
        skillId: 's-draft',
        name: 'Draft Skill',
        description: 'desc draft',
        status: 'draft',
        origin: 'personal',
        canEdit: true,
        agents: [],
      },
    ]);

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('Published Skill')).toBeTruthy();
      expect(screen.getByText('Draft Skill')).toBeTruthy();
    });

    // Open status select and choose "已发布"
    const statusSelectBtn = screen.getByRole('button', { name: '状态' });
    fireEvent.pointerDown(statusSelectBtn, { button: 0 });
    fireEvent.keyDown(statusSelectBtn, { key: 'ArrowDown' });

    const publishedOption = await screen.findByRole('menuitem', { name: '已发布' });
    fireEvent.click(publishedOption);

    // Draft Skill should be filtered out
    await waitFor(() => {
      expect(screen.getByText('Published Skill')).toBeTruthy();
      expect(screen.queryByText('Draft Skill')).toBeNull();
    });

    // Clear filter via X button inside select
    const clearBtn = screen.getByRole('button', { name: '清除' });
    fireEvent.click(clearBtn);

    await waitFor(() => {
      expect(screen.getByText('Published Skill')).toBeTruthy();
      expect(screen.getByText('Draft Skill')).toBeTruthy();
    });
  });

  it('deletes draft skill directly without secondary confirmation modal', async () => {
    mockSkillPages([
      {
        skillId: 's-draft',
        name: 'Draft to delete',
        description: 'draft description',
        status: 'draft',
        origin: 'personal',
        canEdit: true,
        agents: [],
      },
    ]);
    vi.mocked(apiFetch).mockResolvedValueOnce(undefined);

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('Draft to delete')).toBeTruthy();
    });

    const deleteBtn = screen.getByRole('button', { name: '删除' });
    fireEvent.click(deleteBtn);

    // Directly calls DELETE API without opening confirmation dialog
    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith('/api/advisor/skills/s-draft', {
        method: 'DELETE',
      });
    });
    expect(screen.queryByText('删除“Draft to delete”？')).toBeNull();
  });

  it('prompts confirmation when deleting published skill and deletes upon confirm', async () => {
    mockSkillPages([
      {
        skillId: 's-pub',
        name: 'Published to delete',
        description: 'published description',
        status: 'published',
        origin: 'personal',
        canEdit: true,
        agents: [],
      },
    ]);
    vi.mocked(apiFetch).mockResolvedValueOnce(undefined);

    renderSkillsPage();

    await waitFor(() => {
      expect(screen.getByText('Published to delete')).toBeTruthy();
    });

    const deleteBtn = screen.getByRole('button', { name: '删除' });
    fireEvent.click(deleteBtn);

    // Confirmation dialog should open
    await waitFor(() => {
      expect(screen.getByText('删除“Published to delete”？')).toBeTruthy();
      expect(
        screen.getByText('这将永久删除“Published to delete”。此操作无法撤销。'),
      ).toBeTruthy();
    });

    // Test cancel
    const cancelBtn = screen.getByRole('button', { name: '取消' });
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(screen.queryByText('删除“Published to delete”？')).toBeNull();
    });
    expect(apiFetch).not.toHaveBeenCalled();

    // Click delete again and confirm
    fireEvent.click(deleteBtn);
    await waitFor(() => {
      expect(screen.getByText('删除“Published to delete”？')).toBeTruthy();
    });

    // Click confirm "删除" in dialog (the one inside dialog)
    const dialogConfirmBtn = screen.getAllByRole('button', { name: '删除' });
    // The second button is the confirm button inside dialog
    fireEvent.click(dialogConfirmBtn[dialogConfirmBtn.length - 1]);

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith('/api/advisor/skills/s-pub', {
        method: 'DELETE',
      });
    });
  });
});
