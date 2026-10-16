---
title: 工作空间四模块缺陷修复计划
description: 基于实际代码与隔离复现，规划智能体、工具与MCP、技能、知识库十项缺陷的串行修复与验收。
---

# 工作空间四模块缺陷修复计划（Implementation Plan）

实施更新：用户已于2026-10-16授权按本计划修复。实际执行记录与结果分别见technical-advisor-app/docs/workspace-remediation-progress-2026-10-16.md、workspace-remediation-results-2026-10-16.md；下方“只交付计划”是原编写阶段状态，不表示当前尚未实施。

> **执行约定**：本轮只交付计划，未开始修复。后续获得用户实施授权后，按本文任务逐项执行；每项先补失败回归，再实现、验证和记录。默认由一个执行者串行写入，同文件不得并行修改；如需智能体审查，只分配只读任务。不得自动提交、推送、部署或重启服务。

**Goal**：修复工作空间四模块已确认的十项缺陷，使权限、文件操作、智能体发布、工具撤销和知识库恢复符合实际业务预期。

**Architecture**：复用现有 AccessGuard、JDBC Store、AgentCreationOperationStore、AgentProvisioner、CatalogExecutionGuard、框架 MCP 工具、PgVectorStore 和 TanStack Query。优先在应用层修复，不新增独立发布平台、权限系统或运行引擎；必要的生命周期字段通过现有 AdvisorSchema 做向后兼容扩展。

**Tech Stack**：Java / Spring Boot / JDBC / PostgreSQL / Reactor / AgentScope；React / TypeScript / TanStack Query / Vitest / i18next。版本以当前 POM 和 package.json 为准。

**Spec**：本文第1—4节定义本批修复的产品与兼容契约，第5—7节给出实施任务、验证与交付条件。原创建流程依据为同目录 2026-10-13-agent-creation-flow.md；本次只修正已确认的业务断点，不把原方案中尚未核实的功能视为已有能力。

**日期与状态**：2026-10-16，待用户审阅；所有新增字段、错误码、辅助类型和测试场景均属于拟实施设计，不代表已经存在。

## Global Constraints

- 规范依据：仓库 AGENTS.md、.ai-spec/change-levels.md、.ai-spec/collaboration.md、.ai-spec/backend/java.md、.ai-spec/frontend/spec.md 及两端 gates.md。
- 本轮交付是文档；后续整批修复按高级改动规划，覆盖权限和运行时语义，必须先经用户评审。
- 业务规则、方案和执行记录放在 docs；不把任务日志写进 .ai-spec。
- 保留混合工作区的其他修改，不 reset、stash、整目录覆盖或格式化无关文件。
- 延续现有路由、资源标识、权限点、分页组件、API工具和中英词典，不新增依赖来替换已有能力。
- 新增前后端契约在本文中明确标为拟新增；实施中如需改变契约，先更新计划，不在代码中另定规则。
- 本轮不启动浏览器。实施时优先自动化；未做的浏览器、真实PostgreSQL和外部MCP验收必须明确列为缺口。
- 不使用真实账号、真实模型、生产数据库或有副作用的外部工具来复现缺陷；测试使用隔离数据、临时目录和受控替身。
- 不以既有测试通过证明新缺陷已关闭，不把“源码修改”“构建通过”“服务更新”“现场验收”混为一种状态。

## 1. 事实基线与问题编号

### 1.1 已核实的现状

本计划编写时 HEAD 为 fa8ba57，工作区仍有列表分页、知识库界面等未提交修改。后续实施前必须重新读取 git status 和任务文件diff，不能仅凭提交号恢复现场。

工作空间菜单中的四个模块为：智能体管理、工具与MCP、技能管理、知识库。上一轮差异审查只确认了文档轮询问题；补查存量业务后形成以下十项，本计划不把存量缺陷归因于当前分页改造。

| 编号 | 对应审查问题 | 优先级 | 已核实证据 |
| --- | --- | --- | --- |
| R01 | 技能名称越界写入与删除 | P1 | 隔离HTTP调用中改名200、发布204，文件写到预期技能目录之外；撤回沿已保存路径递归删除 |
| R02 | MCP确认可更新、删除其他服务或用户的工具 | P1 | 隔离H2与假MCP中，使用自己的服务确认请求删除了另一用户工具 |
| R03 | 模块权限只限制入口，后端操作仍可执行 | P1 | 仅授予聊天权限，skill.manage校验为false，创建技能仍返回201 |
| R04 | 草稿保存期间的新输入被误标已保存 | P1 | 实际React Hook中，保存A期间输入B，最终界面B、写入仅A、未保存标记为false |
| R05 | 发布丢失工具、技能等选择 | P1 | 有效工具和已发布技能进入草稿后，发布成功，正式绑定仍为空 |
| R06 | PostgreSQL知识库重启后检索为空 | P1 | search只读内存stores，缺少句柄直接返回空；句柄只在写入路径创建 |
| R07 | 旧运行中的MCP工具缺少实时撤销检查 | P1 | HTTP调用有目录守卫；MCP直接注册客户端，旧连接由租约继续保留 |
| R08 | 技能ZIP解压无大小和数量限制 | P1 | 按实际读取字节无限累加到内存，无累计、单条或条目数量边界 |
| R09 | 发布成功后草稿仍未关闭，可重复生成正式智能体 | P2 | 隔离流程中发布后草稿仍存在，换请求键再次发布得到不同正式ID |
| R10 | 解析计数和分页内容不同步，文档停止刷新 | P2 | 分页与parsingCount分次查询，前端只用后者决定轮询 |

