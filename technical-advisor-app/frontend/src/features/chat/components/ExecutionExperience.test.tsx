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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { i18nResources } from '@/i18n/resources';
import { ToolLine } from './ToolLine';
import { ThinkingLine } from './ThinkingLine';
import { TurnGroup } from './TurnGroup';
import { ToolConfirmationCard } from './gateCards';
import { vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConversationPanel } from '../ConversationPanel';
import type { ConversationBlock, TurnProjection } from '../model/blocks';
const i18n=createInstance();void i18n.use(initReactI18next).init({resources:i18nResources,lng:'zh-CN',defaultNS:'app',react:{useSuspense:false}});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('shows a clear failure without deleting the displayed history', async () => {
 vi.stubGlobal('localStorage', { getItem: () => null });
 vi.stubGlobal('fetch', async (url: string) => {
   if (String(url).includes('/commands/clear:execute')) return new Response('unavailable', {status: 503});
   const data = String(url).endsWith('/sessions/clear-test')
     ? {session: {sessionId: 'clear-test', title: 'session', status: 'idle', agentId: null}, entries: [{seq: 1, turn: 1, kind: 'user_message', payload: {text: 'saved history'}, createdAt: 0}], deliverables: []}
     : String(url).includes('/models/active') ? {modelName: 'fixture'} : [];
   return new Response(JSON.stringify(data), {headers: {'Content-Type': 'application/json'}});
 });
 const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
 render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><ConversationPanel sessionId="clear-test" /></I18nextProvider></QueryClientProvider>);
 await screen.findByText('saved history');
 const input = screen.getByRole('textbox');
 fireEvent.change(input, {target: {value: '/clear'}});
 fireEvent.keyDown(input, {key: 'Enter'});
 fireEvent.keyDown(input, {key: 'Enter'});
 await waitFor(() => expect(screen.getByText('清空失败，原会话内容已保留，请稍后重试')).toBeTruthy());
 expect(screen.getByText('saved history')).toBeTruthy();
 client.clear();
});

it('collects explicit decisions for every permission request before resuming', () => {
 const onResolve = vi.fn();
 const interrupts = ['one', 'two'].map(id => ({ id, reason: 'tool_call', metadata: { toolName: id, 'deepscope.interruptKind': 'permission_confirm', toolInput: { path: 'file.txt' } } }));
 render(<I18nextProvider i18n={i18n}><ToolConfirmationCard interrupts={interrupts} busy={false} onResolve={onResolve} /></I18nextProvider>);
 expect((screen.getByRole('button', {name: '提交确认'}) as HTMLButtonElement).disabled).toBe(true);
 fireEvent.click(screen.getByRole('button', {name: '允许 one'}));
 fireEvent.click(screen.getByRole('button', {name: '拒绝 two'}));
 fireEvent.click(screen.getByRole('button', {name: '提交确认'}));
 expect(onResolve).toHaveBeenCalledWith([{interruptId: 'one', status: 'resolved', payload: {approved: true}}, {interruptId: 'two', status: 'resolved', payload: {approved: false}}]);
});

