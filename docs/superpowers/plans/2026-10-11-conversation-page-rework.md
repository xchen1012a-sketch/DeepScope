# 会话页改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan phase-by-phase. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 会话页模块整体达到产品级：主对话、时间线、子智能体三处共用同一份回合记录，用户能看见 agent 真正做了什么（思考、工具调用、子智能体派发与耗时），并按 DeepSeek Harness 的紧凑回合、Think 行、工具行、子会话交互呈现。

**Architecture:** 数据源不变——实时走 `POST /agui/run/{agentId}` 的 AG-UI SSE，落库与回放走 `advisor_events`（`POST /api/advisor/sessions/{id}/messages` 是唯一写入点，`GET /api/advisor/sessions/{id}` 是唯一回放入口）。**不把 advisor 改为读内核会话日志**：那会引入新的跨系统契约，属独立立项。五个阶段严格向前依赖：先把记录补全（否则视图是空壳），再把三处视图统一到一份「块」模型，最后输入区与清理、收口。

**Tech Stack:** React 18 + TypeScript 5.9 + Vite 6 + Tailwind CSS 4（无 `tailwind.config.js`，主题在 `src/index.css` 的 `@theme`）+ Radix UI + class-variance-authority + `cn()` + TanStack Query 5 + i18next + Vitest 4（默认 node 环境，无 DOM 测试设施）+ ESLint flat config。

**Spec:** `docs/superpowers/specs/2026-10-11-conversation-stream-design.md`（v1.2，状态「待评审」）。

**改动分级：高级改动**（`.ai-spec/change-levels.md` §三）：改动跨前后端契约（`POST /api/advisor/sessions/{id}/messages` 请求体、`advisor_events` 载荷形状与事件种类），且整体改变用户可见行为。因此兼容性分析与回滚方案见下方 §执行前评审材料，**必须在开工前随本计划一起评审**。`.ai-spec/change-levels.md` §四.8：未经明确授权不 commit、不 push。

## Global Constraints

以下要求对每个阶段生效，来源 `.ai-spec/`，逐条照抄：

- **门禁（工具强制）**：`npm run lint` 必须零 error（`react-hooks/rules-of-hooks` 为 error 级）；`npm run build` 内含 `tsc --noEmit`，TS 编译错误即失败；`npm test`（Vitest）必须通过；涉及关键用户流程的变更须跑 `npm run test:e2e`。命令一律在 `technical-advisor-app/frontend` 执行。**禁止跳过、禁用规则或弱化断言**。
- **文案零硬编码**：用户可见文案必须经 `t()`；词条在 `src/i18n/locales/{en,zh-CN}/{app,common}.json`，默认命名空间 `app`，默认语言 `zh-CN`，回退 `en`。**新增文案必须同时补齐 en 与 zh-CN**。
- **i18n 机器强制**（`src/i18n/i18n.test.ts:47-62`、`src/i18n/bodyLocalization.test.tsx:108-116`）：两语言扁平键集合必须完全相同；`{{param}}` 插值参数逐键一致；zh 不允许空值；源码中每个字面量 `t('…')` / `translate('…')` / `i18next.t('…')` 的键必须在两种语言的 `app` 或 `common` 中存在（模板字符串键不受保护，需人工核对）。
- **服务端状态**：一律通过 TanStack Query 获取与缓存，query key 按资源维度稳定命名；禁止手工把服务端缓存同步进本地 state。
- **样式**：Tailwind 原子类 + `cn()`；可复用组件变体用 class-variance-authority；禁止用内联 style 表达类能表达的外观。
- **组件边界**：交互基础组件优先组合 Radix 原语，不自行实现焦点管理与键盘导航。`src/components/` 不得新增对某个 feature 内部模块的依赖。
- **依赖克制**：UI 用 Radix、样式用 Tailwind、图标用 lucide-react、Markdown 用 react-markdown；引入新的大类库属中级以上改动，须在本计划内说明理由。
- **类型收敛**：`no-explicit-any` 当前 off，但函数签名禁止用 `any` 掩盖入参/返回结构。
- **契约先行**：跨前后端改动必须先定义 API 契约，确认后再各自实现。
- **文档同步**：用户可见行为变化必须同步更新 `docs/v2` 的 EN 与 ZH 页面。
- **收尾**：无生成产物误提交（`target/`、`node_modules/`、`dist/`）。

---

## 执行口径（已定，不再逐项确认）

**三个口径**

1. **设计文档即验收标准**：按 v1.2 的当前文字执行。它自身标注「待评审」——若评审改动某条，改哪条就同步改本计划对应步骤，不改整体排法。
2. **上下文窗口来源取 A（应用层）**：窗口随 `usage[i].contextWindow` 提交，**不改 `agentscope-extensions-agui`**。只有 A 被证伪时才回到 B，并按高级改动额外评审。
3. **兼容性与回滚即下方两节**，随本计划作为评审材料；本改动**无不可逆点**（不删既有字段、不改既有行语义）。

**流程压缩（规范不省，只压缩次数）**

- **门禁次数**：每阶段结束跑一次 `npm test && npm run build && npm run lint`；阶段二额外跑 `mvn -T1 -pl technical-advisor-app/backend -am test`；阶段五跑全量与真实验收。步骤中间只在改动落地的文件上跑定向测试（`npm test -- <file>`），不每次全量。
- **提交次数**：**每阶段一个提交**，共 5 个。`.ai-spec/change-levels.md` §四.8 要求明确授权——按阶段边界逐次确认，或开工前拿一次覆盖全程的授权。
- **e2e 的处置**：`npm run test:e2e` 无 Playwright 配置、浏览器未安装，跑它不验证任何东西。**本计划不新建 e2e 套件**（配置 + 选择器 + 后端依赖属独立立项）。关键用户流程按「组件交互测试（阶段一引入的 jsdom + testing-library）+ 仓库既有真后端验收脚本 `acceptance/stage4.test.mjs`」覆盖。**在阶段五如实记录这条缺口，不声称 e2e 通过。**
- **docs 校验的处置**：`docs/v2` 那对页面未登记进 `docs/docs.json` 导航，`Page missing from navigation` 是既有红。阶段五**在 `docs.json` 的 en/zh 各补一个导航项**把它消掉，而不是跳过校验。
- **并行**：阶段四的「输入区说真话」只依赖阶段二 Step 4，可与阶段三并行；其余严格串行（阶段三依赖阶段二的数据）。