R01、R02、R03、R04、R05、R09已取得隔离复现；R06、R07、R08、R10为代码路径确认，仍需在实施中固化为可重复的回归测试。此前相关前端148项、后端157项通过，不覆盖这些新场景；这些数字不是本计划的验收结果。

### 1.2 代码定位

以下路径均相对仓库根目录，行号以实施时实际内容为准。

| 范围 | 主要落点 |
| --- | --- |
| 技能 | technical-advisor-app/backend/src/main/java/io/agentscope/advisor/skill/SkillCatalog.java、SkillPackage.java、SkillSync.java、AdvisorSkillController.java |
| MCP确认与撤销 | technical-advisor-app/backend/src/main/java/io/agentscope/advisor/tool/McpUpdateService.java、CatalogExecutionGuard.java、ToolCatalog.java；agent/AgentProvisioner.java |
| 权限 | technical-advisor-app/backend/src/main/java/io/agentscope/advisor/access/AccessGuard.java、AccessPoints.java；四模块Controller及创建、预览入口 |
| 草稿保存与发布 | technical-advisor-app/frontend/src/features/agent/useAgentCreationDraft.ts、AgentCreationPage.tsx；backend对应agent/AgentCreationService.java、AgentCreationDraftStore.java、AgentCreationOperationStore.java、AgentStore.java |
| 知识库 | technical-advisor-app/backend/src/main/java/io/agentscope/advisor/knowledge/PgChunkIndex.java、KnowledgeSearch.java、AdvisorKnowledgeController.java；frontend对应knowledge/components/DocumentManagerTab.tsx、LibraryDetailPage.tsx |

## 2. 修复后的业务契约

### 2.1 权限不是数据范围

先判断用户能否执行这类操作，再判断资源是否属于其可见或可编辑范围。不得把“拥有资源”直接当成“有模块操作权限”，也不得用 account.isAdmin 替代已分配角色的权限结果。

拟采用下表，沿用现有九个权限点，不新增“超级权限”或角色系统：

| 操作 | 模块权限 | 资源范围 |
| --- | --- | --- |
| 智能体管理列表、详情、工作区读取 | agent.view 或 agent.manage | 继续使用当前所有者与管理范围规则 |
| 智能体创建、编辑、删除；草稿写入、生成、预览执行、发布 | agent.manage | 再校验对象归属；只读权限不能写入 |
| 工具与MCP管理、导入、编辑、删除、测试和确认变更 | tool.manage | 再校验工具及服务归属；不可通过自己的服务操作他人工具 |
| 技能管理、导入、生成、发布、撤回、修改、删除 | skill.manage | 再校验技能归属 |
| 知识库列表、详情和检索 | knowledge.view 或 knowledge.manage | 再校验知识库可见范围 |
| 知识库创建、修改、上传、重处理、删除 | knowledge.manage | 再校验编辑范围 |
| 正式智能体的能力绑定修改 | agent.manage | 同时核验绑定目标对该智能体所有者可用；不能据此获得资源管理权 |

兼容例外必须按真实调用方明确处理：

- 聊天选智能体、自动路由继续按 chat.use工作，但只暴露当前可用智能体的必要摘要，不因此开放管理详情、工作区、草稿或修改接口。
- 智能体创建/编辑的工具、技能、知识库选择器允许agent.manage读取其可选资源；该读取不得允许修改资源，返回canEdit必须结合资源模块权限。
- 聊天摘要使用拟新增GET /api/advisor/agents/available，只返回agentId、name、description、origin等聊天现有消费字段，保持数组形状；ConversationPanel改用该入口，管理列表与详情仍执行管理权限。
- 工具、技能、知识库选择器复用各自现有GET列表路由，拟新增purpose=selection参数，仅允许agent.manage读取选择摘要；返回既有摘要数组，canEdit不得因该参数变成true。未传参数继续执行原模块管理/读取权限。
- 选择上下文可携带agentId或draftId，二者互斥；后端先校验对应智能体/草稿的访问与编辑权，再从数据库解析目标所有者，不能信任客户端ownerUserId。新建且尚无草稿时只列调用者自己的可选资源。
- 选择器与管理列表继续使用独立查询key；切换目标智能体时，目标上下文必须进入key，不能把他人的候选资源复用到当前草稿。
- 无已分配角色账号的旧行为继续由AccessGuard.allowed决定；跨所有者范围继续由seesAll决定，二者不可混用。
- 已分配角色后按实际角色执行，包括原管理员账号被收窄权限的情况。权限存储失败时拒绝访问，不回退到管理员身份。
- 缺少模块权限返回403；有模块权限但对象不在可见范围时按现有404隐藏规则处理。