it('cancels unknown interrupt kinds without inventing an approval payload', () => {
 const onResolve = vi.fn();
 render(<I18nextProvider i18n={i18n}><ToolConfirmationCard interrupts={[{id: 'unknown', reason: 'external'}]} busy={false} onResolve={onResolve} /></I18nextProvider>);
 fireEvent.click(screen.getByRole('button', {name: '取消等待'}));
 expect(onResolve).toHaveBeenCalledWith([{interruptId: 'unknown', status: 'cancelled'}]);
});
const base:ConversationBlock={anchor:'t',kind:'tool_call',index:1,seq:1,turn:1,status:'done',createdAt:0,summary:'execute',tool:{id:'t',name:'execute',args:'{"command":"cd /private/project && cat secret.txt"}',result:'actual returned content',status:'done',startedAt:null}};
const view=(block=base)=><I18nextProvider i18n={i18n}><ToolLine block={block}/></I18nextProvider>;
it('keeps raw commands out of the row and puts actual results before explicitly requested technical details',()=>{
 const {container}=render(view());expect(container.textContent).not.toContain('cd /private/project');
 fireEvent.click(screen.getByRole('button'));
 expect(screen.getByText('actual returned content')).toBeTruthy();
 expect(container.textContent).not.toContain('secret.txt');
 fireEvent.click(screen.getByRole('button',{name:'技术详情'}));
 expect(container.textContent).toContain('cd /private/project');
});
it('does not replace a missing result with its input',()=>{
 const {container}=render(view({...base,tool:{...base.tool!,result:undefined}}));
 fireEvent.click(screen.getByRole('button'));expect(screen.getByText('操作已结束，未返回可展示内容')).toBeTruthy();
 expect(container.textContent).not.toContain('secret.txt');
});
it('replaces internal thinking fragments with an understandable status and stops motion on completion',()=>{
 const ui=(streaming:boolean)=><I18nextProvider i18n={i18n}><ThinkingLine text="Need inspect raw internal reasoning" streaming={streaming}/></I18nextProvider>;
 const {container,rerender}=render(ui(true));expect(container.textContent).toContain('raw internal');
 expect(screen.getByText('正在分析')).toBeTruthy();expect(container.querySelector('[data-process-loading]')).toBeTruthy();
 rerender(ui(false));expect(container.querySelector('[data-process-loading]')).toBeNull();
 expect(screen.getByText('分析记录')).toBeTruthy();
});

it('keeps only one live focus across batches and disables motion while waiting for user action', () => {
 const blocks = [
   {...base, anchor: 'old', kind: 'thinking', thinkingText: 'old reasoning', status: 'running' as const, tool: undefined},
   ...['a', 'b'].map(anchor => ({...base, anchor, status: 'running' as const, tool: {...base.tool!, status: 'running' as const, result: undefined}})),
 ];
 const turn: TurnProjection = {turn: 1, user: null, process: blocks, ordered: blocks, finalText: null, outside: [], collapsedByDefault: false, toolCount: 2, earlierReplyCount: 0, subagentCount: 0};
 const ui = (enabled: boolean) => <I18nextProvider i18n={i18n}><TurnGroup turn={turn} forcedOpen={false} highlight={null} activityEnabled={enabled} renderBlock={block => block.kind === 'thinking' ? <ThinkingLine text={block.thinkingText!} streaming /> : <ToolLine block={block} />} /></I18nextProvider>;
 const {container, rerender} = render(ui(true));
 expect(container.querySelectorAll('[data-process-loading]')).toHaveLength(1);
 expect(container.querySelectorAll('.process-status-live')).toHaveLength(1);
 fireEvent.click(screen.getByRole('button', {name: /2项操作/}));
 expect(container.querySelectorAll('[data-process-loading]')).toHaveLength(1);
 expect(container.querySelectorAll('.process-status-live')).toHaveLength(1);
 rerender(ui(false));
 expect(container.querySelector('[data-process-loading]')).toBeNull();
 expect(container.querySelector('.process-status-live')).toBeNull();
});

it('keeps a child failure visible in the folded batch even when its transport call returned normally', () => {
 const failed = {...base, anchor: 'spawn', tool: {...base.tool!, name: 'agent_spawn', args: '{}', result: 'agent_key: a\nstatus: error\nerror: model unavailable'}};
 const blocks = [failed, {...base, anchor: 'other'}];
 const turn: TurnProjection = {turn: 1, user: null, process: blocks, finalText: null, outside: [], collapsedByDefault: false, toolCount: 2, earlierReplyCount: 0, subagentCount: 0};
 render(<I18nextProvider i18n={i18n}><TurnGroup turn={turn} forcedOpen={false} highlight={null} renderBlock={block => <ToolLine block={block} />} /></I18nextProvider>);
 expect(screen.getByRole('button', {name: /2项操作/}).textContent).not.toContain('失败');
 fireEvent.click(screen.getByRole('button', {name: /2项操作/}));
 expect(screen.getByRole('button', {name: /子任务未完成/})).toBeTruthy();
 fireEvent.click(screen.getByRole('button', {name: /子任务未完成/}));
 expect(screen.getByText('error: model unavailable')).toBeTruthy();
});
