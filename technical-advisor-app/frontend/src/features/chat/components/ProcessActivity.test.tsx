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
import { afterEach, describe, expect, it } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import type { ToolCall } from '../conversationModel';
import type { ConversationBlock, TurnProjection } from '../model/blocks';
import { ThinkingLine } from './ThinkingLine';
import { ToolLine } from './ToolLine';
import { TurnGroup } from './TurnGroup';

function renderLocalized(ui: React.ReactElement, language = 'en') {
  const i18n = createInstance();
  void i18n.use(initReactI18next).init({ resources: i18nResources, lng: language, fallbackLng: 'en', defaultNS: 'app', interpolation: { escapeValue: false }, react: { useSuspense: false } });
  return render(ui, { wrapper: ({ children }) => <I18nextProvider i18n={i18n}>{children}</I18nextProvider> });
}
afterEach(cleanup);

function toolBlock(id: number, overrides: Partial<ToolCall> = {}): ConversationBlock {
  const tool: ToolCall = { id: `call-${id}`, name: 'read_file', args: JSON.stringify({ path: `file-${id}.md` }), result: `output-${id}`, status: 'done', startedAt: 1000, durationMs: 1000, ...overrides };
  return { anchor: `tool-${id}`, index: id, seq: id, turn: 1, kind: 'tool_call', status: tool.status, createdAt: 1000, summary: tool.name, tool };
}
function toolTurn(ordered: ConversationBlock[]): TurnProjection {
  return { turn: 1, user: null, process: ordered, finalText: null, outside: [], collapsedByDefault: false, toolCount: ordered.length, earlierReplyCount: 0, subagentCount: 0, ordered };
}
const group = (blocks: ConversationBlock[], highlight: string | null = null) => <TurnGroup turn={toolTurn(blocks)} forcedOpen={false} highlight={highlight} renderBlock={block => <ToolLine block={block} />} />;