**预计**：5 个阶段、5 个提交、6 次阶段门禁 + 1 次验收。

## 执行前评审材料：兼容性分析

| 面 | 结论 | 依据 |
| --- | --- | --- |
| `advisor_events.payload` 新字段 | **只增不改，向后兼容**。新增 `startedAt`、`failed`、`contextWindow`、`firstTokenMs`、`outputTokensPerSecond` 全部可选；新增事件种类 `subagent_step` | 老行缺字段时读侧必须降级：不显示耗时、不画上下文环、详情页显示「该任务没有落下来的过程记录」，**不得显示 0 或空壳** |
| `POST /api/advisor/sessions/{id}/messages` 请求体 | **放宽而非收紧**：允许 `text` 为空，只要 `reasoning`/`toolCalls`/`usage` 有内容；响应码不变 | 老客户端与老数据（`text` 非空）行为不变 |
| `advisor_events` 既有行语义 | **不改**。`created_at` 仍是服务端追加时间，不被复用为业务时间 | 新增的业务时间一律放进 `payload` |
| 未知 kind 的处理 | 老读侧遇到 `subagent_step` 时忽略即可，不得报错 | 读侧按 kind 分发，天然容错 |
| 若选 B（改扩展） | `token_usage` 的 value 增 `contextWindow` 属扩展公共契约变更；新键为新增，老消费方忽略 | 需在本节补充新老消费方清单与兼容结论 |
| 未改动 | 内核会话日志与 `SessionEventCodec`；`advisor_deliverables` 结构；提案裁决契约；会话列表行为；`frontend/package.json` 的 `build`/`test` 脚本名（验收脚本以它们为真值） | — |

## 执行前评审材料：回滚方案

| 场景 | 回滚动作 |
| --- | --- |
| 任一阶段完成后 | 该阶段是独立提交，`git revert <sha>` 即回到上一阶段；阶段三引入的新组件与旧组件在同一提交内替换，回退即恢复旧渲染 |
| 阶段二之后 | 后端改动是「放宽校验 + 新增可选字段 + 新增一种 kind」，回退后新数据里的 `subagent_step` 行会被旧读侧忽略（未知 kind 不渲染），不破坏老逻辑 |
| 数据 | `advisor_events` 只增不改，**无需数据回滚** |
| 不可逆点 | **无**：没有删除既有字段，也没有改写既有行的语义 |
| 回滚触发条件 | 任一阶段门禁出现非既有失败；或阶段五验收脚本出现 `RUN_ERROR` |

---

## 现状（已核实，非推测）

**已经实现、不要重做**：紧凑回合（按 `event.turn` 分组、结束收起、收起条三项计数、最终正文留组外并前置通栏分隔线、进行中不收起）；思考行（默认收起、流出跟最后一行、展开不内部滚动）；工具行（同一行两态、点开看参数与结果）；输入区的统计条、缓存命中四格、模型位与「当前」标记、上下文环组件与面板；时间线的滚动定位与 1.5 秒高亮框架。

**决定排法的关键事实**：

1. advisor 的落库与回放**只经过 `advisor_events`**，不读内核会话日志（`AdvisorSessionController.java:188-190` 唯一写入；`:155-186` 唯一回放）。
2. **整轮落库被 `if (streamingText.trim())` 包住**（`useAdvisorConversation.ts:568-582`），后端也拒空正文（`AdvisorSessionController.java:202-205`）→ 只有思考、没有正文的回合，过程记录全丢。
3. **工具开始时间从未落库**：前端在 `TOOL_CALL_START` 已持有该时刻（`useAdvisorConversation.ts:362`）但没写进载荷（`:365-372` 只有 `endedAt`）；回放拿事件行 `created_at` 顶替（`conversationModel.ts:206`），而那行是回合结束才写的 → 刷新后耗时≈0。
4. **`contextWindow` 没有任何生产者**：AG-UI 的 `token_usage` value 里没有（`ModelCallUsageEventConverter.java:54-62`），`UsageSample.contextWindow`（`conversationModel.ts:65`）无赋值点 → `contextOccupancy` 恒 null → 上下文环永不出现。
5. **子智能体自身过程只存在于父流**：CUSTOM `subagent.lifecycle|text|thinking|tool_call|tool_result`（`SubagentEventConverter.java:39-45`）已上线，但前端 `classifyAguiLine`（`aguiClient.ts:120-164`）无这些分支，直接丢弃；子智能体 `DurableSession` 被显式置空（`DefaultAgentManager.java:249-255`），内核日志里也没有 → 详情页恒为空。
6. **内核侧用量是死路径**：`DurableSession.java:265` 给 `AssistantMessageData` 的 usage 传 `null`；`RequestContext` 全仓库无构造点。**不要**指望内核 `request/context`。
7. **两个 usage 解析器字段集不同**：`ConversationPanel.usageOf`（`:86-98`，保留 `contextWindow`/`timeSeconds`）与 `useAdvisorConversation.usageFrom`（`:428-438`，丢掉两者）；同一步第二次用量会覆盖（`applyUsageSignal:411-417`）。
8. **一批死代码**：`ThinkingCard.tsx`、`DeliverableCard.tsx`、`processCards.tsx` 的 `RouteBanner`/`LoadSkillCard`/`DecompositionCard`/`DispatchCard`/`SelfTaskCard`/`SubagentFactsCard`/`SubagentSettledCard`/`AdvisorNoteCard`/`QuestionCard`/`StatusBadge`、`gateCards.tsx` 的 `AgentRouteBanner`、`conversationModel.ts` 的 `timelineCards`/`TimelineCard`、`useAdvisorConversation.ts` 的 `replayThinking`、`ProcessPanel.tsx` 的 `pane='sidebar'`（`:108-163`）。
9. **缺失 i18n 键**：`chat.timelineKind.*`（`ProcessPanel.tsx:347` 回落英文 kind）、`chat.settledStatus.running`（`:301`）。
10. **测试红线**：`conversationModel.test.ts:136-227` 直接断言 `turns[0].process` 的 kind 序列与 `trajectoryRows` 的 label 序列；挪动可见性会命中，必须**有意识地同步改预期**。
11. **没有 DOM 测试设施**：无 jsdom / happy-dom / `@testing-library/*`；组件层零测试；`npm run test:e2e` 无配置且浏览器未下载。阶段一已补 jsdom + testing-library。
12. **既有失败（已核实，与本轮无关，不要改它）**：`agentscope-harness` 的 `WaitAsyncResultsToolTest.timeoutClampedToMax` 断言 `elapsed < 130_000ms`，而该工具把 `waitForResults(600s)` 夹到 120s 后实测 **148571ms**（隔离复跑，18 个用例真跑）；重负载下同一用例实测 **714109ms**。结论：这是**阈值卡边的计时型既有失败**，与对话页改动无因果关系（阶段一、二只动 `features/chat`、`src/api/advisor.ts`、advisor 后端与 i18n，未触及 `agentscope-core` / `agentscope-harness`）。按 `.ai-spec/frontend/gates.md` 与 `backend/gates.md` 的「禁止弱化断言」，**不放宽它的阈值**。
13. **由此得到的验证口径**：跑 `mvn -T1 -pl technical-advisor-app/backend -am test` 会连带跑 harness 全量（约 2 分钟起，重负载下更久），其中可能出现上面这条既有失败。阶段五判读结果时**必须按用例名分类**：`WaitAsyncResultsToolTest.timeoutClampedToMax` 计入既有失败，其余任何失败按回归处理。

