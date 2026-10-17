// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { api, apiFetch, ApiError } from '@/lib/apiClient';
import { RetrievalSettingsTab } from './RetrievalSettingsTab';
import type { LibraryDetail } from '../types';

vi.mock('@/lib/apiClient', async original => ({ ...await original<typeof import('@/lib/apiClient')>(), apiFetch: vi.fn(), api: { get: vi.fn() } }));

const rebind = { model: 'Vector model', choose: 'Choose a vector model', save: 'Save model', title: 'Change vector model', confirm: 'Change and rebuild', cancel: 'Cancel', systemDefault: 'System default', notice: 'Rebuild started', saved: 'Model saved', busy: 'Library is busy', failed: 'Model change failed', description: 'Rebuild {{count}} documents and pause retrieval?', help: 'Changes require reindexing.', loadFailed: 'Could not load models', reload: 'Reload', rebuilding: 'Retrieval paused during rebuild.' };

const labels = {
  preset: 'Processing preset', advanced: 'Advanced settings', expand: 'Expand', collapse: 'Collapse',
  general: 'General', qa: 'Q&A', long: 'Long document', custom: 'Custom', chunking: 'Chunking',
  retrieval: 'Retrieval', chunkSize: 'Chunk length', overlap: 'Overlap', recallLimit: 'Result limit',
  threshold: 'Similarity threshold', reset: 'Reset changes', save: 'Save', saving: 'Saving',
  saved: 'Saved', savedReprocess: 'Saved; reprocess existing documents',
  chunkingNotice: 'Chunk settings apply to new or reprocessed documents.',
  invalid: 'Check the parameters.', readOnly: 'Read-only settings.', saveFailed: 'Save failed.',
};
const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: { en: { translation: { knowledge: { rebind, editor: { settings: labels } } } } },
  lng: 'en', interpolation: { escapeValue: false }, react: { useSuspense: false },
});
const library: LibraryDetail = {
  libraryId: 'lib-1', name: 'Library', description: '', chunkSize: 512, overlap: 50,
  recallLimit: 5, scoreThreshold: 0.2, readyCount: 1, canEdit: true, documents: [],
};

function setup(value = library) {
  const onChanged = vi.fn();
  const onReindexed = vi.fn();
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const view = (detail: LibraryDetail) => <I18nextProvider i18n={i18n}>
    <QueryClientProvider client={client}><RetrievalSettingsTab library={detail} onChanged={onChanged} onReindexed={onReindexed} /></QueryClientProvider>
  </I18nextProvider>;
  return { ...render(view(value)), onChanged, onReindexed, update: view };
}

function expand() { fireEvent.click(screen.getByRole('button', { name: 'Expand' })); }
function change(name: string, value: string) { fireEvent.change(screen.getByRole('spinbutton', { name }), { target: { value } }); }

