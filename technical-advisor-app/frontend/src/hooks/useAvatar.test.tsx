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
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useAvatar } from './useAvatar';
import { getAvatar } from '@/api/account';
vi.mock('@/api/account', () => ({ getAvatar: vi.fn() }));
const revoke = vi.fn();
let count = 0;
beforeEach(() => {
  count = 0; revoke.mockReset();
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:avatar-' + ++count), revokeObjectURL: revoke });
  vi.mocked(getAvatar).mockImplementation(async () => new Blob(['avatar'], { type: 'image/png' }));
});
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals(); });
function wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>;
}
it('releases old object URLs on version change and unmount', async () => {
  const { result, rerender, unmount } = renderHook(({ version }) => useAvatar({ userId: 'alice', avatarUrl: '/api/me/avatar', avatarUpdatedAt: version }), { wrapper, initialProps: { version: 1 } });
  await waitFor(() => expect(result.current.url).toBe('blob:avatar-1'));
  rerender({ version: 2 });
  await waitFor(() => expect(result.current.url).toBe('blob:avatar-2'));
  expect(revoke).toHaveBeenCalledWith('blob:avatar-1');
  unmount(); expect(revoke).toHaveBeenCalledWith('blob:avatar-2');
});
it('never displays the previous account avatar while the next request is pending', async () => {
  const { result, rerender } = renderHook(({ userId }) => useAvatar({ userId, avatarUrl: '/api/me/avatar', avatarUpdatedAt: 1 }), { wrapper, initialProps: { userId: 'alice' } });
  await waitFor(() => expect(result.current.url).toBe('blob:avatar-1'));
  let release: (value: Blob) => void = () => undefined;
  vi.mocked(getAvatar).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  rerender({ userId: 'bob' });
  expect(result.current.url).toBeUndefined();
  await act(async () => release(new Blob(['bob'])));
  await waitFor(() => expect(result.current.url).toBe('blob:avatar-2'));
});
