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
import { useState } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { can } from '@/api/auth';
import { api, apiFetch } from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import { ModelPicker } from './ModelPicker';
import type { ReasoningEffort } from '../aguiClient';

vi.mock('@/api/auth', () => ({ can: vi.fn(() => true) }));
vi.mock('@/lib/apiClient', () => ({ api: { get: vi.fn() }, apiFetch: vi.fn() }));

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources, lng: 'en', fallbackLng: 'en', defaultNS: 'app',
  interpolation: { escapeValue: false }, react: { useSuspense: false },
});

const models = [
  { endpointId: 'deepseek', kind: 'chat', modelName: 'DeepSeek', current: true },
  { endpointId: 'second/model', kind: 'chat', modelName: 'Sonnet', current: false },
];

function Harness({ disabled = false }: { disabled?: boolean }) {
  const [thinking, setThinking] = useState(false);
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');
  return (
    <ModelPicker
      thinking={thinking}
      onToggleThinking={() => setThinking(!thinking)}
      reasoningEffort={reasoningEffort}
      onSelectReasoningEffort={setReasoningEffort}
      sessionId="session-a"
      disabled={disabled}
      fallbackModelName="Reported model"
    />
  );
}

function mount(disabled = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><Harness disabled={disabled} /></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}

beforeEach(() => {
  vi.mocked(can).mockReturnValue(true);
  vi.mocked(api.get).mockResolvedValue({ endpointId: 'deepseek', choices: models });
  vi.mocked(apiFetch).mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('ModelPicker', () => {
  it('changes only the current session model', async () => {
    const user = mount();
    await screen.findByText('DeepSeek');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    expect(screen.getByRole('menuitemradio', { name: 'DeepSeek' }).getAttribute('aria-checked')).toBe('true');
    vi.mocked(api.get).mockResolvedValue({ endpointId: 'second/model', choices: models });
    await user.click(screen.getByRole('menuitemradio', { name: 'Sonnet' }));
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/api/advisor/sessions/session-a/model', { method: 'PUT', body: JSON.stringify({ endpointId: 'second/model' }) }));
    await screen.findByText('Sonnet');
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('selects the effective default directly without a follow-default menu item', async () => {
    const user = mount();
    await screen.findByText('DeepSeek');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    expect(screen.queryByRole('menuitemradio', { name: i18n.t('chat.composer.followDefault') })).toBeNull();
    expect(screen.getByRole('menuitemradio', { name: 'DeepSeek' }).getAttribute('aria-checked')).toBe('true');
  });

  it('keeps Think inside the menu and reflects its selected state at the entry', async () => {
    const user = mount();
    await screen.findByText('DeepSeek');
    expect(screen.queryByRole('menuitemcheckbox')).toBeNull();
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    const thinking = screen.getByRole('menuitemcheckbox', { name: 'Think' });
    await user.click(thinking);
    expect(thinking.getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).toContain('Think');
    await user.click(thinking);
    expect(thinking.getAttribute('aria-checked')).toBe('false');
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).not.toContain('Think');
  });

  it('allows Think without model-management permission and preserves the reported model', async () => {
    vi.mocked(can).mockReturnValue(false);
    const user = mount();
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).toContain('Reported model');
    expect(screen.queryByRole('menuitemradio', { name: 'DeepSeek' })).toBeNull();
    expect(screen.queryByRole('menuitemradio', { name: 'Sonnet' })).toBeNull();
    await user.click(screen.getByRole('menuitemcheckbox', { name: 'Think' }));
    expect(screen.getByRole('menuitemcheckbox').getAttribute('aria-checked')).toBe('true');
    expect(api.get).toHaveBeenCalledWith('/api/advisor/sessions/session-a/model');
    expect(api.get).not.toHaveBeenCalledWith('/api/advisor/models');
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('keeps the current model and shows an error when switching fails', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new Error('Model unavailable'));
    const user = mount();
    await screen.findByText('DeepSeek');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    await user.click(screen.getByRole('menuitemradio', { name: 'Sonnet' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).toContain('DeepSeek');
    expect(screen.getByRole('menuitemradio', { name: 'DeepSeek' }).getAttribute('aria-checked')).toBe('true');
  });

  it('disables changes while a conversation is running', () => {
    mount(true);
    expect((screen.getByRole('button', { name: /Choose model/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('gates the reasoning slider behind Think and echoes the selected tier at the entry', async () => {
    const user = mount();
    await screen.findByText('DeepSeek');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    expect(screen.getByRole('radiogroup', { name: 'Reasoning effort' })).toBeTruthy();
    const extraHigh = screen.getByRole('radio', { name: 'Extra High' });
    expect((extraHigh as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByRole('menuitemcheckbox', { name: 'Think' }));
    const enabledTier = screen.getByRole('radio', { name: 'Extra High' });
    expect((enabledTier as HTMLButtonElement).disabled).toBe(false);
    await user.click(enabledTier);
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).toContain('Extra High');
    expect(screen.getByRole('radio', { name: 'Extra High' }).getAttribute('aria-checked')).toBe('true');
  });

  it('snaps to the tier under the pointer while dragging the slider', async () => {
    const user = mount();
    await screen.findByText('DeepSeek');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    await user.click(screen.getByRole('menuitemcheckbox', { name: 'Think' }));
    const track = screen.getByRole('radiogroup', { name: 'Reasoning effort' });
    track.getBoundingClientRect = () => ({ left: 0, width: 100, top: 0, height: 20, right: 100, bottom: 20, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    fireEvent.pointerDown(track, { pointerId: 1, clientX: 15 });
    fireEvent.pointerMove(track, { pointerId: 1, clientX: 85 });
    fireEvent.pointerUp(track, { pointerId: 1 });
    expect(screen.getByRole('radio', { name: 'Max' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('button', { name: /Choose model/ }).textContent).toContain('Max');
  });
});