describe('RetrievalSettingsTab', () => {
  beforeEach(() => {
    vi.resetAllMocks(); vi.mocked(apiFetch).mockResolvedValue(undefined);
    vi.mocked(api.get).mockResolvedValue([{ endpointId: 'old', name: 'Old model', dimensions: 2, systemDefault: false }, { endpointId: 'new', name: 'New model', dimensions: 3, systemDefault: true }]);
  });
  afterEach(cleanup);

  it('saves an empty library model directly without a confirmation dialog', async () => {
    vi.mocked(apiFetch).mockResolvedValue({ affectedDocuments: 0 });
    const { onChanged, onReindexed } = setup({ ...library, embeddingEndpointId: 'old', documentCount: 0 });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: rebind.model }));
    await user.click(screen.getByRole('menuitem', { name: 'New model (System default)' }));
    await user.click(screen.getByRole('button', { name: rebind.save }));
    await screen.findByText(rebind.saved);
    expect(screen.queryByRole('dialog')).toBeNull(); expect(onChanged).toHaveBeenCalledOnce(); expect(onReindexed).not.toHaveBeenCalled();
  });

  it('keeps the selected target after a conflict and permits retry', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(409, 'busy')).mockResolvedValue({ affectedDocuments: 0 });
    const { onChanged } = setup({ ...library, embeddingEndpointId: 'old', documentCount: 0 });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: rebind.model }));
    await user.click(screen.getByRole('menuitem', { name: 'New model (System default)' }));
    await user.click(screen.getByRole('button', { name: rebind.save }));
    expect((await screen.findByRole('alert')).textContent).toBe(rebind.busy); expect(onChanged).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: rebind.save }));
    await screen.findByText(rebind.saved); expect(onChanged).toHaveBeenCalledOnce();
  });

  it('requires confirmation for populated libraries and cancellation preserves the binding', async () => {
    vi.mocked(apiFetch).mockResolvedValue({ affectedDocuments: 2 });
    const { onReindexed } = setup({ ...library, embeddingEndpointId: 'old', embeddingModelName: 'Old model', documentCount: 2 });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: rebind.model }));
    await user.click(screen.getByRole('menuitem', { name: 'New model (System default)' }));
    await user.click(screen.getByRole('button', { name: rebind.save }));
    expect(screen.getByRole('dialog')).toBeTruthy(); expect(apiFetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: rebind.cancel }));
    expect(apiFetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: rebind.save }));
    await user.click(screen.getByRole('button', { name: rebind.confirm }));
    await waitFor(() => expect(onReindexed).toHaveBeenCalledOnce());
    expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib-1/reindex', { method: 'POST', body: '{"embeddingEndpointId":"new"}' });
  });

  it('starts with one preset selector and collapsed advanced fields', () => {
    setup();
    expect(screen.getByRole('button', { name: 'Processing preset' }).textContent).toContain('General');
    expect(screen.queryByRole('spinbutton')).toBeNull();
    expand();
    expect(screen.getAllByRole('spinbutton')).toHaveLength(4);
    expect(screen.queryByRole('slider')).toBeNull();
  });

  it.each([
    ['Q&A', { chunkSize: 256, overlap: 30, recallLimit: 3, scoreThreshold: 0.4 }],
    ['Long document', { chunkSize: 1024, overlap: 100, recallLimit: 8, scoreThreshold: 0.15 }],
  ])('selects and saves the %s preset in one step', async (name, values) => {
    setup();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Processing preset' }));
    await user.click(screen.getByRole('menuitem', { name }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(labels.savedReprocess);
    expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib-1', {
      method: 'PATCH', body: JSON.stringify(values),
    });
  });

  it('opens custom parameters without changing the stored values', async () => {
    setup();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Processing preset' }));
    await user.click(screen.getByRole('menuitem', { name: 'Custom' }));
    expect(screen.getAllByRole('spinbutton')).toHaveLength(4);
    expect((screen.getByRole('spinbutton', { name: 'Chunk length' }) as HTMLInputElement).value).toBe('512');
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('saves changed chunk settings and updates its own saved baseline', async () => {
    const { onChanged } = setup(); expand(); change('Chunk length', '600');
    expect(screen.getByText(labels.chunkingNotice)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(labels.savedReprocess);
    expect(apiFetch).toHaveBeenCalledWith('/api/advisor/knowledge/lib-1', {
      method: 'PATCH', body: JSON.stringify({ chunkSize: 600, overlap: 50, recallLimit: 5, scoreThreshold: 0.2 }),
    });
    expect(onChanged).toHaveBeenCalledOnce();
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('preserves drafts on background refresh and resets explicitly', () => {
    const { rerender, update } = setup(); expand(); change('Result limit', '7');
    rerender(update({ ...library, readyCount: 2 }));
    expect((screen.getByRole('spinbutton', { name: 'Result limit' }) as HTMLInputElement).value).toBe('7');
    fireEvent.click(screen.getByRole('button', { name: 'Reset changes' }));
    expect((screen.getByRole('spinbutton', { name: 'Result limit' }) as HTMLInputElement).value).toBe('5');
  });

  it.each([
    ['Chunk length', ''], ['Chunk length', '0'], ['Chunk length', '1.5'],
    ['Overlap', '512'], ['Overlap', '-1'], ['Result limit', '21'],
    ['Result limit', '0'], ['Similarity threshold', '1.1'], ['Similarity threshold', '-0.1'],
  ])('blocks invalid %s value %s', async (name, value) => {
    setup(); expand(); change(name, value);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('alert').textContent).toBe(labels.invalid);
    expect(apiFetch).not.toHaveBeenCalled();
    expect((screen.getByRole('spinbutton', { name }) as HTMLInputElement).value).toBe(value);
  });

  it('keeps fields after a failed save and allows retry', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new Error('Network unavailable'));
    setup(); expand(); change('Result limit', '7');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findByRole('alert')).textContent).toBe(labels.saveFailed);
    expect((screen.getByRole('spinbutton', { name: 'Result limit' }) as HTMLInputElement).value).toBe('7');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByText('Saved')).toBeTruthy());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows a concise read-only state', () => {
    setup({ ...library, canEdit: false });
    expect(screen.getByText(labels.readOnly)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(screen.queryByRole('button', { name: rebind.save })).toBeNull();
  });
});
