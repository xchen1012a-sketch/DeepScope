# 交接文档：工具 / MCP 生命周期 —— 第 1、2 项进展与续接

> 本文档取代 `handoff-2026-10-14.md`（11:51 版本）作为续接依据：那份描述的是第 1 项**开工前**的状态，本文档描述开工**之后**的真实状态，并由接手代理在 2026-10-14 12:55 前后实测复核。
> 唯一技术依据（总方案）：`docs/superpowers/plans/2026-10-13-tool-mcp-lifecycle.md`
> **工作区仍是多批混合未提交状态：未 commit、未 push、未 reset。严禁规整他人改动。**

## 0. 一句话现状

**第 1 项（测试调用面板，检查点 T4）已完成并通过独立复审；第 2 项（重复导入与请求幂等，检查点 T3）完成约九成、聚焦测试全绿，但未收尾即中断；其余 5 项未开工。**

接手代理重跑完整后端门禁：`Tests run: 242, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`；`spotless:check` 131 files clean。

中断原因：上一会话额度耗尽，随后的请求报 401（与代码无关）。**最后一次 GREEN 之后没有任何代码改动**（文件时间核对：最后改动 12:38:14，最后 GREEN 结束 12:38:40），即当前磁盘状态就是“已验证状态”。

## 1. 编号对照（三个体系别串台）

| 上一份交接编号 | 总方案检查点 | 会话内部叫法 | 状态 |
|---|---|---|---|
| 第1项 测试调用面板与停止机制 | T4 | Task 1 | 已完成 + 独立复审 Approved |
| 第2项 重复导入与请求幂等 | T3 | Task 2 | 半成品（未收尾、未复审） |
| 第3项 调用时凭据注入与前端录入 | T2 / T4 | — | 未开工（其认证契约已被第2项部分先行实现，见 §3） |
| 第4项 MCP 刷新差异确认 | T5 | Task 4 | 未开工（brief 已预写） |
| 第5项 删除影响提示与安全清理 | T6 | — | 未开工 |
| 第6项 定向更新与运行租约 | T6 | — | 未开工 |
| 第7项 前端 6 个组件界面 | T7 | — | 未开工 |
| （清单未列出的）迁移 / 集成 / e2e / 文档 | T8 | — | 未开工，勿遗漏 |

## 2. 第 1 项：测试调用面板（T4）—— 已完成

### 交付物（全部未提交）

新增：

- `tool/ToolHttpExecutor.java`（统一 HTTP 执行器）
- `tool/ToolHttpRequestMapper.java`（请求映射）
- `tool/ToolTestService.java`（测试运行、停止、有界结果）
- `tool/CatalogExecutionGuard.java`（真实调用前的前置检查：已删除 / 失效 / 新设 deny）
- `tool/ToolTestRunStore.java`、`tool/BoundedToolOutput.java`、`tool/ToolCredentialRedactor.java`
- 测试：`ToolHttpExecutorTest`、`ToolTestServiceTest`

修改：

- `tool/AdvisorToolController.java`：新增 `POST /api/advisor/tools/{toolId}/test`、`POST /api/advisor/tools/tests/{runId}/stop`（按账号校验、幂等取消）
- `tool/HttpCatalogTool.java`：正式调用改为复用统一执行器（测试与真实调用同一条路径）
- `tool/ToolProblem.java`：增加可选详情（fieldErrors / currentRevision / confirmationId）
- `AdvisorToolControllerTest.java`：解开原 2 个 `@Disabled`（现工具测试目录已无 `@Disabled`）
- `frontend/src/i18n/locales/{en,zh-CN}/app.json`：各 +20 个错误 / 停止说明键

### 关键行为（已实现并有测试覆盖）