---

## File Structure

**新增**

- `src/features/chat/model/blocks.ts` — 单一「块」模型：把 `advisor_events` + 实时信号折成 `ConversationBlock[]`，主对话与时间线都从它派生，取代 `trajectoryRows` 与 `projectTurns` 双源。
- `src/features/chat/model/toolCall.ts` — 工具调用块的折叠、耗时、失败态。
- `src/features/chat/model/usage.ts` — 唯一用量解析：四格 token、`contextWindow`、`timeSeconds`、首 token、每秒输出。
- `src/features/chat/model/subagent.ts` — 子智能体聚合（派发/事实/落定/自身过程）与耗时。
- `src/features/chat/components/TurnGroup.tsx` — 紧凑回合外壳（收起条、过程区、分隔线、最终正文）。
- `src/features/chat/components/ThinkingLine.tsx` — Think 行（含 anchor 与序号）。
- `src/features/chat/components/ToolLine.tsx` — 工具行（含状态/起止/耗时）。
- `src/features/chat/components/TimelineCard.tsx` — 时间线卡片。
- `src/features/chat/components/SubagentCard.tsx` — 子智能体卡片。
- `src/features/chat/components/SubagentView.tsx` — 只读子会话视图。
- 对应 `*.test.ts` / `*.test.tsx`。

**改造**

- `src/features/chat/aguiClient.ts`、`useAdvisorConversation.ts`、`conversationModel.ts`、`ConversationPanel.tsx`、`ProcessPanel.tsx`、`ComposerDock.tsx`
- `src/api/advisor.ts`（契约）
- `technical-advisor-app/backend/.../session/AdvisorSessionController.java`、`TimelineStore.java`
- `src/i18n/locales/{en,zh-CN}/app.json`

**删除**（阶段四统一处理，删组件同时删对应 i18n 键）

- `ThinkingCard.tsx`、`DeliverableCard.tsx`、`processCards.tsx` 除 `StatusDot`/`ErrorCard` 外的导出、`gateCards.tsx` 的 `AgentRouteBanner`、`conversationModel.ts` 中被取代者。

---

# 阶段一 · 地基（测试设施 + 契约）

**这一阶段做完是什么样**：组件交互可以被测试；新增载荷字段的形状定死，前后端各自实现前不再改。这是唯一没有用户可见产出的阶段——它是必要地基。

**Files:**
- Modify: `technical-advisor-app/frontend/package.json`
- Modify: `src/api/advisor.ts:141-154`
- Test: `src/features/chat/components/__smoke__.test.tsx`

**Interfaces:**
- Produces: `*.test.tsx` 可在 jsdom 下渲染组件；`ToolCallInput.startedAt?`/`status: 'failed'`；`UsageInput.contextWindow?`/`firstTokenMs?`/`outputTokensPerSecond?`；`SubagentRunInput`/`SubagentStepInput`；`PostMessageInput.subagentRuns?`。

- [x] **Step 1: 安装测试依赖**

```bash
cd technical-advisor-app/frontend
npm install --save-dev jsdom @testing-library/react @testing-library/user-event @testing-library/dom
```

若 registry 不可达：**停下并回报**，不要改用「只跑纯函数测试」的降级方案，也不要动现有测试。

- [x] **Step 2: 逐文件启用 jsdom，不新增 vitest 配置**

测试文件首行加 `// @vitest-environment jsdom`，避免引入 `vitest.config.ts` 改变全局环境（现有测试都在 node 环境）。

- [x] **Step 3: 渲染冒烟测试**

`src/features/chat/components/__smoke__.test.tsx`：

```tsx
// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

describe('test environment', () => {
  it('renders into a document', () => {
    render(<p>ok</p>);
    expect(screen.getByText('ok')).toBeTruthy();
  });
});
```

- [x] **Step 4: 定义新增载荷字段（只落类型，不实现行为）**

`src/api/advisor.ts`：