### 2.2 技能文件与压缩包边界

- 新建、上传、更新、发布、撤回和同步均使用同一名称及目录校验规则。
- 名称作为单个目录段处理：拒绝空值、绝对路径、斜杠、反斜杠、点段、控制字符，以及Windows设备名和不合法路径形式；正常中文名称不应因安全修复被无故替换。
- 路径规范化后必须位于当前所有者的受管目录内；已有路径、父目录和目标不得通过符号链接跳出边界。删除前重新核验，不信任历史live_dir。
- 非法名称拒绝保存，不能悄悄转换成另一技能名。历史异常路径先阻止发布、同步、撤回等文件操作，保留数据库证据，禁止自动删除疑似目录。
- ZIP拟设固定初始上限：单条解压内容16 MiB、累计64 MiB、条目总数512、技能定义数64。数值属于本方案设计，不是当前实现事实；压缩上传限制不能替代解压限制。
- 按实际流读取量检查上限，不信任ZIP头部大小；目录、忽略文件等条目也要计数，跳过内容仍受累计读取限制。
- 整包校验通过后才进入创建流程。超过上限或任一资源路径不合法时，本次上传不创建技能、不留下半成品资源。

### 2.3 MCP确认与调用边界

- 确认变更中的每个toolId都必须属于目标serverId，所有者也必须一致，且属于该次可确认变更集合。
- changed项的远端名称必须对应同一个本地工具，不允许用任意名称和任意ID拼接；removed项只能删除已确认移除的本服务工具。
- 所有选择先完整校验，再在同一数据库事务中写入。任意一项非法，整批不更新、不删除、不清空pending记录。
- 远端快照与确认指纹必须一致；数据库中的工具版本也要匹配，避免覆盖刷新后其他人的编辑。
- MCP每次真正发出新调用前重新核验目录状态。已删除、未批准、已禁用或已脱离绑定的工具不得通过旧运行继续执行。
- 不强行终止已经发到远端的调用；撤销约束作用于撤销后开始的新调用。保留已有连接租约和安全退休机制。

### 2.4 草稿、发布与知识库恢复

- 保存只确认本次请求包含的编辑版本。请求期间新增输入必须保持未保存状态，并在后续自动保存或flush中真正提交。
- 同一草稿只对应一次成功激活。不同请求键、连续点击和并发请求都不能创建多个正式智能体。
- 正式智能体在配置、绑定、文件和运行时准备完成之前，对列表、选择器、路由、会话启动和AG-UI执行均不可用。
- 成功后从“未完成草稿”列表移除，仍保留操作记录和必要草稿身份，支持已授权的结果重放；重新创建应显式建立新草稿。
- 发布失败保留可继续编辑的草稿，不能留下可见的半成品智能体。涉及文件、数据库、注册表时采用可恢复阶段和补偿，不宣称跨介质原子事务。
- 重启后的知识库检索必须从已有持久化索引恢复，不要求用户重传或重处理已有文档。
- 文档页中的可见解析项和全库解析数量任一非零，都继续轮询；后端统计不一致时不能永久停在旧状态。

## 3. 拟调整的契约与兼容

本节为设计，实施时同步Java响应、TypeScript类型、词典与测试。除列出的变更外，现有成功响应及资源ID保持兼容。

| 入口 | 拟调整内容 |
| --- | --- |
| 四模块受保护接口 | 统一落实403模块权限拒绝；保持对象不可见的404；完善canEdit计算 |
| GET /api/advisor/agents/available（拟新增） | chat.use授权、按当前可用性及归属返回最小聊天摘要数组；不返回工作区和草稿内容 |
| GET /api/advisor/tools、/skills、/knowledge的purpose=selection（拟新增） | agent.manage授权，按已验证agentId/draftId解析目标所有者；既有摘要数组形状，无资源管理能力 |
| POST /api/advisor/mcp-servers/{serverId}/approve | 非法工具归属或不合法选择返回422；远端指纹或本地版本冲突返回409；不产生部分写入 |
| POST /api/advisor/skills、PUT /api/advisor/skills/{skillId} | 非法名称/资源路径返回400；超出解压配额返回413；沿用现有code/messageKey响应习惯 |
| POST /api/advisor/agent-creation-drafts/{draftId}/activations | 保留现有202和OperationDto；同草稿已成功时返回既有成功操作，发布中返回同一个运行操作；修改已激活草稿返回409 |
| GET /api/advisor/agent-overview及草稿列表 | 默认不再把已激活草稿列为未完成；正式列表排除发布准备中的行 |
| 草稿DTO | 拟增加activationState、activatedAgentId，旧客户端忽略新增字段；页面据此禁用重复发布并跳转既有结果 |
| 知识库documents分页 | 保持现有items/page/size/total/totalPages/parsingCount，无需新增事件协议 |
| MCP运行工具 | 保持工具名、schema、调用ID、原生确认及结果结构；新增应用层执行前守卫，不改变框架公共API |

