# 交接：会话页改造（第四步 · UI）

| 项 | 内容 |
| --- | --- |
| 日期 | 2026-10-12 |
| 范围 | `technical-advisor-app`（前端会话页模块 + advisor 后端的事件记录） |
| 详细计划（权威） | `docs/superpowers/plans/2026-10-11-conversation-page-rework.md` |
| 设计依据（验收标准） | `docs/superpowers/specs/2026-10-11-conversation-stream-design.md`（v1.2） |
| 停在 | 阶段二已提交；阶段三半成品在 `stash@{0}`；阶段四、五未开始 |
| 工作区 | 干净，停在 `997c1ec` |

**一句话现状**：前两步（测试地基与契约、记录补全）已完成并验证提交；第三步（一份块模型、三处视图）动了一半、**未经验证**、已用 stash 保住；第四、五步未开始。

---

## 1. 已经交付的三个提交

| 提交 | 内容 | 验证证据（真跑过） |
| --- | --- | --- |
| `c86a605` | 上一轮：旧会话引擎删除后遗留的失败全部清零（归属读共享、瞬态文件容错、sandbox 降级、AgentSession 四个缺陷、docs 对账） | 全量 `mvn -T1 clean test` 零失败；架构断言绿 |
| `b8f4ba4` | **阶段一**：前端测试地基（jsdom + testing-library，逐文件 `// @vitest-environment jsdom`，未加全局 vitest 配置）+ 对话载荷契约（`UsageInput`/`ToolCallInput`/`SubagentStepInput`/`SubagentRunInput`，`PostMessageInput.subagentRuns`） | 13 文件 / 90 用例、`build`、`lint` 0 error |
| `997c1ec` | **阶段二**：记录补全——工具真实起止与失败态、无正文回合也落库、上下文窗口驱动上下文环、子智能体自身过程按名字挂回派发并按序回放 | 前端 **15 文件 / 139 用例**、`build` 成功、`lint` 0 error（27 个既有 warning 与基线同数）；后端定向 **24 用例**（TimelineStore 6 / ReportActions 5 / AdvisorSessionController 8 / AdvisorAguiEventEnricher 5）全过 |

阶段二的实现细节与偏差都写在计划书的「阶段二」一节里；三处需要知道的取舍：

- 工具失败态用**应用层 `AguiEventEnricher`**（把 state 写进 AG-UI 已有的 `rawEvent` 槽），**没有**用自定义 converter——自定义 converter 会覆盖内置 `ToolResultEventConverter`，而它的结果缓冲逻辑在扩展包内无法复刻。扩展源码零改动。
- 子智能体步骤与派发的关联是**按名字确定性对账**（`AGENT_START` 的 `name` ↔ `dispatch` 的 `name`），同名 FIFO 兜底，无派发则丢弃；**不是**按到达顺序（计划书最初那样写，已被证伪并改进）。
- 上下文面板的「系统提示/工具/消息」三行是**启发式估算**（拿不到系统提示与工具 schema 原文），面板已标「估算」，不与占用百分比强求对齐。`firstTokenMs`/`outputTokensPerSecond` 目前只有前端生产者。

## 2. `stash@{0}` 里的半成品（阶段三）

`git stash list` 第一条：`phase3-wip: block model and three views (unverified, stopped for budget)`。

它已经动过（17 条路径，**全部未验证**：没构建、没跑测试、接线未知）：

- 新建：`components/{ThinkingLine,ToolLine,TurnGroup,TimelineCard,SubagentCard,SubagentView,ProcessCardShell}.tsx`、`model/`
- 删除：`components/FlowTranscript.tsx`
- 修改：`ConversationPanel.tsx`、`ProcessPanel.tsx`、`conversationModel.ts`(+test)、`useAdvisorConversation.ts`(+test)、`i18n/locales/{en,zh-CN}/app.json`

**两条处理路径，选一条：**

- **重做（推荐）**：`git stash drop`，直接按计划书阶段三从干净状态开工。半成品没有验证过，继承它未必比重做便宜，而且它删了 `FlowTranscript` 但接线状态未知。
- **继承**：`git stash pop`，**第一步先跑 `npm run build`** 看破损面，再按计划书阶段三逐步收口。

## 3. 五个阶段总表

| 阶段 | 状态 | 交付物 | 验收标准 | 计划书章节 |
| --- | --- | --- | --- | --- |
| 一 · 地基（测试设施 + 契约） | ✅ 已提交 `b8f4ba4` | 组件可测；载荷字段定死 | `npm test`/`build`/`lint` 全绿；新字段全可选不破坏既有调用点 | 「# 阶段一 · 地基」 |
| 二 · 记录补全 | ✅ 已提交 `997c1ec` | 刷新后能看到真实过程（工具耗时、失败态、无正文回合、子智能体过程、上下文窗口） | 前端 139 用例 + 后端 24 用例全过；老数据缺字段时降级不显示 0 | 「# 阶段二 · 记录补全」 |
| 三 · 一份块模型，三处视图 | ⚠️ 半成品在 `stash@{0}` | 主对话/时间线/子智能体三处从同一份块渲染；时间线倒序可定位；子智能体卡片对齐且点开是同一套只读对话流 | 见计划书阶段三 Step 1-4 的测试；新增组件交互测试（jsdom）；时间线倒序、贴顶、无跳转目标的卡片不出现 | 「# 阶段三 · 一份块模型，三处视图」 |
| 四 · 输入区与清理 | ⬜ 未开始 | 输入区对任何权限都说真话；死代码与死文案清干净 | `modelsQuery.enabled` 不再由 `settings.manage` 单独决定；`grep` 确认无未引用导出；i18n 键双语同时删且集合仍相等 | 「# 阶段四 · 输入区与清理」 |
| 五 · 收口 | ⬜ 未开始 | 文档与代码一致；前后端与真实验收全过 | 前端三门禁 + 后端门禁 + `acceptance/stage4.test.mjs`；`docs.json` 补 en/zh 导航项；完整 diff 自检 | 「# 阶段五 · 收口」 |

