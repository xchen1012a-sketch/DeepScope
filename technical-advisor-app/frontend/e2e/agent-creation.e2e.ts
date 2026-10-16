import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('claw_token', 'local-ui-test');
    localStorage.setItem('controlplane.console.locale', 'zh-CN');
  });

  const drafts: any[] = [];
  const agents = [
    {
      agentId: 'existing-agent-1',
      name: '现有架构助手',
      description: '负责系统架构评估',
      origin: 'personal',
      mine: true,
      canEdit: true,
      counts: { skills: 1, subagents: 0 },
    },
  ];

  await page.route('http://127.0.0.1:5190/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

    if (path === '/api/auth/me') {
      return json({
        userId: 'test-user',
        username: '测试工程师',
        roles: ['admin'],
        isAdmin: true,
        permissions: ['agent.view', 'agent.edit', 'chat.use'],
      });
    }

    if (path === '/api/advisor/agents') {
      return json(agents);
    }

    if (path === '/api/advisor/agent-creation-drafts') {
      if (request.method() === 'GET') {
        return json(drafts);
      }
      if (request.method() === 'POST') {
        const body = request.postDataJSON() || {};
        const newDraft = {
          draftId: 'draft-mock-1',
          ownerUserId: 'test-user',
          mode: body.mode || 'describe',
          sourceAgentId: body.sourceAgentId || null,
          purpose: body.purpose || '',
          name: body.name || '',
          description: body.description || '',
          behavior: body.behavior || '',
          tools: body.tools || [],
          skills: body.skills || [],
          resources: body.resources || [],
          revision: 1,
          createdAt: Date.now(),
          updatedAt: Date.now(),
        };
        drafts.push(newDraft);
        return json(newDraft, 201);
      }
    }

    if (path.startsWith('/api/advisor/agent-creation-drafts/draft-mock-1')) {
      if (request.method() === 'GET') {
        return json(drafts[0] || {});
      }
      if (request.method() === 'PATCH') {
        const body = request.postDataJSON() || {};
        const draft = drafts[0];
        if (draft) {
          Object.assign(draft, body);
          draft.revision += 1;
        }
        return json(draft);
      }
      if (path.endsWith('/checks')) {
        return json({ issues: [] });
      }
      if (path.endsWith('/activations')) {
        return json({
          operationId: 'op-act-1',
          draftId: 'draft-mock-1',
          operationType: 'activation',
          requestKey: 'key-1',
          baseRevision: drafts[0]?.revision || 1,
          state: 'succeeded',
          stage: 'registered',
          preallocatedAgentId: 'agent-activated-1',
          startedAt: Date.now(),
          finishedAt: Date.now(),
        });
      }
      if (path.endsWith('/previews')) {
        return json({
          previewId: 'prev-1',
          draftId: 'draft-mock-1',
          draftRevision: drafts[0]?.revision || 1,
          ownerUserId: 'test-user',
          state: 'idle',
          history: [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
          expiresAt: Date.now() + 86400000,
        });
      }
    }

    if (path === '/api/advisor/sessions') {
      if (request.method() === 'POST') {
        return json({
          sessionId: 'session-new-1',
          title: '会议纪要助手',
          agentId: 'agent-activated-1',
          agentName: '会议纪要助手',
          status: 'idle',
          createdAt: Date.now(),
          updatedAt: Date.now(),
        });
      }
      return json([]);
    }

    if (path === '/api/advisor/sessions/session-new-1') {
      return json({
        session: {
          sessionId: 'session-new-1',
          title: '会议纪要助手',
          agentId: 'agent-activated-1',
          agentName: '会议纪要助手',
          status: 'idle',
          createdAt: Date.now(),
          updatedAt: Date.now(),
        },
        entries: [],
        deliverables: [],
      });
    }

    return route.fulfill({ status: 200, json: {} });
  });
});

test('full agent creation flow from purpose to activation and chat entry', async ({ page }) => {
  await page.goto('/agent');
  const newAgentBtn = page.getByRole('button', { name: /新建主智能体/ });
  await expect(newAgentBtn).toBeVisible();
  await newAgentBtn.click();

  await expect(page).toHaveURL(/\/agent\/new/);
  await expect(page.getByRole('heading', { name: '创建智能体' })).toBeVisible();

  // Fill in purpose
  const purposeInput = page.getByPlaceholder(/根据输入的技术文档/);
  await purposeInput.fill('整理会议纪要，提取结论、待办事项、负责人和截止时间');

  // Verify preview is permanently visible
  await expect(page.getByText('沙箱试运行')).toBeVisible();

  // One-click create and start
  const submitBtn = page.getByRole('button', { name: '创建并开始使用' }).first();
  await submitBtn.click();

  // Should navigate into chat session
  await expect(page).toHaveURL(/\/chat\/session-new-1/);
});