错误码拟新增：invalid_skill_name、invalid_skill_path、skill_archive_limit_exceeded、invalid_mcp_selection、draft_already_activated、activation_recovery_required。工具侧沿用ToolProblem；其他接口采用项目现有异常或响应方式，不为了统一文案重写整个错误系统。中英提示和调用端处理必须同时交付。

### 3.1 生命周期数据扩展

为避免删除草稿后级联丢失操作记录，R09拟在现有草稿表增加：

- activation_state：默认draft；取值draft、publishing、activated、needs_review。
- activated_agent_id：成功后的正式ID；未成功为空。
- activation_operation_id：当前/最近一次受控发布操作。

R05拟在advisor_agents增加lifecycle，旧行默认active，新发布准备行使用staging。AgentStore用于正式可见性、路由和启动的读取必须排除staging；仅受控发布和恢复路径可读取准备行。

通过AdvisorSchema现有幂等扩展方式实施，配套数据库迁移测试，不新增Flyway或第二套迁移框架。约束至少保证同一草稿不能被并发发布两次；正确性由数据库行锁和状态比较保证，不能只依赖JVM synchronized。

历史数据处理：

1. 无成功激活记录的草稿保留draft。
2. 只有一个有效成功目标的历史草稿关联既有正式ID并标记activated，不重新创建。
3. 多个成功目标、成功记录指向不存在目标等情况标记needs_review，保留全部正式数据，不自动挑选或删除。
4. 旧运行操作若无可靠完成证据，按可恢复异常处理，不能无依据标成成功。

## 4. 文件职责与执行顺序

仅在已有文件职责无法容纳安全边界时新增小型应用辅助类，新增项均为拟新增：

| 职责 | 文件范围 |
| --- | --- |
| 名称、受管路径 | skill/SkillCatalog.java、SkillSync.java、SkillPackage.java；拟新增skill/SkillPaths.java，所有文件操作复用 |
| 模块权限 | access/AccessGuard.java、四模块Controller、agent创建/预览Controller；前端相应能力字段及调用方 |
| MCP选择校验 | tool/McpUpdateService.java、ToolCatalog.java、AdvisorToolController.java |
| MCP调用守卫 | agent/AgentProvisioner.java、tool/CatalogExecutionGuard.java；拟新增tool/GuardedCatalogTool.java，装饰现有AgentTool并委托原结果处理 |
| ZIP配额 | skill/SkillPackage.java、AdvisorSkillController.java；测试覆盖实际流式解压 |
| 草稿保存 | frontend/src/features/agent/useAgentCreationDraft.ts、AgentCreationPage.tsx及既有测试 |
| 发布闭环 | agent/AgentCreationService.java、AgentCreationDraftStore.java、AgentCreationOperationStore.java、AgentStore.java、AgentOverviewStore.java、AgentProvisioner.java；skill、tool、knowledge现有绑定Store；db/AdvisorSchema.java |
| 索引恢复 | knowledge/PgChunkIndex.java、KnowledgeSearch.java；复用已保存embeddingDimensions |
| 轮询 | frontend/src/features/knowledge/components/DocumentManagerTab.tsx、LibraryDetailPage.tsx；knowledge/AdvisorKnowledgeController.java |
| 文档 | docs/v2/en/docs/others/technical-advisor-login.md及对应zh页面；后续结果记录放technical-advisor-app/docs |

推荐串行顺序：R03 → R01 → R02 → R08 → R04 → R09 → R05 → R06 → R07 → R10。

依赖与冲突：

- R03先明确权限，不让后续修复绕过授权；选择器和聊天摘要的兼容回归随R03一起完成。
- R01与R08共享技能上传/解析入口，串行修改；路径校验先于配额后的落盘。
- R02与R07共享工具身份与目录守卫，但确认和执行是不同边界，必须分别验证。
- R09提供同草稿发布唯一性和恢复状态，R05消费这些状态完成全部配置交付；两项需联动验收后再更新运行服务。
- R04的flush保证最新编辑已经保存，R05必须使用该revision对应的数据库快照，不能另取过期前端状态。
- 每项结束先记录RED/GREEN与diff，再进入下一项。未取得实施授权之前不得执行下面的编码步骤。

## 5. 实施任务

### R03：后端模块权限与调用方兼容

**文件**：access/AccessGuard.java；agent/AdvisorAgentController.java、AgentOverviewController.java、AgentCreationController.java、AgentPreviewService.java；skill/AdvisorSkillController.java；tool/AdvisorToolController.java；knowledge/AdvisorKnowledgeController.java；frontend/src/api/agent.ts、api/tool.ts、features/chat/ConversationPanel.tsx、features/agent/components/CreationSettingsSection.tsx、ToolSelectionSection.tsx、SkillSelectionSection.tsx、features/knowledge/KnowledgeSelection.tsx及相关canEdit。

