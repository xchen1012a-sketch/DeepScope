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

import { describe, expect, it } from 'vitest';
import type { ConversationEvent } from '../useAdvisorConversation';
import enApp from '@/i18n/locales/en/app.json';
import zhApp from '@/i18n/locales/zh-CN/app.json';
import { buildBlocks, findBlock, renderedBlocks, timelineCards, TIMELINE_KINDS } from './blocks';
import { normalizeLegacyDeliverables } from './legacyDeliverable';

function entry(seq: number, kind: string, payload: Record<string, unknown> = {}, turn = 1): ConversationEvent {
  return { seq, turn, kind, payload, createdAt: 1_700_000_000_000 + seq * 1000 };
}

describe('buildBlocks visibility', () => {
  it('keeps questions outside the process group and hides empty delivery metadata', () => {
    const turns = buildBlocks(
      [
        entry(1, 'user_message', { text: '看一下仓库' }),
        entry(2, 'route', { mode: 'general', reason: '通用' }),
        entry(3, 'decompose', { reason: '拆一下' }),
        entry(4, 'question', { text: '哪个仓库？' }),
        entry(5, 'deliverable', { title: '身份核实' }),
        entry(6, 'tool_call', { id: 'c0', name: 'report_route', args: '{}', status: 'done' }),
        entry(7, 'tool_call', { id: 'c1', name: 'read_file', args: '{}', status: 'done' }),
      ],
      false,
    );
    const [turn] = turns;
    expect(turn?.process.map((block) => block.kind)).toEqual(['tool_call']);
    expect(turn?.toolCount).toBe(1);
    expect(turn?.outside.map((block) => block.kind)).toEqual(['question']);
    expect(renderedBlocks(turn!).some(block => block.kind === 'deliverable')).toBe(false);
    expect(JSON.stringify(turn?.process)).not.toContain('decompose');
    expect(JSON.stringify(turn?.process)).not.toContain('report_route');
    expect(JSON.stringify(turn?.process)).not.toContain('通用模式');
  });

  it('shows skill loads, dispatches, self tasks, settled reports and notes as process blocks', () => {
    const [turn] = buildBlocks(
      [
        entry(1, 'user_message', { text: '问' }),
        entry(2, 'load_skill', { skillName: 'repo-survey' }),
        entry(3, 'dispatch', { subtaskId: 's1', name: 'code-surveyor' }),
        entry(4, 'self_task', { title: '自查' }),
        entry(5, 'subagent_settled', { subagentId: 's1', status: 'done', summary: '结论' }),
        entry(6, 'advisor_note', { text: '记一笔' }),
      ],
      false,
    );
    expect(turn?.process.map((block) => block.kind)).toEqual([
      'load_skill',
      'dispatch',
      'self_task',
      'subagent_settled',
      'advisor_note',
    ]);
    expect(turn?.subagentCount).toBe(1);
    expect(turn?.toolCount).toBe(0);
  });

  it('leaves routing results in the record without drawing them', () => {
    const [turn] = buildBlocks([entry(1, 'route', { mode: 'specialist' }), entry(2, 'agent_route', {})], false);
    expect(turn?.process).toEqual([]);
    expect(turn?.outside).toEqual([]);
    expect(timelineCards([turn!])).toEqual([]);
  });

  it('leaves facts out of the transcript and keeps them on the subagent record', () => {
    const [turn] = buildBlocks(
      [entry(1, 'subagent_facts', { subagentId: 's1', facts: [{ id: 'f1', fact: 'x' }] })],
      false,
    );
    expect(turn?.process).toEqual([]);
  });
});