```ts
/** 一次模型调用落定后的用量；窗口与采样缺失时不写，读侧据此不显示，而不是显示 0。 */
export interface UsageInput {
  stepId: string;
  uncachedInput: number;
  cacheRead: number;
  cacheWrite: number;
  output: number;
  projectedTokens: number;
  timeSeconds: number;
  /** 提供方声明的上下文窗口；缺失即不显示上下文环。 */
  contextWindow?: number;
  /** 首个输出 token 的等待毫秒数；没有采样就不写。 */
  firstTokenMs?: number;
  /** 输出速率；采样不足就不写。 */
  outputTokensPerSecond?: number;
}

/** 子智能体自身过程的一个步骤，按发生顺序排列。 */
export interface SubagentStepInput {
  kind: 'thinking' | 'tool_call';
  text?: string;
  toolCallId?: string;
  name?: string;
  args?: unknown;
  result?: unknown;
  status?: 'running' | 'done' | 'failed';
  startedAt?: number;
  endedAt?: number;
}

export interface SubagentRunInput {
  /** 派发时写入的 subtaskId，用于把过程挂回对应派发。 */
  subtaskId: string;
  events: SubagentStepInput[];
}
```

`ToolCallInput` 增 `startedAt?: number`，`status` 联合类型加 `'failed'`；`PostMessageInput` 增 `subagentRuns?: SubagentRunInput[]`。

**实际做法（与上文的一点偏差，已核实）**：`toolCalls`/`usage` 当前声明为 `Record<string, unknown>[]`，而生产者直接传 `event.payload`（`useAdvisorConversation.ts:576-579`）。把它们收紧成具体类型会牵动实时事件载荷的类型链，属于阶段二的活（反正要改那两个生产者）。因此阶段一只导出 `ToolCallInput`/`UsageInput` 供阶段二使用，并在两个数组上留注释指向它们；**收紧动作放在阶段二 Step 1/Step 4**，与补 `startedAt`/`contextWindow` 同一次完成。

- [x] **Step 5: 门禁**

```bash
npm test && npm run build && npm run lint
```

Expected：测试全绿（基线 12 文件 / 89 用例 + 冒烟）；构建成功；lint 0 error（允许既有 warning）。

- [x] **Step 6: 提交（需授权）**

```bash
git add technical-advisor-app/frontend/package.json technical-advisor-app/frontend/package-lock.json technical-advisor-app/frontend/src
git commit -m "test(frontend): add a jsdom setup and define the conversation payload fields"
```

---

# 阶段二 · 记录补全

**这一阶段做完是什么样**：刷新之后能看到**真实的**过程——工具的真实起止与耗时、失败态、只产生思考的回合不再丢失、子智能体自己的思考与工具调用、以及上下文窗口。此时**旧界面已经受益**（工具行、轨迹耗时都变准），但三处视图还没统一。

**为什么在这个位置**：视图是记录的投影。先把记录补全，否则阶段三画出来的是空壳。

**Files:**
- Modify: `src/features/chat/aguiClient.ts:100-117`、`:120-164`
- Modify: `src/features/chat/useAdvisorConversation.ts:337-347`、`:349-386`、`:404-438`、`:548-556`、`:568-582`
- Modify: `src/features/chat/conversationModel.ts:198-209`、`:341-354`
- Modify: `src/features/chat/ConversationPanel.tsx:86-98`、`:258`
- Modify: `technical-advisor-app/backend/.../session/AdvisorSessionController.java:202-205`、`:207-218`
- Modify: `technical-advisor-app/backend/.../session/TimelineStore.java:49-69`、`:167-184`
- Test: `aguiClient.test.ts`、`useAdvisorConversation.test.ts`、`conversationModel.test.ts`

**Interfaces:**
- Consumes: 阶段一的载荷类型。
- Produces: `ToolCall { startedAt: number | null; endedAt: number | null; durationMs: number | null; status: 'running' | 'done' | 'failed' }`（`durationMs` 仅在起止都已知时给出）；`hasRecordableContent(finished): boolean`；唯一的 `usageFrom`（保留 `contextWindow`/`timeSeconds`）；新持久化种类 `subagent_step`；`SubagentRecord.steps`。

## Step 1：工具调用的开始时间

- [ ] 写失败测试（`conversationModel.test.ts`）：

```ts
it('uses the recorded start time instead of the commit time', () => {
  const event = entry(7, 'tool_call', {
    id: 'c1', name: 'demo_lookup', status: 'done', startedAt: 1_000, endedAt: 4_500,
  });
  const call = toolFromEvent(event, 'turn-1');
  expect(call?.startedAt).toBe(1_000);
  expect(call?.durationMs).toBe(3_500);
});

it('falls back to no duration when only the commit time is known', () => {
  const event = entry(8, 'tool_call', { id: 'c2', name: 'x', status: 'done', endedAt: 2_000 });
  expect(toolFromEvent(event, 'turn-1')?.durationMs).toBeNull();
});
```

- [ ] 跑 `npm test -- conversationModel`，Expected：FAIL。
- [ ] 实现：`TOOL_CALL_START` 时把 `Date.now()` 放进信号（首次信号写入，后续不覆盖）；`applyToolSignal` 写进 payload；`toolFromEvent` 读 `payload.startedAt`，缺失则 `durationMs = null`；**不要**再用 `event.createdAt` 顶替起始时间。
- [ ] 跑 `npm test -- conversationModel useAdvisorConversation`，Expected：PASS。

## Step 2：工具失败态

- [ ] 先探测来源：读 `TOOL_CALL_RESULT` 现有字段确认是否带 state/error。若不带，走**应用层注册自定义 converter**（`AgentEventConverterRegistry` 支持 customConverters，`AgentEventConverterRegistry.java:78-83`），把 `ToolResultEndEvent` 的 state 透出成 `TOOL_CALL_RESULT` 的可选字段——**不改扩展源码**。
- [ ] 写失败测试：

```ts
it('marks a tool that ended in error as failed', () => {
  const event = entry(9, 'tool_call', { id: 'c3', name: 'x', status: 'failed', startedAt: 1, endedAt: 2 });
  expect(toolFromEvent(event, 'turn-1')?.status).toBe('failed');
});
```