**消费/产出**：消费AccessGuard.allowed和seesAll、现有AccessPoints；产出第2.1节授权矩阵，供所有后续任务复用。

- [ ] 逐路由登记读取、修改、模型调用和外部执行所需权限，包含JSON/上传两套技能创建入口、预览、MCP确认、知识库上传及绑定接口。
- [ ] 补回归：仅chat.use的账号直接创建技能/工具/知识库/智能体被403拒绝；knowledge.view和agent.view只读；角色被收窄的管理员不再靠身份绕过。
- [ ] 补兼容回归：聊天仍可获取必要的可用智能体摘要；agent.manage可读取能力选择器但不能据此编辑技能/工具/知识库；无角色旧账号仍遵守既有兼容策略。
- [ ] 先执行测试取得行为RED，再接入统一权限判断及资源归属检查；canEdit与后端写权限保持一致。
- [ ] 校验被拒请求没有数据库、文件、模型或网络副作用，权限存储失败同样不放行。
- [ ] 以角色矩阵、跨所有者场景和既有控制器测试GREEN作为完成条件。

### R01：技能名称与文件路径

**文件**：SkillCatalog.java、SkillSync.java、SkillPackage.java、AdvisorSkillController.java；拟新增SkillPaths.java和SkillPathsTest.java，扩展AdvisorSkillControllerTest.java、SkillSyncTest.java。

**消费/产出**：消费R03权限；产出受管路径校验，R08和R05的技能落盘必须复用。

- [ ] 补绝对路径、点段、Unix/Windows分隔符、控制字符、符号链接、历史异常live_dir回归；正常中文技能也要通过。
- [ ] 固化此前隔离HTTP复现：合法创建 → 非法改名 → 发布，预期非法改名阶段400，预期根目录之外没有写入。
- [ ] 对新增/改名先校验名称，再更新文件和数据库；发布、撤回、同步、重命名删除旧目录都重新核验实际路径。
- [ ] 历史异常路径拒绝文件操作并给出可理解错误；不得通过“修复”自动删除未知目录。
- [ ] 路径失败时原技能、原发布目录及其他所有者资源保持完整；重复正常发布/撤回仍可用。
- [ ] 行为RED/GREEN及上述兼容用例全部通过后完成。

### R02：MCP确认集合与归属

**文件**：McpUpdateService.java、ToolCatalog.java、AdvisorToolController.java；McpUpdateServiceTest.java、AdvisorToolControllerTest.java。

**消费/产出**：消费R03服务权限、目标服务和已保存diff；产出在事务内验证的确认集合，R07继续使用稳定toolId。

- [ ] 把已复现的“自己的服务删除他人HTTP工具”写为回归；再补changed分支、同用户不同服务、重复ID、名称与ID错配、伪造removed和非法权限值。
- [ ] 读取并校验目标服务、待确认集合、远端指纹和本地revision；必须在事务写入前覆盖全部选择，事务内再次核验归属及版本。
- [ ] 不信任前端提供的name、toolId、fingerprint组合；目标工具必须同时属于本服务、本所有者和本次合法变更。
- [ ] 无效选择422；快照/版本冲突409；一项非法时其余合法项也不得落库，pending保持可重新审查。
- [ ] 确认成功保留toolId、权限及既有绑定，重复或并发确认不生成重复工具；保留初次批准旧请求格式的授权兼容。
- [ ] 两用户、两服务的真实数据库回归GREEN后完成；假MCP不访问外网。

### R08：ZIP流式限额与整包预检

**文件**：SkillPackage.java、AdvisorSkillController.java、SkillPackageTest.java、AdvisorSkillControllerTest.java；技能导入错误提示及双语词典。

**消费/产出**：消费R01受管路径；产出在任何创建写入之前完成的整包校验。

- [ ] 补单条16 MiB、累计64 MiB、512条、64个技能定义的边界与越界测试；小压缩体积但高解压体积也必须被拦截。
- [ ] 补未知/伪造ZIP大小、重复路径、路径穿越、被忽略文件过多、多技能最后一项非法等测试。
- [ ] 以实际读取字节数累计，超限立即停止，不先把全部资源读入内存再检查；不能为了测试真的耗尽内存。
- [ ] 先校验全部定义和资源，再执行创建；失败时不留下部分技能、目录或列表记录。
- [ ] 将配额异常稳定映射到413与双语提示；合法单文件Markdown和现有多技能ZIP仍通过。
- [ ] RED/GREEN和上传HTTP回归通过后完成。

### R04：自动保存与最新编辑版本

**文件**：frontend/src/features/agent/useAgentCreationDraft.ts、useAgentCreationDraft.test.tsx、AgentCreationPage.tsx及AgentManagement.test.tsx。

**消费/产出**：消费既有revision与If-Match；产出能保证最新编辑已持久化的flush结果，R05据此激活对应快照。

