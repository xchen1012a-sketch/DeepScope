# 工具与技能斜杠命令实施计划

## 已确认的范围

复用 `advisor_tools`（普通工具与 MCP 工具）、`advisor_skills`（技能）及现有命令接口。资源创建即保存，命令入口只展示当前用户可调用资源；管理员也不能通过管理权限查看或修改他人的工具、MCP 和技能。

资源状态为唯一依据，不在命令表重复保存资源状态。工具须已批准、生命周期 ready、权限 allow/ask、配置有效；MCP 工具还须关联同用户已接入且未禁用的服务器。技能须 published 且正式内容有效。测试通过不作为硬门槛。禁止、撤销批准、取消发布、删除后，列表及发送时检查立即生效。

## 实施步骤

- [x] 收紧 ToolCatalog、SkillCatalog 的列表、分页及详情归属检查；同步 MCP 详情与选择器。
- [x] 新增资源命令适配器，复用现有 CommandDto，稳定编码区分工具/技能及同名资源；现有系统命令优先。
- [x] 命令执行接口按当前用户及实时状态查找，禁用或越权返回 404。资源命令发送时经应用 Middleware 再校验，交给既有模型工具/技能执行链，保留审批和智能体绑定。未绑定到当前智能体返回明确错误，不偷偷修改永久绑定。
- [x] 接入普通/基础会话的统一输入处理；前端每次呼出斜杠重新查询，补普通工具类型文案和错误提示。
- [x] 定向验证：资源状态矩阵、管理员归属隔离、MCP 归属、同名唯一性、状态变化后的拒绝调用、Middleware 的有效转换和绑定检查；现有命令与输入框测试。同步 EN/ZH 文档。

## 契约与边界

继续使用 GET `/api/advisor/commands` 与 POST `/api/advisor/commands/{code}:execute`。响应延续现有结构，增加 targetType `tool`，origin 仍为 `custom`。编码使用类型加资源 ID，名称独立展示，避免同名冲突。执行接口只解析资源；真正执行发生在聊天发送链路，不能将测试接口当正式执行接口。

不修改库模块、数据库业务数据、模型配置或已有资源绑定；不新增数据库表、依赖或第二套输入框。纯应用级改动；可撤回适配器及 Middleware 接线恢复旧行为，已保存工具与技能不受影响。

只做相关定向测试和格式/类型门禁；不启动浏览器、不启停后端、不提交。

## 审查补充

- 聊天候选、路由及运行入口统一要求智能体属于当前用户，关闭管理员间接运行他人私有能力的路径；管理详情可见性仍沿用原管理权限。
- 运行时只挂载智能体同用户的工具；effectiveTools 和 MCP 接入检查排除非 ready、未接入、已禁用或服务归属不匹配的资源，重建不恢复禁用状态。
- 命令 ID 不冲突不等于运行时名称不冲突；同一智能体存在多个同名工具时拒绝资源命令并提示改名或仅保留一个绑定。
- 上述三处均新增回归并先确认失败，再修复；测试只使用本地 H2、测试模型和模拟 MCP，不调用真实业务工具。

## 验证结果

- 后端相关 10 个测试类共 93 项通过，按变更分组执行并只复验失败项或后续修改影响项；非全量测试。覆盖 CommandCatalog、CommandController、ResourceCommandMiddleware、ToolCatalog、AdvisorSkillController、SkillSync、ToolRuntimeCoordinator、McpUpdateService、AdvisorAgentController 和 AgentProvisionerPresentation。
- 前端 ComposerDock 与 SlashCommandMenu 共 48 项通过；构建通过，lint 为 0 error、46 条既有 warning。最终新增的同名错误提示仅为已有分支和双语词条补充。
- 受影响 Java 文件经过 scoped Spotless；`git diff --check` 通过。只读复查确认三处审查缺口关闭，未追加浏览器或服务验证。
- 证据：`/tmp/resource-slash-green2.log`、`/tmp/resource-slash-skill-final.log`、`/tmp/resource-slash-review-green.log`、`/tmp/resource-slash-runtime-final.log`、`/tmp/resource-slash-front-green.log`、`/tmp/resource-slash-front-build.log`、`/tmp/resource-slash-front-lint.log`。最初的失败轮次及原因保留在相应 red 日志与 backend-final 日志中，不作为最终通过证据。
- 未修改运行中数据库或重启用户服务；代码需重新构建并重启后生效。未进行真实模型或远端 MCP 联调。
