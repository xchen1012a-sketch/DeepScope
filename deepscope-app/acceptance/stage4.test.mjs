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
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const here = dirname(fileURLToPath(import.meta.url));
const app = resolve(here, '..');
const baseUrl = process.env.ADVISOR_BASE_URL ?? 'http://localhost:18080';
const prefix = `tds-acceptance-${randomUUID()}`;
const visitorEnv = {
  ...process.env,
  ACCEPTANCE_VISITOR_USERNAME: prefix,
  ACCEPTANCE_VISITOR_PASSWORD: randomUUID(),
};
const resources = { agents: [], sessions: [] };
let runtimeLibs;

async function request(path, token, method = 'GET', body, expected = 200) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(660_000),
  });
  assert.equal(response.status, expected, `${method} ${path} returned ${response.status}`);
  return response.status === 204 || !response.headers.get('content-type')?.includes('json')
    ? null : response.json();
}

function fixture(action) {
  const result = spawnSync('java', ['--class-path', `${resolve(app, 'backend/target/classes')}:${runtimeLibs}/BOOT-INF/lib/*`, resolve(here, 'FixtureAccounts.java'), action], {
    env: visitorEnv, encoding: 'utf8', timeout: 30_000,
  });
  assert.equal(result.status, 0, `Visitor fixture ${action} failed`);
}

async function session(token, agentId) {
  const created = await request('/api/advisor/sessions', token, 'POST', { agentId });
  resources.sessions.push({ id: created.sessionId, token });
  return created.sessionId;
}