- [ ] 用可控Promise复现保存A期间输入B；覆盖首次创建与后续PATCH两条路径。不得只检查界面仍显示B。
- [ ] 为本地编辑维护递增版本；每次保存捕获已发送版本，响应只确认该版本，不清除更晚编辑的dirty状态。
- [ ] 仅保持一个在途保存；需要时串行提交下一版，使用服务器返回的新revision，不另起并发PATCH。
- [ ] flush必须等待并保存调用期间已存在的最新修改，失败返回明确失败；409保留本地输入，不把旧快照交给发布。
- [ ] 补失败重试、卸载后响应、切换草稿、连续输入和点击“创建并开始使用”回归，校验实际请求体及次数。
- [ ] 保存状态、离开提示与数据库内容一致，RED/GREEN后完成。

### R09：同草稿唯一发布与状态恢复

**文件**：AgentCreationDraftStore.java、AgentCreationOperationStore.java、AgentCreationService.java、AgentOverviewStore.java、AgentCreationController.java、AdvisorSchema.java；frontend/src/api/agentCreation.ts、AgentCreationPage.tsx、AgentPage.tsx；相关Store/Controller/激活测试。

**消费/产出**：消费既有draftId、operationId、requestKey；产出第3.1节持久化发布状态，R05使用同一个预分配ID完成准备和提交。

- [ ] 固化隔离复现：成功发布后换请求键再发布，预期返回同一个正式ID，正式智能体只增加一条；草稿不再列为未完成。
- [ ] 扩展幂等字段及迁移测试；保留草稿和操作记录，不直接删草稿触发外键级联删除。
- [ ] 在同一数据库连接和事务中锁定草稿行、检查revision与状态、占用唯一发布操作并保存预分配ID；需要时为现有Store增加连接版重载，不能锁住一条连接却在另一条连接中独立提交占位。不同请求键和不同服务实例也不能各自创建目标。
- [ ] publishing返回现有操作；activated返回既有结果并禁止继续修改、生成、试跑该草稿；失败恢复draft，保留失败证据供重试。
- [ ] 失败后重试沿用该草稿已持久化的预分配ID，先核对残留目标与操作归属再恢复；不能因更换请求键再分配一个正式ID。若历史归属无法判定，返回activation_recovery_required并保留待核对状态。
- [ ] 覆盖进程中断、响应丢失重试、目标不存在、多个历史成功目标和旧数据回填；不自动删除历史重复智能体。
- [ ] 和R05联动验收前不部署该阶段，避免只隐藏草稿但继续丢失配置。

### R05：完整配置发布与半成品隔离

**文件**：AgentCreationService.java、AgentStore.java、AgentProvisioner.java、AdvisorSchema.java；ToolCatalog.java、SkillCatalog.java、KnowledgeCatalog.java的既有绑定方法；AgentCreationActivationAndPreviewTest.java及相关API测试。

**消费/产出**：消费R04已保存revision、R09唯一发布占位、R01技能路径；产出active正式智能体及完整资源绑定。

- [ ] 固化有效工具/已发布技能进入草稿但发布后绑定为空的回归；同时覆盖MCP工具和已经支持的资源类型。
- [ ] 发布前读取一个确定revision的数据库快照，校验资源真实存在、批准/发布状态以及对草稿所有者的可用性。管理员代操作不能把资源所有权换成管理员。
- [ ] 资源字段按实际支持的结构解析，不把任意resources JSON当成可执行文件说明；尚无物化能力的非空类型必须返回明确检查错误，不能成功后忽略。
- [ ] 在现有数据源事务内建立staging正式行及完整绑定；对应连接版Store方法复用已有写入逻辑，禁止各自自动提交造成半批配置。
- [ ] 准备受管文件并调用现有prepare构建运行时；准备行在所有管理列表、聊天选择、自动路由、会话绑定和AG-UI解析路径均不可用。
- [ ] 先发布已准备运行时，再用数据库事务将正式行设为active、草稿设为activated、操作设为succeeded；只有全部完成才向调用方宣告成功。
- [ ] 准备、注册或最终数据库提交失败时补偿本次目标，不影响其他智能体；补偿失败保留可恢复状态，不能声称完全回滚。
- [ ] 崩溃后的staging/运行操作要能按预分配ID恢复或安全清理；启动注册只装载active，不能误把半成品启用。
- [ ] 验收必须读取实际绑定、文件和runtime schema，并真正通过假工具/假技能验证生效；不能只断言操作为succeeded。

### R06：已有PostgreSQL索引恢复

**文件**：knowledge/PgChunkIndex.java、KnowledgeSearch.java、AdvisorConfiguration.java；拟新增PgChunkIndexTest.java，扩展KnowledgeLibraryTest.java。

**消费/产出**：消费KnowledgeCatalog已保存的embeddingDimensions与现有pgvector表；产出进程重建后可检索的索引句柄。

