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
import { cleanup, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiFetch } from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import SettingsPage from './SettingsPage';
import { setAccountIdentity, clearAccountIdentity } from '@/lib/accountIdentity';
import { clearTabDrafts } from '@/lib/tabDraftStorage';
vi.mock('@/api/auth', () => ({ can: () => true, getUserId: () => 'test-user', getUsername: () => 'test-user', isAdmin: () => true }));

vi.mock('@/lib/apiClient', async (original) => ({
  ...await original<typeof import('@/lib/apiClient')>(),
  api: { get: vi.fn(), post: vi.fn() }, apiFetch: vi.fn(),
}));
const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
const models = [
  { endpointId: 'one', kind: 'chat', provider: 'openai', modelName: 'gpt-model', displayName: 'Primary', current: true, baseUrl: '', secretMask: '••••', lastTestStatus: 'passed', lastTestAt: 1720000000000, lastTestElapsedMs: 120 },
  { endpointId: 'two', kind: 'chat', provider: 'dashscope', modelName: 'qwen-plus', displayName: 'Qwen Plus', current: false, baseUrl: '', secretMask: '••••' },
];
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><SettingsPage /></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}
beforeEach(() => {
  vi.mocked(api.get).mockImplementation(async path => {
    const params = new URL(String(path), 'http://localhost').searchParams;
    const items = params.get('kind') === 'embedding' ? [] : models.filter(model => !params.get('q') || model.modelName.includes(params.get('q')!));
    return { items, page: Number(params.get('page') ?? 1), size: 10, total: items.length, totalPages: 1, defaultModel: models[0] };
  });
  vi.mocked(api.post).mockResolvedValue({ ok: true, message: '', elapsedMs: 120, dimensions: null });
  vi.mocked(apiFetch).mockImplementation(async (path, options) => {
    if (String(path).includes('/providers')) return { items: [{ id: 'openai', name: 'OpenAI', provider: 'openai', baseUrl: 'https://api.openai.com/v1' }, { id: 'deepseek', name: 'DeepSeek', provider: 'openai', baseUrl: 'https://api.deepseek.com/v1' }], total: 2 };
    if (String(path).endsWith('/options') && options?.body) return { items: ['gpt-4.1', 'backend-only-model'], total: 2 };
    return {};
  });
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

async function openCreate() {
  const user = mount();
  await user.click(screen.getByRole('button', { name: 'Connect model' }));
  const drawer = screen.getByRole('dialog');
  await user.click(within(drawer).getByRole('combobox', { name: 'Provider' }));
  await user.click(await screen.findByRole('option', { name: 'OpenAI' }));
  await user.type(within(drawer).getByLabelText('API Key'), 'sk-test-secret');
  await user.type(within(drawer).getByLabelText('Model ID'), 'gpt-new');
  return { user, drawer };
}

it('selects a PDF parsing model in the existing configuration drawer without changing chat default', async () => {
  const user = mount();
  const row = await screen.findByRole('row', { name: /Qwen Plus/ });
  await user.click(within(row).getByRole('button', { name: /Configure/ }));
  const drawer = screen.getByRole('dialog');
  await user.click(within(drawer).getByRole('checkbox', { name: 'Use for knowledge PDF parsing' }));
  expect(within(drawer).getByText(/Saving sends a test image/)).toBeTruthy();
  await user.click(within(drawer).getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/two', expect.objectContaining({
    method: 'PUT', body: expect.stringContaining('"knowledgePdf":true'),
  })));
  const request = vi.mocked(apiFetch).mock.calls.find(([path]) => path === '/api/advisor/models/two')?.[1];
  expect(JSON.parse(String(request?.body)).makeCurrent).toBe(false);
});