async function run(token, sid, agentId, text) {
  await request(`/api/advisor/sessions/${sid}/messages`, token, 'POST', { role: 'visitor', text }, 201);
  const response = await fetch(`${baseUrl}/agui/run/${encodeURIComponent(agentId)}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ threadId: sid, runId: randomUUID(), messages: [{ id: randomUUID(), role: 'user', content: text }] }),
    signal: AbortSignal.timeout(660_000),
  });
  assert.equal(response.status, 200, 'Real model run should start');
  const events = (await response.text()).split(/\r?\n/).filter(line => line.startsWith('data:')).flatMap(line => {
    try { return [JSON.parse(line.slice(5).trim())]; } catch { return []; }
  });
  assert.ok(events.some(event => event.type === 'RUN_FINISHED'), 'Model run must finish');
  assert.ok(!events.some(event => event.type === 'RUN_ERROR'), 'Model run must not fail');
  const answer = events.filter(event => event.type === 'TEXT_MESSAGE_CONTENT').map(event => event.delta ?? '').join('');
  if (answer.trim()) await request(`/api/advisor/sessions/${sid}/messages`, token, 'POST', { role: 'advisor', text: answer }, 201);
  return events;
}

test('stage 4 real model, workspace and approval acceptance', { timeout: 1_400_000 }, async t => {
  assert.ok(process.env.ADVISOR_ADMIN_PASSWORD, 'Set ADVISOR_ADMIN_PASSWORD without storing it in the repository');
  runtimeLibs = await mkdtemp(resolve(tmpdir(), 'tds-acceptance-'));
  const jar = resolve(app, 'backend/target/technical-advisor-backend-2.0.5-SNAPSHOT.jar');
  const extracted = spawnSync('jar', ['xf', jar, 'BOOT-INF/lib'], { cwd: runtimeLibs, encoding: 'utf8' });
  assert.equal(extracted.status, 0, 'Build the backend package before acceptance');
  const admin = (await request('/api/auth/login', null, 'POST', {
    username: process.env.ADVISOR_ADMIN_USERNAME ?? 'admin', password: process.env.ADVISOR_ADMIN_PASSWORD,
  })).token;
  let visitor;
  let visitorUserId;
  try {
    fixture('create');
    const visitorLogin = await request('/api/auth/login', null, 'POST', {
      username: visitorEnv.ACCEPTANCE_VISITOR_USERNAME, password: visitorEnv.ACCEPTANCE_VISITOR_PASSWORD,
    });
    visitor = visitorLogin.token;
    visitorUserId = visitorLogin.userId;
    const catalog = await request('/api/advisor/agents', admin);
    assert.ok(Array.isArray(catalog) && catalog.some(agent => agent.agentId === 'technical-advisor'));
    const created = await request('/api/advisor/agents', admin, 'POST', { name: prefix, description: '阶段四真实验收用智能体' }, 201);
    resources.agents.push(created.agentId);
    assert.equal(created.canEdit, true);
    const original = await readFile(resolve(app, 'frontend/package.json'), 'utf8');
    await request(`/api/advisor/agents/${created.agentId}/files`, admin, 'POST', { path: 'knowledge/frontend-package.json', content: original }, 201);
    const copy = await request('/api/advisor/agents', admin, 'POST', { name: `${prefix} 副本`, description: '复制后的独立验收工作区', copyFromAgentId: created.agentId }, 201);
    resources.agents.push(copy.agentId);
    assert.equal((await request(`/api/advisor/agents/${copy.agentId}/files?path=knowledge%2Ffrontend-package.json`, admin)).content, original);
    await t.test('create and copy preserve real independent workspace content', () => {
      assert.notEqual(created.agentId, copy.agentId);
      assert.equal(copy.mine, true);
    });

    await t.test('free mode routes once per turn and keeps its decisions in the timeline', async () => {
      const sid = await session(admin);
      for (const text of ['分析前端构建配置', '检查测试脚本']) {
        const decision = await request(`/api/advisor/sessions/${sid}/route`, admin, 'POST', { text });
        assert.ok(decision.agentId && decision.reason);
      }
      const detail = await request(`/api/advisor/sessions/${sid}`, admin);
      assert.equal(detail.session.agentId, null);
      assert.equal(detail.entries.filter(entry => entry.kind === 'agent_route').length, 2);
    });

    await t.test('builtin approval returns 403 for a real ordinary visitor', async () => {
      const sid = await session(visitor, 'technical-advisor');
      await run(visitor, sid, 'technical-advisor', '这是子智能体审批权限验收。请调用 report_proposal 提出 frontend-permission-surveyor，职责是核对真实前端构建配置；content 使用合法 Markdown 定义，必须以 --- 开始且 YAML 含 description，然后结束 ---，正文只要求核对真实事实。不要落盘，不要派发，等待批准。');
      const detail = await request(`/api/advisor/sessions/${sid}`, visitor);
      const proposal = detail.entries.find(entry => entry.kind === 'proposal');
      assert.ok(proposal, 'Real model must submit the proposal');
      await request(`/api/advisor/sessions/${sid}/proposals/${proposal.seq}/resolve`, visitor, 'POST', { approved: true }, 403);
      await request(`/api/advisor/sessions/${sid}/proposals/${proposal.seq}/resolve`, visitor, 'POST', { approved: false });
    });

    await t.test('F08 approves an edited draft and uses the new subagent on the next turn', async () => {
      const sid = await session(admin, copy.agentId);
      await run(admin, sid, copy.agentId, '这是动态子智能体验收。现有工作区没有前端配置调查员，需要独立上下文调查 React/Vite 项目。请只用 report_proposal 提出 frontend-manifest-surveyor，description 写核对前端 package.json 的构建与测试脚本，goal 写补足独立前端调查能力；content 必须是以 --- 开始的合法定义、YAML 含 description、关闭 --- 后是调查职责。不要落盘、不要派发、不要提交交付物，等访客批准。');
      const before = await request(`/api/advisor/sessions/${sid}`, admin);
      const proposal = before.entries.find(entry => entry.kind === 'proposal');
      assert.ok(proposal, 'Real model must propose a new subagent');
      assert.equal(before.session.status, 'awaiting_visitor');
      assert.equal((await request(`/api/advisor/agents/${copy.agentId}`, admin)).counts.subagents, 0, 'No subagent may exist before approval');
      const manifestPath = resolve(app, 'frontend/package.json');
      const edited = `---\ndescription: Investigate the real frontend manifest with source-backed facts.\n---\n\n读取 ${manifestPath}，只报告 scripts.build 与 scripts.test 的真实值及来源路径，不做无依据推断。\n`;
      const resolution = await request(`/api/advisor/sessions/${sid}/proposals/${proposal.seq}/resolve`, admin, 'POST', { approved: true, content: edited });
      assert.equal((await request(`/api/advisor/agents/${copy.agentId}/files?path=${encodeURIComponent(resolution.file)}`, admin)).content, edited);
      const name = basename(resolution.file, '.md');
      const events = await run(admin, sid, copy.agentId, `访客已批准并落盘 ${resolution.file}。请先 report_dispatch，再通过 agent_spawn 派出已注册名称 ${name}（不要使用 general-purpose 替代）核对 ${manifestPath} 的 scripts.build 与 scripts.test，等待该子智能体返回真实结果，再用 report_facts 和 report_deliverable 汇报，结论注明来源。不要重新提案，不要自己代查。`);
      const toolStarts = events.filter(event => event.type === 'TOOL_CALL_START');
      assert.ok(toolStarts.some(event => event.toolCallName === 'agent_spawn'), 'The approved subagent must actually be spawned');
      const argumentsText = events.filter(event => event.type === 'TOOL_CALL_ARGS').map(event => event.delta ?? '').join('');
      assert.ok(argumentsText.includes(name), 'Spawn arguments must select the approved definition');
      assert.ok(events.some(event => event.type === 'CUSTOM' && event.name === 'subagent.lifecycle' && event.value?.type === 'AGENT_END'), 'A real child agent must complete');
      const childText = events.filter(event => event.type === 'CUSTOM' && event.name === 'subagent.text').map(event => event.value?.delta ?? '').join('');
      assert.ok(childText.includes(JSON.parse(original).scripts.build), 'The child must return the real build command');
      const after = await request(`/api/advisor/sessions/${sid}`, admin);
      assert.ok(after.entries.some(entry => entry.kind === 'subagent_facts'), 'Subagent facts must be returned and persisted');
      assert.ok(after.deliverables.length > 0, 'The subagent investigation must produce a deliverable');
      const report = JSON.stringify(after.deliverables);
      const manifest = JSON.parse(original);
      assert.ok(report.includes(manifest.scripts.build), 'Deliverable must contain the real build command');
      assert.ok(report.includes(manifest.scripts.test), 'Deliverable must contain the real test command');
      assert.ok(report.includes('package.json'), 'Deliverable must cite its real source');
    });
  } finally {
    for (const { id, token } of resources.sessions.reverse()) {
      await request(`/api/advisor/sessions/${id}`, token, 'DELETE', undefined, 204);
    }
    for (const id of resources.agents.reverse()) {
      await request(`/api/advisor/agents/${id}`, admin, 'DELETE', undefined, 204);
      await rm(resolve(process.env.ADVISOR_DATA_DIR ?? resolve(app, 'data'), 'agents', id), { recursive: true, force: true });
    }
    if (visitor) await request('/api/auth/logout', visitor, 'POST', undefined, 204);
    fixture('delete');
    if (visitorUserId) await rm(resolve(process.env.ADVISOR_DATA_DIR ?? resolve(app, 'data'), 'workspace', visitorUserId), { recursive: true, force: true });
    await rm(runtimeLibs, { recursive: true, force: true });
  }
});
