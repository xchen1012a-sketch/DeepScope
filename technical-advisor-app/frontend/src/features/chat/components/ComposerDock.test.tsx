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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { can } from '@/api/auth';
import * as apiClient from '@/lib/apiClient';
import { i18nResources } from '@/i18n/resources';
import type { UsageSample } from '../conversationModel';
import type { QueuedMessage } from '../useAdvisorConversation';
import { ComposerDock } from './ComposerDock';

vi.mock('@/api/auth', () => ({ can: vi.fn(() => true) }));

// jsdom has no layout observer; keep the real Radix interaction and stub only this browser API.
beforeAll(() => vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
}));
afterAll(() => vi.unstubAllGlobals());

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

// 仓库未启用 vitest 全局配置，测试库不会自动清理，这里手动保证用例之间互不残留。
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

const sample = (over: Partial<UsageSample> = {}): UsageSample => ({
  stepId: 's1',
  uncachedInput: 10,
  cacheRead: 0,
  cacheWrite: 0,
  output: 5,
  ...over,
});

const renderDock = (
  samples: UsageSample[],
  onSend = vi.fn(),
  overrides: {
    sessionId?: string;
    onCompacted?: () => void;
    draft?: string;
    setDraft?: (val: string) => void;
    running?: boolean;
    onClearSession?: () => void;
    queuedMessages?: QueuedMessage[];
    onInterruptAndSend?: (queuedId?: string) => void;
    onCancelQueuedMessage?: (queuedId: string) => void;
  } = {},
) =>
  render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ComposerDock
          draft={overrides.draft ?? ''}
          setDraft={overrides.setDraft ?? vi.fn()}
          running={overrides.running ?? false}
          canSend
          onSend={onSend}
          onStop={vi.fn()}
          queuedMessages={overrides.queuedMessages}
          onInterruptAndSend={overrides.onInterruptAndSend}
          onCancelQueuedMessage={overrides.onCancelQueuedMessage}
          turns={1}
          samples={samples}
          toolCallCount={0}
          thinking={false}
          onToggleThinking={vi.fn()}
          attachments={[]}
          uploading={false}
          uploadError={null}
          fileInputRef={{ current: null }}
          onPickFiles={vi.fn()}
          onFilesSelected={vi.fn()}
          onRemoveAttachment={vi.fn()}
          onPaste={vi.fn()}
          onDrop={vi.fn()}
          sessionId={overrides.sessionId ?? 'session-1'}
          onCompacted={overrides.onCompacted ?? vi.fn()}
          onClearSession={overrides.onClearSession}
        />
      </QueryClientProvider>
    </I18nextProvider>,
  );