- [ ] 实现并在 `ToolLine` 用状态文案区分三态（文案键双语同时加）。

## Step 3：没有最终正文的回合也要落库

- [ ] 写失败测试（先抽出判定，才能被测）：

```ts
it('records a round that produced process but no final text', () => {
  expect(hasRecordableContent({
    text: '', reasoning: '先看构建脚本',
    toolCalls: [{ id: 'c1', name: 'read_file', args: { path: 'package.json' }, status: 'done' }],
    usage: [],
  })).toBe(true);
});

it('records a round that produced only usage', () => {
  expect(hasRecordableContent({ text: '', reasoning: '', toolCalls: [], usage: [{ stepId: 's1' }] })).toBe(true);
});

it('skips a round that produced nothing at all', () => {
  expect(hasRecordableContent({ text: '', reasoning: '', toolCalls: [], usage: [] })).toBe(false);
});
```

- [ ] 跑 `npm test -- useAdvisorConversation`，Expected：FAIL。
- [ ] 实现前端：新增 `hasRecordableContent`，落库条件从 `streamingText.trim()` 改为它；正文为空时 `text` 传空串。
- [ ] 实现后端：`AdvisorSessionController` 的校验改为「`text`/`reasoning`/`toolCalls`/`usage` 至少一项非空」；`text` 为空仍写 `advisor_message`；**响应码不变**。

## Step 4：上下文窗口 → 上下文环真正出现

- [ ] **定来源**（见开工前第 2 项确认）：A 应用层——窗口来自已保存的模型端点（`AdvisorSchema.java:152-157`）或 `ModelProbe`/`AdvisorModelController`，随 `usage[i].contextWindow` 提交；B 扩展层——`ModelCallUsageEventConverter.java:54-62` 把 `ChatModelBase.contextWindowSize` 放进 `token_usage`。**不要**接内核 `request/context`（无生产写入方）。
- [ ] 写失败测试：

```ts
it('shows the context ring only when both the projection and the window are known', () => {
  expect(contextOccupancy(1200, 8000)).toEqual({ used: 1200, total: 8000, ratio: 0.15 });
  expect(contextOccupancy(1200, undefined)).toBeNull();
  expect(contextOccupancy(undefined, 8000)).toBeNull();
});
```

- [ ] 若 `contextOccupancy` 已正确，本步改为在 `aguiClient.test.ts` 断言 `token_usage` 携带窗口，并把窗口从信号传到样本、再传到落库载荷。
- [ ] **合并两个 usage 解析器**：删除 `ConversationPanel.usageOf`（`:86-98`），保留 `usageFrom` 为唯一解析器并保留 `timeSeconds`/`contextWindow`；`applyUsageSignal` 的同 step 替换必须**保留**已到达的 `contextWindow`，不得被后到样本清空。
- [ ] 补上下文面板的「系统提示 / 工具 / 消息」三行估算（来源是前端实际发给 `/agui/run` 的内容），面板标「估算」；首 token 与每秒输出仅在 `usage` 有采样时显示，无采样不参与平均。

## Step 5：子智能体自身过程（解析 → 收集 → 落库 → 回放）

- [ ] 写失败测试（解析）：

```ts
it('classifies subagent thinking and tool events instead of dropping them', () => {
  expect(classifyAguiLine(sse('CUSTOM', { name: 'subagent.thinking', value: { text: 't' } })))
    .toEqual({ kind: 'subagent-thinking', text: 't' });
  expect(classifyAguiLine(sse('CUSTOM', { name: 'subagent.tool_call', value: { id: 'c1' } })))
    .toEqual({ kind: 'subagent-tool-call', toolCallId: 'c1' });
});
```

- [ ] 跑 `npm test -- aguiClient`，Expected：FAIL（当前返回 null 被丢弃）。
- [ ] 实现解析与归集：`classifyAguiLine` 增 `subagent.thinking|tool_call|tool_result|text|lifecycle` 分支；按派发写入的 `subtaskId` 归集成有序步骤，随 `advisor_message` 的 POST 一起提交（`subagentRuns`）；`subagent.lifecycle` 的 `AGENT_END` 只用于收尾，不重复计步。
- [ ] 实现落库：`TimelineStore` 增 `SUBAGENT_STEP` 种类；`appendRunArtifacts` 接收 `subagentRuns`，按 `subtaskId` + 递增 `order` 追加，保证回放顺序；**不改 `created_at` 语义**。
- [ ] 写回放测试：

```ts
it('rebuilds one ordered subagent record from the replay', () => {
  const [record] = groupSubagents([
    entry(1, 'dispatch', { subtaskId: 's1', name: 'code-surveyor', spec: { task: '看构建脚本' } }),
    entry(2, 'subagent_step', { subtaskId: 's1', order: 1, kind: 'thinking', text: '先看构建脚本' }),
    entry(3, 'subagent_step', { subtaskId: 's1', order: 2, kind: 'tool_call', toolCallId: 'c9', name: 'read_file', status: 'done' }),
    entry(4, 'subagent_settled', { subagentId: 's1', status: 'done', summary: '结论' }),
  ]);
  expect(record.steps.map((step) => step.kind)).toEqual(['thinking', 'tool_call']);
  expect(record.status).toBe('done');
});

it('ignores steps that point at no dispatch instead of inventing a card', () => {
  expect(groupSubagents([entry(1, 'subagent_step', { subtaskId: 'ghost', order: 1, kind: 'thinking' })])).toHaveLength(0);
});
```

## Step 6：阶段门禁

- [ ] 前端：

```bash
cd technical-advisor-app/frontend && npm test && npm run build && npm run lint
```

- [ ] 后端：

```bash
cd ../../.. && mvn -T1 -pl technical-advisor-app/backend -am test
```

Expected：全部通过；无既有失败之外的新失败。

- [ ] 提交（需授权）：

```bash
git add technical-advisor-app/frontend/src technical-advisor-app/backend
git commit -m "feat(advisor): complete the conversation record the views will project"
```

---

# 阶段三 · 一份块模型，三处视图