- 请求映射覆盖 path / query / header / cookie、数组 style/explode、JSON / form / multipart 文本字段；GET/HEAD/OPTIONS 不写 JSON 正文。
- 执行前校验必填、类型、枚举、范围、长度、数组大小（含嵌套字段）。
- 默认总预算 30s；响应读取 256KiB+1，展示截断到 256KiB 并返回 `truncated`。
- 不自动跟随重定向；逐跳检查目标；跨 origin 不携带认证与自定义请求头。
- 全局线程池并发 4 / 排队 16，满则 429 `execution_busy`；HTTP 测试、HTTP 正式调用、MCP 测试共用。
- 每账号同时最多 1 个活动测试；`runId` 绑定账号 / toolId / revision / 规范化 input / 目标 / 定义；重复 runId 返回同一结果、不重发写请求；摘要不同 409 `run_conflict`；跨实例恢复与 inflight unknown 不重发已实测。
- 凭据脱敏覆盖：原文、解码表示、JSON Unicode 转义、截断前缀（`ToolCredentialRedactor`）；先脱敏再做最终截断。

### 证据

- 当时全量：229/229 通过、0 跳过；spotless 128 files clean。
- 独立复审：`task-1-fix2-review.md` →「本轮范围质量：Approved，新增 Critical/Important：无」。
- 历史日志：`/tmp/advisor-lifecycle-1014/task-1-*.log`。
- 已知保留限制（不算缺口）：MCP SDK transport 的原始读取限额属外部契约，应用层无法收紧，已在报告中明示。

## 3. 第 2 项：重复导入与请求幂等（T3）—— 半成品

### 已实现且被聚焦测试覆盖的行为

- 导入 / 预览委托给新服务 `ToolDefinitionService`；**成功响应仍是工具数组、201**（兼容旧客户端）。
- 预览候选新增：`sourceId`、`operationId`、`definitionHash`、`duplicates:[{toolId,name,revision,sameOperation,canEdit}]`。
- 导入新增：`chosenOperations`（使用预览 operationId）、`duplicateActions: Record<operationId,{action:'create'|'update'|'skip', name?, toolId?, expectedRevision?}>`、`definitionHash?`、Header `Idempotency-Key?`。
- update 必须带 `toolId + expectedRevision`；保留目标原 permission / 原 credential / 绑定；提交的 permission 只对新建设生效。
- 幂等：同账号同 key 同内容重发 → 返回原结果快照（工具后来被改过也返回原快照）；同 key 不同内容 → 409；**无 key 也走同账号锁**，避免同名并发静默覆盖。
- 事务：解析 / 探测在事务外；定义 + 新凭据 + 幂等结果在同一事务；批内失败整批回滚（用真实 H2 CHECK 约束注入验证过）。
- 无效选择在探测前拒绝。
- 兼容裁决（已由上级批准）：`boundToolId` 绑定 MCP 原工具、且提交不同且未占用的本地新名 → 仍按原契约 201 新建别名；原工具保留、新别名独立 ID；相同本地名重导仍需 `duplicateActions`。

### 已建立的契约细节（下一个 AI 必须知道）

- 前端 API 实际入口是 `frontend/src/api/tool.ts`（不是 `advisor.ts`）。
- 第 2 项新增错误码：`duplicate_confirmation_required`、`idempotency_conflict`、`invalid_idempotency_key`、`invalid_selection`、`definition_changed`、`tool_name_conflict`、`tool_edit_forbidden`。
- 幂等表真实 namespace：结果 `import:request:<用户key>`；账号仲裁行 `import:lock`（与第 1 项的 `test:<runId>` 隔离）；`result_json` 存完整 ToolRow 快照；digest 为规范化 body JSON 的 SHA256（数组顺序保留、content 原字符串参与摘要）。
- `configJson` 合并 `source:{id,operationId,kind}`，**不整块覆盖**旧配置（保留既有顶层 `credentialId` / `authType` 等）。
- `source.id` 规范化：HTTP = `http:<scheme>://<lowercase-host>:<effectivePort>`；`operationId` = `METHOD:<raw-path>[?raw-query]`（模板花括号保留）；MCP = `mcp:<serverId>` / `mcp:<boundToolId>`。
- auth：缺省或 `{action:'keep'}` = 保留每目标原 credential，并按**目标 owner** 解密用于探测；显式 Bearer / APIKey / Basic = 替换并生成新引用；管理员跨 owner 替换按目标 owner 加密、每 owner 一份 pending，事务内共同插入。

### 证据

