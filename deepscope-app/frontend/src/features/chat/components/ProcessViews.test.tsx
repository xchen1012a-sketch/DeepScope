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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FactItem } from '@/api/advisor';
import { i18nResources } from '@/i18n/resources';
import type { ToolCall } from '../conversationModel';
import type { ConversationBlock, TimelineCardView, TurnProjection } from '../model/blocks';
import type { SubagentStep } from '../subagentSteps';
import type { SubagentRecord } from '../useAdvisorConversation';
import { SubagentCard } from './SubagentCard';
import { SubagentView } from './SubagentView';
import { ThinkingLine } from './ThinkingLine';
import { TimelineCard } from './TimelineCard';
import { ToolLine } from './ToolLine';
import { TurnGroup } from './TurnGroup';
import { Markdown } from './Markdown';

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  resources: i18nResources,
  lng: 'en',
  fallbackLng: 'en',
  defaultNS: 'app',
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

const renderWithI18n = (ui: React.ReactElement) =>
  render(ui, { wrapper: ({ children }: { children: React.ReactNode }) => <I18nextProvider i18n={i18n}>{children}</I18nextProvider> });

// 仓库未启用 vitest 全局配置，测试库不会自动清理，这里手动保证用例之间互不残留。
afterEach(cleanup);

const tool = (over: Partial<ToolCall> = {}): ToolCall => ({
  id: 'c1',
  name: 'read_file',
  args: '{"path":"a.md"}',
  result: 'file body',
  status: 'done',
  startedAt: 1_000,
  endedAt: 2_500,
  durationMs: 1_500,
  ...over,
});

const toolBlock = (over: Partial<ToolCall> = {}): ConversationBlock => ({
  anchor: 'blk-tool-1',
  index: 2,
  seq: 3,
  turn: 1,
  kind: 'tool_call',
  status: 'done',
  createdAt: 1_700_000_000_000,
  summary: 'read_file',
  tool: tool(over),
});

const block = (over: Partial<ConversationBlock> & Pick<ConversationBlock, 'anchor' | 'kind'>): ConversationBlock => ({
  index: 1,
  seq: 1,
  turn: 1,
  status: 'done',
  createdAt: 0,
  summary: '',
  ...over,
});

const turn = (over: Partial<TurnProjection> = {}): TurnProjection => ({
  turn: 1,
  user: null,
  process: [],
  finalText: null,
  outside: [],
  collapsedByDefault: false,
  toolCount: 0,
  earlierReplyCount: 0,
  subagentCount: 0,
  ...over,
});

const card = (over: Partial<TimelineCardView> = {}): TimelineCardView => ({
  anchor: 'blk-1',
  index: 1,
  seq: 1,
  kind: 'tool_call',
  status: 'done',
  summary: 'read_file',
  createdAt: 1_700_000_000_000,
  ...over,
});

const record = (over: Partial<SubagentRecord> = {}): SubagentRecord => ({
  id: 't1',
  name: 'Investigator',
  task: 'dig the repo',
  status: 'done',
  startedAt: 1_000,
  endedAt: 61_000,
  factCount: 0,
  facts: [],
  summary: 'all clear',
  steps: [],
  ...over,
});

const step = (over: Partial<SubagentStep> & Pick<SubagentStep, 'order' | 'kind'>): SubagentStep => ({
  subtaskId: 't1',
  ...over,
});