**这一阶段做完是什么样**：用户可见的改造完成。主对话、时间线、子智能体三处从同一份「块」渲染，互相对得上；时间线倒序、点卡片能跳到主对话对应位置并高亮；子智能体卡片对齐且点开是同一套只读对话流。

**为什么在这个位置**：数据已在阶段二补齐；此阶段只改投影与渲染，不再动持久化。

**Files:**
- Create: `src/features/chat/model/blocks.ts`、`components/TurnGroup.tsx`、`ThinkingLine.tsx`、`ToolLine.tsx`、`TimelineCard.tsx`、`SubagentCard.tsx`、`SubagentView.tsx` 及对应测试
- Modify: `conversationModel.ts:23-38`、`:219-288`、`:312-377`；`ConversationPanel.tsx:100-154`、`:260-279`、`:281-295`、`:353-377`；`ProcessPanel.tsx:58`、`:68-72`、`:108-163`、`:178-240`、`:242-355`；`useAdvisorConversation.ts:222-254`；`src/i18n/locales/{en,zh-CN}/app.json`

**Interfaces:**
- Consumes: 阶段二的 `ToolCall`、`usageFrom`、`SubagentRecord.steps`。
- Produces: `ConversationBlock { anchor: string; seq: number; turn: number; kind: BlockKind; status: 'running' | 'done' | 'failed'; createdAt: number; summary: string }`；`buildBlocks(events, running): TurnProjection[]`；`timelineCards(turns: TurnProjection[]): TimelineCardView[]`（倒序，只保留主对话真正渲染了目标的块）；`SubagentRecord { subtaskId; name; taskSummary; status; startedAt; endedAt; steps: ConversationBlock[] }`。

## Step 1：块模型与可见性

- [ ] 写失败测试：

```ts
it('keeps dispatch, question and deliverable as visible blocks', () => {
  const kinds = buildBlocks([
    entry(1, 'user_message'), entry(2, 'dispatch'), entry(3, 'question'), entry(4, 'deliverable'),
  ], false).flatMap((turn) => turn.process.map((b) => b.kind));
  expect(kinds).toEqual(['dispatch']);
  // question / deliverable 在外层，不进过程组
});
```

- [ ] 跑 `npm test -- blocks conversationModel`，Expected：FAIL。
- [ ] 实现：按设计 §3/§5 把 `question`、`deliverable` 移出 `HIDDEN_KINDS` 并放到过程组**外**；`load_skill`、`dispatch`、`self_task`、`subagent_settled`、`advisor_note` 进入过程组成为可见块；`route`/`agent_route` 保持隐藏（设计 §3：路由结果留在记录里但不画）。
- [ ] **有意识地**同步 `conversationModel.test.ts:136-227` 中断言 kind 序列与 label 序列的用例；改前确认新预期符合设计 §3 可见性表。

## Step 2：主对话改渲染块模型

- [ ] `ConversationPanel` 的 `renderBlock` 覆盖新块类型；删掉 `EventItem` 里 `return null` 的那批分支；`TurnGroup`/`ThinkingLine`/`ToolLine` 从 `FlowTranscript` 迁出并补 anchor 与序号（序号对用户可见，设计 spec:56）。
- [ ] 给实时思考行与流式正文加稳定 anchor；`ToolLine` 内层不再重复 `data-anchor`；访客块与 outside 块补 `ring` 高亮。
- [ ] 定位改为「按块序号」而不是硬编码 seq 字符串，避免「合成 seq → DB seq」换 id 后静默失败。

## Step 3：时间线改倒序卡片

- [ ] 写失败测试：

```ts
it('lists cards newest first', () => {
  const turns = buildBlocks(
    [entry(1, 'user_message'), entry(2, 'tool_call', { id: 'c1', name: 'read_file' })], false);
  const seqs = timelineCards(turns).map((card) => card.seq);
  expect(seqs).toEqual([...seqs].sort((a, b) => b - a));
});

it('drops cards whose target block is not rendered', () => {
  expect(timelineCards(buildBlocks([entry(1, 'agent_route')], false))).toHaveLength(0);
});

it('carries the block status so a running card can say so itself', () => {
  const turns = buildBlocks([entry(1, 'tool_call', { id: 'c1', name: 'x', status: 'running' })], true);
  expect(timelineCards(turns).map((card) => card.status)).toEqual(['running']);
});
```

- [ ] 跑 `npm test -- blocks TimelineCard`，Expected：FAIL。
- [ ] 实现：`ProcessPanel` 时间线改用 `timelineCards`，渲染为「左图标 + 标题（`t('chat.timelineKind.<kind>')`）+ 一行摘要 + 右侧时间」；**删除三车道色带**（`:192-210`）与 `LANE_COLOR`/`LANE_TEXT`；跟随改**贴顶**（仅当用户正看着最上面时插入新卡，已向下翻则不动）；进行中由卡片自身状态表示。
- [ ] 补 `chat.timelineKind.*` 子树（en/zh 同时）。

## Step 4：子智能体列表与只读详情

- [ ] 写失败测试：

```ts
it('keeps a start and end time for each dispatch', () => {
  const [record] = groupSubagents([dispatch('s1', 1_000), settled('s1', 9_000)]);
  expect(record.startedAt).toBe(1_000);
  expect(record.endedAt).toBe(9_000);
});

it('exposes the subagent own steps in order', () => {
  const [record] = groupSubagents([
    dispatch('s1', 1_000),
    subagentStep('s1', 1, 'thinking', { text: '先看构建脚本' }),
    subagentStep('s1', 2, 'tool_call', { toolCallId: 'c9', name: 'read_file', status: 'done' }),
  ]);
  expect(record.steps.map((step) => step.kind)).toEqual(['thinking', 'tool_call']);
});
```

