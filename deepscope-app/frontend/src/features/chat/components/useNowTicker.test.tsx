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
import { StrictMode, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useNowTicker } from './useNowTicker';

describe('process clock resources', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });
  it('pauses hidden work and cleans up timers on completion and unmount', () => {
    vi.useFakeTimers();
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    const { result, rerender, unmount } = renderHook(({ active }) => useNowTicker(active), {
      initialProps: { active: true }, wrapper: ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode>,
    });
    expect(vi.getTimerCount()).toBe(1);
    const before = result.current;
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current).toBeGreaterThan(before);
    hidden.mockReturnValue(true);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(vi.getTimerCount()).toBe(0);
    hidden.mockReturnValue(false);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(vi.getTimerCount()).toBe(1);
    rerender({ active: false });
    expect(vi.getTimerCount()).toBe(0);
    rerender({ active: true });
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(vi.getTimerCount()).toBe(0);
  });
});