- RED→GREEN 完整链路：`/tmp/advisor-lifecycle-1014/task-2-paused-report.md`（含每条命令退出码与用例数）。
- 最后聚焦 GREEN：`ToolDefinitionServiceTest,AdvisorToolControllerTest` → 31 tests / 0 failures / 0 errors / 0 skipped（12:38:40 结束）。
- 注意：`task-2-green-controller.log` 文件名是 green，内容是**历史失败**记录（22 tests / 1 failure），不要误读。

## 4. 接手代理本次实测（2026-10-14 12:55）

| 命令 | 实测结果 |
|---|---|
| `mvn -T1 -pl technical-advisor-app/backend test` | 242 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS |
| `mvn -T1 -pl technical-advisor-app/backend spotless:check` | 131 files clean，BUILD SUCCESS |
| 前端测试 / lint / build | 未重跑（第 2 项未改前端；基线 314 passed 见上一份 handoff） |
| SQLite / 真实并发 / e2e | 未跑（属后续阶段） |

日志：`/tmp/advisor-lifecycle-1014/next-agent-verify-backend.log`。
含义：第 1 项的完成结论仍然成立，第 2 项的实现没有破坏任何既有测试；但第 2 项自身边界缺口（见 §6）仍未解决。

## 5. 当前工作区文件清单（全部未提交）

- 第 1 项相关（新增）：`CatalogExecutionGuard`、`ToolHttpExecutor`、`ToolHttpRequestMapper`、`ToolTestService`、`ToolTestRunStore`、`BoundedToolOutput`、`ToolCredentialRedactor`（+ 对应测试 `ToolHttpExecutorTest`、`ToolTestServiceTest`）
- 第 2 项相关（新增）：`tool/ToolDefinitionService.java`、`tool/ToolImportRequestStore.java`、`test/.../ToolDefinitionServiceTest.java`
- 第 2 项相关（修改）：`tool/AdvisorToolController.java`、`tool/ToolCatalog.java`、`tool/ToolImport.java`、`test/.../AdvisorToolControllerTest.java`
- 前序会话产物（未跟踪）：`ToolAuth`、`ToolProperties`、`ToolConnectionProbe`、`ToolCredentialStore`、`ToolProblem`
- 工作区其余两批改动（智能体管理 / 斜杠命令 / 会话页；两个 docs 文件）**不属于本范围，不要动**。

## 6. 未完成 / 已知缺口（按建议处理顺序）

第 2 项收尾（必须先做）：

1. **i18n 与类型未补**：7 个新错误码在 `en/zh-CN app.json` 中均为 0 命中（已实测），`frontend/src/api/tool.ts` 未加新字段类型。可参考 `agentCreation.ts` 里已有的 `Idempotency-Key` 写法，不要自创第二套。
2. **疑似竞态（需先写真实 RED 再修）**：同 key 并发时，初次 `requests.find` 无结果 → 另一实例先提交；当前实例在事务外 validate 看到刚提交的 duplicate，可能先报 `duplicate_confirmation_required`，而未重读新落库的幂等快照。
3. **auth 动作边界**：目前只专门识别 `keep`；`clear` 无 type 时自然变 none，但 `clear` 带 type/value 可能误入替换；未知 action 未显式拒绝。完整 keep/replace/clear 契约需补测（第 3 项编辑路径会共用）。
4. **额外边界未实测**：SQLite 真实并发、无 key 并发同名、不同 key / 同 key 不同内容并发。
5. **`source.operationId` 含 raw-query**，需按凭据脱敏要求审查查询串中的认证值；来源文档保存与“来源文档认证提取”未做。
6. **代码健康**：`ToolDefinitionService` 约 520 行未整理（候选 / 选择 / 重复计划逻辑可提为包内 helper，不要膨胀公共接口）；Controller 中疑似不再调用的 `withConfig` helper 未清理。
7. **第 3 项开启时注意**：现有 PATCH 编辑路径需 merge 旧 `configJson`，避免丢本项的 `source` 元数据（本项只正确处理了导入 update）。
8. **语义待确认**：显式 skip 全部候选当前返回 201 空数组（与无解析操作 422 已区分）；未选择 / 空 `chosenOperations` 为 422。
9. **权限范围说明**：管理员跨 owner 编辑与本人导入使用不同账号仲裁行；跨账号同目标更新靠 revision 条件 UPDATE 保证不覆盖，当前未承诺全账号名称唯一。