- [ ] 实现卡片：外壳与时间线卡片一致（同样圆角、内边距、字号、图标行）；内容只有四样——名称、任务一行摘要、状态、耗时。耗时起算点是派发写入时间：进行中每秒更新，已结束固定；不到一分钟显示秒，满一分钟显示分秒；没有派发时间则不显示耗时。补 `chat.settledStatus.running`（en/zh 同时）。
- [ ] 实现只读详情：列表倒序（最新在上）；点卡片打开该子智能体自己的视图，复用 `TurnGroup`/`ThinkingLine`/`ToolLine`；顶部可返回且返回后列表停在原滚动位置；一次性任务只读，不能在里面再发一条；进行中的思考与工具随子事件即时更新；刷新后能回放任务、事实、总结、起止时间与耗时。
- [ ] 删掉 `ProcessPanel` 的死分支 `pane='sidebar'`（`:108-163`）。

## Step 5：阶段门禁（含渲染测试）

- [ ] `cd technical-advisor-app/frontend && npm test && npm run build && npm run lint`
- [ ] 提交（需授权）：

```bash
git add technical-advisor-app/frontend/src
git commit -m "feat(chat): one block model renders the conversation, timeline and subagents"
```

---

# 阶段四 · 输入区与清理

**这一阶段做完是什么样**：输入区对任何权限的用户都说真话；代码里不再有未引用的卡片、横幅与被取代的投影。

**Files:**
- Modify: `ComposerDock.tsx:85-111`、`:169-198`、`:199-225`
- Delete: 见 File Structure 的删除清单
- Modify: `processCards.tsx`、`gateCards.tsx`、`ConversationPanel.tsx`、`conversationModel.ts`、`useAdvisorConversation.ts`、`src/i18n/locales/{en,zh-CN}/app.json`
- Test: 新增 `ComposerDock.test.tsx`

## Step 1：输入区说真话

- [ ] 写失败测试：

```tsx
// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';

it('shows the model actually in use when the user cannot manage settings', () => {
  vi.mocked(can).mockReturnValue(false);
  vi.mocked(useAdvisorModels).mockReturnValue({ data: [], isSuccess: true } as never);
  render(
    <I18nextProvider i18n={testI18n}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ComposerDock
          sessionId="s1" running={false} samples={[]} currentModelName="gpt-4o-mini"
          onSend={() => {}} onStop={() => {}}
        />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  expect(screen.getByText('gpt-4o-mini')).toBeTruthy();
  expect(screen.queryByText(testI18n.t('chat.composer.modelEmpty'))).toBeNull();
});
```

- [ ] 跑 `npm test -- ComposerDock`，Expected：FAIL。
- [ ] 实现：`modelsQuery.enabled` 不再由 `settings.manage` 单独决定；无该权限时显示当前在用的模型名，菜单里说明需去模型设置添加；运行中文本框不可输入、模型位仍可点（现有行为保留）。

## Step 2：删死代码与死文案

- [ ] 删前逐个确认无引用：

```bash
cd technical-advisor-app/frontend
grep -rn "ThinkingCard\|DeliverableCard\|RouteBanner\|AgentRouteBanner\|StatusBadge\|timelineCards\|replayThinking" src | grep -v "\.test\."
```

Expected：除定义处外无命中；有命中说明已被使用，**停下确认**再删。
- [ ] 删除组件并同步删 i18n 键（`chat.modeGeneral`/`chat.modeSpecialist`/`chat.agentRoute.*`/`chat.thinkingActive`/`chat.thinkingDone*` 等），**en 与 zh-CN 同时删**；删前 grep 确认没有别处复用同一键。
- [ ] `npm test && npm run build && npm run lint`，Expected：全绿（`i18n.test.ts` 与 `bodyLocalization.test.tsx` 通过——键集合仍相等、无悬空 `t('…')`）。
- [ ] 提交（需授权）：

```bash
git add technical-advisor-app/frontend/src
git commit -m "chore(chat): remove the dead cards, banners and duplicate projections"
```

---

# 阶段五 · 收口

**这一阶段做完是什么样**：文档与代码一致，前后端与真实验收全过，改动范围自检完毕。

**Files:**
- Modify: `docs/v2/zh/docs/others/technical-advisor-login.md`、`docs/v2/en/docs/others/technical-advisor-login.md`、`technical-advisor-app/frontend/README.md`、本计划（写回实测结果）

- [x] **Step 1: 文档同步（EN/ZH 成对）**：两页 front matter 的 `en_link`/`zh_link` 必须互指且精确匹配；补上会话页行为变化（紧凑回合、Think 行、工具行、时间线倒序与定位、子智能体只读视图、统计条与上下文环的显示条件）。

  **已知既有问题**：这两页**未登记进 `docs/docs.json` 导航**，`docs` 目录的 `npm run check` 现在就会报 `Page missing from navigation`。本轮要么在 `docs.json` 的 en/zh 各补一个导航项，要么不去触发 docs 校验——**二选一需要用户决定**，不要默默跳过。

- [x] **Step 2: 前端全量门禁**：`cd technical-advisor-app/frontend && npm test && npm run build && npm run lint`
- [x] **Step 3: 后端门禁**：`cd ../../.. && mvn -T1 -pl technical-advisor-app/backend -am test`
- [x] **Step 4: 端到端验收（需环境）**：

```bash
mvn -T1 -pl technical-advisor-app/backend -am package
cd technical-advisor-app/acceptance && npm test
```

  需要 `ADVISOR_ADMIN_PASSWORD` 与真实数据库；脚本把 `frontend/package.json` 的 `scripts.build`/`scripts.test` 当真值，**这两个脚本必须继续存在**。

- [x] **Step 5: 自检完整 diff**：

```bash
git status --porcelain && git diff --stat
```

  逐文件确认只含本计划范围内的改动；确认没有 `dist/`、`node_modules/`、`target/` 混入；把实测结果与任何剩余缺口写回本计划。

- [ ] **Step 6: 提交（需授权）**：

```bash
git add docs technical-advisor-app/frontend/README.md docs/superpowers/plans/2026-10-11-conversation-page-rework.md
git commit -m "docs(chat): describe the reworked conversation page"
```

---

## 实测结果（2026-10-12 写回）