- [ ] 补“写入 → 关闭实例 → 新建实例 → 不重处理直接检索”的真实PostgreSQL+pgvector回归；MemoryChunkIndex不能代替重启验收。
- [ ] 复用现有PgVectorStore，按已保存维度和已校验表名懒打开持久化索引；search和deleteDocument都不能因缓存为空就直接认为没有数据。
- [ ] 打开已有索引不允许清表、重建或改变维度；打开失败返回明确不可用错误，不能静默包装成零结果。
- [ ] 缺少维度、模型维度不兼容、真实零命中、重启后删除文档分别测试；并发首次打开不得重复构造泄漏连接。
- [ ] 检查应用关闭时释放已打开资源，保留现有数据表。若现有SDK构造行为需确认，先读本仓库对应PgVectorStore源码和版本，不凭旧文档猜测。
- [ ] 没有可用隔离pgvector环境时记录阻塞；只通过替身测试不得将R06标为现场验收完成。
- [ ] 集成测试使用专用环境变量ADVISOR_TEST_PGVECTOR_URL、ADVISOR_TEST_PGVECTOR_USER、ADVISOR_TEST_PGVECTOR_PASSWORD指向隔离测试库；测试使用随机命名的schema/库资源并清理，只输出脱敏诊断。禁止默认回退到应用运行数据源；进入该验收门禁而环境缺失时必须明确失败，不能以自动跳过充当通过。

### R07：MCP实际调用前撤销守卫

**文件**：AgentProvisioner.java、CatalogExecutionGuard.java、ToolRuntimeCoordinator.java；拟新增GuardedCatalogTool.java、GuardedCatalogToolTest.java，扩展ToolRuntimeCoordinatorTest.java及相关测试。

**消费/产出**：消费R02稳定toolId、现有AgentTool和连接租约；产出在每次调用订阅时执行的应用层守卫。

- [ ] 持有旧runtime和租约，再删除工具/服务、撤回批准、改deny或解除绑定，随后调用旧工具；预期远端调用计数为0。
- [ ] 用应用层AgentTool装饰器包装现有MCP工具，保留名称、schema、原始结果、取消和连接归属；检查通过才委托原callAsync。checkPermissions必须委托原工具，保留MCP只读标记、ask确认和元信息传播，不能继承默认实现后丢失权限行为。
- [ ] 检查应在Mono订阅边界进行，不能在构建工具时仅执行一次；覆盖延迟订阅和子智能体共享工具路径。
- [ ] 目录查询失败时拒绝新调用；绑定校验需包含agentId与toolId，不只检查工具仍存在。
- [ ] 保留已发起远端调用的原语义和旧连接安全退休，不承诺撤销已经发生的业务副作用。
- [ ] 原生ask确认仍由框架权限机制处理，不能因为套守卫就降级成默认允许；补ask恢复、deny、删除及合法调用回归。
- [ ] 不修改核心公共API；若现有注册扩展不足，先更新本计划再扩大到SDK。守卫与原工具结果转换不得重复实现。

### R10：文档轮询与状态对账

**文件**：frontend/src/features/knowledge/components/DocumentManagerTab.tsx、LibraryDetailPage.tsx、DocumentManagerTab.test.tsx；KnowledgePage.test.tsx；必要时后端AdvisorKnowledgeController.java及分页测试。

**消费/产出**：消费现有parsingCount与items，保留服务端分页；产出不会因统计短暂不一致而永久停止的刷新规则。

- [ ] 补响应items含parsing但parsingCount为0的回归，下一次轮询应取到ready或failed；覆盖第一次请求和轮询中途两种情况。
- [ ] 轮询条件为“当前页有解析中文档，或全库解析数量大于0”；所有条件归零才停止，避免在无任务时持续轮询。
- [ ] 验证筛选结果为空、解析任务位于别页、切换标签、组件卸载、失败重试和库切换；不取全量文档来修复分页。
- [ ] 检索页readyCount/解析摘要也要对账，不让已有可查资料永久锁住测试按钮；必要的一次补拉有次数上限，不能形成无限请求环。
- [ ] 测试同时断言状态变化、请求次数和定时器释放，RED/GREEN后完成。

## 6. 验证、审查与完成条件

### 6.1 单项测试记录

每项必须记录：复现输入、预期行为、实际RED断言、修改文件、GREEN命令、执行结果与未测环境。编译失败、格式错误和缺失测试类都不算行为RED；临时脚本复现必须固化成仓库回归测试。

定向Java命令在仓库根目录执行。每次只执行当前任务所列的对应命令，确认实际测试类与数量；以下新增类只有在实施时创建后才应运行，不能把未找到指定测试当作通过：

| 任务 | 拟执行命令 |
| --- | --- |
| R03 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=AdvisorAgentControllerTest,AgentCreationControllerTest,AdvisorSkillControllerTest,AdvisorToolControllerTest,KnowledgeLibraryTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R01 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=SkillPathsTest,AdvisorSkillControllerTest,SkillSyncTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R02 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=McpUpdateServiceTest,AdvisorToolControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R08 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=SkillPackageTest,AdvisorSkillControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R09/R05 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=AgentCreationDraftStoreTest,AgentCreationActivationAndPreviewTest,AgentOverviewStoreTest,AdvisorAgentControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R06 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=PgChunkIndexTest,KnowledgeLibraryTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |
| R07 | mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest=GuardedCatalogToolTest,ToolRuntimeCoordinatorTest,McpUpdateServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true |

