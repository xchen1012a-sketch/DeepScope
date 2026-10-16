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
import { act, renderHook } from '@testing-library/react';
import { useAgentCreationDraft } from './useAgentCreationDraft';
import * as api from '@/api/agentCreation';

vi.mock('@/api/agentCreation', async () => {
  const actual = await vi.importActual<typeof import('@/api/agentCreation')>('@/api/agentCreation');
  return {
    ...actual,
    createDraft: vi.fn(),
    getDraft: vi.fn(),
    updateDraft: vi.fn(),
  };
});

describe('useAgentCreationDraft', () => {
  it('serializes concurrent flush callers that wait for the same earlier save', async () => {
    let releaseFirst!: (value: Awaited<ReturnType<typeof api.createDraft>>) => void;
    let releaseSecond!: (value: Awaited<ReturnType<typeof api.updateDraft>>) => void;
    const saved = { draftId: 'waiters', ownerUserId: 'u1', mode: 'describe', sourceAgentId: null,
      purpose: '', name: 'A', description: '', behavior: '', tools: [], skills: [], resources: [],
      revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(api.createDraft).mockImplementationOnce(() => new Promise(resolve => { releaseFirst=resolve; }));
    vi.mocked(api.updateDraft).mockImplementation(() => new Promise(resolve => { releaseSecond=resolve; }));
    const {result,unmount}=renderHook(()=>useAgentCreationDraft({}));
    act(()=>result.current.updateName('A'));
    let first!:Promise<unknown>, second!:Promise<unknown>, third!:Promise<unknown>;
    act(()=>{first=result.current.flush();});
    act(()=>result.current.updateName('B'));
    act(()=>{second=result.current.flush(); third=result.current.flush();});
    await act(async()=>{releaseFirst(saved); await Promise.resolve();});
    expect(api.updateDraft).toHaveBeenCalledTimes(1);
    await act(async()=>{releaseSecond({...saved,name:'B',revision:2}); await Promise.all([first,second,third]);});
    unmount();
  });

  it('flushes edits made while the first save is in flight instead of marking them saved', async () => {
    let resolveFirst!: (draft: Awaited<ReturnType<typeof api.createDraft>>) => void;
    const saved = { draftId: 'race', ownerUserId: 'u1', mode: 'describe', sourceAgentId: null,
      purpose: '', name: 'A', description: '', behavior: '', tools: [], skills: [], resources: [],
      revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(api.createDraft).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }));
    vi.mocked(api.updateDraft).mockResolvedValueOnce({ ...saved, name: 'B', revision: 2 });
    const { result, unmount } = renderHook(() => useAgentCreationDraft({}));
    act(() => result.current.updateName('A'));
    let first!: Promise<unknown>;
    act(() => { first = result.current.flush(); });
    act(() => result.current.updateName('B'));
    await act(async () => { resolveFirst(saved); await first; });
    await act(async () => { await result.current.flush(); });
    expect(api.updateDraft).toHaveBeenCalledWith('race', 1, expect.objectContaining({ name: 'B' }));
    expect(result.current.hasUnsavedChanges).toBe(false);
    expect(result.current.name).toBe('B');
    unmount();
  });

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.mocked(api.createDraft).mockReset();
    vi.mocked(api.getDraft).mockReset();
    vi.mocked(api.updateDraft).mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('initializes with default empty state when no draftId is passed', () => {
    const { result } = renderHook(() => useAgentCreationDraft({}));
    expect(result.current.draftId).toBeNull();
    expect(result.current.revision).toBe(1);
    expect(result.current.purpose).toBe('');
    expect(result.current.name).toBe('');
    expect(result.current.status).toBe('idle');
  });

  it('does not create sidebar drafts for temporary new input even after debounce, and clears dirty when erased', async () => {
    const { result } = renderHook(() => useAgentCreationDraft({ deferInitialSave: true }));
    act(() => result.current.updatePurpose('临时随便输入'));
    await act(async () => { await vi.advanceTimersByTimeAsync(1600); });
    expect(api.createDraft).not.toHaveBeenCalled();
    expect(result.current.hasUnsavedChanges).toBe(true);
    act(() => result.current.updatePurpose('   '));
    await act(async () => { await vi.advanceTimersByTimeAsync(1600); });
    expect(api.createDraft).not.toHaveBeenCalled();
    expect(result.current.hasUnsavedChanges).toBe(false);
    await act(async () => { expect(await result.current.flush()).toBeNull(); });
    expect(api.createDraft).not.toHaveBeenCalled();
  });

  it('creates a deferred new draft only on explicit flush and then autosaves further edits', async () => {
    const saved = { draftId: 'explicit', ownerUserId: 'u', mode: 'describe', sourceAgentId: null, purpose: '', name: '手动助手', description: '', behavior: '', tools: [], skills: [], resources: [], revision: 1, createdAt: 0, updatedAt: 0 };
    vi.mocked(api.createDraft).mockResolvedValueOnce(saved);
    vi.mocked(api.updateDraft).mockResolvedValueOnce({ ...saved, behavior: '按资料回答', revision: 2 });
    const { result } = renderHook(() => useAgentCreationDraft({ deferInitialSave: true }));
    act(() => result.current.updateName(saved.name));
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(api.createDraft).not.toHaveBeenCalled();
    await act(async () => { await result.current.flush(); });
    expect(api.createDraft).toHaveBeenCalledTimes(1);
    act(() => result.current.updateBehavior('按资料回答'));
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(api.updateDraft).toHaveBeenCalledWith('explicit', 1, expect.objectContaining({ behavior: '按资料回答' }));
  });

  it('loads existing draft if initialDraftId is provided', async () => {
    vi.mocked(api.getDraft).mockResolvedValueOnce({
      draftId: 'draft-100',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '帮助整理会议纪要',
      name: '会议纪要助手',
      description: '自动提取会议待办',
      behavior: '请提取结论',
      tools: ['tool-1'],
      skills: ['skill-1'],
      resources: [],
      revision: 3,
      createdAt: 1000,
      updatedAt: 2000,
    });

    const { result } = renderHook(() =>
      useAgentCreationDraft({ initialDraftId: 'draft-100' }),
    );

    await act(async () => {
      await Promise.resolve();
    });

    expect(api.getDraft).toHaveBeenCalledWith('draft-100');
    expect(result.current.draftId).toBe('draft-100');
    expect(result.current.revision).toBe(3);
    expect(result.current.purpose).toBe('帮助整理会议纪要');
    expect(result.current.name).toBe('会议纪要助手');
    expect(result.current.status).toBe('saved');
  });

  it('debounces auto-save on input update and creates new draft on first save', async () => {
    const onCreated = vi.fn();
    vi.mocked(api.createDraft).mockResolvedValueOnce({
      draftId: 'new-draft-1',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '写周报',
      name: '周报助手',
      description: '',
      behavior: '',
      tools: [],
      skills: [],
      resources: [],
      revision: 1,
      createdAt: 1000,
      updatedAt: 1000,
    });

    const { result } = renderHook(() => useAgentCreationDraft({ onCreated }));

    act(() => {
      result.current.updatePurpose('写周报');
    });

    expect(result.current.purpose).toBe('写周报');
    expect(api.createDraft).not.toHaveBeenCalled();

    // Fast-forward 800ms debounce
    await act(async () => {
      vi.advanceTimersByTime(800);
      await Promise.resolve();
    });

    expect(api.createDraft).toHaveBeenCalledTimes(1);
    expect(result.current.draftId).toBe('new-draft-1');
    expect(result.current.status).toBe('saved');
    expect(onCreated).toHaveBeenCalledWith(
      expect.objectContaining({ draftId: 'new-draft-1' }),
    );
  });

  it('flushes immediately on flush() without waiting for debounce', async () => {
    vi.mocked(api.createDraft).mockResolvedValueOnce({
      draftId: 'draft-flush',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '即时刷新保存',
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

    const { result } = renderHook(() => useAgentCreationDraft({}));

    act(() => {
      result.current.updatePurpose('即时刷新保存');
    });

    await act(async () => {
      const res = await result.current.flush();
      expect(res?.draftId).toBe('draft-flush');
    });

    expect(api.createDraft).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe('saved');
  });

  it('handles 409 revision conflict gracefully', async () => {
    vi.mocked(api.getDraft).mockResolvedValueOnce({
      draftId: 'draft-conflict',
      ownerUserId: 'u1',
      mode: 'describe',
      sourceAgentId: null,
      purpose: '原内容',
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

    const { result } = renderHook(() =>
      useAgentCreationDraft({ initialDraftId: 'draft-conflict' }),
    );

    await act(async () => {
      await Promise.resolve();
    });

    vi.mocked(api.updateDraft).mockRejectedValueOnce({
      status: 409,
      message: 'revision_conflict',
    });

    act(() => {
      result.current.updatePurpose('冲突的修改');
    });

    await act(async () => {
      await result.current.flush();
    });

    expect(result.current.status).toBe('conflict');
    expect(result.current.errorMessage).toContain('并发修改');
  });
});