**阶段一～四**：全部完成。阶段一~三提交于 `5aa0ed0`，阶段四提交于 `c4ce372`。阶段四对计划做了一处修正：计划设想无权限用户走 `/api/advisor/models`，实测该接口要求设置权限（403），故「当前在用模型名」改由应用层 `AdvisorAguiEventEnricher` 随 `token_usage.value` 透出 `modelName`（与阶段二 `contextWindow` 同通道同模式，载荷只增可选字段），前端经用量链路带到输入区。

**Step 1 文档同步**：两页 front matter 互指精确匹配；`docs.json` 的 en（Reference）与 zh（参考）组各补一个导航项。`docs` 目录 `node scripts/check-docs.mjs` 实测：两页的 `Page missing from navigation` 红已消除；剩余 237 个错误均为既有破损（`/v2/*/service/*` 整树页面缺失与页面内本地链接失效），不在本计划范围。`frontend/README.md` 无过时描述，未改动。

**Step 2 前端门禁**：`npm test` 19 文件 / 171 用例全过；`npm run build` 成功；`npm run lint` 0 error / 25 warnings（持平阶段三新基线）。

**Step 3 后端门禁**：`mvn -T1 -pl technical-advisor-app/backend -am test` BUILD SUCCESS，advisor 后端 96 用例全过（含阶段四新增的 enricher 模型名断言 5 个）。本轮 harness 的 `WaitAsyncResultsToolTest.timeoutClampedToMax` 既有失败未触发（计时型卡边，结果不稳定）；后续轮次若出现，仍按 §现状第 12/13 条计入既有失败。

**Step 4 端到端验收**：`mvn package` 成功；`acceptance` 脚本（真实后端 + 真实模型）4 个用例 **3 过 1 失败**，两次全量复跑失败点相同（稳定失败，非波动）：

- ✔ 工作区创建与复制保留真实独立内容
- ✔ 自由模式每轮路由一次且决策留在时间线
- ✔ 内置智能体的审批对真实普通访客返回 403（该用例中模型成功提出了提案）
- ✖ `F08 approves an edited draft and uses the new subagent on the next turn`，失败于第一段断言 `Real model must propose a new subagent`——模型在**复制出的智能体**上未按提示提出提案。判读：脚本为 HTTP 直调后端，失败环节在模型决策；本计划改动（前端渲染、enricher 向 `token_usage` 透出字段）不在该链路，同一提示词在内置智能体上可成功提案，故非本计划回归。按 README 口径保留真实断言、不做模拟替代。**此为验收缺口，如实记录：验收未全过。**

**e2e 缺口**（按执行口径如实记录）：`npm run test:e2e` 无 Playwright 配置、浏览器未安装，本计划未新建 e2e 套件；关键用户流程由 jsdom 组件交互测试 + 上述真后端验收脚本覆盖。

**Step 5 diff 自检**：全部改动落在计划 Files 清单内（`frontend/src`、i18n 两份 locale、advisor 后端 enricher 及其测试、docs 两页 + `docs.json`、本计划）；无 `dist/`、`node_modules/`、`target/` 混入。

## 自检：设计文档逐条对应

| 设计要求（spec 行） | 落在哪个阶段 |
| --- | --- |
| 三块共用同一份回合记录（§1:19） | 阶段三 Step 1（单一 `buildBlocks`）+ Step 3（时间线接同一份） |
| 时间线倒序、新卡在顶、不拉回位置（§5:72） | 阶段三 Step 3 |
| 一张卡对应主对话一个块（§5:74） | 阶段三 Step 1 + Step 3 |
| 卡片形态：图标/标题/一行摘要/右侧时间（§2:23） | 阶段三 Step 3 |
| 点卡片滚动 + 高亮 1.5 秒；跳不到就不出卡（§5:78） | 阶段三 Step 2 + Step 3 第二条断言 |
| 删掉「正在使用：…」横幅（§3:54、§5:76） | 已事实达成；阶段四 Step 2 清残留代码与文案 |
| 紧凑回合：收起条三项计数、最终正文留组外、无正文不收起（§3:36-43） | 已实现；阶段三 Step 1 纳入块模型 |
| Think 行：收起摘要、流出跟最后一行、展开不内部滚动（§3:45-50） | 已实现；阶段三 Step 2 补 anchor 与序号 |
| 工具行：同一行两态、点开看参数与结果（§3:52） | 已实现；阶段二 Step 1-2 补起止/失败态 |
| 输入区统计条、模型位、上下文环、缓存命中（§4:60-68） | 缓存与统计条已实现；窗口与三行拆分在阶段二 Step 4；模型位在阶段四 Step 1 |
| 子智能体卡片四样 + 耗时规则（§6:84-91） | 阶段三 Step 4 |
| 点开是同一套只读对话流、刷新可回放（§6:93） | 阶段二 Step 5 + 阶段三 Step 4 |
| 记录补：工具起止/失败（§7:99） | 阶段二 Step 1-2 |
| 记录补：思考全文（§7:100） | 已实现；阶段二 Step 3 解掉「无正文不落库」 |
| 记录补：子智能体耗时与有序记录（§7:101） | 阶段三 Step 4（耗时）+ 阶段二 Step 5（有序记录） |
| 记录补：每次模型调用用量与窗口（§7:102） | 阶段二 Step 4 |
| 不嵌入 DeepSeek 客户端、不加轨迹图、子视图只读（§8:108） | 阶段三 Step 3 删色带；Step 4 只读 |
| 会话列表/提案裁决/交付物正文保持现有行为（§8:108） | 不在任何阶段的改动面内 |

## 自检：不改的东西

- 不把 advisor 改成读内核会话日志（需新的跨系统契约，独立立项）。
- 不改 `agentscope-core` 的会话日志、事件编解码与投影；不改 `SessionEventCodec`。
- 不改 `advisor_deliverables` 结构、提案裁决契约、会话列表行为。
- 不改 `frontend/package.json` 的 `build`/`test` 脚本名（验收脚本以它们为真值）。
- 不引入 DeepSeek Harness 客户端、轨迹图或新的状态管理库。