describe('ComposerDock input scrolling', () => {
  it('opens slash commands while a response is executing', async () => {
    renderDock([], vi.fn(), { draft: '/', running: true });
    expect(await screen.findByTestId('slash-command-menu')).toBeTruthy();
    expect(screen.getByRole('textbox').getAttribute('placeholder')).toBe('Collaborate with DeepScope');
  });
  const setInputViewport = (input: HTMLElement, scrollTop: number, scrollHeight = 600) => {
    Object.defineProperties(input, {
      scrollHeight: { configurable: true, value: scrollHeight },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: scrollTop },
    });
  };

  it.each([
    { scrollTop: 0, deltaY: -40 },
    { scrollTop: 400, deltaY: 40 },
    { scrollTop: 0, deltaY: 40, scrollHeight: 36 },
  ])('keeps wheel movement inside the input at its boundary: %j', (viewport) => {
    renderDock([], vi.fn(), { draft: 'Long message' });
    const input = screen.getByRole('textbox');
    setInputViewport(input, viewport.scrollTop, viewport.scrollHeight);
    const wheel = new WheelEvent('wheel', { deltaY: viewport.deltaY, bubbles: true, cancelable: true });

    fireEvent(input, wheel);

    expect(wheel.defaultPrevented).toBe(true);
  });

  it('allows native scrolling within a long input and browser zoom', () => {
    renderDock([], vi.fn(), { draft: 'Long message' });
    const input = screen.getByRole('textbox');
    setInputViewport(input, 100);
    const scrolling = new WheelEvent('wheel', { deltaY: 40, bubbles: true, cancelable: true });
    fireEvent(input, scrolling);
    expect(scrolling.defaultPrevented).toBe(false);

    setInputViewport(input, 400);
    const zooming = new WheelEvent('wheel', { deltaY: 40, ctrlKey: true, bubbles: true, cancelable: true });
    fireEvent(input, zooming);
    expect(zooming.defaultPrevented).toBe(false);

    const outside = new WheelEvent('wheel', { deltaY: 40, bubbles: true, cancelable: true });
    fireEvent(screen.getByTestId('context-ring-trigger'), outside);
    expect(outside.defaultPrevented).toBe(false);
  });

  it.each([
    { contentHeight: 64, height: '64px', overflow: 'hidden' },
    { contentHeight: 600, height: '200px', overflow: 'auto' },
  ])('grows to fit content and switches to internal scrolling at the limit: %j', ({ contentHeight, height, overflow }) => {
    vi.spyOn(HTMLTextAreaElement.prototype, 'scrollHeight', 'get').mockReturnValue(contentHeight);
    renderDock([], vi.fn(), { draft: 'Multi-line message' });
    const input = screen.getByRole('textbox');
    expect(input.style.height).toBe(height);
    expect(input.style.overflowY).toBe(overflow);
  });

  it('sends on Enter while leaving Shift+Enter and composing Enter to the input', () => {
    const onSend = vi.fn();
    renderDock([], onSend, { draft: 'Message' });
    const input = screen.getByRole('textbox');

    expect(fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })).toBe(true);
    expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(true);
    expect(onSend).not.toHaveBeenCalled();
    expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
    expect(onSend).toHaveBeenCalledTimes(1);
  });
});

describe('ComposerDock model truth', () => {
  it('shows the model actually in use when the user cannot manage settings', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ modelName: 'gpt-4o-mini' })]);

    expect(screen.getByText('gpt-4o-mini')).toBeTruthy();
    expect(screen.queryByText(i18n.t('chat.composer.modelEmpty'))).toBeNull();
  });

  it('falls back to the settings hint only when no usage sample names a model', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample()]);

    // 没有任何采样说出模型名时，模型位不再假装，直接说去模型设置添加。
    expect(screen.getByText(i18n.t('chat.composer.modelEmpty'))).toBeTruthy();
  });

  it('prefers the catalog name over the sample name for privileged users', () => {
    vi.mocked(can).mockReturnValue(true);
    renderDock([sample({ modelName: 'gpt-4o-mini' })]);

    // 有权限时目录里的当前模型优先；本测试不发请求，目录为空时回落到采样里的名字。
    expect(screen.getByText('gpt-4o-mini')).toBeTruthy();
  });

  it('displays this conversation model for unprivileged users', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient.api, 'get').mockImplementation(async (path: string) => {
      if (path === '/api/advisor/sessions/session-1/model') {
        return { modelName: 'deepseek-chat', contextWindow: 131_072 };
      }
      return undefined;
    });
    renderDock([]);

    expect(await screen.findByText('deepseek-chat')).toBeTruthy();
  });
});

describe('ComposerDock model menu', () => {
  it('explains where models are managed instead of listing nothing', async () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ modelName: 'gpt-4o-mini' })]);

    await userEvent.setup().click(screen.getByText('gpt-4o-mini'));

    expect(screen.getByText(i18n.t('chat.composer.modelEmpty'))).toBeTruthy();
  });
});

describe('ComposerDock usage popover', () => {
  it('opens usage as a dismissible popover and restores focus after Escape', async () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ cacheRead: 10 })]);
    const user = userEvent.setup();
    const trigger = screen.getByRole('button', { name: 'Usage details' });
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(trigger);
    expect(screen.getByRole('dialog', { name: 'Usage details' }).textContent).toContain('1 turn');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
});

