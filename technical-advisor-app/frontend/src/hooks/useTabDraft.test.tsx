/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// @vitest-environment jsdom
import { act, renderHook, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { setAccountIdentity, clearAccountIdentity } from '@/lib/accountIdentity';
import { clearTabDrafts, draftKey } from '@/lib/tabDraftStorage';
import { useTabDraft } from './useTabDraft';
const account = (userId: string) => setAccountIdentity('fake-token', { userId, username: userId, roles: [] });
beforeEach(() => { sessionStorage.clear(); account('alice'); });
afterEach(() => { cleanup(); clearAccountIdentity(); sessionStorage.clear(); });

it('restores text immediately after remount, isolates sessions and clears on explicit reset', () => {
  const first = renderHook(() => useTabDraft('chat:one', ''));
  act(() => first.result.current[1]('Unsaved message'));
  expect(sessionStorage.getItem(draftKey('alice', 'chat:one')!)).toBe('"Unsaved message"');
  first.unmount();
  const next = renderHook(() => useTabDraft('chat:one', ''));
  expect(next.result.current[0]).toBe('Unsaved message');
  const other = renderHook(() => useTabDraft('chat:two', ''));
  expect(other.result.current[0]).toBe('');
  act(() => next.result.current[2]());
  expect(sessionStorage.getItem(draftKey('alice', 'chat:one')!)).toBeNull();
});
it('isolates users and ignores old acknowledgements after switching accounts', () => {
  const view = renderHook(() => useTabDraft('chat:new', ''));
  act(() => view.result.current[1]('Alice draft'));
  const oldSet = view.result.current[1];
  act(() => account('bob'));
  expect(view.result.current[0]).toBe('');
  act(() => { view.result.current[1]('Bob draft'); oldSet(''); });
  expect(view.result.current[0]).toBe('Bob draft');
  act(() => account('alice'));
  expect(view.result.current[0]).toBe('Alice draft');
});
it('uses the latest text for async receipt cleanup and handles corrupt storage', () => {
  sessionStorage.setItem(draftKey('alice', 'chat:one')!, '{broken');
  const view = renderHook(() => useTabDraft('chat:one', ''));
  const accepted = view.result.current[1];
  act(() => view.result.current[1]('New text'));
  act(() => accepted(current => current === 'Old text' ? '' : current));
  expect(view.result.current[0]).toBe('New text');
});
it('ignores a response callback from a previous mount after a newer draft is entered', () => {
  const first = renderHook(() => useTabDraft('chat:one', ''));
  act(() => first.result.current[1]('Submitted text'));
  const oldSet = first.result.current[1];
  first.unmount();
  const next = renderHook(() => useTabDraft('chat:one', ''));
  act(() => next.result.current[1]('New draft'));
  act(() => oldSet(current => current === 'Submitted text' ? '' : current));
  next.unmount();
  expect(renderHook(() => useTabDraft('chat:one', '')).result.current[0]).toBe('New draft');
});
it('retains model form secrets only in tab storage and clears all application drafts on sign out', () => {
  const initial = { modelName: '', secret: '' };
  const view = renderHook(() => useTabDraft('model:chat:new', initial));
  act(() => view.result.current[1]({ modelName: 'test-model', secret: 'fake-key' }));
  view.unmount();
  expect(renderHook(() => useTabDraft('model:chat:new', initial)).result.current[0]).toEqual({ modelName: 'test-model', secret: 'fake-key' });
  sessionStorage.setItem('unrelated', 'keep');
  clearTabDrafts();
  expect(sessionStorage.getItem(draftKey('alice', 'model:chat:new')!)).toBeNull();
  expect(sessionStorage.getItem('unrelated')).toBe('keep');
});
