# 智能体创建流程重构：技术实现方案书

> **执行约定**：本文件为方案交付，尚未开始编码。后续使用 executing-plans 按任务逐项推进，每项遵守 TDD：先确认行为测试失败，再实现，再验证。用户审查本方案后，才能开始实施；未经授权不 commit、不 push。

**Goal**：让新用户通过“说清用途 → 确认创建”得到可用智能体，系统承担保存、生成、检查和启用，失败或离开后能够继续完成。

**Architecture**：在独立 technical-advisor-app 中补充创建草稿与隔离试聊。现有 `advisor_agents`、AgentProvisioner 和正式会话保持运行定义的唯一来源；草稿在启用完成之前不进入正式注册表、会话选择或自动路由。模型、权限、工具和技能能力复用既有实现。

**Tech Stack**：React 18 / TypeScript / TanStack Query / React Router / Tailwind / Radix / i18next；现有 Spring Boot / JDBC / PostgreSQL / HarnessAgent / AG-UI。具体版本以 manifest/POM 为准。

**Spec**：用户已确认并补充“新人一眼看懂、交互尽量简单”的产品流程已整理在本文第 1～4 节；第 5～11 节给出对应技术设计与执行检查点。本文件是本次创建流程的唯一实施方案。

**改动等级**：按跨前后端、内部接口和用户可见流程变化的中级改动执行；不修改框架公共契约。若实现需要修改核心 AG-UI 事件、Harness 公共 API 或公共权限语义，先停止并升级评审。

## 1. 目标、范围与现状

### 1.1 本次交付范围

- 新建、复制和手动配置使用统一创建页。
- 草稿保存、恢复、明确的行为生成状态和失败恢复。
- 工具、技能、知识定义与高级子智能体配置按需展开。
- 基于草稿快照的隔离试聊，沿用现有思考与工具过程展示。
- 启用前检查、可恢复且幂等的启用、成功后开始正式对话。
- 所有界面文案同步 EN/ZH；继续使用当前模型设置和既有权限标识。

本次不引入模板市场、组织分享、正式智能体版本发布平台或新的模型配置系统。已经启用的智能体继续使用现有详情页编辑；本方案仅新增创建闭环。

### 1.2 当前实现的具体断点

| 已核实入口 | 当前行为 | 本次处理 |
| --- | --- | --- |
| `AgentPage.tsx` / `CreateAgentDialog.tsx` | 两步弹窗：空白或复制、名称和简介 | 改为持续可恢复的创建页，复制快捷入口预填 |
| `CreateAgentDialog.submit` | 创建成功回调先跳转，再生成行为，最后再次触发完成回调 | 创建、生成、启用职责拆开；导航只由一次明确成功结果驱动 |
| `BehaviorDraftController` | 生成后直接覆盖 `AGENTS.md` | 新创建页使用“生成候选 → 明确应用”流程，旧正式详情页接口保持兼容 |
| `AgentProvisioner.run/register` | 启动注册所有正式行；创建后立即注册 | 草稿另存，不调用正式 register；仅启用流程注册正式定义 |
| `AgentWorkspace.sections` | 工具展示来自已注册 runtime | 草稿能力列表来自保存的选择与既有目录，不为显示详情提前注册 |
| `BehaviorSection` | 进入详情后编辑行为、保存影响智能体已有会话 | 草稿独立编辑和试聊，不能影响正式会话 |

### 1.3 参考依据

GPT Builder 的描述/配置、预览与草稿保存，以及 Copilot Studio 的描述用途、完善配置、测试后发布，作为流程参考；下面的“启用”适配当前应用，并非引入对外发布平台。

