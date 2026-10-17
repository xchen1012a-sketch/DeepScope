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
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { apiFetch } from '@/lib/apiClient';
import userEvent from '@testing-library/user-event';
import { useKnowledgeUpload } from './useKnowledgeUpload';

vi.mock('@/lib/apiClient', () => ({ apiFetch: vi.fn() }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

it('clears the native file selection when removing a queued file and accepts the same file without closing', async () => {
  vi.mocked(apiFetch).mockResolvedValue(undefined);
  function Harness() {
    const upload = useKnowledgeUpload('lib', true, vi.fn());
    return <><button onClick={upload.selectFiles}>Open upload</button>{upload.input}</>;
  }
  render(<Harness />);
  fireEvent.click(screen.getByText('Open upload'));
  const user = userEvent.setup();
  const file = new File(['same'], 'same.md', { type: 'text/markdown' });
  let input = screen.getByLabelText('knowledge.editor.documents.selectFiles') as HTMLInputElement;
  await user.upload(input, file);
  expect(screen.getByText('same.md')).toBeTruthy();
  // A native picker may restore its last selection; removal must discard that native control.
  const oldInput = input;
  fireEvent.click(screen.getByRole('button', { name: 'knowledge.uploadDialog.remove' }));
  input = screen.getByLabelText('knowledge.editor.documents.selectFiles') as HTMLInputElement;
  expect(input).not.toBe(oldInput);
  expect(input.files?.length).toBe(0);
  await user.upload(input, file);
  expect(screen.getByText('same.md')).toBeTruthy();
  fireEvent.click(screen.getByText('knowledge.uploadDialog.submit'));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(apiFetch).toHaveBeenCalledTimes(1);
});

it('reopens a completed upload with a fresh picker and accepts the same file again', async () => {
  vi.mocked(apiFetch).mockResolvedValue(undefined);
  function Harness() {
    const upload = useKnowledgeUpload('lib', true, vi.fn());
    return <><button onClick={upload.selectFiles}>Open upload</button>{upload.input}</>;
  }
  render(<Harness />);
  const file = new File(['same content'], 'same.md');
  fireEvent.click(screen.getByText('Open upload'));
  const input = screen.getByLabelText('knowledge.editor.documents.selectFiles');
  fireEvent.change(input, { target: { files: [file] } });
  fireEvent.click(screen.getByText('knowledge.uploadDialog.submit'));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.click(screen.getByText('Open upload'));
  expect(screen.queryByText('same.md')).toBeNull();
  expect(screen.getByText('knowledge.uploadDialog.choose')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('knowledge.editor.documents.selectFiles'), { target: { files: [file] } });
  fireEvent.click(screen.getByText('knowledge.uploadDialog.submit'));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('opens the file picker from the drop zone without reopening it for file actions', () => {
  function Harness() {
    const upload = useKnowledgeUpload('lib', true, vi.fn());
    return <><button onClick={upload.selectFiles}>Open upload</button>{upload.input}</>;
  }
  render(<Harness />);
  fireEvent.click(screen.getByText('Open upload'));
  const input = screen.getByLabelText('knowledge.editor.documents.selectFiles') as HTMLInputElement;
  const pick = vi.spyOn(input, 'click');
  const area = screen.getByRole('region', { name: 'knowledge.uploadDialog.area' });
  fireEvent.click(area);
  expect(pick).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByText('knowledge.uploadDialog.choose'));
  expect(pick).toHaveBeenCalledTimes(2);
  fireEvent.change(input, { target: { files: [new File(['text'], 'notes.md')] } });
  fireEvent.click(within(area).getByText('notes.md'));
  fireEvent.click(within(area).getByRole('button', { name: 'knowledge.uploadDialog.remove' }));
  expect(pick).toHaveBeenCalledTimes(2);
  const nextPick = vi.spyOn(screen.getByLabelText('knowledge.editor.documents.selectFiles') as HTMLInputElement, 'click');
  fireEvent.keyDown(area, { key: 'Enter' });
  expect(nextPick).toHaveBeenCalledTimes(1);
});

it('keeps uploaded files inside the drop zone and retries only failed files', async () => {
  vi.mocked(apiFetch).mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce(undefined);
  function Harness() {
    const upload = useKnowledgeUpload('lib', true, vi.fn());
    return <><button onClick={upload.selectFiles}>Open upload</button>{upload.input}</>;
  }
  render(<Harness />);
  fireEvent.click(screen.getByText('Open upload'));
  fireEvent.change(screen.getByLabelText('knowledge.editor.documents.selectFiles'), { target: { files: [new File(['one'], 'one.md'), new File(['two'], 'two.md')] } });
  expect(apiFetch).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('knowledge.uploadDialog.submit'));
  const area = screen.getByRole('region', { name: 'knowledge.uploadDialog.area' });
  await within(area).findByText('knowledge.uploadDialog.failed');
  expect(within(area).getByText('one.md')).toBeTruthy();
  expect(within(area).getByText('knowledge.uploadDialog.uploaded')).toBeTruthy();
  fireEvent.click(screen.getByText('knowledge.uploadDialog.submit'));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(apiFetch).toHaveBeenCalledTimes(3);
});

it('uploads selected files directly and refreshes partial successes when a later upload fails', async () => {
  vi.mocked(apiFetch).mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('network failed'));
  const changed = vi.fn();
  const { result } = renderHook(() => useKnowledgeUpload('lib', true, changed));
  await act(() => result.current.uploadFiles([new File(['one'], 'one.md'), new File(['two'], 'two.md')]));
  expect(apiFetch).toHaveBeenCalledTimes(2);
  expect(apiFetch).toHaveBeenNthCalledWith(1, '/api/advisor/knowledge/lib/documents', expect.objectContaining({ method: 'POST', body: expect.any(FormData) }));
  expect(changed).toHaveBeenCalledTimes(1);
  expect(result.current.error).toBe('knowledge.editor.documents.uploadFailed');
});

it('ignores concurrent upload starts and read-only attempts', async () => {
  let resolve!: () => void;
  vi.mocked(apiFetch).mockImplementation(() => new Promise<void>(done => { resolve = done; }));
  const { result } = renderHook(() => useKnowledgeUpload('lib', true, vi.fn()));
  let first!: Promise<void>;
  act(() => { first = result.current.uploadFiles([new File(['one'], 'one.md')]); });
  await act(() => result.current.uploadFiles([new File(['two'], 'two.md')]));
  expect(apiFetch).toHaveBeenCalledTimes(1);
  await act(async () => { resolve(); await first; });
  const readonly = renderHook(() => useKnowledgeUpload('lib', false, vi.fn()));
  await act(() => readonly.result.current.uploadFiles([new File(['three'], 'three.md')]));
  expect(apiFetch).toHaveBeenCalledTimes(1);
});