describe('model settings experience', () => {
  it('restores an unfinished connection after remount and removes it after explicit discard', async () => {
    setAccountIdentity('test-only', { userId: 'draft-owner', username: 'draft-owner', roles: [] });
    clearTabDrafts();
    try {
      await openCreate();
      cleanup();
      const user = mount();
      await user.click(screen.getByRole('button', { name: 'Connect model' }));
      const drawer = screen.getByRole('dialog');
      expect((within(drawer).getByLabelText('Model ID') as HTMLInputElement).value).toBe('gpt-new');
      expect((within(drawer).getByLabelText('API Key') as HTMLInputElement).value).toBe('sk-test-secret');
      expect((within(drawer).getByRole('combobox', { name: 'Provider' }) as HTMLInputElement).value).toBe('OpenAI');
      await user.click(within(drawer).getByRole('button', { name: 'Cancel' }));
      await user.click(within(screen.getByRole('dialog', { name: 'Discard changes?' })).getByRole('button', { name: i18n.t('modelSettings.manage.discard') }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      await user.click(screen.getByRole('button', { name: 'Connect model' }));
      expect((screen.getByLabelText('Model ID') as HTMLInputElement).value).toBe('');
      expect((screen.getByLabelText('API Key') as HTMLInputElement).value).toBe('');
    } finally { cleanup(); clearTabDrafts(); clearAccountIdentity(); }
  });
  it('duplicates a configuration into a new persisted list row instead of the clipboard', async () => {
    const user = mount();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    const copy = { ...models[1], endpointId: 'copied-model', displayName: 'Qwen Plus copy' };
    vi.mocked(api.post).mockImplementation(async path => {
      if (path.endsWith('/copy')) {
        vi.mocked(api.get).mockResolvedValue({ items: [...models, copy], page: 1, size: 10, total: 3, totalPages: 1 });
        return copy;
      }
      return {};
    });
    await user.click(within(await screen.findByRole('row', { name: /Qwen Plus/ })).getByRole('button', { name: 'Copy configuration' }));
    expect(await screen.findByText('Qwen Plus copy')).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/api/advisor/models/two/copy', { displayName: 'Qwen Plus copy' });
    expect(writeText).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
  it('preserves the source and reports a failed duplication without creating a local row', async () => {
    const user = mount();
    vi.mocked(api.post).mockRejectedValue(new Error('copy failed'));
    await user.click(within(await screen.findByRole('row', { name: /Qwen Plus/ })).getByRole('button', { name: 'Copy configuration' }));
    expect(await screen.findByText('Could not copy. Please retry.')).toBeTruthy();
    expect(screen.getByText('Qwen Plus')).toBeTruthy();
    expect(screen.queryByText('Qwen Plus copy')).toBeNull();
  });
  it('requests embedding suppliers and embedding IDs for vector connections', async () => {
    const user = mount();
    await user.click(screen.getByRole('tab', { name: 'Vector model' }));
    await user.click(screen.getAllByRole('button', { name: 'Connect model' })[0]);
    const drawer = screen.getByRole('dialog');
    await user.click(within(drawer).getByRole('combobox', { name: 'Provider' }));
    await user.click(await screen.findByRole('option', { name: 'OpenAI' }));
    await user.click(within(drawer).getByRole('combobox', { name: 'Model ID' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/options', expect.objectContaining({ body: expect.stringContaining('"kind":"embedding"') })));
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).includes('providers?kind=embedding'))).toBe(true);
  });
  it('shows connection success as a temporary top toast instead of a list footer message', async () => {
    const user = mount();
    const row = await screen.findByRole('row', { name: /Qwen Plus/ });
    await user.click(within(row).getByRole('button', { name: /More actions/ }));
    await user.click(screen.getByRole('menuitem', { name: 'Test connection' }));
    await waitFor(() => expect(screen.getByRole('status', { name: 'Notification' }).textContent).toMatch(/Test passed/));
    const toast = screen.getByRole('status', { name: 'Notification' });
    expect(toast.className).toContain('fixed');
    expect(toast.className).toContain('top-');
    expect(screen.queryByText('Testing…')).toBeNull();
  });
  it('reports the count actually fetched from the selected supplier', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Connect model' }));
    const drawer = screen.getByRole('dialog');
    vi.mocked(apiFetch).mockResolvedValueOnce({ items: [{ id: 'zhipu', name: '智谱 GLM', provider: 'openai', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' }], total: 1 });
    await user.click(within(drawer).getByRole('combobox', { name: 'Provider' }));
    await user.click(await screen.findByRole('option', { name: '智谱 GLM' }));
    await user.type(within(drawer).getByLabelText('API Key'), 'sk-test-secret');
    vi.mocked(api.post).mockResolvedValueOnce({ items: ['glm-5.3', 'glm-4.7'], total: 2, complete: true, status: 'discovered' });
    await user.click(within(drawer).getByRole('button', { name: 'Fetch models' }));
    expect(await within(drawer).findByText('Fetched 2 models from this provider. Open Model ID to select.')).toBeTruthy();
    expect(api.post).toHaveBeenLastCalledWith('/api/advisor/models/discover', expect.objectContaining({ provider: 'openai', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', secret: 'sk-test-secret' }));
  });
  it('opens a blank new connection without automatically opening or fetching dropdowns', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Connect model' }));
    const drawer = screen.getByRole('dialog');
    expect((within(drawer).getByRole('combobox', { name: 'Provider' }) as HTMLInputElement).value).toBe('');
    expect((within(drawer).getByLabelText('Address') as HTMLInputElement).value).toBe('');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(apiFetch).not.toHaveBeenCalled();
  });
  it('switches to a custom OpenAI-compatible supplier when the address is edited', async () => {
    const { user, drawer } = await openCreate();
    const address = within(drawer).getByLabelText('Address');
    await user.clear(address);
    await user.type(address, 'https://gateway.example.com/v1');
    expect((within(drawer).getByRole('combobox', { name: 'Provider' }) as HTMLInputElement).value).toBe('Custom OpenAI-compatible service');
    expect((address as HTMLInputElement).value).toBe('https://gateway.example.com/v1');
  });
  it('keeps partial discovery visibly incomplete instead of claiming the full provider catalog', async () => {
    const { user, drawer } = await openCreate();
    vi.mocked(api.post).mockResolvedValueOnce({ items: ['partial-id'], total: 1, complete: false, status: 'discovered' });
    await user.click(within(drawer).getByRole('button', { name: 'Fetch models' }));
    expect(await within(drawer).findByText('Fetched 1 models; this list is incomplete. You can still enter a model ID.')).toBeTruthy();
  });
  it('opens existing configuration with its saved ID but without fetching model options', async () => {
    const user = mount();
    const row = await screen.findByRole('row', { name: /Qwen Plus/ });
    await user.click(within(row).getByRole('button', { name: 'Configure' }));
    expect((screen.getByRole('combobox', { name: 'Model ID' }) as HTMLInputElement).value).toBe('qwen-plus');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(apiFetch).not.toHaveBeenCalled();
  });
  it('retains the supplier key link when reopening an OpenAI-compatible endpoint', async () => {
    const endpoint = { ...models[1], provider: 'openai', providerName: 'DeepSeek', presetId: 'deepseek', baseUrl: 'https://api.deepseek.com/v1', providerKeysUrl: 'https://platform.deepseek.com/api_keys' };
    vi.mocked(api.get).mockResolvedValue({ items: [endpoint], page: 1, size: 10, total: 1 });
    const user = mount();
    await user.click(within(await screen.findByRole('row', { name: /Qwen Plus/ })).getByRole('button', { name: 'Configure' }));
    const drawer = screen.getByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: 'Replace key' }));
    expect(within(drawer).getByRole('link', { name: 'Get API key' }).getAttribute('href')).toBe('https://platform.deepseek.com/api_keys');
  });
  it('searches providers on the server and applies the selected supplier connection', async () => {
    const user = mount();
    await user.click(screen.getByRole('button', { name: 'Connect model' }));
    const drawer = screen.getByRole('dialog');
    const provider = within(drawer).getByRole('combobox', { name: 'Provider' });
    await user.click(provider);
    await user.type(provider, 'deep');
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(expect.stringMatching(/\/providers\?.*q=deep/), expect.objectContaining({ signal: expect.any(AbortSignal) })));
    await user.click(await screen.findByRole('option', { name: 'DeepSeek' }));
    expect((within(drawer).getByLabelText('Address') as HTMLInputElement).value).toBe('https://api.deepseek.com/v1');
    expect(drawer.querySelector('details')).toBeNull();
    await user.type(within(drawer).getByLabelText('API Key'), 'keep-this-key');
    await user.click(within(drawer).getByRole('combobox', { name: 'Model ID' }));
    expect(await screen.findByRole('option', { name: 'backend-only-model' })).toBeTruthy();
    await user.type(within(drawer).getByRole('combobox', { name: 'Model ID' }), 'ds');
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/options', expect.objectContaining({
      method: 'POST', body: expect.stringContaining('"q":"ds"'),
    })));
    // This does not match the query: its presence proves that the UI renders the server response.
    expect(await screen.findByRole('option', { name: 'backend-only-model' })).toBeTruthy();
    await user.click(screen.getByRole('option', { name: 'backend-only-model' }));
    expect((within(drawer).getByLabelText('Model ID') as HTMLInputElement).value).toBe('backend-only-model');
    expect((within(drawer).getByLabelText('API Key') as HTMLInputElement).value).toBe('keep-this-key');
  });
  it('uses list columns instead of a large default model summary', async () => {
    mount();
    await screen.findByText('Qwen Plus');
    expect(screen.queryByText('Default chat model')).toBeNull();
    expect(screen.getByRole('columnheader', { name: 'API address' })).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'API key' })).toBeTruthy();
  });
  it('requests the next model page and sends provider filters to the server', async () => {
    vi.mocked(api.get).mockImplementation(async path => {
      const params = new URL(String(path), 'http://localhost').searchParams;
      const page = Number(params.get('page') ?? 1);
      return { items: [models[page === 2 ? 1 : 0]], page, size: 10, total: 11, totalPages: 2, defaultModel: models[0] };
    });
    const user = mount();
    await screen.findByRole('row', { name: /gpt-model/ });
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await screen.findByRole('row', { name: /Qwen Plus/ });
    expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/page=2/));
    await user.click(screen.getByRole('combobox', { name: 'Filter provider' }));
    await user.click(await screen.findByRole('option', { name: 'OpenAI' }));
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/page=1.*presetId=openai/)));
    const calls = vi.mocked(api.get).mock.calls;
    const lastPath = String(calls[calls.length - 1]?.[0]);
    expect(new URL(lastPath, 'http://localhost').searchParams.get('kind')).toBe('chat');
  });

  it('keeps results until search is submitted and resets on tab change', async () => {
    const user = mount();
    await screen.findByText('Qwen Plus');
    await user.type(screen.getByRole('searchbox'), 'qwen');
    expect(screen.getAllByText('Primary').length).toBeGreaterThan(0);
    expect(vi.mocked(api.get).mock.calls).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.queryByRole('row', { name: /gpt-model/ })).toBeNull());
    expect(api.get).toHaveBeenLastCalledWith(expect.stringMatching(/q=qwen/));
    expect(screen.queryByText('Primary')).toBeNull();
    const requestCount = vi.mocked(api.get).mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(vi.mocked(api.get).mock.calls.length).toBeGreaterThan(requestCount));
    await user.click(screen.getByRole('tab', { name: 'Vector model' }));
    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('');
  });

  it('tests without saving and invalidates a successful result when the model changes', async () => {
    const { user, drawer } = await openCreate();
    await user.click(within(drawer).getByRole('button', { name: 'Test connection' }));
    await within(drawer).findByText(/Test passed/);
    expect(api.post).toHaveBeenCalledWith('/api/advisor/models/test', expect.objectContaining({ modelName: 'gpt-new', secret: 'sk-test-secret' }));
    expect(vi.mocked(api.post).mock.calls.some(([path]) => path === '/api/advisor/models')).toBe(false);
    await user.type(within(drawer).getByLabelText('Model ID'), '-changed');
    expect(within(drawer).queryByText(/Test passed/)).toBeNull();
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/advisor/models', expect.objectContaining({ modelName: 'gpt-new-changed', makeCurrent: false })));
  });

  it('preserves failed drafts and confirms discarding changes', async () => {
    const { user, drawer } = await openCreate();
    vi.mocked(api.post).mockRejectedValue(new Error('unavailable'));
    await user.click(within(drawer).getByRole('button', { name: 'Save' }));
    await within(drawer).findByRole('alert');
    expect((within(drawer).getByLabelText('Model ID') as HTMLInputElement).value).toBe('gpt-new');
    await user.click(within(drawer).getByRole('button', { name: 'Cancel' }));
    const confirm = screen.getByRole('dialog', { name: 'Discard changes?' });
    await user.click(within(confirm).getByRole('button', { name: 'Keep editing' }));
    expect((screen.getByLabelText('Model ID') as HTMLInputElement).value).toBe('gpt-new');
  });

  it('edits a model while retaining the stored secret', async () => {
    const user = mount();
    const row = await screen.findByRole('row', { name: /Qwen Plus/ });
    await user.click(within(row).getByRole('button', { name: 'Configure' }));
    const drawer = screen.getByRole('dialog');
    expect(within(drawer).queryByLabelText('API Key')).toBeNull();
    await user.clear(within(drawer).getByLabelText('Display name'));
    await user.type(within(drawer).getByLabelText('Display name'), 'Renamed');
    await user.click(within(drawer).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/two', expect.objectContaining({ method: 'PUT' })));
    const request = vi.mocked(apiFetch).mock.calls.find(([path]) => path === '/api/advisor/models/two');
    const body = JSON.parse(String(request?.[1]?.body));
    expect(body.displayName).toBe('Renamed');
    expect(body.secret).toBeUndefined();
  });

  it('requires confirmation before changing the system default', async () => {
    const user = mount();
    const row = await screen.findByRole('row', { name: /Qwen Plus/ });
    await user.click(within(row).getByRole('button', { name: /More actions/ }));
    await user.click(screen.getByRole('menuitem', { name: 'Set as default' }));
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).endsWith('/current'))).toBe(false);
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Set as default' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/models/two/current', { method: 'POST' }));
  });

  it('retains manual input if fetching models is unsupported', async () => {
    const { user, drawer } = await openCreate();
    vi.mocked(api.post).mockRejectedValue(new Error('not supported'));
    await user.click(within(drawer).getByRole('button', { name: 'Fetch models' }));
    await within(drawer).findByText('Could not fetch models. Enter a model ID manually.');
    expect((within(drawer).getByLabelText('Model ID') as HTMLInputElement).value).toBe('gpt-new');
  });
});