当前JDK25下既有测试使用net.bytebuddy.experimental；如果环境变化，应记录实际JDK与结果，不能把环境参数当成跳过测试的手段。

前端定向命令在technical-advisor-app/frontend执行：

    npm test -- src/features/agent/useAgentCreationDraft.test.tsx
    npm test -- src/features/knowledge/components/DocumentManagerTab.test.tsx src/features/knowledge/KnowledgePage.test.tsx

### 6.2 阶段和最终门禁

| 阶段 | 必须验证 |
| --- | --- |
| 权限与安全：R03/R01/R02/R08 | 角色矩阵、两用户两服务、受管路径、ZIP限额、事务回滚；相关Controller/Store测试 |
| 保存与发布：R04/R09/R05 | React在途保存、最新revision、数据库并发发布、半成品不可见、失败补偿和重启恢复 |
| 运行与知识：R06/R07/R10 | 隔离pgvector重启、旧MCP运行撤销、原生确认、分页解析状态与定时器 |
| 最终 | 前后端完整测试、格式、构建、整仓clean verify、任务diff检查、只读业务审查、双语文档 |

仓库根目录：

    mvn -B -T1 -pl technical-advisor-app/backend -am test -Dnet.bytebuddy.experimental=true
    mvn -B -T1 -pl technical-advisor-app/backend -am spotless:check
    mvn -B -T1 clean verify -Dnet.bytebuddy.experimental=true

前端目录：

    npm test
    npm run lint
    npm run build

不使用skipTests，不禁用规则或放宽断言。整仓失败后必须记录原因；修正后的局部通过不能写成“一次整仓全绿”。本批最终需取得修复后完整clean verify的明确结果。

浏览器验收依据用户偏好按必要性执行：自动化确认数据和权限后，若仍需核验发布交互、未保存提示等真实联动，再执行指定Playwright场景。若未执行，交付列明缺口，不以组件测试替代现场结果。

### 6.3 文档与审查

- 用户可见权限、发布状态、上传限制、MCP撤销和知识库恢复说明同步到docs/v2对应中英页面。
- 只读审查按完整业务链检查：用户操作 → 权限 → 数据/文件 → runtime → 失败与恢复；不只查看当前diff和测试数量。
- 安全项检查不能仅测HTTP状态，还要断言数据库、目录与远端调用没有越权副作用。
- 最终检查任务增量；工作区其他任务的格式或测试失败单独说明，不能擅自整批修复或回滚。

### 6.4 完成定义

- [ ] R01—R10各有可重复的回归与实际RED/GREEN记录。
- [ ] 四模块权限与所有者隔离、只读角色、聊天/创建选择器兼容均通过。
- [ ] 自动保存不丢编辑；发布不丢配置、不重复创建、不暴露半成品。
- [ ] MCP确认不能跨资源操作，旧运行的新调用遵守撤销。
- [ ] 技能文件路径与解压限制受控，失败无越界或半批写入。
- [ ] 知识库重启检索和文档轮询有对应验证；真实环境缺口如实标明。
- [ ] 完整门禁、只读审查、中英文档和最终结果记录齐备。
- [ ] 源码完成、自动化通过、服务更新、现场验收分别列出，未做的不标完成。

## 7. 发布、数据处理与回滚

1. 实施后先确认无真实在途任务，再更新运行服务；本次计划授权不包含重启或部署。
2. 生命周期字段仅增不删，旧正式行默认active；先升级兼容读取和权限检查，再启用新的发布写入，避免旧节点绕过唯一发布约束。发布窗口内不混跑能创建旧式半成品的节点。
3. 上线前只读统计历史异常技能路径、重复激活和孤立staging；输出待处理清单，不自动删用户文件或挑选重复目标。
4. 安全校验失败时先关闭相应写入/执行入口，再定位数据或兼容问题；不能为恢复可用性而回退成越权放行或无界解压。
5. 数据库字段暂留以支持回退读取，不做破坏性删列。旧版本若不认识发布状态，回退前必须禁止新的发布并处理staging，否则不能直接切回。
6. 文件和注册表补偿按预分配ID核对归属；清理仅限本次受管目标，禁止根据未校验路径递归删除。
7. 对历史已越界写入、已重复生成或已产生的远端副作用，不承诺代码修复可以自动撤销；单独核实并经用户确认处理。

## 8. 本次文档交付声明

本文件依据当前代码和前述审查编写。只新增计划文档，不修改业务代码、测试或数据库，不运行真实模型/MCP，不提交、不重启、不部署。

本文提出的迁移、错误码、生命周期、配额及测试均未实现。实施前由用户审阅，后续逐项核对现状后再开始修复。