- [OpenAI：Creating and editing GPTs](https://help.openai.com/en/articles/8554397-creating-and-editing-gpts)
- [Microsoft：Create and deploy an agent](https://learn.microsoft.com/en-us/microsoft-copilot-studio/fundamentals-get-started)

## 2. 用户路径与状态

### 2.1 新用户只看见两个动作

```text
点击创建智能体 → 告诉它要帮你做什么 → 查看并修改生成的工作要求 → 创建并开始使用
                                          └→ 试一试（可选）
```

**核心交互原则**：一个什么都不知道的人，应能从标题、例子和唯一主按钮看出“这里能创建一个帮我做事的助手”。系统流程可以完整，用户操作必须简洁。

- 首屏只展示一句用途输入、几个可点击的例子和“帮我生成”主按钮。名称、简介、模型、权限、创建方式选择不占首屏。
- “复制现有智能体”“自己填写”放在“其他创建方式”；卡片上的复制入口仍直接预填，熟练用户不必绕行。
- 默认沿用当前模型和个人可见范围，不要求新人先理解模型、MCP、技能、工作区或发布等概念。
- 生成后默认展示名称和可编辑的“工作要求”，工具、技能、知识定义及子智能体集中在默认收起的“更多设置”。系统不会因为生成的文字提到了某工具就自动授权或启用它。
- “试一试”为可选入口，点击后才打开右侧面板；无需试聊也可创建。点击“创建并开始使用”统一完成当前方案确认、保存、检查、启用，再新建正式会话，不额外跳一个确认步骤。
- 首次生成内容展示在预览编辑区，不静默覆盖已保存行为。点击“试一试”或“创建并开始使用”即明确接受当前可见方案，系统串行应用/保存后继续；实现无需额外暴露“应用候选”按钮。重新生成涉及已修改内容时，提示“使用新内容 / 保留当前内容”。
- 初次结果区尚有未应用候选时，用户开始编辑结果、点击“试一试”或点击“创建并开始使用”，均作为明确采用当前方案的操作。先按baseRevision应用一次候选，再串行保存用户修改，不能先保存名称导致随后应用候选版本冲突。候选过期则保留输入并提示重新整理，不静默覆盖。
- 用户主动编辑的内容按草稿自动保存规则处理；返回与恢复时读取已保存内容及最新生成候选，保留未应用结果的含义，不能将候选与正式已生效配置混淆。
- 不展示三步进度条；用途、方案、试聊、检查和启用仍是内部状态，以页面实际内容变化体现。
- 原始名称上限120字符、简介400字符、用途4,000字符，继续前后端一致校验；同名允许，用户可改建议名称。
- 草稿在首次点击“帮我生成”、复制确认或手动保存时建立；打开空页面不生成空草稿。生成失败时用途已保存，用户可重试或改为自己填写。
- 复制仍要求主动选来源；定义独立复制，不复制会话、记忆、状态目录或账号凭据。切换方式、来源或重新生成不能静默清空输入。

### 2.1.1 面向新人的文案规则

| 技术/旧文案 | 用户看到的文案 |
| --- | --- |
| 定义用途 | 你想让它帮你做什么？ |
| 行为说明 | 工作要求 |
| 应用候选 | 由“试一试 / 创建并开始使用”承接，不增加必点按钮 |
| 行为生成失败 | 没有生成成功，你的内容已保存 |
| 隔离预览 / 配置版本 | 试一试；只有配置已改变时提示“这是修改前的测试结果” |
| 启用智能体 | 创建并开始使用 |
| DRAFT / 尚未启用 | 未完成 · 继续编辑 |
| 创建检查失败 | 还有一项需要处理，完成后就能使用 |

底层名称、状态枚举和协议字段保留技术精度；默认页面使用通俗文案。高级设置中专业名词需要一行用途解释，不能只有图标或缩写。

### 2.2 两种生命周期，操作状态独立

| 生命周期 | 列表行为 | 正式运行 |
| --- | --- | --- |
| DRAFT / 草稿 | 当前创建者及既有管理权限者可查看、继续编辑或删除 | 不可选、不参与路由、不注册 |
| ACTIVE / 可用 | 只显示一个正式智能体卡片，草稿记录作为创建结果关联保留 | 复用现有注册、会话和权限行为 |

保存的 `dirty/saving/saved/error`、生成的 `idle/running/succeeded/failed`、启用的 `idle/running/succeeded/failed` 是操作状态，不增加五六种列表生命周期。

生成、试聊和启用都有各自结果。试聊失败不等于草稿保存失败；草稿保存成功不等于已启用。

### 2.3 完成与离开

- 自动保存成功显示“已保存”；尚未确认成功时显示“有未保存修改”，不得用乐观文案伪装成功。
- 未完成的智能体从列表“继续编辑”恢复；试聊记录与正式会话分开。
- 有未保存内容时离开：提供“保存后离开 / 继续编辑 / 放弃本次未保存修改”。草稿删除是独立操作，需要确认。
- 生成中可以离开，服务端操作继续到超时或终态；也可明确点击“停止生成”。重新进入读取真实状态。
- 创建并启用成功后直接新建锁定该智能体的正式会话。若用户暂不开始对话，可返回列表；如果会话创建失败，展示“智能体已创建，暂时没能打开对话”，只重试打开对话，不重做启用。

## 3. 前端 Markdown 草图：先看懂，再逐步展开

草图框线表示布局区域，实际采用紫色、白色和留白。默认页面只突出一个主操作；专业配置按需展开，示例属于可点击填入用途的快捷操作。所有固定文案同步EN/ZH。

### 3.1 列表入口

```text
智能体                                      [ 创建智能体 ]
创建一个能帮你处理固定任务的助手。

代码审查助手                         报告助手
帮你检查代码中的问题                 帮你整理资料并生成报告
[ 开始对话 ] [更多]                   未完成 · [继续编辑]
```

复制、编辑、删除放在“更多”，复制可直接预填来源；未完成项不出现正式对话按钮。

### 3.2 首屏：只问一件事

```text
← 返回                       创建智能体

你想让它帮你做什么？
告诉它要做的任务，系统会帮你整理工作要求。

┌─────────────────────────────────────────────────────┐
│ 例如：帮我整理会议纪要，列出结论、待办和负责人。      │
└─────────────────────────────────────────────────────┘

试试这些： [整理会议纪要] [检查代码] [整理资料]

                                     [ 帮我生成 ]

其他创建方式：复制现有智能体 · 自己填写
```

用途有内容时才可生成，缺内容在输入区提示。点击后同一页面显示“正在整理工作要求，你的内容已保存”，提供“停止”；不跳走、不要求先选模型或填写名称。

### 3.3 生成后：看结果，直接创建

```text
← 返回                       创建智能体          已保存

名称      [ 会议纪要助手                              ]

它会这样帮你工作                         [修改任务描述]
┌─────────────────────────────────────────────────────┐
│ 整理会议内容，提取结论、待办事项、负责人和截止时间。  │
│ 未明确的负责人或时间标为“待确认”，不自行编造。        │
└─────────────────────────────────────────────────────┘

[ 更多设置 ]                          [ 先试一试 ]

                                [ 创建并开始使用 ]
```

名称和工作要求直接可改；简介由系统建议，可在更多设置中调整。默认不展示工具/技能数量、配置版本、模型标识或权限概念。复制与手动方式完成基础输入后也落在这张结果页。

### 3.4 可选试聊与更多设置

```text
创建智能体                                      已保存

┌──────────────────────────────┬──────────────────────────┐
│ 会议纪要助手                 │ 试一试             [关闭]│
│ 工作要求                     │ 看看它是否符合你的要求。 │
│ 整理结论和待办…              │                          │
│                              │ [帮我整理这段会议记录]   │
│ 更多设置（按需展开）         │                          │
│ 添加资料：让它参考你的文件   │ 思考摘要 / 具体操作      │
│ 使用工具：让它执行额外操作   │ 回复内容…                │
│ 技能：复用已有的工作方法     │                          │
│ 其他高级设置                 │ [输入测试内容          ] │
│                              │ [清空]           [发送]  │
└──────────────────────────────┴──────────────────────────┘

                                [ 创建并开始使用 ]
```

- 默认关闭试聊与更多设置；点击对应入口才打开，不增加必经步骤。
- 窄屏打开试聊时切换同一页面的视图，关闭后回到原编辑内容，不丢状态。
- 上传资料或连接工具失败，错误留在对应位置。纯对话配置不显示“未添加知识/技能”等无意义缺项。
- 配置改变后才提示“这是修改前的测试结果，重新试一次”，平时不露出revision。
- 实际写入、执行命令等操作仍遵守原方案权限和确认约束，简单交互不能隐藏真实影响。

### 3.5 创建过程、成功与错误

```text
正在创建智能体…
正在检查你填写的内容。
```

随后状态随真实阶段更新为“正在准备使用”。完成后直接进入正式对话，标题是新智能体名称。无需用户再点击一次“启用”，也不用必须经过独立成功页。

```text
没有生成成功
你的任务描述已保存，可以再试一次，也可以自己填写。
[ 再试一次 ]  [ 自己填写 ]

这份资料还没处理好
[ 重试 ]  [ 移除资料 ]

暂时没能保存
修改还保留在这里，请重试后再创建。
[ 重试保存 ]

智能体已创建，暂时没能打开对话
[ 打开对话 ]  [ 返回智能体列表 ]
```

错误固定讲清“发生什么、内容是否保留、接下来点什么”；不暴露operationId、HTTP码、runtime或服务器异常。技术信息仅供日志与必要的故障详情使用。

## 4. 产品异常契约

| 异常 | 系统行为 | 恢复动作 |
| --- | --- | --- |
| 名称、用途或行为缺失 | 字段旁提示；提交后聚焦错误摘要，关联到具体字段 | 补齐后继续，保留其他输入 |
| 同名 | 提示同名，不将显示名称作为唯一标识 | 改名或继续；复制建议可区分名称 |
| 生成超时、空响应、模型不可用 | 当前内容不变，草稿保留，生成任务记录终态与原因 | 重试同一草稿或手动填写 |
| 旧生成结果晚到 | 仅作为旧版本候选保留，不自动写入当前配置 | 明确应用前校验版本；过期时重新生成 |
| 来源删除或权限丢失 | 复制前检查；失败不产生可运行的半成品 | 换来源或手动填写，保留用途/名称 |
| 文件处理、工具连接失败 | 能力行显示具体原因；已选择且无效的能力阻止启用 | 重试、重新连接或明确取消该选择 |
| 无可用模型 | 可保存和编辑草稿，生成/试聊/启用显示模型不可用 | 按权限跳转模型设置或联系管理员 |
| 试聊失败/取消 | 已确认的试聊内容保留，当前轮标记失败/中断 | 修改配置后新测试，或同一配置重试 |
| 保存失败/多窗口冲突 | 不覆盖较新草稿；未保存本地编辑保留 | 查看服务器版本并重新应用编辑 |
| 启用失败/响应超时 | 草稿保留；先查真实启用结果，不重复创建 | 查询原操作并继续或重试 |
| 双击启用 | 同一操作合并，一份正式智能体 | 返回同一正式 agentId |
| 登录失效 | 停止写入，提示重新登录 | 同一账号登录后恢复已保存草稿；未保存内容只留当前页面内存，不跨账号展示 |
| 权限被收回 | 读不可见对象返回404，已可见但不可编辑返回403 | 停止保存和启用，保留当前页面待处理输入；恢复权限后继续 |
| 创建后首次对话失败 | 智能体仍可用，不回滚为草稿 | 重试新建会话，避免重复建智能体 |

## 5. 技术架构与数据边界

### 5.1 选择单独草稿实体的理由

新增 `advisor_agent_creation_drafts` 保存创建进度；不把未完成草稿写进 `advisor_agents`。现有 provisioner 启动扫描、会话路由和正式权限查询因此不会误注册草稿，旧程序回退时也不会把草稿当正式智能体。

草稿只承载创建过程。启用完成后，正式行为和运行配置仍由 `AgentStore + AgentWorkspace + ToolCatalog/SkillCatalog` 管理，不形成第二套正式智能体配置来源。

### 5.2 持久化对象

| 对象 | 主要信息 | 约束 |
| --- | --- | --- |
| 创建草稿 | draftId、ownerUserId、创建方式、sourceAgentId、用途、名称、简介、behavior、工具/MCP选择、技能选择、知识/子智能体定义引用、revision、创建/更新时间 | 按现有归属与管理权限读写；JSON快照用现有 Jackson 序列化为 TEXT，兼容现有 H2 测试 |
| 生成操作（创建操作表） | operationId、requestKey、baseRevision、状态、候选内容、失败码、起止时间 | 每个草稿最多一个运行任务；候选不会直接覆盖已保存配置 |
| 启用操作（创建操作表） | operationId、requestKey、activationRevision、预分配 agentId、阶段、终态、失败码 | 单个草稿最多一个正式结果；agentId在首次启用前分配并持久化，重试复用 |
| 试聊会话 | previewId、ownerUserId、draftId、draftRevision、配置快照引用、消息/过程记录、状态、创建/活动时间 | 单独 `advisor_agent_preview_sessions`，不写正式 SessionStore、TimelineStore 与正式记忆 |
| 草稿资源 | draft根目录下的定义文件和知识文件；快照引用保存规范路径、内容摘要和资源状态 | 不接收客户端绝对路径；沿用 AgentWorkspace 路径/符号链接/格式校验，凭据仅引用现有连接 |

基础配置检查验证模型接入配置是否存在且可使用、既有能力目录和资源状态是否有效，不额外调用模型或执行工具证明可用。连接或生成过程中真实失败仍走对应恢复路径，界面不承诺服务未来持续在线。

生成/启用操作存入专用 `advisor_agent_creation_operations` 表，保存完整操作身份、请求摘要与终态；唯一键为 `(draft_id, operation_type, request_key)`，同键不同请求摘要返回409。不能只在草稿中保存“最后一次任务”，否则较早请求重发时无法保证幂等。草稿Store同时管理这些创建操作；不增加通用任务平台、消息中间件或额外依赖。

草稿记录用 revision 做条件更新。资源先写入带唯一内容标识的暂存文件，再在数据库提交引用；数据库快照是已保存内容的权威来源。未被引用的暂存文件可清理，避免“文件已改而版本没更新”。启用和试聊均从固定快照物化目录，不读正在修改的文件。

### 5.3 共享数据类型与类职责

- `AgentCreationDraft`：上述草稿快照、revision、资源问题和当前操作身份；请求/响应使用显式DTO，不使用任意Map接收完整控制字段。
- `CreationOperation`：operationId、type、baseRevision、requestKey摘要、state、stage、候选或agentId、errorCode和时间。state固定为queued/running/succeeded/failed/interrupted；只有生成候选可成功但尚未应用。
- `CreationCheckIssue`：code、severity=error/warning、section、messageKey、可选resourceId；只有error阻止启用，未试聊只产生warning。
- `AgentPreviewSession`：previewId、draftId、draftRevision、owner、模型调用记录和运行历史。服务端校验所有关联，DTO不暴露文件系统目录。

这些应用级类型由相应Controller/Store共享；前端API定义保持字段和枚举一致。

包路径均相对 `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/`。

| 新增/复用 | 文件或类 | 职责 |
| --- | --- | --- |
| 新增 | agent/AgentCreationDraftStore.java | 草稿快照、revision、生成/启用结果和幂等归属 |
| 新增 | agent/AgentCreationController.java | 草稿CRUD、生成候选、检查、启用接口；统一当前账号与可见性校验 |
| 新增 | agent/AgentCreationService.java | 复制、检查、候选应用、启用协调，控制跨DB/文件/注册表失败恢复 |
| 新增 | agent/AgentPreviewStore.java | 隔离试聊元数据、历史与快照关联 |
| 新增 | agent/AgentPreviewController.java | 试聊创建、读取、SSE运行、停止/清空；服务端检查owner和draft关系 |
| 新增 | agent/AgentPreviewService.java | 隔离runtime、当前模型调用、工具权限、取消与清理 |
| 修改 | db/AdvisorSchema.java | 幂等新增三个应用表；保留现有表和正式数据 |
| 修改/复用 | agent/BehaviorDraftService.java | 以用途和明确可用能力生成候选；保留正式详情页调用兼容性 |
| 修改/复用 | agent/AgentProvisioner.java | 提取当前构建过程为应用内部共享构建函数；正式注册与preview构建各自控制副作用 |
| 修改/复用 | agent/AgentWorkspace.java | 从合法定义快照物化工作区，复用路径与格式校验 |
| 修改/复用 | agent/AgentStore.java | 支持启用使用预分配ID创建正式行；旧create接口行为保留 |
| 复用 | tool/ToolCatalog.java、tool/ToolPermissions.java | 目录、选择、连接及权限检查；不以草稿生成结果自动授予权限 |
| 复用 | skill/SkillCatalog.java、skill/SkillSync.java | 选择可访问的现有技能并物化；复制定义与复制授权分开 |
| 复用 | model/UsageGuard、Model bean、SelectedModelMiddleware | 沿用当前保存的模型接入及实际上下文，不固定智谱/DeepSeek型号 |

正式 `AdvisorAgentController` 的创建/编辑/路由兼容保留；管理页同时读取正式列表与草稿列表。现有会话选择器仍只请求正式列表。

### 5.4 保存与生成时序

```text
用途填写 → 创建草稿 → 获得 draftId/revision → 路由切到可恢复地址
                         ↓
                    编辑并自动保存 → 服务端revision更新 → 确认“已保存”
                         ↓
                    提交生成操作（固定revision与requestKey）
                         ↓
                    生成候选 / 记录失败 → 前端读取终态
                         ↓
                    用户应用候选 → 检查baseRevision → 保存新revision
```

- 自动保存默认停输入800ms后发起；同一草稿保存串行，后续编辑合并到最新待保存快照。
- 浏览器返回的旧响应不能覆盖新本地输入；查询数据不得无条件重置 dirty 编辑区。
- 确认离开、试聊和启用前立即 flush 待保存内容；flush失败就停在当前页。
- 首次建立草稿用请求幂等键；同账号同键同内容返回同一 draftId；同键不同内容返回409。首次创建的requestKey与请求摘要也持久化到草稿表，并设置账号内唯一约束。
- 生成在有界执行器中运行，2个worker、队列32；满时返回429，保持当前草稿。沿用30秒模型生成超时。
- 前端仅任务运行且页面可见时每2秒读状态，终态/隐藏/卸载停止轮询；恢复页面立即重新读。当前部署按单服务实例设计；服务端重启将未完成生成标记为中断，可重试，不伪装完成。取消任务必须取消模型订阅并写入中断终态，不能只隐藏前端加载状态。生成空响应或非法候选字段归为生成失败，名称/简介遵守长度限制，候选不能自动开启模型建议的工具。
- 重试生成创建新operationId；同一requestKey重发返回旧操作，避免响应丢失后重复付费调用。

### 5.5 启用时序与失败恢复

```text
固定草稿revision → 校验 → 持久化启用操作与预分配agentId
   → 物化只读配置快照到正式工作目录
   → 保存既有能力选择并构建runtime（尚未对正式调用开放）
   → 单次发布：正式行写入 + 注册确认 + 创建结果关联
   → 返回正式agentId，草稿变为ACTIVE创建结果
```

数据库与注册表/文件没有跨资源事务，不能声称一个SQL事务包住整个过程。采用应用级可恢复操作：

1. 先持久化操作身份和阶段；对同一草稿取得数据库行锁/条件更新排他权，长时间模型或网络调用不持有数据库事务。
2. 配置准备阶段只操作预分配agentId的独立暂存目录；目录与能力选择可通过同一快照重复重建。
3. 正式发布阶段将暂存目录原子切换到目标目录；由创建操作表的预分配agentId关联正式行，处于尚未完成启用的关联对象不进入 route、AG-UI resolver 和创建锁定会话入口。
4. register成功后，数据库事务将操作记为成功并解除上述运行闸门。管理正式列表只展示完成创建的结果。旧接口创建的行没有创建操作关联，按现有行为可用；不得误拒绝旧智能体。
5. register失败注销runtime、撤回未完成正式行/能力绑定，草稿保留；清理失败保留失败阶段并在重试/启动时修复。
6. 进程在发布中重启：按持久化阶段修复相同agentId；成功结果只确认，不重复创建；残缺对象保持不可运行直到补偿完成。
7. 旧程序回退前先暂停创建操作并清理“尚未完成启用”的正式残留行，再启动旧程序，避免旧路由跳过新运行闸门。

开始启用前要求当前revision已保存、生成操作已结束或已明确取消，并关闭活动试聊；这些条件通过才锁定启用快照。启用中禁止修改当前草稿快照。启用失败解除编辑锁；失败后修改配置，下一次启用使用新revision。业务只产生一个成功正式结果。

## 6. 前后端接口契约（拟新增，实施前以本文为准）

统一前缀：`/api/advisor/agent-creation-drafts`。复用现有登录过滤器覆盖的 `/api/advisor`，不新增匿名入口。

| 方法与路径 | 请求要点 | 返回与语义 |
| --- | --- | --- |
| POST `/` | mode=describe/copy/manual、用途/名称/简介、可选sourceAgentId；Idempotency-Key | 201草稿快照；重复同键返回同一草稿；复制检查来源后再提交完整草稿 |
| GET `/` | 沿用本人/管理权限范围 | 草稿列表；成功关联正式结果不重复显示为草稿 |
| GET `/{draftId}` | 当前账号 | 草稿、revision、资源状态、生成/启用操作状态、成功agentId |
| PATCH `/{draftId}` | If-Match=revision；完整编辑快照 | 保存后的快照与新revision；409版本冲突；启用运行中409锁定 |
| DELETE `/{draftId}` | 当前revision | 删除未启用草稿并释放资源；生成可取消，试聊先停止；已启用结果不通过此接口删除正式智能体 |
| POST `/{draftId}/resources` | 既有支持格式的知识/定义输入，来源与目标相对路径 | 资源引用、ready/failed与原因；沿用资源模块已有解析能力，不增加格式转换管线 |
| POST `/{draftId}/generations` | baseRevision；Idempotency-Key | 202 operationId与状态；候选包含建议名称/简介/behavior，不写入当前草稿 |
| GET `/{draftId}/generations/{operationId}` | 所属关系检查 | 状态、候选、baseRevision、失败码 |
| POST `/{draftId}/generations/{operationId}/cancel` | 所属关系检查 | 幂等取消；已完成则返回原终态，取消后候选不会自动应用 |
| POST `/{draftId}/generations/{operationId}/apply` | If-Match=baseRevision；用户选定的候选字段 | 原子应用为新revision；过期409，现有内容不变 |
| POST `/{draftId}/checks` | 已保存revision | issues数组：code、severity、目标区域、messageKey、可选resourceId；不调用模型或执行工具 |
| POST `/{draftId}/activations` | revision；Idempotency-Key | 202启用操作；已成功返回同一agentId |
| GET `/{draftId}/activations/{operationId}` | 所属关系检查 | 阶段/终态/agentId；用于超时确认与恢复 |
| POST `/{draftId}/previews` | 已保存revision | previewId、固定配置版本、有效期 |
| GET `/{draftId}/previews/{previewId}` | 所属关系检查 | 同一预览的历史、状态与配置版本 |
| POST `/{draftId}/previews/{previewId}/runs` | 本轮输入、runId、Think、必要的实际工具运行确认 | SSE；复用现有AG-UI事件编码，不注册正式agentId |
| POST `/{draftId}/previews/{previewId}/runs/{runId}/stop` | 所属关系检查 | 幂等停止该轮，保留已确认输出 |
| DELETE `/{draftId}/previews/{previewId}` | 所属关系检查 | 停止并清空试聊，草稿配置不变 |

默认限制：同账号最多一个活动试聊轮次（服务端条件更新控制，而非仅靠按钮禁用）；单轮上限120秒；闲置预览24小时过期。沿用UsageGuard，试聊和行为生成仍计入真实模型用量，不绕过配额。过期只清理预览，不删除草稿。

主要错误契约：

| 状态 | code示例 | 处理 |
| --- | --- | --- |
| 400 | invalid_input / invalid_resource_path | 按字段定位，保留输入 |
| 401 | unauthorized | 重新登录后恢复同账号草稿 |
| 403 | edit_forbidden | 对可见但不可编辑对象停止写入 |
| 404 | draft_unavailable / source_unavailable / preview_unavailable | 不暴露无权访问对象，展示不可用恢复路径 |
| 409 | revision_conflict / generation_stale / activation_in_progress / idempotency_conflict | 重新读取真实版本或操作，不盲重试 |
| 422 | activation_check_failed / resource_unavailable | 返回issues及修复入口；手动关闭失败能力后再检查 |
| 429 | usage_limited / queue_full | 不丢草稿，提示稍后重试 |
| 502/503/504 | model_unavailable / generation_failed / generation_timeout | 生成中转任务终态，当前草稿内容保持 |
| 500 | activation_failed / storage_unavailable | 返回可追踪operationId，不返回服务端目录、凭据或原始堆栈 |

error body统一为 `code/messageKey/operationId/fieldErrors或issues`，不扩大全站错误协议改造。SSE开始后错误使用已有运行错误事件与终态，不能同时按HTTP错误体解析。

## 7. 隔离试聊的实现约束

- 用同一份创建草稿revision构建 HarnessAgent；仅注册到应用私有预览容器，不进入正式AG-UI注册表。
- 复用当前 AguiRequestProcessor 和事件编码器，单独提供 preview runtime resolver；服务端从previewId解析owner/draft/revision，不相信客户端agentId或workspace路径。
- `AgentProvisioner`共享构建函数只负责构建；正式 register、SkillSync的正式物化以及连接缓存发布保持在正式入口。预览不得通过“先注册正式agent再隐藏前端”的方式实现。
- 共享构建函数接收明确的运行ID、定义快照、workspace/state路径、已校验的工具选择与权限；只有正式入口执行registry发布。预览的内置报告动作使用PreviewStore适配，不挂载会写入正式TimelineStore的ReportActions实例，既有custom事件形状保持不变。
- 预览state、记忆和本地工作目录放在 `data-dir/agent-previews/{previewId}`；正式工作区、正式SessionStore和TimelineStore不被写入。
- 已有 AG-UI 过程信号、思考/工具分段投影、ProcessText分页和计时清理复用。前端提取展示适配层即可，不能复制第二套SSE解析器或整份ConversationPanel。预览响应和事件落库复用同一运行流（单订阅广播），不能为前端和持久化各订阅一次而重复调用模型。
- 行为生成与试聊的真实model response usage需纳入现有用量记录；提供方未返回的数值显示未知，不把估算写成真实采样。
- 模型使用当前保存的接入端和SelectedModelMiddleware；每轮固定实际模型/上下文快照，不写死供应商或1M容量。
- 预览默认关闭命令执行、写文件以及无法确认只读的外部操作；用户主动选择本轮实际工具运行后仍须通过既有工具权限。确认标志不绕过deny/ask或来源访问权限。
- 本地预览目录隔离不等于主机命令沙盒。实际执行入口明确说明可能产生真实效果；不声称UNRESTRICTED命令已被隔离。不会为测试自动复制账号凭据或打开原来无权使用的工具。
- 修改草稿不改变已创建预览的快照；新revision建立新preview。停止、断连、超时、删除草稿和过期时统一取消Flux/模型订阅、释放连接、定时器与临时文件。
- 后端消费运行事件，保存到PreviewStore供断连后读取；每个runId同一preview只执行一次，失败重试使用新runId并复用已保存访客输入，不重复追加访客消息；不接入正式AguiRoundPersister，也不产生正式报告、发布文件或子智能体定义提案。

## 8. 前端组件、路由与状态

路径相对 `technical-advisor-app/frontend/src/`。

| 文件 | 职责 |
| --- | --- |
| main.tsx | 新增 `/agent/new` 与 `/agent/creation/:draftId`；`/agent/:agentId`保留正式详情 |
| api/agentCreation.ts（新增） | 上述草稿/操作/preview契约、TanStack Query keys和请求封装 |
| features/agent/AgentCreationPage.tsx（新增） | 用途/结果两种页面内容、草稿恢复、当前revision、创建后进入对话 |
| features/agent/useAgentCreationDraft.ts（新增） | 本地dirty编辑、串行防抖保存、flush、冲突恢复与离开处理 |
| features/agent/components/CreationPurposeForm.tsx（新增） | 首屏用途输入、例子、可选复制/手动入口 |
| features/agent/components/CreationSettingsSection.tsx（新增） | 结果编辑区旁的默认收起设置，复用工具/技能/资料选择 |
| features/agent/components/CreationSummary.tsx（新增） | 结果页内联检查提示和“创建并开始使用”操作，不单独增加确认步骤 |
| features/agent/components/AgentPreviewPanel.tsx（新增） | 隔离试聊会话、发送/停止/清空、过期与配置版本提示 |
| features/agent/useAgentPreview.ts（新增） | 既有SSE客户端适配、PreviewStore历史获取和订阅清理 |
| features/agent/AgentPage.tsx | 新建跳创建页，正式列表与草稿列表合并展示，继续编辑/复制入口 |
| features/agent/components/CreateAgentDialog.tsx | 新创建页切换完成后移除旧入口，避免双回调和静默生成 |
| features/agent/components/ToolSelectionSection.tsx、SkillSelectionSection.tsx | 将纯选择UI与正式保存适配拆开，创建页复用目录/选择器，正式详情保持原行为 |
| features/agent/components/BehaviorSection.tsx | 复用编辑控件；创建页候选应用不会调用旧覆盖接口 |
| features/chat/aguiClient.ts | 仅抽取可注入endpoint的既有SSE传输能力，默认正式endpoint保持不变 |
| features/chat/model/presentationParts.ts及过程组件 | 通过展示适配层复用；正式会话逻辑不整体搬入创建页 |
| i18n/locales/{en,zh-CN}/app.json | 创建状态、步骤、issues、错误恢复及试聊文案同步 |

服务端状态放TanStack Query；本地仅保留未保存编辑、展开区域与焦点，不手工复制服务端缓存。已有能力选择器若包含正式agentId耦合，先拆保存适配，不能直接对草稿调用正式绑定接口。

生成状态轮询、自动保存debounce、SSE请求和预览取消各自有cleanup；StrictMode测试确认不重复建立任务/草稿。运行中禁用冲突主操作；返回修改、重试、应用候选都有明确有效状态。

## 9. 按依赖顺序实施：单份方案，逐项TDD

每个任务都先跑新增测试确认行为失败，再做最小实现。以下任务是实施检查点，不是多版产品方案。未授权不提交；通过后只在新增修改/失败时重跑门禁。

### Task 1：草稿保存与权限边界

**文件**：AdvisorSchema、AgentCreationDraftStore、AgentCreationController/Service；新增测试 AgentCreationDraftStoreTest、AgentCreationControllerTest。

- [ ] 写失败测试：同键重复建草稿只返回一个draftId；旧revision保存409；来源无权404；可见不可编辑403；同名允许；重启读取快照；草稿不出现于正式AgentStore与registry。
- [ ] 运行目标测试并确认上述行为缺失；实现新增表、归属规则、条件保存、复制及文件引用校验。
- [ ] 验证所有用例通过；既有AgentStoreTest与复制/权限测试继续通过。

### Task 2：候选生成与并发恢复

**文件**：BehaviorDraftService、AgentCreationService/Controller、AdvisorConfiguration；新增 AgentCreationGenerationTest。

- [ ] 写失败测试：202操作可查询；同键不重复模型调用；模型失败/空响应/30秒超时保留当前行为；旧revision候选应用409；重启将运行任务变为中断；队列满429。
- [ ] 最小实现有界任务、固定baseRevision、独立候选、显式应用和取消清理；使用测试模型计数与可控制完成的响应，不进行真实模型调用。
- [ ] 验证任务完成、失败、晚到和手动编辑均不会覆盖当前内容；旧正式行为生成接口保持兼容。

### Task 3：检查、幂等启用与正式入口闸门

**文件**：AgentCreationService、AgentStore、AgentWorkspace、AgentProvisioner、AdvisorAgentController、AdvisorSessionController、AdvisorConfiguration；新增 AgentCreationActivationTest。

- [ ] 写失败测试：未填行为/无效能力不能启用；无工具的纯对话可启用；相同operation只产生一个正式结果；register失败留草稿；每个发布阶段注入失败后重试/重启恢复同一agentId。
- [ ] 写正式入口失败测试：未完成发布行不能被路由、锁定会话或直接AG-UI调用；不可见与权限不足语义不变。
- [ ] 实现固定快照、能力绑定、工作区物化、注册和补偿；复用现有模型与权限。
- [ ] 验证旧创建接口立即可用、基本聊天降级、单候选路由优化和既有会话行为继续通过。

### Task 4：隔离试聊与资源释放

**文件**：AgentPreviewStore/Controller/Service、AgentProvisioner共享构建部分；新增 AgentPreviewControllerTest、AgentPreviewServiceTest。

- [ ] 写失败测试：跨用户preview不可见；客户端不能指定任意agent/path；同revision试聊一致；修改草稿不改变旧preview；内置报告动作无正式session/timeline/state污染，持久化与前端消费不会触发两次模型调用。
- [ ] 写失败测试：默认拒绝写操作/命令，确认不绕过既有deny；配额限制、120秒超时、停止/断连/删除/24小时过期都释放订阅与连接。
- [ ] 实现私有runtime容器、AG-UI事件复用、独立历史和清理；模拟工具与模型验证，不调用真实外部资源。
- [ ] 验证中断历史可读取、Think关闭与完成态过程展示不回退。

### Task 5：创建页与自动保存

**文件**：api/agentCreation、AgentCreationPage、useAgentCreationDraft、用途表单、更多设置和内联创建摘要组件、AgentPage/main、双语词典；新增 AgentCreationPage.test.tsx、useAgentCreationDraft.test.tsx。

- [ ] 写失败测试：三个起点进入同一创建页；首次保存只建一草稿；复制来源不默认错选；返回/恢复保留内容；草稿没有正式对话按钮。
- [ ] 写失败测试：旧保存响应不覆盖dirty输入；版本冲突保留编辑；StrictMode无重复创建；保存失败阻止试聊/启用；候选应用由明确用户操作承接；旧内容替换需确认；不出现双成功导航。
- [ ] 实现800ms串行保存、恢复路由、错误定位和EN/ZH文案，复用现有能力选择UI。
- [ ] 验证保存后离开/取消离开/放弃未保存修改、登录变化和窄屏页签状态一致。

### Task 6：可选试聊与一次点击开始使用

**文件**：AgentPreviewPanel/useAgentPreview、CreationSummary、既有aguiClient适配；新增 AgentPreviewPanel.test.tsx。

- [ ] 写失败测试：配置版本变化提示旧结果并新建preview；发送/停止/清空有效；隐藏/卸载停止轮询和订阅；长过程继续分页，不批量挂载完整输出。
- [ ] 写失败测试：不打开更多设置、不试聊也可创建；默认只有一个主按钮；创建过程中后台串行保存/检查/启用；启用超时先查结果；重复操作不重复导航；开始对话失败只重试建会话；错误不会重置草稿。
- [ ] 最小实现展示适配、操作查询及一次点击串行确认/保存/检查/启用/打开对话；不复制正式会话状态管理。
- [ ] 验证整个流程通过后移除旧创建弹窗路径；正式详情与会话入口继续可用。

### Task 7：集成验收与文档

- [ ] 新增 `frontend/e2e/agent-creation.spec.ts`，覆盖描述创建→保存→试聊→启用、复制→修复资源→启用、刷新恢复、模型失败转手动、重复启用及权限失效。
- [ ] 关键用户流程E2E属于本次必要验证：只在对应阶段运行有界的headless Playwright，使用可控模型/工具，不启动人工浏览器反复巡视，不发真实付费模型请求。
- [ ] 更新 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md`，修正创建后立即可用的描述，补充草稿/试聊/启用边界。
- [ ] 自检任务范围内diff、路径、许可证、双语键和真实测试数；执行以下最终门禁并记录结果。

## 10. 验证命令与完成标准

仓库根目录，目标测试使用Surefire继承模块时加入 `-Dsurefire.failIfNoSpecifiedTests=false`，并确认目标类确实执行：

```bash
mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=AgentCreationDraftStoreTest,AgentCreationControllerTest,AgentCreationGenerationTest,AgentCreationActivationTest,AgentPreviewControllerTest,AgentPreviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -T1 -pl technical-advisor-app/backend -am test
mvn -T1 -pl technical-advisor-app/backend -am package
mvn -T1 -pl technical-advisor-app/backend -am spotless:check
```

前端目录：

```bash
npm test -- src/features/agent/AgentCreationPage.test.tsx src/features/agent/useAgentCreationDraft.test.tsx src/features/agent/components/AgentPreviewPanel.test.tsx
npm test
npm run lint
npm run build
npm run test:e2e -- e2e/agent-creation.spec.ts
```

- 自动测试使用现有H2/JDBC风格与可控模型、临时目录、模拟工具；PostgreSQL迁移另做真实语法/幂等验证，不能将H2通过等同于线上迁移成功。
- 一次人工验收涵盖新建、复制、刷新恢复、失败重试、启用及开始对话。工程E2E发现问题时针对失败场景修复，不无理由扩大浏览器验证。
- 通过门禁、集成流程和聚焦复审后才算完成；方案本身不能被当作实现已完成。
- 交付记录包含实际通过数、剩余限制、是否重启及数据库是否迁移。本次写方案没有运行上述实现门禁，也没有重启服务或迁移数据库。

## 11. 兼容、回滚与交付风险

### 11.1 兼容

- 现有正式创建、编辑、删除、文件与路由接口保持原行为；新前端创建流改用草稿接口。正式详情页旧行为生成不在此阶段重写为版本发布。
- 既有智能体默认可用，不批量转草稿；原工作目录和历史数据不迁移。
- 三张新增表与文件目录属于应用层；AG-UI事件、SDK序列化格式、核心公共API保持兼容。
- 配置快照为小型元数据与正文；文件存引用，避免把二进制或大知识全文反复塞入自动保存请求。

### 11.2 回滚

1. 暂停新建/启用操作，终止预览和生成；等待已成功发布记录确认。
2. 补偿或移除尚未完成发布的正式残留行与绑定，保留原草稿和失败阶段供恢复。
3. 前端回到旧创建入口，后端回到旧程序；正式智能体继续原运行方式。
4. 新增草稿/创建操作/预览表保留，不直接删除；新程序恢复后可继续草稿。草稿目录不参与旧启动注册。
5. 若迁移失败，启动应报告错误并保留原数据，不能启动一个草稿接口静默失效的半可用版本。

### 11.3 关键风险与处置

| 风险 | 必须有的证据 |
| --- | --- |
| 草稿误入正式运行 | 选择器、route、锁定会话、直接AG-UI四条入口都拒绝未完成对象 |
| 启用跨资源失败导致重复 | 每个阶段故障注入、同agentId重试、重启补偿测试 |
| 保存/生成覆盖新输入 | revision冲突、晚到候选与多窗口回归 |
| 试聊污染正式历史或实际资源 | 独立存储目录断言、正式表零写入、默认拒绝副作用和明确实际运行确认 |
| 后台任务导致页面卡死或泄漏 | 有界队列、停止/隐藏/卸载/超时/StrictMode资源测试 |

## 12. 当前交付状态

- [x] 当前创建/生成/注册/权限实现已按相关源码核实。
- [x] 用户确认的产品路径、异常、前端Markdown草图已纳入；按新人简洁交互要求更新为用途与结果两种内容，试聊/高级设置可选。
- [x] 状态、草稿/正式隔离、接口、幂等和恢复边界已明确。
- [x] 文件职责、按依赖的TDD任务、验收与回滚方案已写入。
- [ ] 用户审查技术方案后开始Task 1。
- [ ] 按Task 1～7实施并逐项更新本文件真实验收结果。
