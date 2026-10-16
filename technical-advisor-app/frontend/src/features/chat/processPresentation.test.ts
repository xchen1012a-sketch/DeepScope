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
import { describeTool } from './processPresentation';
import type { ToolCall } from './conversationModel';
const tool=(over:Partial<ToolCall>={}):ToolCall=>({id:'t',name:'execute',args:'{"command":"cd /private/project && npm test"}',status:'done',startedAt:null,...over});
describe('readable action and result presentation',()=>{
 it('does not copy shell commands into titles or infer test intent from compound commands',()=>{
  const result=describeTool(tool());expect(result.titleKey).toBe('chat.activity.actions.run.done');expect(result.target).toBe('');
  expect(describeTool(tool({args:'{"command":"npm test"}'})).titleKey).toBe('chat.activity.actions.test.done');
 });
 it('uses only a filename for an absolute file target',()=>{
  const result=describeTool(tool({name:'read_file',args:'{"path":"/private/project/TurnGroup.tsx"}'}));
  expect(result.target).toBe('TurnGroup.tsx');
 });
 it('keeps unknown actions neutral rather than exposing arbitrary names or arguments',()=>{
  const result=describeTool(tool({name:'mcp__unknown',args:'{"command":"secret-command","query":"private query"}'}));
  expect(result.titleKey).toBe('chat.activity.actions.other.done');expect(result.target).toBe('');
 });
 it('does not use input as a result or show a successful result before return',()=>{
  expect(describeTool(tool({result:tool().args})).resultKey).toBe('chat.activity.result.echo');
  expect(describeTool(tool({result:'',status:'done'})).resultKey).toBe('chat.activity.result.empty');
  expect(describeTool(tool({result:'old output',status:'running'})).resultKey).toBe('chat.activity.result.waiting');
 });
 it('summarizes only consistent explicit test statistics and never guesses from exit code',()=>{
  const result=describeTool(tool({args:'{"command":"npm test"}',result:'{"tests":{"passed":7,"failed":1,"skipped":0,"total":8}}'}));
  expect(result.resultKey).toBe('chat.activity.result.tests');expect(result.resultValues).toEqual({passed:7,failed:1,total:8,skipped:0});
  expect(describeTool(tool({result:'{"exitCode":0}'})).resultKey).toBe('chat.activity.result.returned');
  expect(describeTool(tool({result:'{"tests":{"passed":7,"failed":1,"total":1}}'})).resultKey).toBe('chat.activity.result.returned');
 });
 it('does not call a dispatched child task completed when its spawn tool returns',()=>{
  const result=describeTool(tool({name:'agent_spawn',args:'{"title":"前端审查"}',result:'task created'}));
  expect(result.titleKey).toBe('chat.activity.actions.spawn.done');expect(result.resultKey).toBe('chat.activity.result.dispatched');
 });
 it('describes the real shell result format without calling exit zero a successful business result',()=>{
  const result=describeTool(tool({result:'Exit code: 0\n\nactual output'}));
  expect(result.resultKey).toBe('chat.activity.result.exit');
  expect(result.resultValues).toEqual({code:0});
  expect(result.content).toBe('actual output');
 });
 it('reads task status only from the task tool header, never from quoted output',()=>{
  const running=describeTool(tool({name:'task_output',result:'task_id: t1\nstatus: running\n\nTask still running...'}));
  expect(running.action).toBe('collect');
  expect(running.resultKey).toBe('chat.activity.result.taskRunning');
  const complete=describeTool(tool({name:'task_output',result:'task_id: t1\nstatus: completed\ncreated_at: 2026-10-15T00:00:00Z\n\nResult:\nreal summary\nstatus: running'}));
  expect(complete.resultKey).toBe('chat.activity.result.taskCompleted');
  expect(complete.content).toBe('real summary\nstatus: running');
 });
 it('keeps a failed input echo out of the result content and uses the declared child label',()=>{
  expect(describeTool(tool({status:'failed',result:tool().args})).content).toBeUndefined();
  expect(describeTool(tool({name:'agent_spawn',args:'{"label":"前端审查"}'})).target).toBe('前端审查');
 });
 it('shows an actual synchronous child reply without promoting a dispatch receipt to completion',()=>{
  const result=describeTool(tool({name:'agent_spawn',result:'agent_key: a\nagent_id: reviewer\nsession_id: s\nstatus: ok\nreply:\nactual child reply'}));
  expect(result.resultKey).toBe('chat.activity.result.taskReply');
  expect(result.content).toBe('actual child reply');
 });
 it('decodes the persisted JSON string return and the capitalized TaskTool status',()=>{
  const result=describeTool(tool({name:'task_output',result:JSON.stringify('task_id: t\nstatus: Completed\ncreated_at: 2026-10-15\n\nResult:\nactual result')}));
  expect(result.resultKey).toBe('chat.activity.result.taskCompleted');
  expect(result.content).toBe('actual result');
 });
 it.each(['error','cancelled','timeout'])('does not present the real spawn status %s as a dispatch receipt',state=>{
  const result=describeTool(tool({name:'agent_spawn',result:`agent_key: a\nagent_id: reviewer\nsession_id: s\nstatus: ${state}\nerror: model unavailable`}));
  expect(result.resultKey).not.toBe('chat.activity.result.dispatched');
  expect(result.content).toContain('model unavailable');
  expect(result.titleKey).not.toBe('chat.activity.actions.spawn.done');
 });
});
