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
import type { ConversationBlock, TurnProjection } from '../model/blocks';
import { TurnGroup } from './TurnGroup';

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: i18nResources, lng: 'zh-CN', defaultNS: 'app', react: { useSuspense: false } });
afterEach(cleanup);
const block = (anchor: string, kind: string, text: string): ConversationBlock => ({ anchor, index: 1, seq: 1, turn: 1, kind, text, summary: text, status: 'done', createdAt: 0 });
const process = [block('thought', 'thinking', '思考文字'), block('before', 'advisor_message', '先检查配置'), block('tool', 'tool_call', '读取配置')];
const final = [block('answer-a', 'advisor_message', '最终答案A'), block('answer-b', 'advisor_message', '最终答案B')];
const turn: TurnProjection = { turn: 1, user: null, process, ordered: [...process, ...final], finalText: final[0], finalBlocks: final, durationMs: 82000, outside: [], collapsedByDefault: true, toolCount: 1, earlierReplyCount: 1, subagentCount: 0 };
const view = (value = turn, highlight: string | null = null) => <I18nextProvider i18n={i18n}><TurnGroup turn={value} forcedOpen={false} highlight={highlight} renderBlock={b => <p>{b.text}</p>} /></I18nextProvider>;

describe('round collapse interaction', () => {
  it('puts a factual operation description above tool-only steps when the model sent no commentary', () => {
    const tool = { ...process[2], tool: { id: 'read', name: 'read_file', args: '{"path":"README.md"}', status: 'done' as const, startedAt: null, durationMs: null } };
    render(view({ ...turn, collapsedByDefault: false, ordered: [tool, ...final], process: [tool] }));
    expect(screen.getByText('读取文件')).toBeTruthy();
    const description = document.querySelector('[data-process-description]');
    const anchor = document.querySelector('[data-anchor="tool"]');
    expect(Boolean(description!.compareDocumentPosition(anchor!) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
  });
  it('keeps the iteration limit visible above a folded summary', () => {
    render(view({ ...turn, completionNotice: 'iteration_limit' }));
    expect(screen.getByText('已达到执行轮次上限')).toBeTruthy();
    expect(screen.queryByText('读取配置')).toBeNull();
  });
  it('shows fixed elapsed time, folds every process line, and preserves all final text', () => {
    render(view());
    const trigger = screen.getByRole('button', { name: /用时 1分钟 22秒/ });
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('先检查配置')).toBeNull();
    expect(screen.queryByText('思考文字')).toBeNull();
    expect(screen.queryByText('读取配置')).toBeNull();
    expect(screen.getByText('最终答案A')).toBeTruthy();
    expect(screen.getByText('最终答案B')).toBeTruthy();
    fireEvent.click(trigger);
    expect(screen.getByText('先检查配置')).toBeTruthy();
  });
  it('keeps a manual expansion across updates and opens a located manually closed process', () => {
    const { rerender } = render(view());
    fireEvent.click(screen.getByRole('button'));
    rerender(view({ ...turn }));
    expect(screen.getByText('读取配置')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    expect(screen.queryByText('读取配置')).toBeNull();
    rerender(view(turn, 'tool'));
    expect(screen.getByText('读取配置')).toBeTruthy();
  });
  it('uses an honest generic entry when duration is missing', () => {
    render(view({ ...turn, durationMs: null }));
    expect(screen.getByRole('button', { name: '查看执行过程' })).toBeTruthy();
    expect(screen.queryByText(/0秒/)).toBeNull();
  });
  it('automatically folds once when the executing turn becomes confirmed', () => {
    const { rerender } = render(view({ ...turn, collapsedByDefault: false }));
    expect(screen.getByText('先检查配置')).toBeTruthy();
    rerender(view());
    expect(screen.queryByText('先检查配置')).toBeNull();
  });
});
