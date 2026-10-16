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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { afterEach, describe, expect, it } from 'vitest';
import { i18nResources } from '@/i18n/resources';
import { ActionBatch } from './ActionBatch';
import { ToolLine } from './ToolLine';
import { TurnGroup } from './TurnGroup';
import type { ConversationBlock, TurnProjection } from '../model/blocks';
const i18n=createInstance();
void i18n.use(initReactI18next).init({resources:i18nResources,lng:'zh-CN',defaultNS:'app',react:{useSuspense:false}});
afterEach(cleanup);
const block=(id:string,kind='tool_call',status:ConversationBlock['status']='done'):ConversationBlock=>({anchor:id,index:1,seq:1,turn:1,kind,status,createdAt:0,summary:id});
function view(blocks:ConversationBlock[],highlight:string|null=null,ended=false) {
 const final=ended?block('最终回答','advisor_message'):null;
 const turn:TurnProjection={turn:1,user:null,ordered:final?[...blocks,final]:blocks,process:blocks,finalText:final,outside:[],toolCount:blocks.filter(b=>b.kind==='tool_call').length,subagentCount:0,earlierReplyCount:0,collapsedByDefault:ended};
 return <I18nextProvider i18n={i18n}><TurnGroup turn={turn} forcedOpen={false} highlight={highlight} renderBlock={b=><p>{b.summary}</p>}/></I18nextProvider>;
}
describe('action batch disclosure during execution',()=>{
 it('folds adjacent commands and dispatch records together without hiding the surrounding commentary',()=>{
  render(view([block('先检查','advisor_message'),block('命令一'),block('派发一','dispatch'),block('命令二'),block('然后总结','advisor_message')]));
  const trigger=screen.getByRole('button',{name:/3项操作/});
  expect(trigger.getAttribute('aria-expanded')).toBe('false');
  expect(screen.queryByText('命令一')).toBeNull();expect(screen.queryByText('派发一')).toBeNull();
  expect(screen.getByText('先检查')).toBeTruthy();expect(screen.getByText('然后总结')).toBeTruthy();
  fireEvent.click(trigger);
  expect(screen.getByText('命令一')).toBeTruthy();expect(screen.getByText('派发一')).toBeTruthy();
  expect([...document.querySelectorAll('[data-anchor]')].map(x=>x.getAttribute('data-anchor'))).toEqual(['先检查','命令一','派发一','命令二','然后总结']);
 });
 it('keeps manual expansion when results or counts update and opens a located folded action',()=>{
  const blocks=[block('一'),block('二','tool_call','running')];
  const {rerender}=render(view(blocks));
  expect(screen.getByRole('button',{name:/2项操作/}).textContent).toContain('执行中');
  fireEvent.click(screen.getByRole('button',{name:/2项操作/}));
  rerender(view([...blocks.map(b=>({...b,status:'done' as const})),block('三')]));
  expect(screen.getByText('三')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:/3项操作/}));expect(screen.queryByText('三')).toBeNull();
  rerender(view([...blocks,block('三')],'三'));expect(screen.getByText('三')).toBeTruthy();
 });
 it('keeps the folded batch neutral and retains the underlying failed action',()=>{
  render(view([block('成功命令'),block('错误命令','tool_call','failed')]));
  expect(screen.getByRole('button',{name:/2项操作/}).textContent).not.toContain('失败');
  expect(screen.queryByText('错误命令')).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:/2项操作/}));
  expect(screen.getByText('错误命令')).toBeTruthy();
 });
 it('does not merge across text or thinking boundaries and leaves single actions visible',()=>{
  render(view([block('单条'),block('说明','advisor_message'),block('批次一'),block('批次二'),block('思考','thinking'),block('末条')]));
  expect(screen.getByText('单条')).toBeTruthy();expect(screen.getByText('末条')).toBeTruthy();
  expect(screen.getAllByRole('button',{name:/2项操作/})).toHaveLength(1);
 });
 it('preserves an explicitly opened single tool when the batch grows',()=>{
  const item={...block('one'),tool:{id:'one',name:'read_file',args:'{"path":"one.md"}',result:'one body',status:'done' as const,startedAt:null,durationMs:null}};
  function ControlledBatch({items}:{items:ConversationBlock[]}) {
    const [open,setOpen]=useState(false);
    return <ActionBatch blocks={items} highlight={null} open={open} onOpenChange={setOpen}>{items.map(b=><ToolLine key={b.anchor} block={b}/>)}</ActionBatch>;
  }
  const renderBatch=(items:ConversationBlock[])=><I18nextProvider i18n={i18n}><ControlledBatch items={items}/></I18nextProvider>;
  const {rerender}=render(renderBatch([item]));
  fireEvent.click(screen.getByRole('button',{name:/one.md/}));
  expect(screen.getByText('one body')).toBeTruthy();
  rerender(renderBatch([item,{...item,anchor:'two',tool:{...item.tool,id:'two',args:'{"path":"two.md"}',result:'two body'}}]));
  expect(screen.getByRole('button',{name:/2项操作/}).getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByText('one body')).toBeTruthy();
 });

 it('retains an expanded batch after the completed turn folds and is reopened',()=>{
  const blocks=[block('原命令一'),block('原命令二')];
  const {rerender}=render(view(blocks));
  fireEvent.click(screen.getByRole('button',{name:/2项操作/}));
  expect(screen.getByText('原命令一')).toBeTruthy();
  rerender(view(blocks,null,true));
  expect(screen.queryByText('原命令一')).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'查看执行过程'}));
  expect(screen.getByText('原命令一')).toBeTruthy();
  expect(screen.getByRole('button',{name:/2项操作/}).getAttribute('aria-expanded')).toBe('true');
 });

});