## 7. 铁律与避坑（沿用原提示词，已更新）

1. 工作区混着三批以上未提交改动：**严禁 commit / push / reset / 规整他人代码**。
2. Java 改完先 `mvn -T1 -pl technical-advisor-app/backend spotless:apply` 再跑门禁；保留 Maven 完整退出码，不要管道到 tail 后再判断。
3. 前端文案与错误码必须 `en` 与 `zh-CN` 同步；前端 TS target 是 ES2020（没有 `.at()`）。
4. 注入 `ObjectMapper` 参数名固定 `advisorObjectMapper`；H2 测试 URL 必须带 `DB_CLOSE_DELAY=-1`。
5. 需要绑定本地端口的测试在沙箱内会失败，原命令用 require_escalated 重跑；不要因此改业务代码。
6. 禁改 `deepscope-core` / `deepscope-harness`；数据库既有表 / 列（`advisor_tool_change_requests`、`advisor_tool_definition_revisions`、`provider_name`、`lifecycle_status`、`pending_json`）保持原样。
7. 测试要用真实行为断言；**跳过的测试就是缺口，必须明说**；中间结论不要报成最终结果；RED 必须是行为失败，不能拿格式失败冒充。

## 8. 续接提示词（可直接复制给下一个 AI）

```text
你是接手「技术顾问 App」工具 / MCP 生命周期的 AI。工作目录 /Users/agent/deepscope-java，业务代码只在 technical-advisor-app/。全程中文汇报，结论先行、说重点。

先读（按顺序）：
1. technical-advisor-app/docs/handoff-2026-10-14-tool-mcp-lifecycle.md（当前真实状态，本文档）
2. /tmp/advisor-lifecycle-1014/task-2-paused-report.md（第 2 项最完整的交接细节）
3. technical-advisor-app/docs/handoff-2026-10-14.md（背景与关键决定）
4. docs/superpowers/plans/2026-10-13-tool-mcp-lifecycle.md（总方案，唯一技术依据）
5. .ai-spec/backend/java.md、.ai-spec/backend/gates.md、.ai-spec/change-levels.md、.ai-spec/collaboration.md

第一件事：验证基线，不要先写代码
mvn -T1 -pl technical-advisor-app/backend test        # 期望 242/242，0 skipped（2026-10-14 实测）
mvn -T1 -pl technical-advisor-app/backend spotless:check

然后只续接第 2 项（T3 重复导入与请求幂等）的收尾，按本文档 §6 的 1→5 顺序：
- 先补 7 个错误码的 EN/ZH 词典和 frontend/src/api/tool.ts 类型；
- 竞态与 auth 边界先写真实 RED，再最小修复；
- 完成后跑完整门禁 + spotless:check，报告写明“跳过的测试 = 缺口”。
第 2 项通过独立复审前，不要开工第 3 项。

纪律：不 commit / push / reset；保留混合 diff；改 Java 先 spotless:apply；i18n 中英同步；ObjectMapper 注入参数名 advisorObjectMapper；H2 加 DB_CLOSE_DELAY=-1；端口测试需要沙箱外权限；禁改 deepscope-core / harness；既有表列保持原样。

汇报格式：改了哪些文件、跑了哪些检查及实际结果、还剩哪些缺口。
```

## 9. 证据与临时文件位置（/tmp 易失，建议先备份关键几份）

`/tmp/advisor-lifecycle-1014/`：

- `progress.md` —— 全程 SDD 账本（含各任务裁决）
- `task-1-report.md`（21KB）、`task-1-fix2-review.md` —— 第 1 项报告与复审结论
- `task-2-paused-report.md`（11KB）—— 第 2 项暂停交接（最完整）
- `task-1-*.log`、`task-2-*.log` —— RED / GREEN / format 原始日志
- `task-1-brief.md`、`task-2-brief.md`、`task-4-brief.md` —— 任务简报；**第 4 项（MCP 刷新差异确认）的 brief 已写好**，含 provider_name / newName 或 409 的裁决
- `next-agent-verify-backend.log` —— 本文档 §4 的实测日志
