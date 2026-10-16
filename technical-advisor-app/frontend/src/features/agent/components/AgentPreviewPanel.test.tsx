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
import { AgentPreviewPanel } from './AgentPreviewPanel';
import * as api from '@/api/agentCreation';

vi.mock('@/api/agentCreation', async () => {
  const actual = await vi.importActual<typeof import('@/api/agentCreation')>('@/api/agentCreation');
  return {
    ...actual,
    createPreview: vi.fn(),
    runPreviewTurn: vi.fn(),
  };
});

describe('AgentPreviewPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders preview header and empty placeholder state', () => {
    render(<AgentPreviewPanel draftId="d1" revision={1} permanent={true} />);
    expect(screen.getByText('沙箱试运行')).toBeDefined();
    expect(screen.getByText(/隔离环境/)).toBeDefined();
    expect(screen.getByText(/验证智能体的实际行为/)).toBeDefined();
    // In permanent mode, no close button is rendered
    expect(screen.queryByLabelText('关闭试聊')).toBeNull();
  });

  it('sends preview turn and displays user and assistant messages', async () => {
    vi.mocked(api.createPreview).mockResolvedValueOnce({
      previewId: 'prev-123',
      draftId: 'd1',
      draftRevision: 1,
      ownerUserId: 'u1',
      state: 'idle',
      history: [],
      createdAt: 1000,
      updatedAt: 1000,
      expiresAt: 2000,
    });

    vi.mocked(api.runPreviewTurn).mockResolvedValueOnce({
      previewId: 'prev-123',
      reply: '你好！我是会议纪要助手，请问有什么可以帮助你？',
    });

    render(<AgentPreviewPanel draftId="d1" revision={1} permanent={true} />);

    const input = screen.getByPlaceholderText('输入测试消息验证智能体效果...');
    fireEvent.change(input, { target: { value: '你好' } });

    const submitBtn = input.closest('form')?.querySelector('button[type="submit"]');
    expect(submitBtn).toBeDefined();
    fireEvent.click(submitBtn!);

    await waitFor(() => {
      expect(api.createPreview).toHaveBeenCalledWith('d1', 1);
      expect(api.runPreviewTurn).toHaveBeenCalledWith('d1', 'prev-123', '你好');
      expect(screen.getByText('你好')).toBeDefined();
      expect(screen.getByText('你好！我是会议纪要助手，请问有什么可以帮助你？')).toBeDefined();
    });
  });

  it('calls onClose when close button is clicked in non-permanent mode', () => {
    const onClose = vi.fn();
    render(<AgentPreviewPanel draftId="d1" revision={1} onClose={onClose} permanent={false} />);
    const closeBtn = screen.getByLabelText('关闭试聊');
    expect(closeBtn).toBeDefined();
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalled();
  });
});