describe('buildBlocks turn shape', () => {
  it('collapses a finished turn whose last step is a reply without a tool call', () => {
    const [turn] = buildBlocks(
      [
        entry(1, 'user_message', { text: '问' }),
        entry(2, 'tool_call', { id: 'c1', name: 'read_file', args: '{}', status: 'done' }),
        entry(3, 'advisor_message', { text: '结论', reasoning: '先读文件\n再总结' }),
      ],
      false,
    );
    expect(turn?.collapsedByDefault).toBe(true);
    expect(turn?.finalText?.text).toBe('结论');
    expect(turn?.process.map((block) => block.kind)).toEqual(['tool_call', 'thinking']);
    expect(turn?.toolCount).toBe(1);
    expect(turn?.subagentCount).toBe(0);
    expect(turn?.earlierReplyCount).toBe(0);
  });

  it('leaves a finished turn open when it never produced a final reply', () => {
    const [turn] = buildBlocks(
      [
        entry(1, 'user_message', { text: '问' }),
        entry(2, 'advisor_message', { text: '我先查', reasoning: '想一下' }),
        entry(3, 'tool_call', { id: 'c1', name: 'search', args: '{}', status: 'running' }),
      ],
      false,
    );
    expect(turn?.collapsedByDefault).toBe(false);
    expect(turn?.finalText).toBeNull();
  });

  it('counts a dispatch as a subagent and not as a tool call', () => {
    const [turn] = buildBlocks(
      [entry(1, 'user_message', { text: '问' }), entry(2, 'dispatch', { subtaskId: 't1', name: '调查员' }), entry(3, 'advisor_message', { text: '已派发' })],
      false,
    );
    expect(turn?.toolCount).toBe(0);
    expect(turn?.subagentCount).toBe(1);
  });

  it('keeps the open turn expanded', () => {
    const [turn] = buildBlocks([entry(1, 'user_message', { text: '问' })], true);
    expect(turn?.collapsedByDefault).toBe(false);
  });

  it('carries a one line summary of what the block did', () => {
    const [turn] = buildBlocks(
      [
        entry(1, 'tool_call', { id: 'c1', name: 'read_file', status: 'done', startedAt: 1, endedAt: 2 }),
        entry(2, 'advisor_message', { reasoning: '先看依赖\n再看调用方' }),
      ],
      false,
    );
    expect(turn?.process.map((block) => block.summary)).toEqual(['read_file', '先看依赖']);
  });
});

describe('block anchors', () => {
  it('numbers every rendered block in render order so the timeline can scroll to it', () => {
    const [turn] = buildBlocks(
      [
        entry(1, 'user_message', { text: '问' }),
        entry(2, 'tool_call', { id: 'c1', name: 'read_file', status: 'done' }),
        entry(3, 'advisor_message', { text: '答', reasoning: '想' }),
        entry(4, 'question', { text: '追问' }),
      ],
      false,
    );
    expect(renderedBlocks(turn!).map((block) => block.anchor)).toEqual([
      'flow-1',
      'flow-2',
      'flow-3',
      'flow-4',
      'flow-5',
    ]);
    expect(renderedBlocks(turn!).map((block) => block.index)).toEqual([1, 2, 3, 4, 5]);
  });

  it('keeps the anchor stable when live synthetic seqs are replaced by database seqs', () => {
    const live = buildBlocks(
      [
        entry(1_000_000_001, 'user_message', { text: '问' }),
        entry(1_000_000_002, 'tool_call', { id: 'c1', name: 'read_file', status: 'running' }),
      ],
      true,
    );
    const replayed = buildBlocks(
      [
        entry(11, 'user_message', { text: '问' }),
        entry(12, 'tool_call', { id: 'c1', name: 'read_file', status: 'done', startedAt: 1, endedAt: 3 }),
      ],
      false,
    );
    const liveTool = findBlock(live, 'flow-2');
    const replayedTool = findBlock(replayed, 'flow-2');
    expect(liveTool?.block.tool?.name).toBe('read_file');
    expect(replayedTool?.block.anchor).toBe(liveTool?.block.anchor);
    expect(replayedTool?.block.seq).toBe(12);
    expect(replayedTool?.turn.turn).toBe(1);
  });

  it('returns nothing for an anchor that is no longer rendered', () => {
    expect(findBlock(buildBlocks([entry(1, 'user_message', { text: '问' })], false), 'flow-9')).toBeNull();
  });
});