describe('ThinkingLine', () => {
  it('uses a loading indicator while thinking and a static icon when settled, with a detail affordance', () => {
    const { container, rerender } = renderWithI18n(<ThinkingLine text="checking" streaming />);
    expect(container.querySelector('[data-process-loading]')).toBeTruthy();
    expect(container.querySelector('.lucide-chevron-right')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    rerender(<ThinkingLine text="checking more" streaming={false} />);
    expect(container.querySelector('[data-process-loading]')).toBeNull();
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('true');
  });

  it('keeps the full text hidden until clicked', () => {
    const { container } = renderWithI18n(<ThinkingLine text={'first line\nsecond line'} streaming={false} />);
    expect(screen.getByText('Analysis record')).toBeTruthy();
    expect(screen.queryByText('first line')).toBeNull();
    expect(container.querySelector('.whitespace-pre-wrap')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('true');
    expect(container.querySelector('.whitespace-pre-wrap')?.textContent).toBe('first line\nsecond line');
  });

  it('shows long historical thinking continuously after expansion', () => {
    const text = 'x'.repeat(12000) + 'tail';
    const { container } = renderWithI18n(<ThinkingLine text={text} streaming={false} />);
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('.whitespace-pre-wrap')?.textContent).toBe(text);
    expect(screen.queryByRole('button', { name: 'Next portion' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous portion' })).toBeNull();
  });

  it('shows a single streaming preview and retains the visible index', () => {
    const { container } = renderWithI18n(<ThinkingLine text={'earlier\nlatest'} streaming index={7} />);
    expect(screen.getByText('Analyzing')).toBeTruthy();
    expect(container.querySelector('pre')).toBeNull();
    expect(container.querySelector('[data-thinking-preview]')?.textContent).toBe('latest');
    expect(screen.getByText('7')).toBeTruthy();
  });
});

describe('ToolLine', () => {
  it('presents a readable action row with a detail affordance, while retaining failure feedback', () => {
    const { container } = renderWithI18n(<ToolLine block={toolBlock({ status: 'running', result: undefined })} />);
    expect(screen.getByText('Reading file')).toBeTruthy();
    expect(container.querySelector('[data-process-loading]')).toBeTruthy();
    expect(container.querySelector('.lucide-chevron-right')).toBeTruthy();
  });

  it('shows action, target and duration with accessible status and lazy details', () => {
    const { container } = renderWithI18n(<ToolLine block={toolBlock()} />);
    expect(screen.getByText('File read returned')).toBeTruthy();
    expect(screen.queryByTitle('read_file')).toBeNull();
    expect(screen.getByText('a.md')).toBeTruthy();
    expect(screen.getByRole('button').textContent).toContain('File read returned');
    expect(screen.getByText('1.5s')).toBeTruthy();
    expect(screen.queryByText('2')).toBeNull();
    expect(container.textContent).not.toContain('file body');
    fireEvent.click(container.querySelector('button') as HTMLButtonElement);
    expect(container.textContent).toContain('file body');
    expect(container.textContent).not.toContain('{"path":"a.md"}');
    fireEvent.click(screen.getByRole('button', { name: 'Technical details' }));
    expect(container.textContent).toContain('{"path":"a.md"}');
  });

  it('labels running and failed tools instead of a duration-only done state', () => {
    const { rerender } = renderWithI18n(
      <I18nextProvider i18n={i18n}>
        <ToolLine block={toolBlock({ status: 'running', durationMs: null, result: undefined })} />
      </I18nextProvider>,
    );
    expect(screen.getByText('Reading file')).toBeTruthy();
    rerender(
      <I18nextProvider i18n={i18n}>
        <ToolLine block={toolBlock({ status: 'failed' })} />
      </I18nextProvider>,
    );
    expect(screen.getByText('File read incomplete')).toBeTruthy();
  });

  it('bounds argument previews for large streamed JSON', () => {
    renderWithI18n(<ToolLine block={toolBlock({ args: JSON.stringify({ query: 'x'.repeat(20000) }) })} />);
    expect(screen.getByRole('button').textContent?.length).toBeLessThan(200);
  });

  it('keeps unknown tool names in technical details even when they match an object prototype property', () => {
    renderWithI18n(<ToolLine block={toolBlock({ name: 'constructor' })} />);
    expect(screen.getByText('Operation returned')).toBeTruthy();
    expect(screen.queryByText('constructor')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByRole('button', { name: 'Technical details' }));
    expect(screen.getByText('constructor')).toBeTruthy();
  });

  it('renders nothing when the block carries no tool', () => {
    const { container } = renderWithI18n(<ToolLine block={{ ...toolBlock(), tool: undefined }} />);
    expect(container.querySelector('button')).toBeNull();
  });
});

describe('TurnGroup', () => {
  const renderBlock = (b: ConversationBlock) => <p>{b.kind}</p>;

  it('keeps reply paragraphs visible and folds only the tools between them', () => {
    const ordered = [
      block({ anchor: 'text-a', kind: 'advisor_message', text: 'before' }),
      { ...toolBlock(), anchor: 'tool-a' }, { ...toolBlock(), anchor: 'tool-b' },
      block({ anchor: 'text-b', kind: 'advisor_message', text: 'after' }),
    ];
    const { container } = renderWithI18n(<TurnGroup turn={turn({ ordered })} forcedOpen={false} highlight={null} renderBlock={b => <p>{b.text ?? b.anchor}</p>} />);
    expect(screen.getByText('before')).toBeTruthy();
    expect(screen.getByText('after')).toBeTruthy();
    expect(screen.queryByText('tool-a')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /2 operations/ }));
    expect(screen.getByText('tool-a')).toBeTruthy();
    expect([...container.querySelectorAll('p')].map(node => node.textContent)).toEqual(['before', 'tool-a', 'tool-b', 'after']);
  });

  it('keeps a batch disclosure available before a final answer exists', () => {
    const ordered = ['a', 'b', 'c', 'd', 'e'].map(anchor => ({ ...toolBlock(), anchor }));
    renderWithI18n(<TurnGroup turn={turn({ ordered, process: ordered })} forcedOpen={false} highlight={null} renderBlock={b => <p>{b.anchor}</p>} />);
    expect(screen.queryByText('a')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /5 operations/ }));
    for (const anchor of ['a', 'b', 'c', 'd', 'e']) expect(screen.getByText(anchor)).toBeTruthy();
  });

  it('renders an open turn without a collapse bar', () => {
    renderWithI18n(
      <TurnGroup
        turn={turn({ process: [block({ anchor: 'blk-t1', kind: 'thinking' })] })}
        forcedOpen={false}
        highlight={null}
        renderBlock={renderBlock}
      />,
    );
    expect(screen.getByText('thinking')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('collapses the process behind a generic entry when timing is absent', () => {
    renderWithI18n(
      <TurnGroup
        turn={turn({
          collapsedByDefault: true,
          toolCount: 2,
          earlierReplyCount: 1,
          subagentCount: 0,
          process: [block({ anchor: 'blk-t1', kind: 'thinking' })],
          finalText: block({ anchor: 'blk-final', kind: 'advisor_message', text: 'final answer' }),
        })}
        forcedOpen={false}
        highlight={null}
        renderBlock={renderBlock}
      />,
    );
    expect(screen.getByText('View execution process')).toBeTruthy();
    expect(screen.getByText('advisor_message')).toBeTruthy();
    expect(screen.queryByText('thinking')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByText('thinking')).toBeTruthy();
  });

  it('does not label a plain finished reply as Thought', () => {
    renderWithI18n(<TurnGroup turn={turn({ collapsedByDefault: true, finalText: block({ anchor: 'answer', kind: 'advisor_message', text: 'answer' }) })} forcedOpen={false} highlight={null} renderBlock={renderBlock} />);
    expect(screen.queryByText('Thought')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('advisor_message')).toBeTruthy();
  });

  it('rings the block frame when highlighted', () => {
    const { container } = renderWithI18n(
      <TurnGroup
        turn={turn({ process: [block({ anchor: 'blk-t1', kind: 'thinking' })] })}
        forcedOpen={false}
        highlight="blk-t1"
        renderBlock={renderBlock}
      />,
    );
    const frame = container.querySelector('[data-anchor="blk-t1"]') as HTMLElement;
    expect(frame.className).toContain('ring-2');
  });
});

describe('TimelineCard', () => {
  it('renders title and summary, and reports the anchor on click', () => {
    const onLocate = vi.fn();
    renderWithI18n(<TimelineCard card={card()} onLocate={onLocate} />);
    expect(screen.getByText('Tool')).toBeTruthy();
    expect(screen.getByText('read_file')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    expect(onLocate).toHaveBeenCalledWith('blk-1');
  });

  it('marks a running card with its own status chip and shows none when done', () => {
    const { container, rerender } = renderWithI18n(<TimelineCard card={card({ status: 'running' })} onLocate={vi.fn()} />);
    expect(screen.getByText('Running')).toBeTruthy();
    rerender(
      <I18nextProvider i18n={i18n}>
        <TimelineCard card={card({ status: 'done' })} onLocate={vi.fn()} />
      </I18nextProvider>,
    );
    expect(container.textContent).not.toContain('Done');
  });
});

describe('SubagentCard', () => {
  it('shows exactly name, task, status and duration', () => {
    const onOpen = vi.fn();
    renderWithI18n(<SubagentCard record={record()} onOpen={onOpen} />);
    expect(screen.getByText('Investigator')).toBeTruthy();
    expect(screen.getByText('dig the repo')).toBeTruthy();
    expect(screen.getByText('Done')).toBeTruthy();
    expect(screen.getByText('1m 0s')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    expect(onOpen).toHaveBeenCalledWith('t1');
  });

  it('omits the duration when the dispatch time is missing', () => {
    const { container } = renderWithI18n(
      <SubagentCard record={record({ startedAt: undefined, endedAt: undefined })} onOpen={vi.fn()} />,
    );
    expect(container.querySelector('.font-mono')).toBeNull();
  });
});

describe('SubagentView', () => {
  it('goes back and replays the recorded steps with the summary as final text', () => {
    const onBack = vi.fn();
    renderWithI18n(
      <SubagentView
        record={record({
          steps: [
            step({ order: 1, kind: 'thinking', text: 'look at build' }),
            step({ order: 2, kind: 'tool_call', tool: tool() }),
          ],
        })}
        onBack={onBack}
      />,
    );
    expect(screen.getByText('Analysis record')).toBeTruthy();
    expect(screen.queryByText('look at build')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Analysis record' }));
    expect(screen.getByText('look at build')).toBeTruthy();
    expect(screen.getByText('File read returned')).toBeTruthy();
    expect(screen.getByText('Started')).toBeTruthy();
    expect(screen.getByText('Ended')).toBeTruthy();
    expect(screen.getByText('all clear')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalled();
  });

  it('lists facts and sources without internal id badges', () => {
    const facts: FactItem[] = [{ id: 'F1', fact: 'the repo uses vite', source: 'repo', conclusion: 'confirmed' }];
    renderWithI18n(<SubagentView record={record({ facts })} onBack={vi.fn()} />);
    expect(screen.queryByText('F1')).toBeNull();
    expect(screen.getByText('the repo uses vite')).toBeTruthy();
  });

  it('says no steps when the record is empty', () => {
    renderWithI18n(<SubagentView record={record({ steps: [], summary: '' })} onBack={vi.fn()} />);
    expect(screen.getByText('No process record was stored for this sub-agent')).toBeTruthy();
  });
});

describe('Markdown GFM tables', () => {
  it('renders markdown pipe tables as HTML table elements', () => {
    const md = '| 模块 | 状态 |\n| --- | --- |\n| 核心层 | 正常 [F1] |';
    const { container } = renderWithI18n(<Markdown text={md} />);
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.querySelectorAll('th')).toHaveLength(2);
    expect(container.querySelectorAll('td')).toHaveLength(2);
    expect(screen.getByText('核心层')).toBeTruthy();
    expect(screen.queryByText('F1')).toBeNull();
  });
});

describe('inline tool failure', () => {
  it('reserves failure diagnostics for details without an extra notice below the action', () => {
    renderWithI18n(<ToolLine block={toolBlock({ status: 'failed', result: 'Permission denied\nlong diagnostic output' })} />);
    expect(screen.queryByText('Operation incomplete; check its output or technical details')).toBeNull();
    expect(screen.queryByText('Permission denied')).toBeNull();
    expect(screen.queryByText('long diagnostic output')).toBeNull();
  });
});