## 4. 下一步该怎么做

1. **确认口径**（计划书「执行口径（已定）」一节，不要重新决策）：设计文档即验收标准；上下文窗口取**应用层**（不改 `agentscope-extensions-agui`）；每阶段一个提交；门禁每阶段跑一次。
2. **处理 stash**：按上面第 2 节二选一。
3. **按计划书阶段三开工**：五步（块模型与可见性 → 主对话渲染块 → 时间线倒序卡片 → 子智能体列表与只读详情 → 门禁）。每步先写失败测试再实现；**必须同步修改** `conversationModel.test.ts:136-227` 里断言 kind 序列与 label 序列的用例——那是设计可见性变更的直接后果，不是为了让测试变绿。
4. **阶段门禁**（在 `technical-advisor-app/frontend`）：

   ```bash
   npm test && npm run build && npm run lint
   ```

   Expected：测试全绿、构建成功、**lint 0 error**（warning 允许，且不应比 27 条更多）。
5. **提交阶段三**（授权口径：按阶段提交，提交信息用计划书里给的那句）。
6. **阶段四**：输入区收口 + 删死代码与死文案（删前 grep 确认无引用；i18n 键 en/zh 同时删）。
7. **阶段五**：`docs/v2` EN/ZH 对账（那对页面必须互链精确匹配）+ 在 `docs/docs.json` 的 en/zh 各补一个导航项（消掉既有的 `Page missing from navigation`）+ 前端全量 + 后端门禁 + 真实验收 + 完整 diff 自检。

## 5. 已知坑与既有失败（不要误判成回归）

| 项 | 事实 | 怎么处理 |
| --- | --- | --- |
| `WaitAsyncResultsToolTest.timeoutClampedToMax` | `agentscope-harness` 的计时型既有失败：断言 `elapsed < 130_000ms`，隔离实测 **148571ms**，重负载下 **714109ms** | **既有，与本改造无因果关系**（我们没碰 core/harness）。按规范**不放宽它的阈值**；跑 `-am` 门禁时按用例名分类，只有它计入既有失败 |
| `npm run test:e2e` | 无 Playwright 配置，浏览器未下载 | 本轮**不新建 e2e 套件**（属独立立项）。关键流程靠组件交互测试（jsdom）+ `acceptance/stage4.test.mjs` 覆盖，并在阶段五如实记录这条缺口，**不声称 e2e 通过** |
| `docs/docs.json` | `docs/v2` 那对技术参谋页面未登记进导航，`docs` 的 `npm run check` 现在就会红 | 阶段五补 en/zh 导航项；**不要**默默跳过校验 |
| `npm audit` | 前端装测试依赖后报 10 个既有依赖漏洞 | 不在本轮范围；**不要**跑 `npm audit fix`（会改动依赖树） |
| 提交授权 | `.ai-spec/change-levels.md` §四.8：未经明确授权不 commit、不 push | 用户已授权**按阶段各提交一次**；除此之外不要提交 |

## 6. 不要做的事

- 不改 `agentscope-core`（内核会话日志、`SessionEventCodec`、投影）与 `agentscope-extensions`（尤其 `agentscope-extensions-agui`）。
- 不把 advisor 改成读内核会话日志——那需要新的跨系统契约，属独立立项。
- 不改 `advisor_deliverables` 结构、提案裁决契约、会话列表行为。
- 不改 `frontend/package.json` 的 `build`/`test` 脚本名（`acceptance/stage4.test.mjs` 以它们为真值）。
- 不引入 DeepSeek Harness 客户端、轨迹图或新的状态管理库。
- 不删既有字段、不改既有行的语义（本改造的兼容性结论是「只增不改」，回滚无不可逆点）。

## 7. 验证命令速查

```bash
# 前端门禁（每阶段必跑，目录：technical-advisor-app/frontend）
npm test && npm run build && npm run lint

# 后端（只跑 advisor 相关，避开上面那条既有计时失败）
cd ../../.. && mvn -T1 -pl technical-advisor-app/backend -am test \
  -Dtest='AdvisorSessionControllerTest,AdvisorAguiEventEnricherTest,TimelineStoreTest,ReportActionsTest' \
  -DfailIfNoTests=false

# 后端全量（会连带跑 harness 全量，约 2 分钟起，可能命中上面那条既有失败）
mvn -T1 -pl technical-advisor-app/backend -am test

# 真后端验收（需要数据库与 ADVISOR_ADMIN_PASSWORD，本轮未跑）
mvn -T1 -pl technical-advisor-app/backend -am package
cd technical-advisor-app/acceptance && npm test

# 当前状态回看
git log --oneline -3 && git stash list && git status --porcelain
```