describe('timelineCards', () => {
  it('turns the visible blocks into cards in the order the conversation renders them', () => {
    const turns = buildBlocks(
      [
        entry(1, 'user_message', { text: '第一问' }),
        entry(2, 'decompose', { reason: '拆' }),
        entry(3, 'question', { text: '追问' }),
        entry(4, 'tool_call', { id: 'c1', name: 'read_file', args: '{"p":"a"}', status: 'done' }),
        entry(5, 'deliverable', { title: '身份核实' }),
        entry(6, 'advisor_message', { text: '答', reasoning: '想' }),
      ],
      false,
    );
    const cards = timelineCards(turns);
    expect(cards.map((card) => card.kind)).toEqual([
      'question',
      'advisor_message',
      'thinking',
      'tool_call',
      'user_message',
    ]);
    expect(cards[0]?.summary).toBe('追问');
    expect(cards[1]?.summary).toBe('答');
    expect(cards[3]?.summary).toBe('read_file');
  });

  it('lists cards newest first', () => {
    const turns = buildBlocks(
      [entry(1, 'user_message', { text: '问' }), entry(2, 'tool_call', { id: 'c1', name: 'read_file' })],
      false,
    );
    const seqs = timelineCards(turns).map((card) => card.seq);
    expect(seqs).toEqual([...seqs].sort((left, right) => right - left));
  });

  it('drops cards whose target block is not rendered', () => {
    expect(timelineCards(buildBlocks([entry(1, 'agent_route')], false))).toHaveLength(0);
  });

  it('carries the block status so a running card can say so itself', () => {
    const turns = buildBlocks([entry(1, 'tool_call', { id: 'c1', name: 'x', status: 'running' })], true);
    expect(timelineCards(turns).map((card) => card.status)).toEqual(['running']);
  });

  it('names every timeline kind in both languages', () => {
    const kindKeys = (values: Record<string, unknown>) =>
      (values.chat as { timelineKind: Record<string, string> }).timelineKind;
    const en = kindKeys(enApp as unknown as Record<string, unknown>);
    const zh = kindKeys(zhApp as unknown as Record<string, unknown>);
    for (const kind of TIMELINE_KINDS) {
      expect(en[kind], `en chat.timelineKind.${kind}`).toBeTruthy();
      expect(zh[kind], `zh chat.timelineKind.${kind}`).toBeTruthy();
    }
  });
});