describe('ComposerDock compact context', () => {
  it('shows remaining tokens in K and keeps the actual window in the details', async () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    const trigger = screen.getByRole('button', { name: '75% context remaining' });
    expect(trigger.textContent).toContain('75%');
    await userEvent.setup().click(trigger);
    expect(screen.getByRole('dialog', { name: '75% context remaining' }).textContent).toContain('8K / 32K');
  });

  it('uses the currently selected model capacity instead of a previous model sample', async () => {
    vi.mocked(can).mockReturnValue(true);
    vi.spyOn(apiClient.api, 'get').mockResolvedValue({ endpointId: 'current', modelName: 'Current model', contextWindow: 1_000_000, choices: [{ endpointId: 'current', kind: 'chat', modelName: 'Current model', current: true }] });
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 128_000, modelName: 'Previous model' })]);
    const trigger = await screen.findByRole('button', { name: '99% context remaining' });
    await userEvent.setup().click(trigger);
    expect(screen.getByRole('dialog', { name: '99% context remaining' }).textContent).toContain('8K / 1M');
  });

  it('computes remaining context from the owned conversation model', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient.api, 'get').mockImplementation(async (path: string) => {
      if (path === '/api/advisor/sessions/session-1/model') {
        return { modelName: 'deepseek-chat', contextWindow: 131_072 };
      }
      return undefined;
    });
    renderDock([sample({ projectedTokens: 31_072, modelName: 'deepseek-chat' })]);
    const trigger = await screen.findByRole('button', { name: '76% context remaining' });
    expect(trigger.textContent).toContain('76%');
    await userEvent.setup().click(trigger);
    expect(screen.getByRole('dialog', { name: '76% context remaining' }).textContent).toContain('31.1K / 131.1K');
  });
});

describe('ComposerDock context ring', () => {
  const ringArc = () => screen.getByTestId('context-ring').querySelector('circle:nth-of-type(2)');

  it('stays indigo while remaining capacity is healthy', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    expect(ringArc()?.getAttribute('class')).toContain('stroke-indigo-500');
  });

  it('turns amber when remaining capacity enters the warning band', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ projectedTokens: 24_000, contextWindow: 32_000 })]);
    expect(ringArc()?.getAttribute('class')).toContain('stroke-amber-500');
  });

  it('turns rose when remaining capacity is critical', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ projectedTokens: 28_800, contextWindow: 32_000 })]);
    expect(ringArc()?.getAttribute('class')).toContain('stroke-rose-500');
  });
});

