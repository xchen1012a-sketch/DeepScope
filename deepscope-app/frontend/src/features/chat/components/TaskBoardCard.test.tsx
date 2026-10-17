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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskBoardCard, type TaskBoardItem } from './TaskBoardCard';

afterEach(cleanup);

const mockTasks: TaskBoardItem[] = [
  {
    taskId: 't-1',
    title: '架构调研',
    description: '调研双模持久化设计',
    assigneeRole: 'architect',
    blockedBy: [],
    status: 'SUCCEEDED',
    resultSummary: '【结论】架构调研已办结 | 【产出物】arch.md',
  },
  {
    taskId: 't-2',
    title: '后端编码实现',
    description: '实现任务调度状态机',
    assigneeRole: 'developer',
    blockedBy: ['t-1'],
    status: 'RUNNING',
  },
  {
    taskId: 't-3',
    title: '集成测试验证',
    description: '端到端拟真流转测试',
    assigneeRole: 'tester',
    blockedBy: ['t-2'],
    status: 'BLOCKED',
  },
  {
    taskId: 't-4',
    title: '压测报告',
    description: '模拟高并发压测',
    assigneeRole: 'tester',
    blockedBy: [],
    status: 'FAILED',
    resultSummary: '执行超时',
  },
];

describe('TaskBoardCard component', () => {
  it('renders board header with correct counts and percentage', () => {
    render(<TaskBoardCard tasks={mockTasks} maxTasks={8} availableSlots={2} />);

    expect(screen.getByText('多 Agent 协同任务看板')).toBeDefined();
    expect(screen.getByText('1/4 已办结 (25%)')).toBeDefined();
    expect(screen.getByText('1 运行中')).toBeDefined();
    expect(screen.getByText('槽位余量: 2 · 上限: 4/8')).toBeDefined();
  });

  it('renders all tasks with their respective status labels and role badges', () => {
    render(<TaskBoardCard tasks={mockTasks} />);

    expect(screen.getByText('架构调研')).toBeDefined();
    expect(screen.getByText('后端编码实现')).toBeDefined();
    expect(screen.getByText('集成测试验证')).toBeDefined();
    expect(screen.getByText('压测报告')).toBeDefined();

    expect(screen.getByText('已完成')).toBeDefined();
    expect(screen.getByText('执行中')).toBeDefined();
    expect(screen.getByText('等待依赖')).toBeDefined();
    expect(screen.getByText('执行失败')).toBeDefined();
    expect(screen.getByText('依赖: t-1')).toBeDefined();
  });

  it('toggles deliverable expansion on click', () => {
    render(<TaskBoardCard tasks={mockTasks} />);

    expect(screen.queryByText(/【结论】架构调研已办结/)).toBeNull();

    const deliverableBtns = screen.getAllByText('交付成果');
    fireEvent.click(deliverableBtns[0]);

    expect(screen.getByText(/【结论】架构调研已办结/)).toBeDefined();
  });

  it('triggers onRetry and onSkip callbacks when clicked on failed task', () => {
    const handleRetry = vi.fn();
    const handleSkip = vi.fn();

    render(<TaskBoardCard tasks={mockTasks} onRetry={handleRetry} onSkip={handleSkip} />);

    const retryBtn = screen.getByText('重试');
    fireEvent.click(retryBtn);
    expect(handleRetry).toHaveBeenCalledWith('t-4');

    const skipBtn = screen.getByText('跳过');
    fireEvent.click(skipBtn);
    expect(handleSkip).toHaveBeenCalledWith('t-4');
  });

  it('renders empty message when no tasks present', () => {
    render(<TaskBoardCard tasks={[]} />);
    expect(screen.getByText('当前暂无拆解任务单')).toBeDefined();
  });
});