describe('readable thinking and action records', () => {
  it('uses a readable localized status and leaves reasoning in details', () => {
    const { rerender, container } = renderLocalized(<ThinkingLine text="分析配置与路由" streaming />, 'zh-CN');
    expect(screen.getByRole('button').textContent).toContain('正在分析');
    expect(screen.getByRole('button').textContent).not.toContain('正在思考');
    expect(container.querySelector('pre')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(container.querySelector('pre')?.textContent).toBe('分析配置与路由');
    rerender(<ThinkingLine text="分析配置与路由" streaming={false} />);
    expect(screen.getByRole('button').textContent).toBe('分析记录');
    expect(screen.getAllByRole('button')[0].getAttribute('aria-expanded')).toBe('true');
    expect(container.querySelector('[data-process-loading]')).toBeNull();
  });

  it('keeps empty thinking absent', () => {
    const { container } = renderLocalized(<ThinkingLine text="   " streaming={false} />, 'zh-CN');
    expect(container.querySelector('button')).toBeNull();
  });

  it('keeps manually expanded long thinking in one continuous window through completion', () => {
    const text = 'First line\n' + 'reasoning\n'.repeat(1600) + 'Latest line';
    const { container, rerender } = renderLocalized(<ThinkingLine text={text} streaming />);
    expect(container.querySelector('pre')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    const pre = container.querySelector('pre')!;
    expect(pre.textContent).toBe(text);
    expect(screen.getAllByRole('button')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByRole('button'));
    const expandedPre = container.querySelector('pre');
    rerender(<ThinkingLine text={text + '\nConclusion'} streaming={false} />);
    expect(container.querySelector('pre')).toBe(expandedPre);
    expect(expandedPre?.textContent).toBe(text + '\nConclusion');
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('follows appended lines without interrupting a reader who scrolls up', () => {
    const text = 'Earlier line\n'.repeat(1200);
    const { container, rerender } = renderLocalized(<ThinkingLine text={text} streaming />);
    fireEvent.click(screen.getByRole('button'));
    const pre = container.querySelector('pre')!;
    Object.defineProperties(pre, { scrollHeight: { value: 1000, configurable: true }, clientHeight: { value: 240, configurable: true } });
    rerender(<ThinkingLine text={text + 'Next line\n'} streaming />);
    expect(pre.scrollTop).toBe(760);
    pre.scrollTop = 100;
    fireEvent.scroll(pre);
    rerender(<ThinkingLine text={text + 'Next line\nAnother line\n'} streaming />);
    expect(pre.scrollTop).toBe(100);
    expect(pre.textContent).toBe(text + 'Next line\nAnother line\n');
    pre.scrollTop = 760;
    fireEvent.scroll(pre);
    Object.defineProperty(pre, 'scrollHeight', { value: 1200, configurable: true });
    rerender(<ThinkingLine text={text + 'Next line\nAnother line\nLatest line\n'} streaming />);
    expect(pre.scrollTop).toBe(960);
  });

  it.each([
    ['read_file', { path: 'app.yaml' }, 'File read returned', 'app.yaml'],
    ['list_files', { path: 'src' }, 'Directory listing returned', 'src'],
    ['execute', { path: 'wrong.md', command: 'npm test', working_directory: 'frontend' }, 'Test execution ended', ''],
    ['grep_files', { path: 'src', pattern: 'SessionController', glob: '*.java' }, 'Search results returned', ''],
    ['glob_files', { path: 'src', pattern: '**/*.tsx' }, 'Search results returned', ''],
    ['web_search', { query: 'model context capacity' }, 'Search results returned', ''],
    ['web_fetch', { url: 'https://example.com/docs' }, 'Webpage content returned', 'example.com'],
    ['write_file', { content: 'body', path: 'docs.md' }, 'File write returned', 'docs.md'],
    ['edit_file', { old_string: 'a', path: 'ToolLine.tsx' }, 'File edit returned', 'ToolLine.tsx'],
    ['agent_spawn', { task: 'investigate', agent_id: 'reviewer' }, 'Subtask dispatched', ''],
  ])('describes the actual target of completed %s', (name, args, action, target) => {
    const { container } = renderLocalized(<ToolLine block={toolBlock(1, { name, args: JSON.stringify(args) })} />);
    expect(screen.getByText(action)).toBeTruthy();
    expect(screen.getByRole('button').textContent).toContain(target);
    expect(screen.getByRole('button').textContent).not.toContain('wrong.md');
    expect(container.querySelector('pre')).toBeNull();
    expect(container.textContent).not.toContain('Tests passed');
  });

  it('keeps search expressions and command working directories in technical details', () => {
    const { rerender } = renderLocalized(<ToolLine block={toolBlock(1, { name: 'grep_files', args: '{"path":"src","pattern":"TOKEN"}' })} />);
    expect(screen.getByRole('button').textContent).not.toContain('TOKEN');
    expect(screen.getByRole('button').textContent).not.toContain('src');
    rerender(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"command":"npm test","working_directory":"frontend"}' })} />);
    expect(screen.getByRole('button').textContent).not.toContain('npm test');
    expect(screen.getByRole('button').textContent).not.toContain('frontend');
    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByRole('button', { name: 'Technical details' }));
    expect(screen.getByText(/working_directory/)).toBeTruthy();
  });

  it('updates action tense on the same expanded row and shows failure without inventing success', () => {
    const { rerender } = renderLocalized(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"command":"npm test"}', status: 'running', result: undefined })} />, 'zh-CN');
    expect(screen.getByText('正在运行测试')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    rerender(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"command":"npm test"}', status: 'done' })} />);
    expect(screen.getAllByText('测试执行已结束')).toHaveLength(2);
    expect(screen.getAllByRole('button')[0].getAttribute('aria-expanded')).toBe('true');
    rerender(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"command":"npm test"}', status: 'failed', result: 'Exit code: 1' })} />);
    expect(screen.getAllByText('测试执行异常')[0]).toBeTruthy();
    expect(screen.queryByText('测试通过')).toBeNull();
  });

  it('does not describe a path as a command when command is missing', () => {
    renderLocalized(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"path":"not-a-command","text":"do something"}' })} />);
    expect(screen.getByText('Local operation ended')).toBeTruthy();
    expect(screen.getByRole('button').textContent).not.toContain('not-a-command');
    expect(screen.getByRole('button').textContent).not.toContain('do something');
  });

  it.each(['mcp__vendor__lookup', 'constructor'])('keeps unknown tool %s neutral until technical details are requested', name => {
    renderLocalized(<ToolLine block={toolBlock(1, { name, args: '{"query":"ticket-17","text":"private body"}' })} />);
    expect(screen.getByText('Operation returned')).toBeTruthy();
    expect(screen.queryByText(name)).toBeNull();
    expect(screen.getByRole('button').textContent).not.toContain('ticket-17');
    expect(screen.getByRole('button').textContent).not.toContain('private body');
    expect(screen.queryByText('Read')).toBeNull();
  });

  it('keeps malformed and oversized arguments out of the action summary', () => {
    const { rerender } = renderLocalized(<ToolLine block={toolBlock(1, { name: 'execute', args: '{"command":' })} />);
    expect(screen.getByRole('button').textContent).toContain('Local operation ended');
    rerender(<ToolLine block={toolBlock(1, { name: 'execute', args: JSON.stringify({ command: 'x'.repeat(20000) }) })} />);
    expect(screen.getByRole('button').textContent?.length).toBeLessThan(200);
  });
});

describe('tool-only process visibility', () => {
  it('folds a tool-only batch before the final answer and loads details only on demand', () => {
    const blocks = [1, 2, 3, 4, 5].map(id => toolBlock(id));
    const { container } = renderLocalized(group(blocks));
    expect(screen.queryByText('file-1.md')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /5 operations/ }));
    for (const id of [1, 2, 3, 4, 5]) expect(screen.getByText(`file-${id}.md`)).toBeTruthy();
    expect(container.querySelector('pre')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /file-5.md/ }));
    expect(screen.getByText('output-5')).toBeTruthy();
    expect(screen.queryByText('output-1')).toBeNull();
    expect([...container.querySelectorAll('[data-anchor]')].map(node => node.getAttribute('data-anchor'))).toEqual(['tool-1', 'tool-2', 'tool-3', 'tool-4', 'tool-5']);
  });
  it('keeps failure details available without extra summary or inline notices', () => {
    const blocks = [1, 2, 3, 4, 5].map(id => toolBlock(id, id === 5 ? { status: 'failed', result: 'Permission denied' } : {}));
    renderLocalized(group(blocks));
    expect(screen.getByRole('button', { name: /5 operations/ }).textContent).not.toContain('failed');
    fireEvent.click(screen.getByRole('button', { name: /5 operations/ }));
    expect(screen.getByText('file-5.md')).toBeTruthy();
    expect(screen.queryByText('Operation incomplete; check its output or technical details')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /file-5.md/ }));
    expect(screen.getByText('Permission denied')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'View execution process' })).toBeNull();
  });
  it('shows running status on a folded batch and preserves expansion when it settles', () => {
    const blocks = [1, 2, 3, 4, 5].map(id => toolBlock(id, { status: 'running', result: undefined }));
    const { rerender } = renderLocalized(group(blocks));
    expect(screen.queryByText('file-5.md')).toBeNull();
    expect(screen.getByRole('button', { name: /5 operations/ }).textContent).toContain('Running');
    fireEvent.click(screen.getByRole('button', { name: /5 operations/ }));
    expect(screen.getByText('file-5.md')).toBeTruthy();
    rerender(group(blocks.map(block => ({ ...block, status: 'done', tool: { ...block.tool!, status: 'done' } }))));
    expect(screen.getByText('file-5.md')).toBeTruthy();
  });
});