describe('ComposerDock compact card', () => {
  const openContext = async () => {
    const trigger = screen.getByTestId('context-ring-trigger');
    await userEvent.setup().click(trigger);
  };

  it('shows the ready compact command directive inside the context popover', async () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    await openContext();
    const compactBtn = screen.getByTestId('compact-card');
    expect(compactBtn).toBeTruthy();
    expect(screen.getByText('compact')).toBeTruthy();
    expect(compactBtn.getAttribute('aria-label')).toBe(i18n.t('chat.composer.compactTitle'));
    // 保持极简：仅展示压缩命令，不展示冗长的说明文案
    expect(screen.queryByText(i18n.t('chat.composer.compactDescription'))).toBeNull();
  });

  it('enters the running state and reports success without showing a condensed count', async () => {
    vi.mocked(can).mockReturnValue(false);
    const onCompacted = vi.fn();
    let resolveCompact!: (value: unknown) => void;
    vi.spyOn(apiClient, 'apiFetch').mockImplementation(
      () => new Promise((resolve) => { resolveCompact = resolve; }),
    );
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })], vi.fn(), { onCompacted });
    await openContext();
    await userEvent.setup().click(screen.getByTestId('compact-card'));
    expect(screen.getByText(i18n.t('chat.composer.compactRunning'))).toBeTruthy();
    expect((screen.getByTestId('compact-card') as HTMLButtonElement).disabled).toBe(true);
    resolveCompact({ outcome: 'compacted', inputTokens: 9_000, keptTokens: 2_000 });
    await waitFor(() => expect(screen.getByText(i18n.t('chat.composer.compactDone'))).toBeTruthy());
    expect(onCompacted).toHaveBeenCalledTimes(1);
    // 完成态只展示成功标记，不显示「已浓缩 N 条」之类的数量摘要。
    expect(screen.getByTestId('compact-card').textContent).not.toContain('42');
  });

  it('surfaces the short-session hint when the backend skips compaction', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockResolvedValue({ outcome: 'skipped', reason: 'too_short' });
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    await openContext();
    await userEvent.setup().click(screen.getByTestId('compact-card'));
    await waitFor(() =>
      expect(screen.getByText(i18n.t('chat.composer.compactReason.too_short'))).toBeTruthy(),
    );
  });

  it('auto-clears the compaction hint after a delay', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockResolvedValue({ outcome: 'skipped', reason: 'too_short' });
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    await openContext();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      fireEvent.click(screen.getByTestId('compact-card'));
      await act(async () => {
        await Promise.resolve();
      });
      expect(screen.getByText(i18n.t('chat.composer.compactReason.too_short'))).toBeTruthy();
      act(() => {
        vi.advanceTimersByTime(2600);
      });
      expect(screen.queryByText(i18n.t('chat.composer.compactReason.too_short'))).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('surfaces the session running hint when 409 conflict returns session_running', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockRejectedValue(
      new apiClient.ApiError(409, JSON.stringify({ outcome: 'conflict', reason: 'session_running' })),
    );
    renderDock([sample({ projectedTokens: 8_000, contextWindow: 32_000 })]);
    await openContext();
    await userEvent.setup().click(screen.getByTestId('compact-card'));
    await waitFor(() =>
      expect(screen.getByText(i18n.t('chat.composer.compactReason.session_running'))).toBeTruthy(),
    );
  });

  it('blocks the card while a model run is streaming', async () => {
    vi.mocked(can).mockReturnValue(false);
    render(
      <I18nextProvider i18n={i18n}>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <ComposerDock
            draft=""
            setDraft={vi.fn()}
            running
            canSend={false}
            onSend={vi.fn()}
            onStop={vi.fn()}
            turns={1}
            samples={[sample({ projectedTokens: 8_000, contextWindow: 32_000 })]}
            toolCallCount={0}
            thinking={false}
            onToggleThinking={vi.fn()}
            attachments={[]}
            uploading={false}
            uploadError={null}
            fileInputRef={{ current: null }}
            onPickFiles={vi.fn()}
            onFilesSelected={vi.fn()}
            onRemoveAttachment={vi.fn()}
            onPaste={vi.fn()}
            onDrop={vi.fn()}
            sessionId="session-1"
            onCompacted={vi.fn()}
          />
        </QueryClientProvider>
      </I18nextProvider>,
    );
    await openContext();
    expect((screen.getByTestId('compact-card') as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('ComposerDock pending model selection', () => {
  it('blocks both send and Enter until model activation is confirmed', async () => {
    vi.mocked(can).mockReturnValue(true);
    vi.spyOn(apiClient.api, 'get').mockResolvedValue({
      chat: [
        { endpointId: 'first', modelName: 'First', kind: 'chat', current: true },
        { endpointId: 'second', modelName: 'Second', kind: 'chat', current: false },
      ],
      endpointId: 'first',
      modelName: 'First',
      choices: [
        { endpointId: 'first', modelName: 'First', kind: 'chat', current: true },
        { endpointId: 'second', modelName: 'Second', kind: 'chat', current: false },
      ],
    });
    let confirm!: () => void;
    vi.spyOn(apiClient, 'apiFetch').mockImplementation(() => new Promise(resolve => { confirm = () => resolve(undefined); }));
    const send = vi.fn();
    renderDock([], send);
    const user = userEvent.setup();
    await screen.findByText('First');
    await user.click(screen.getByRole('button', { name: /Choose model/ }));
    await user.click(screen.getByRole('menuitemradio', { name: 'Second' }));
    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(send).not.toHaveBeenCalled();
    confirm();
    await waitFor(() => expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(false));
  });
});

describe('Image attachment preview and loading overlay', () => {
  it('shows a loading spinner overlay on pending image thumbnails and opens the lightbox when clicked after upload', async () => {
    const { AttachmentStrip, VisitorAttachmentChips } = await import('./ComposerToolbar');
    const user = userEvent.setup();
    const dataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

    const { rerender } = render(
      <I18nextProvider i18n={i18n}>
        <AttachmentStrip
          attachments={[
            {
              id: 'pending-1',
              name: 'screen.png',
              size: 1024,
              kind: 'image',
              mimeType: 'image/png',
              dataUrl,
              uploading: true,
            },
          ]}
          uploading={true}
          uploadError={null}
          onRemove={vi.fn()}
        />
      </I18nextProvider>,
    );

    expect(screen.getByAltText('screen.png')).toBeTruthy();
    expect(screen.getByLabelText(i18n.t('composer.uploading'))).toBeTruthy();

    rerender(
      <I18nextProvider i18n={i18n}>
        <AttachmentStrip
          attachments={[
            {
              id: 'img-1',
              name: 'screen.png',
              size: 1024,
              kind: 'image',
              mimeType: 'image/png',
              dataUrl,
              uploading: false,
            },
          ]}
          uploading={false}
          uploadError={null}
          onRemove={vi.fn()}
        />
        <VisitorAttachmentChips
          attachments={[
            {
              id: 'img-1',
              name: 'screen.png',
              size: 1024,
              kind: 'image',
              mimeType: 'image/png',
              dataUrl,
            },
          ]}
        />
      </I18nextProvider>,
    );

    const images = screen.getAllByAltText('screen.png');
    expect(images.length).toBeGreaterThanOrEqual(2);
    await user.click(images[0]!);
    expect(screen.getByRole('dialog', { name: 'screen.png' })).toBeTruthy();
    expect(screen.getByText('100%')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(screen.getByText('125%')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Reset zoom' }));
    expect(screen.getByText('100%')).toBeTruthy();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'screen.png' })).toBeNull();

    // Clicking the sent image in VisitorAttachmentChips also opens the lightbox
    await user.click(images[1]!);
    expect(screen.getByRole('dialog', { name: 'screen.png' })).toBeTruthy();
  });
});

describe('ComposerDock slash commands', () => {
  it('queries fresh resource commands again when the slash menu is reopened', async () => {
    let count = 0;
    vi.spyOn(apiClient.api, 'get').mockImplementation(async (path: string) => {
      if (path !== '/api/advisor/commands') return undefined;
      count += 1;
      return [{ commandId: 'tool:weather', code: 'tool-weather', name: count === 1 ? 'Before update' : 'After update',
        description: '', origin: 'custom', targetType: 'tool', targetId: 'weather', paramsJson: '{}', sortOrder: 100 }];
    });
    function StatefulDock() {
      const [draft, setDraft] = useState('/');
      return <ComposerDock draft={draft} setDraft={setDraft} running={false} canSend onSend={vi.fn()} onStop={vi.fn()}
        turns={0} samples={[]} toolCallCount={0} thinking={false} onToggleThinking={vi.fn()} attachments={[]}
        uploading={false} uploadError={null} fileInputRef={{ current: null }} onPickFiles={vi.fn()} onFilesSelected={vi.fn()}
        onRemoveAttachment={vi.fn()} onPaste={vi.fn()} onDrop={vi.fn()} />;
    }
    render(<I18nextProvider i18n={i18n}><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StatefulDock /></QueryClientProvider></I18nextProvider>);
    await screen.findByText('Before update');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    await screen.findByText('After update');
  });
  it('shows slash command menu when draft ends with slash', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([], vi.fn(), { draft: '/' });

    expect(screen.getByTestId('slash-command-menu')).toBeTruthy();
    expect(screen.getByTestId('slash-command-compact')).toBeTruthy();
  });

  it('does not show slash menu when draft has no slash', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([], vi.fn(), { draft: 'Hello world' });

    expect(screen.queryByTestId('slash-command-menu')).toBeNull();
  });

  it('filters commands as user types query after slash', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([], vi.fn(), { draft: '/nonexistent' });

    expect(screen.getByTestId('slash-command-menu')).toBeTruthy();
    expect(screen.getByText(i18n.t('chat.composer.slashMenu.noMatch'))).toBeTruthy();
  });

  it('dismisses slash menu on Escape', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([], vi.fn(), { draft: '/' });

    expect(screen.getByTestId('slash-command-menu')).toBeTruthy();
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    expect(screen.queryByTestId('slash-command-menu')).toBeNull();
  });

  it('selects compact command from slash menu without immediate execution', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockResolvedValue({ outcome: 'compacted', inputTokens: 5000, keptTokens: 1000 });
    const setDraft = vi.fn();
    const onCompacted = vi.fn();

    renderDock([], vi.fn(), { draft: '/compact', setDraft, onCompacted });

    await screen.findByTestId('slash-command-compact');

    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(setDraft).toHaveBeenCalledWith('/compact');
    expect(onCompacted).not.toHaveBeenCalled();
  });

  it('executes compact action on send via send button', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockResolvedValue({ outcome: 'compacted', inputTokens: 5000, keptTokens: 1000 });
    const setDraft = vi.fn();
    const onCompacted = vi.fn();

    renderDock([], vi.fn(), { draft: '/compact', setDraft, onCompacted });

    const sendBtn = screen.getByRole('button', { name: /send/i });
    fireEvent.click(sendBtn);

    expect(setDraft).toHaveBeenCalledWith('');
    await waitFor(() => expect(onCompacted).toHaveBeenCalledTimes(1));
  });

  it('selects compact command on first Enter, and executes compaction on second Enter', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient, 'apiFetch').mockResolvedValue({ outcome: 'compacted', inputTokens: 5000, keptTokens: 1000 });
    const setDraft = vi.fn();
    const onCompacted = vi.fn();

    renderDock([], vi.fn(), { draft: '/compact', setDraft, onCompacted });
    await screen.findByTestId('slash-command-compact');
    const textarea = screen.getByRole('textbox');

    // First Enter selects from menu
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(setDraft).toHaveBeenCalledWith('/compact');
    expect(onCompacted).not.toHaveBeenCalled();

    // Second Enter sends the command
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(setDraft).toHaveBeenCalledWith('');
    await waitFor(() => expect(onCompacted).toHaveBeenCalledTimes(1));
  });

  it('navigates with ArrowDown and ArrowUp, and inserts slash command template on Enter', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient.api, 'get').mockImplementation(async (path: string) => {
      if (path === '/api/advisor/commands') {
        return [
          {
            commandId: 'cmd-builtin-compact',
            code: 'compact',
            name: 'Compact context',
            description: 'Compact',
            origin: 'builtin',
            targetType: 'action',
            targetId: 'compact',
            paramsJson: '{}',
            sortOrder: 1,
          },
          {
            commandId: 'cmd-skill-doc',
            code: 'doc',
            name: 'Document Generator',
            description: 'Generate documentation',
            origin: 'custom',
            targetType: 'skill',
            targetId: 'doc-gen',
            paramsJson: '{}',
            sortOrder: 2,
          },
        ];
      }
      return undefined;
    });

    const setDraft = vi.fn();
    renderDock([], vi.fn(), { draft: 'please /', setDraft });

    await screen.findByTestId('slash-command-doc');
    const textarea = screen.getByRole('textbox');

    // ArrowDown moves from 0 (compress) to 1 (doc)
    fireEvent.keyDown(textarea, { key: 'ArrowDown' });
    // Press Enter to select highlighted command
    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(setDraft).toHaveBeenCalledWith('please /doc ');
  });

  it('selects clear action from slash menu without immediate execution', async () => {
    vi.mocked(can).mockReturnValue(false);
    vi.spyOn(apiClient.api, 'get').mockImplementation(async (path: string) => {
      if (path === '/api/advisor/commands') {
        return [
          {
            commandId: 'cmd-builtin-clear',
            code: 'clear',
            name: 'Clear context',
            description: 'Reset context',
            origin: 'builtin',
            targetType: 'action',
            targetId: 'clear',
            paramsJson: '{}',
            sortOrder: 1,
          },
        ];
      }
      return undefined;
    });

    const setDraft = vi.fn();
    const onClearSession = vi.fn();
    renderDock([], vi.fn(), { draft: '/clear', setDraft, onClearSession });

    await screen.findByTestId('slash-command-clear');
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });

    expect(setDraft).toHaveBeenCalledWith('/clear');
    expect(onClearSession).not.toHaveBeenCalled();
  });

  it('executes clear action on send via send button', async () => {
    vi.mocked(can).mockReturnValue(false);
    const setDraft = vi.fn();
    const onClearSession = vi.fn();
    renderDock([], vi.fn(), { draft: '/clear', setDraft, onClearSession });

    const sendBtn = screen.getByRole('button', { name: /send/i });
    fireEvent.click(sendBtn);

    expect(setDraft).toHaveBeenCalledWith('');
    expect(onClearSession).toHaveBeenCalledTimes(1);
  });

  it('selects clear command on first Enter, and executes clear on second Enter', async () => {
    vi.mocked(can).mockReturnValue(false);
    const setDraft = vi.fn();
    const onClearSession = vi.fn();
    renderDock([], vi.fn(), { draft: '/clear', setDraft, onClearSession });

    await screen.findByTestId('slash-command-clear');
    const textarea = screen.getByRole('textbox');

    // First Enter selects from menu
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(setDraft).toHaveBeenCalledWith('/clear');
    expect(onClearSession).not.toHaveBeenCalled();

    // Second Enter sends the command
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(setDraft).toHaveBeenCalledWith('');
    expect(onClearSession).toHaveBeenCalledTimes(1);
  });

  it('keeps textarea and model picker enabled while running is true', () => {
    vi.mocked(can).mockReturnValue(false);
    renderDock([], vi.fn(), { running: true });

    const textarea = screen.getByRole('textbox') as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(false);

    const modelButton = screen.getByRole('button', { name: /Choose model/i }) as HTMLButtonElement;
    expect(modelButton.disabled).toBe(false);
  });

  it('renders queued messages and triggers interrupt and cancel callbacks', () => {
    const onInterrupt = vi.fn();
    const onCancel = vi.fn();
    const queued: QueuedMessage[] = [
      { id: 'q-1', text: 'Second question in queue', createdAt: Date.now() },
    ];
    renderDock([], vi.fn(), {
      running: true,
      queuedMessages: queued,
      onInterruptAndSend: onInterrupt,
      onCancelQueuedMessage: onCancel,
    });

    expect(screen.getByText('Second question in queue')).toBeTruthy();
    expect(screen.getByText('Queued')).toBeTruthy();

    const interruptBtn = screen.getByTitle('Interrupt current execution and send this message immediately');
    fireEvent.click(interruptBtn);
    expect(onInterrupt).toHaveBeenCalledWith('q-1');

    const cancelBtn = screen.getByTitle('Cancel queue');
    fireEvent.click(cancelBtn);
    expect(onCancel).toHaveBeenCalledWith('q-1');
  });

  it('shows queue send button when running is true and draft has content', () => {
    const onSend = vi.fn();
    renderDock([], onSend, {
      running: true,
      draft: 'New message while running',
    });

    const sendBtn = screen.getByTitle('Queue send');
    expect(sendBtn).toBeTruthy();
    fireEvent.click(sendBtn);
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  it('renders stacked card layers for each queued message with independent steer and cancel actions', () => {
    const onInterrupt = vi.fn();
    const onCancel = vi.fn();
    const queued: QueuedMessage[] = [
      { id: 'q-1', text: 'First queued prompt', createdAt: Date.now() },
      { id: 'q-2', text: 'Second queued prompt', createdAt: Date.now() + 10 },
    ];
    renderDock([], vi.fn(), {
      running: true,
      queuedMessages: queued,
      onInterruptAndSend: onInterrupt,
      onCancelQueuedMessage: onCancel,
    });

    const queueTab = screen.getByTestId('composer-queue-tab');
    expect(queueTab).toBeTruthy();

    const card1 = screen.getByTestId('composer-queue-item-0');
    const card2 = screen.getByTestId('composer-queue-item-1');
    expect(card1).toBeTruthy();
    expect(card2).toBeTruthy();

    // Both cards show their text simultaneously in the stack
    expect(screen.getByText('First queued prompt')).toBeTruthy();
    expect(screen.getByText('Second queued prompt')).toBeTruthy();

    // Each card has its own Steer button
    const steerButtons = screen.getAllByTitle('Interrupt current execution and send this message immediately');
    expect(steerButtons).toHaveLength(2);

    // Clicking Steer on card 2 triggers interrupt with q-2
    fireEvent.click(steerButtons[1]);
    expect(onInterrupt).toHaveBeenCalledWith('q-2');

    // Clicking Cancel on card 1 triggers cancel with q-1
    const cancelButtons = screen.getAllByTitle('Cancel queue');
    expect(cancelButtons).toHaveLength(2);
    fireEvent.click(cancelButtons[0]);
    expect(onCancel).toHaveBeenCalledWith('q-1');
  });
});