describe('legacy conclusion replay', () => {
  const payload = { title: '旧报告', sections: [{ title: '结论', body: '唯一结论', items: [{ text: '依赖过期', recommendation: '升级依赖', conflict: '版本记录冲突' }] }] };

  it('renders unique historical conclusions as ordinary messages including actual file links', () => {
    const [turn] = buildBlocks([entry(1, 'deliverable', { ...payload, sections: [...payload.sections, { title: '文件', body: '[报告](https://example.com/report.pdf)' }] })], false);
    expect(turn?.finalText?.kind).toBe('advisor_message');
    expect(turn?.finalText?.text).toContain('唯一结论');
    expect(turn?.finalText?.text).toContain('依赖过期');
    expect(turn?.finalText?.text).toContain('升级依赖');
    expect(turn?.finalText?.text).toContain('版本记录冲突');
    expect(turn?.finalText?.text).toContain('[报告](https://example.com/report.pdf)');
    expect(timelineCards([turn!]).map(card => card.kind)).toEqual(['advisor_message']);
  });

  it('hides repeated content but keeps the unique recommendation', () => {
    const [turn] = buildBlocks([
      entry(1, 'deliverable', { sections: [{ body: '唯一结论', items: [{ text: '依赖过期', recommendation: '升级依赖' }] }] }),
      entry(2, 'advisor_message', { text: '唯一结论\n\n依赖过期' }),
    ], false);
    const texts = renderedBlocks(turn!).filter(block => block.kind === 'advisor_message').map(block => block.text).join('\n');
    expect(texts.match(/唯一结论/g)).toHaveLength(1);
    expect(texts.match(/依赖过期/g)).toHaveLength(1);
    expect(texts).toContain('升级依赖');
  });

  it.each([true, false])('preserves old conclusions with ordered parts (captured=%s)', captured => {
    const legacy = entry(2, 'deliverable', payload);
    const parts = [
      ...(captured ? [{ id: 'old-result', kind: 'event', event: legacy }] : []),
      { id: 'final-result', kind: 'text', text: '最终摘要', status: 'done' },
    ];
    const [turn] = buildBlocks([entry(1, 'user_message', { text: '问题' }), legacy, entry(3, 'advisor_message', { partsVersion: 1, parts })], false);
    const blocks = renderedBlocks(turn!);
    expect(blocks.every(block => block.kind !== 'deliverable')).toBe(true);
    expect(blocks.filter(block => block.kind === 'advisor_message').map(block => block.text).join('\n')).toContain('唯一结论');
    expect(new Set(blocks.map(block => block.anchor)).size).toBe(blocks.length);
    expect(timelineCards([turn!]).map(card => card.kind)).not.toContain('deliverable');
  });

  it('compares historical sections against actual ordered text rather than snapshot metadata', () => {
    const legacy = entry(1, 'deliverable', { sections: [{ body: '已有结论' }] });
    const [turn] = buildBlocks([legacy, entry(2, 'advisor_message', { partsVersion: 1, parts: [
      { id: 'old', kind: 'event', event: legacy }, { id: 'answer', kind: 'text', text: '已有结论' },
    ] })], false);
    expect(renderedBlocks(turn!).filter(block => block.kind === 'advisor_message').map(block => block.text)).toEqual(['已有结论']);
  });

  it('does not discard unique content based on an unused aggregate snapshot text', () => {
    const legacy = entry(1, 'deliverable', { sections: [{ body: '唯一结论' }], evidence: [{ id: 'F1', fact: '已确认', source: 'https://example.com/file.zip' }] });
    const [turn] = buildBlocks([legacy, entry(2, 'advisor_message', { text: '唯一结论', partsVersion: 1, parts: [
      { id: 'answer', kind: 'text', text: '实际展示摘要' },
    ] })], false);
    const text = renderedBlocks(turn!).map(block => block.text).join('\n');
    expect(text).toContain('唯一结论');
    expect(text).toContain('https://example.com/file.zip');
    expect(text).not.toContain('F1');
  });

  it('keeps a compatibility tool answer captured as an ordinary message event part', () => {
    const [turn] = buildBlocks([entry(1, 'advisor_message', { partsVersion: 1, parts: [
      { id: 'compat-answer', kind: 'event', event: { seq: 2, kind: 'advisor_message', payload: { text: '兼容工具的唯一结论' } } },
    ] })], false);
    expect(renderedBlocks(turn!).map(block => block.text)).toEqual(['兼容工具的唯一结论']);
  });

  it('keeps an earlier conclusion when a later turn repeats the same words', () => {
    const events = [entry(1, 'deliverable', { sections: [{ body: '之前的结论' }] }, 1), entry(2, 'advisor_message', { text: '之前的结论' }, 2)];
    expect(normalizeLegacyDeliverables(events).map(event => event.payload.text)).toEqual(['之前的结论', '之前的结论']);
    expect(buildBlocks(events, false).map(turn => turn.finalText?.text)).toEqual(['之前的结论', '之前的结论']);
  });

  it('preserves a recommendation when the final answer says the opposite', () => {
    const [turn] = buildBlocks([
      entry(1, 'deliverable', { sections: [{ items: [{ recommendation: '升级依赖' }] }] }),
      entry(2, 'advisor_message', { text: '不要升级依赖' }),
    ], false);
    expect(renderedBlocks(turn!).filter(block => block.kind === 'advisor_message').map(block => block.text)).toEqual(['- 升级依赖', '不要升级依赖']);
  });

  it('shows repeated old report text once while retaining a later unique recommendation', () => {
    const [turn] = buildBlocks([
      entry(1, 'deliverable', { sections: [{ body: '相同结论' }] }),
      entry(2, 'deliverable', { sections: [{ body: '相同结论', items: [{ recommendation: '升级依赖' }] }] }),
    ], false);
    const text = renderedBlocks(turn!).map(block => block.text).join('\n');
    expect(text.match(/相同结论/g)).toHaveLength(1);
    expect(text).toContain('升级依赖');
  });
});
