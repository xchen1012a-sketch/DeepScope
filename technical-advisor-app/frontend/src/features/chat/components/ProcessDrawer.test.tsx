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
import { useRef, useState } from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { buildBlocks } from '../model/blocks';
import { ProcessDrawer } from './ProcessDrawer';
import { ToolLine } from './ToolLine';
import type { ConversationBlock } from '../model/blocks';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
afterEach(cleanup);

const turns = buildBlocks([
  { seq: 1, turn: 1, kind: 'user_message', payload: { text: 'Review the repository' }, createdAt: 1_791_799_200_000 },
  { seq: 2, turn: 1, kind: 'advisor_message', payload: { text: 'The modules have clear boundaries' }, createdAt: 1_791_799_201_000 },
], false);

function Harness({ onLocate }: { onLocate: (anchor: string) => void }) {
  const [open, setOpen] = useState(false);
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }));
  return <QueryClientProvider client={client}><I18nextProvider i18n={i18n}><ProcessDrawer open={open} onOpenChange={setOpen} turns={turns} subagents={[]} onLocate={onLocate} /></I18nextProvider></QueryClientProvider>;
}

describe('ProcessDrawer', () => {
  it('opens the existing timeline and returns to the conversation when a record is selected', async () => {
    const locate = vi.fn();
    render(<Harness onLocate={locate} />);
    const user = userEvent.setup();
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: i18n.t('chat.process.show') }));
    expect(screen.getByRole('dialog')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /Review the repository/ }));
    expect(locate).toHaveBeenCalledWith(turns[0].user?.anchor);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps subagent access inside the drawer and can close with Escape', async () => {
    render(<Harness onLocate={vi.fn()} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: i18n.t('chat.process.show') }));
    await user.click(screen.getByRole('tab', { name: i18n.t('chat.process.subagents') }));
    expect(screen.getByText(i18n.t('chat.process.noSubagents'))).toBeTruthy();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: i18n.t('chat.process.show') }));
  });
});

it('shows selected output before technical details and updates the same step when it returns', async () => {
  const block = {anchor: 'selected', index: 1, seq: 1, turn: 1, kind: 'tool_call', status: 'running' as const, createdAt: 1, summary: 'execute', tool: {id: 'selected', name: 'execute', status: 'running' as const, startedAt: null, args: '{"command":"cd /private/project && cat result.txt"}'}};
  const back = vi.fn();
  const ui = (selectedBlock: typeof block | (Omit<typeof block, 'status' | 'tool'> & {status: 'done'; tool: Omit<typeof block.tool, 'status'> & {status: 'done'; result: string}})) => <I18nextProvider i18n={i18n}><ProcessDrawer open onOpenChange={vi.fn()} turns={turns} subagents={[]} onLocate={vi.fn()} selectedBlock={selectedBlock} onBack={back} /></I18nextProvider>;
  const {rerender} = render(ui(block));
  const user = userEvent.setup();
  expect(screen.getByText('Running, waiting for a result')).toBeTruthy();
  expect(screen.queryByText(/private/)).toBeNull();
  rerender(ui({...block, status: 'done', tool: {...block.tool, status: 'done', result: 'real output'}}));
  expect(screen.getByText('real output')).toBeTruthy();
  await user.click(screen.getByRole('button', {name: 'Technical details'}));
  const dialog = within(screen.getByRole('dialog'));
  expect(dialog.getByText(/cd \/private/)).toBeTruthy();
  await user.click(screen.getByRole('button', {name: 'Back to execution process'}));
  expect(back).toHaveBeenCalledOnce();
});

it('returns keyboard focus to the originating step after closing its details', async () => {
  const block: ConversationBlock = {anchor: 'step', index: 1, seq: 1, turn: 1, kind: 'tool_call', status: 'done', createdAt: 0, summary: '', tool: {id: 'step', name: 'execute', args: '{}', result: 'result', status: 'done', startedAt: null}};
  function StepHarness() {
    const [open, setOpen] = useState(false);
    const origin = useRef<HTMLElement | null>(null);
    return <I18nextProvider i18n={i18n}>
      <ToolLine block={block} onInspect={() => { origin.current = document.activeElement as HTMLElement; setOpen(true); }} />
      <ProcessDrawer open={open} onOpenChange={setOpen} turns={[]} subagents={[]} onLocate={vi.fn()} selectedBlock={block}
        onCloseAutoFocus={event => { if (origin.current?.isConnected) { event.preventDefault(); origin.current.focus(); } }} />
    </I18nextProvider>;
  }
  render(<StepHarness />);
  const user = userEvent.setup();
  const step = screen.getByRole('button', {name: /Local operation ended/});
  step.focus();
  await user.keyboard('{Enter}');
  expect(screen.getByRole('dialog')).toBeTruthy();
  await user.keyboard('{Escape}');
  expect(document.activeElement).toBe(step);
});

it('uses an icon toggle, omits a close button, and resizes the process sidebar with the keyboard', async () => {
  const user = userEvent.setup();
  render(<I18nextProvider i18n={i18n}><ProcessDrawer open onOpenChange={vi.fn()} turns={[]} subagents={[]} onLocate={vi.fn()} /></I18nextProvider>);
  const trigger = screen.getByRole('button', { name: 'Hide process panel' });
  expect(trigger.textContent).toBe('');
  const panel = screen.getByRole('dialog');
  expect(within(panel).queryByRole('button', { name: 'Hide process panel' })).toBeNull();
  const handle = screen.getByRole('separator', { name: 'Resize execution sidebar' });
  const before = Number(handle.getAttribute('aria-valuenow'));
  handle.focus();
  await user.keyboard('{ArrowLeft}');
  expect(Number(handle.getAttribute('aria-valuenow'))).toBeGreaterThan(before);
});
