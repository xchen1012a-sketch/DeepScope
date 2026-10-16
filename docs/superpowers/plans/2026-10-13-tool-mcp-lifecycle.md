# 工具与 MCP 管理完善：技术实现方案

> **执行约定**：本次只交付方案，不修改业务代码。用户审查后使用 executing-plans 按检查点实施；先写行为测试并确认失败，再最小实现和验证。未授权不提交、不推送、不迁移数据库、不重启服务。

**Goal**：让工具在保存前完成执行入口解析与连接检查，支持后续编辑、测试、更新、分配和删除，并补齐已经确认的全部遗漏项。

**Architecture**：继续使用现有 ToolCatalog、MCP 客户端、工具权限、智能体选择关系和 Harness 运行体系。新增统一的解析、连接检查、执行、变更协调服务；工具目录仍是唯一正式配置来源，HTTP与MCP按各自执行方式处理。

**Tech Stack**：现有 React/TypeScript/TanStack Query/Radix/Tailwind/i18next；Spring Boot/JDBC/PostgreSQL/Jackson/Java HttpClient/框架 MCP SDK。版本跟随现有 manifest/POM。

**Spec**：用户确认的四项要求和上一轮查漏清单在本文第1节完整展开。本文件为本次唯一技术方案，实施阶段不代表不同产品版本。

**改动等级**：涉及工具配置契约、鉴权和正在运行连接的回收时机，保守按高级改动评审，包含兼容、回滚和阶段验收。优先限定在 technical-advisor-app；若需要更改框架公共权限、AG-UI事件或核心取消语义，先更新方案并再次确认。

## 1. 范围与覆盖清单

| 用户要求或已查到的遗漏 | 本方案交付 | 对应检查点 |
| --- | --- | --- |
| 地址可留空，确定时先测试连接 | 自动解析实际执行入口，检查成功再保存；缺地址、连接失败、鉴权失败分别提示 | T1/T2 |
| 导入后可编辑 | HTTP完整配置编辑；MCP连接编辑和远端定义同步；原ID和关联保留 | T3/T5 |
| 导入前预览与校验 | 识别操作、实际地址、重复项、不支持内容，零工具不能成功 | T1 |
| 重复导入 | 创建/更新/跳过、命名冲突检查、请求幂等 | T3 |
| 测试调用 | 参数输入、真实请求结果、耗时、取消、明确失败原因 | T4 |
| 鉴权配置 | 无认证、API Key、Bearer、Basic；凭据保留/替换/清除、脱敏 | T2/T4 |
| 文档兼容性 | JSON/YAML、常用OpenAPI结构、参数映射及明确的不支持报告 | T1/T4 |
| MCP刷新不完整 | 新增/变化/移除差异预览、确认、原权限与原ID保留 | T5 |
| MCP连接不可编辑 | 地址、传输、命令、参数、环境变量、请求头可修改并复检 | T5 |
| 删除无影响提示 | 展示关联智能体，确认后事务清理关系并停止新调用 | T6 |
| 更新全量重注册 | 只更新受影响智能体；正在运行的连接延后释放 | T6 |
| 批量失败与错误含糊 | 整批事务、逐项问题、保留输入、可重试和可追踪结果 | T1/T3/T7 |

整体保留工具/MCP两个顶部页签，不引入新的工具市场、通用工作流平台或第二套权限体系。所有固定文案同步EN/ZH，普通用户只看到操作和下一步，不看到内部状态码。

## 2. 现状证据与核心决策

### 2.1 当前代码已核实

- ToolImport：优先输入地址，再取根级第一个servers.url；没有地址生成declared。Schema有地址固定按POST处理。读取JSON但不解析YAML、通用引用、路径级参数和认证方案。
- ToolCatalog.effectiveTools：排除declared，只加载智能体明确选择或订阅的已批准工具。
- AdvisorToolController：导入直接入库；PATCH只改permission；更新/删除/刷新调用refreshAgents，重新注册全部智能体。
- ToolsPage：工具列表只有权限和删除；MCP列表只有刷新和删除；没有编辑/测试/删除影响确认。
- MCP刷新返回pending，但页面没有接续确认；同名工具的目录描述与inputSchema未更新。运行时可能另行读取远端最新定义，不能将目录未更新等同于运行时必然使用旧参数。
- 批量导入每项独立插入；选择关系更新和删除清理缺少统一事务边界。
- HttpCatalogTool：支持路径替换、query和JSON正文；没有完整请求头/认证配置、测试入口与有限响应读取。

### 2.2 地址留空的处理

“没有填写地址”和“没有可用执行入口”不是同一状态。只要后端能解析出执行入口，就允许字段留空并检查连接。

解析优先级：用户显式覆盖 → 操作级servers → 路径级servers → 根级servers → 已明确配置的执行绑定。多个地址让用户选择，变量按文档默认值解析；相对地址只有存在已知文档来源或绑定基地址时才能解析，不猜测为当前前端域名。

- OpenAPI已有完整地址：显示“已从接口说明识别”，输入框保持可选。
- MCP：从服务连接得到执行入口；工具本身不重复填写HTTP地址。
- JSON Schema：参数说明本身不包含执行实现；必须指定HTTP调用方式/地址，或绑定已经存在且有权使用的MCP工具。不能仅因工具名称相同就假设已有实现。
- 既无地址又无执行绑定：返回address_required/executor_required，提示“未找到执行地址，请填写或选择服务”，不保存成看似可用的工具。
- 已有declared条目保留ID，标为“待配置”，可编辑补齐后转换为HTTP工具；不进入可调用列表。

这是对当前“无地址则静默保存声明”的行为调整；“地址”字段本身仍不是无条件必填。

### 2.3 连接检查与测试调用分别负责什么

| 动作 | 自动执行 | 确认内容 | 不声称什么 |
| --- | --- | --- | --- |
| 保存前连接检查 | 是，后端执行 | 配置可解析、协议/主机可达、TLS正常、服务能响应，MCP可初始化并列工具 | 不表示业务参数正确或每个功能已经测试成功 |
| 测试调用 | 用户主动点击 | 使用真实参数执行一次目标工具，展示结果和耗时 | 不自动代表已保存新配置，也不自动修改权限 |

保存前不得拿虚构参数自动执行POST/PUT/PATCH/DELETE等业务操作，也不自动调用MCP业务工具。连接检查不能产生删除订单、发送消息等业务副作用。

HTTP检查：先校验解析后的HTTP(S)目标，建立连接并验证TLS；对可解析的检查路径使用HEAD/OPTIONS等无业务写入的探测。工具参数决定路径且尚无参数时，检查服务基地址，明确显示“连接通过，调用未测试”。HEAD不支持时仍区分服务已经响应和网络未连通，不改用可能有副作用的业务请求。

| 结果 | 是否保存 | 用户提示 |
| --- | --- | --- |
| 执行入口缺失 | 否 | 未识别到地址，请填写或选择服务 |
| DNS、连接拒绝、TLS、超时 | 否 | 已识别地址，但连接失败；检查服务地址、网络或证书 |
| 401/403且属于所配置认证检查 | 否 | 认证失败或权限不足，请检查凭据；不提示补地址 |
| 服务明确5xx不可用 | 否 | 服务暂时不可用，稍后重试 |
| HTTP有响应但检查路径404/405 | 是，连接检查通过 | 服务可连接，具体调用尚未测试；测试面板验证真实操作 |
| 正常连接 / MCP初始化和listTools成功 | 是 | 连接检查通过，已保存 |
| MCP工具名已经不存在 | 否 | 工具已移除，请刷新服务定义 |

连接检查总期限10秒（连接上限4秒）；MCP初始化/列工具沿用现有30秒上限。失败保留前端输入和旧配置。成功结果不永久缓存：地址、认证、传输或命令改变都重新检查；保存接口必须自己检查，不能相信客户端的passed字段。

## 3. 用户流程与前端草图

### 3.1 导入

```text
工具与MCP                       [工具清单] [MCP服务]

[ 添加工具 ]

类型              OpenAPI / 参数说明
接口说明          [ 粘贴JSON或YAML                   ]
服务器地址        [ 可选；自动识别后在此显示来源    ]
认证（可选）      [ 无认证                          ]

识别到 3 个工具
✓ 查询用户    GET    https://api.example.com/users/{id}
✓ 创建用户    POST   https://api.example.com/users
! 删除用户           与已有工具同名 [更新/另建/跳过]

[取消]                              [ 检查连接并保存 ]
```

粘贴后可预览解析结果；点击主按钮后显示“正在检查连接”。成功直接保存；失败留在当前窗口并指出具体问题。重复项、多个环境或不支持内容需要用户选择/修正，不以空列表或部分成功伪装整批成功。

### 3.2 编辑与测试

```text
天气查询       已配置 · 已用于2个智能体      [编辑] [测试] [更多]

编辑工具
名称/说明     [ ...                                ]
请求方式      [ GET                                ]
执行地址      [ 已解析地址                         ]
参数          [ 参数表；复杂结构在高级编辑中修改    ]
认证          [ 未修改凭据 / 替换 / 清除           ]

修改会用于：代码助手、报告助手
[取消]                              [ 检查连接并保存 ]
```

测试面板展示当前“已保存配置”或明确的“未保存配置测试”，不能让用户误以为测试同时保存。普通用户使用参数表；嵌套对象可展开JSON编辑；不引入新的大型编辑器依赖。

```text
测试工具 · 使用当前编辑内容
latitude  [39.9042]   longitude [116.4074]

[执行测试] / [停止]
连接成功 · 请求成功 · 320ms
[ 按页查看返回结果 ]
```

- 有实际写入效果的测试在执行前说明目标和动作，由用户明确确认；同一次请求超时不自动重发写请求。
- 无编辑权限只看已保存配置；测试仍执行现有权限规则，deny不能调用，ask不能被测试入口绕过。
- 请求失败、测试失败、保存失败分别呈现；401、参数错误、超时、服务错误都有下一步。
- 保存期间冻结对应输入与重复提交；取消编辑丢弃本地修改，保存失败保留。离开有未保存内容时复用现有受支持的路由阻止/原生卸载提示模式。
- 测试返回使用现有ProcessText有界分页，不挂载全部大文本；关闭/停止测试取消请求和订阅，CSS加载环遵守减少动态效果。

### 3.3 MCP更新

```text
某MCP服务        [编辑连接] [检查更新] [更多]

发现变化
新增 2 个工具       [选择加入，默认权限：询问]
更新 1 个工具       [查看参数变化]
移除 1 个工具       已用于2个智能体

[取消]                                 [ 确认更新 ]
```

远端工具名称/参数是服务端定义，页面不允许将它们编辑成一个远端不存在的工具。本地可编辑连接信息、说明别名和权限。MCP通知或手动刷新只产生候选差异，不静默授予新工具权限。

服务已移除的工具即时标记不可调用，用户确认后清理绑定；不能为等待确认继续调用不存在的工具。

## 4. 架构、职责与持久化

```text
工具/MCP页面
  → 解析与预览 → 连接检查 → 配置事务保存 → 定向运行更新
  → 统一测试执行器 → 有界结果展示
  → MCP发现 → 变化对比 → 确认事务应用
```

### 4.1 文件职责

下表后端路径相对 `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/`，前端路径相对 `technical-advisor-app/frontend/src/`。

| 文件 | 职责 |
| --- | --- |
| tool/ToolImport.java（修改） | 文档读取、受支持结构展开、规范化定义和逐项诊断 |
| tool/ToolDefinitionService.java（新增） | 解析预览、重复选择、保存/编辑事务协调、revision和幂等 |
| tool/ToolConnectionProbe.java（新增） | HTTP可连接检查、MCP握手/列工具，不执行业务动作 |
| tool/ToolHttpExecutor.java（新增） | 测试与正式HTTP调用共用URL/参数/认证/响应边界处理 |
| tool/HttpCatalogTool.java（修改） | 薄适配到统一执行器，保留框架ToolBase行为 |
| tool/ToolTestService.java（新增） | 测试权限、参数校验、单次运行、取消和结果摘要 |
| tool/McpUpdateService.java（新增） | 服务连接编辑、发现快照、差异对比与确认应用 |
| tool/ToolRuntimeCoordinator.java（新增） | 找出受影响智能体、版本构建、发布、运行租约与旧连接释放 |
| tool/CatalogExecutionGuard.java（新增） | 已删除/失效/新设deny的工具在实际调用前拒绝，继续复用框架ask/allow判断 |
| tool/ToolCredentialStore.java（新增） | 密文与凭据引用、保留/替换/清除；不建设独立账号体系 |
| tool/AdvisorToolController.java（修改） | 显式DTO、新接口与旧接口适配、已有可见性与管理权限 |
| tool/ToolCatalog.java（修改） | 配置完整更新、revision、事务批量操作、关联查询和配置历史 |
| tool/FrameworkMcpToolSource.java / McpEndpointParser.java（修改） | 复用SDK连接和发现，编辑配置、关闭资源及脱敏 |
| agent/AgentProvisioner.java（修改） | 构建新的配置版本、不在准备阶段关闭当前运行连接 |
| AdvisorConfiguration.java / session/AguiRoundPersister.java（局部） | 应用层登记运行起止并释放租约；不改变原事件/历史格式 |
| db/AdvisorSchema.java / AdvisorProperties.java（修改） | 幂等加列、辅助表、检查/响应限制和凭据密钥配置 |
| api/tool.ts（修改） | 编辑、预览、检查、测试、影响查询、MCP差异契约 |
| features/tool/ToolsPage.tsx（修改） | 两个主页签和列表；拆小组件，避免所有流程继续堆在单文件 |
| features/tool/components/ToolImportDialog.tsx（新增） | 预览、重复项、连接检查并保存 |
| features/tool/components/ToolEditorPanel.tsx（新增） | 原地编辑、认证保留/替换、未保存保护 |
| features/tool/components/ToolTestPanel.tsx（新增） | 参数表、结果分页、停止与错误恢复 |
| features/tool/components/McpEditorDialog.tsx / McpChangesDialog.tsx（新增） | 连接编辑和差异确认 |
| features/tool/components/ToolDeleteDialog.tsx（新增） | 影响列表与确认，工具和服务共用 |
| i18n/locales/{en,zh-CN}/app.json（修改） | 新状态、诊断和恢复文案同步 |

### 4.2 数据调整

现有 `advisor_tools` 增加：revision、来源类型/来源文档、标准化请求配置、认证引用、连接检查时间/结果、配置应用状态。保留toolId、origin、permission和所有关联关系。增加providerName区分远端原名与本地调用名；MCP差异按serverId+providerName匹配，本地显示别名不改变发给远端的名称。

现有 `advisor_mcp_servers` 增加：revision、当前定义指纹、最后发现时间、候选差异快照与版本。服务元数据编辑不改变serverId；密钥/敏感头和环境值通过凭据引用读取，返回只包含hasSecret和脱敏信息。

辅助表：

- `advisor_tool_change_requests`：账号内幂等键、请求摘要、结果ID/版本、事务成功与运行应用状态。同键同内容重发返回同一结果，同键不同内容409。
- `advisor_tool_definition_revisions`：工具/服务配置版本与变更摘要，支撑当前轮快照和回滚；不存明文凭据。保留最近20版，仍被运行租约引用的版本不提前删除。
- `advisor_tool_credentials`：owner、密文、nonce、keyId与更新时间。密钥不入库，接口不回传明文。来源文档与标准化请求配置在提取已识别的认证值后再保存，不把Token同时留在原文、URL或参数默认值中。

定义、凭据引用和工具/服务选择关系在同一JDBC事务提交；先完成解析与连接检查，再开启短事务。不要在持有数据库锁时等待网络或MCP进程。

编辑使用If-Match=revision，事务提交前再次校验原版本和权限。任何批量失败整批回滚；无效输入在网络探测前拒绝。删除关系与记录也在同一事务，不再先删绑定再独立删工具。

新导入通过规范化来源/操作标识匹配重复项，并确认create/update/skip；更新保留原ID。不同来源的同名工具必须明确改名或提供稳定调用别名，不能默默覆盖注册表中的同名工具。

### 4.3 认证与支持范围

HTTP支持none、API Key（header或query）、Bearer、Basic，以及非敏感自定义请求头；Authorization由认证配置生成，不作为普通模型参数要求模型填写。OAuth2/OpenID授权流程在导入时明确报“不支持的认证流程”，不静默忽略后保存为可用。

凭据更新采用keep/replace/clear三态，空输入默认keep。替换生成新的凭据版本引用，不原地覆盖仍被旧运行版本使用的值；撤销仍即时阻止新调用，旧引用在租约释放后回收。复用已有权限标识tool.manage和归属规则；读不可见对象404，可见但无编辑权403。模型上下文、日志、预览、错误和导出都不包含凭据。

当前代码未发现可复用的可逆凭据密文服务，拟用JDK AES-GCM，不新增加密库。`ADVISOR_TOOL_CREDENTIAL_KEY`提供Base64编码的32字节密钥；缺密钥时匿名工具可用，保存新凭据报credential_storage_unavailable。已有MCP敏感配置在密钥就绪后按迁移步骤转为引用，不能宣称原明文字段已经加密。

格式支持矩阵：

| 内容 | 本次处理 |
| --- | --- |
| OpenAPI 3.0/3.1 JSON与YAML、独立JSON Schema | 支持；YAML复用项目已有安全解析能力，无新增重复解析器 |
| 本地components引用、路径级/操作级参数、server变量与覆盖 | 展开并校验；递归深度最多32，循环引用保留有效结构或报告不可展开位置 |
| path/query/header/cookie参数、常用数组序列化 | 支持对应映射，不再只处理query；保留style/explode语义。同名参数来自不同位置时保留独立映射，不覆盖字段；顶层数组/标量JSON正文使用明确body入口 |
| JSON、urlencoded、multipart请求正文 | 支持；测试上传使用授权文件引用，不能通过模型参数读取任意服务端绝对路径 |
| 多个服务环境 | 显示候选让用户选择，不静默使用第一个环境 |
| 外部ref、OpenAPI 2.0、回调/Webhook作为可调用操作 | 明确指出不支持的项目，不自动向未知位置获取文件，不伪装解析完整 |
| 完全无paths或未识别操作 | 422 no_operations，不能201空列表 |

预览显示每个错误的位置和受影响操作；本次不宣称支持OpenAPI全部特性。支持矩阵之外的内容必须给出可操作诊断，属于本次兼容性闭环。

## 5. 执行、MCP同步和生效时机

### 5.1 统一执行器

连接检查、测试和真实调用共用规范化目标/认证配置；测试和真实调用共用请求映射实现，避免“测试成功、对话却请求不同地址”。

执行前做必填/类型/枚举/范围验证。GET/HEAD等不把参数错误塞进JSON正文；业务4xx/5xx、JSON解码失败和网络故障分别返回可读结果。不将所有异常归为缺地址。

测试/调用默认30秒期限，响应最多256KiB，先有界读取再展示，超过上限返回truncated标志；大结果仍受既有模型上下文预算约束。测试每账号最多一个活动请求，全局执行并发4、队列16，满时429；取消释放Future/订阅/输入流。写操作超时只能确认未知结果，不自动重试。

网络目标只允许配置支持的协议；重定向每跳重新检查目标且不跨域转发凭据。内网MCP/企业接口可以按当前部署许可使用，不硬编码禁止所有内网，也不把后端localhost当未知相对地址的默认补全。

### 5.2 MCP变化闭环

检查更新执行initialize/listTools并关闭临时连接，得到新增、同名变化、移除三类差异。指纹包括名称、说明和完整schema，不只比较name。

候选差异持久化并返回，页面刷新后仍能看到待确认变化。确认时再次读取远端定义；候选指纹不一致返回409 mcp_changed，重新展示差异。所有选定变化在同一事务应用。

- 新增：默认询问，用户选择加入；订阅整服务的智能体也只获得已确认的新工具。
- 同名变化：更新目录说明/schema，保留toolId、权限和智能体关联；参数有不兼容变化时明确提示，并在确认前阻止相关工具的新调用，避免仍按旧必填参数调用已经改变的远端接口。
- 移除：立即标记不可调用并显示影响，确认后移除正式目录/绑定；缺项检查失败不把“网络断开”误当成远端移除了全部工具。
- 编辑服务：保留serverId，先测试新配置并重新发现，失败仍使用旧保存配置；连接变化造成的工具差异继续确认。
- 框架已提供列表变化通知时复用它生成待确认候选；手动检查更新是必须通过的完整入口，不通过浏览器轮询长期监控服务。

### 5.3 定向更新与运行保护

受影响agentId取自toolId直接选择，以及MCP serverId订阅关系。未选择该工具/服务的智能体不重新注册、不重建连接。

每轮开始固定工具配置版本，登记应用层运行租约；正式流结束、失败、取消、断连终止后在finally路径释放。新版本先构建成功，再切换注册表供后续轮次使用；当前轮仍持有旧实例/连接，旧连接租约归零后才关闭。

CatalogExecutionGuard在真实调用前检查对象是否删除、失效或新设deny，再由原ToolPermissions/框架处理allow/ask；测试不能绕过同样规则。测试未保存定义只允许有编辑权的用户，permission采用当前保存值，不能通过draftDefinition中的allow字段越权。ask确认绑定本次目标、参数、revision和账号，仅授权这一次执行，复用现有框架确认机制。禁止和删除立即阻止新的工具执行。已经发送出去的外部写请求不保证能撤销，页面不能宣称“删除工具撤销了已执行动作”。

数据库保存与运行构建不是同一事务：保存成功后构建失败，显示“配置已保存，运行更新失败”，保留当前可运行版本并提供重试，禁止把新目录状态伪装成已生效。变更操作表保存待应用对象和版本，重启时按最新期望版本恢复；回滚通过前一版本地完整配置再发布，并保留绑定；不能将恢复本地MCP目录等同于回滚远端服务，远端已不兼容的旧定义需保持不可调用。

租约、前置调用检查和回收均放在应用适配层，复用现有SDK，不改变公开AG-UI事件。若无法在已有扩展点可靠覆盖完整结束路径，停止该检查点并升级框架契约评审，不以估计时间后关闭连接替代正确释放。

## 6. API契约

显式DTO与EN/ZH messageKey，统一错误包含code、fieldErrors/operationIssues、可选requestId与currentRevision，不返回堆栈或凭据。新预览/测试/影响接口不修改正式配置。

| 方法与路径 | 请求/返回语义 |
| --- | --- |
| POST `/api/advisor/tools/import/preview` | content、kind、addressOverride、环境选择；返回规范化候选、地址来源、重复项、errors/warnings、definitionHash |
| POST `/api/advisor/tools/import` | 原字段兼容，新增chosenOperations、duplicateActions、auth、definitionHash和Idempotency-Key；后端重新解析/检查连接，事务保存；成功仍返回工具数组 |
| GET `/api/advisor/tools/{toolId}` | 返回完整可编辑定义、revision、hasCredential、检查与应用状态，凭据脱敏 |
| PATCH `/api/advisor/tools/{toolId}` | 原仅permission请求兼容；定义更新带If-Match并检查连接，成功保留ID；permission-only请求不做业务网络探测 |
| POST `/api/advisor/tools/connection-check` | 测当前编辑定义，返回连通性证据与调用是否尚未测试；不保存 |
| POST `/api/advisor/tools/{toolId}/test` | input、savedRevision或明确draftDefinition、runId、必要确认；返回HTTP状态/结果、耗时、truncated、errorCode，不保存配置 |
| POST `/api/advisor/tools/tests/{runId}/stop` | 按账号校验，幂等取消；已发出的外部操作不承诺撤销 |
| GET `/api/advisor/tools/{toolId}/impact` | 受影响正式智能体、服务订阅和最近使用信息，仅返回有权查看范围 |
| DELETE `/api/advisor/tools/{toolId}` | If-Match和已确认影响版本；事务删除/断开关系，停止新调用，回收运行版本 |
| PATCH `/api/advisor/mcp-servers/{serverId}` | 当前revision、新连接配置与凭据动作；测试成功才替换，保留serverId |
| POST `/api/advisor/mcp-servers/{serverId}/refresh` | 返回并保存新增/变化/移除候选、指纹和影响；不自动批准新增 |
| POST `/api/advisor/mcp-servers/{serverId}/approve` | 兼容旧tools批准，扩展acceptedChanges和expectedFingerprint；再次确认远端后原子应用 |
| GET `/api/advisor/mcp-servers/{serverId}/impact` | 查看工具、订阅者和删除影响 |
| DELETE `/api/advisor/mcp-servers/{serverId}` | 版本与影响确认；服务/工具/选择关系原子清理 |
| POST `/api/advisor/tool-changes/{requestId}/retry-apply` | 重试同一已保存版本的运行应用，不能产生重复工具或连接 |

导入/编辑失败只返回相应项诊断，不清空前端字段。返回连接失败时：无地址用address_required，地址已识别用connection_failed/connection_timeout，认证失败用auth_failed，参数问题用invalid_parameters。重复/并发用409，格式和不支持项用422，权限使用现有401/403/404语义。

幂等键绑定账号、请求摘要和目标revision；双击或响应丢失后重发同一请求返回原结果，不重复导入。测试写请求的同runId也只执行一次；新runId重试必须由用户明确发起。

## 7. 按依赖执行的TDD检查点

每项先运行目标失败测试再实现，完成后做范围内复审。以下均为完整目标的一部分，不因先完成导入编辑就将剩余项标为完成。

### T1：解析、预览与校验

- [ ] ToolImportTest补充：地址留空但servers有效、操作/路径覆盖、变量/多个环境、本地ref、JSON/YAML、参数位置、无操作、无执行绑定、unsupported内容与准确位置。
- [ ] 新ToolDefinitionServiceTest确认预览零正式写入、重复候选可见、无效输入不发网络请求。
- [ ] 实现规范化DTO与逐项诊断；前端导入预览展示和错误字段定位通过。

### T2：保存前连接检查与认证

- [ ] 新ToolConnectionProbeTest覆盖DNS/TLS/超时、401/403、5xx、HEAD不支持、已响应但未业务验证；确认POST/DELETE业务handler调用计数为零。
- [ ] 新ToolCredentialStoreTest覆盖keep/replace/clear、密钥缺失、密文与脱敏、权限、重定向不泄露认证。
- [ ] 用本地模拟HTTP/MCP验证“检查成功后保存、失败无写入”，不请求真实生产服务。

### T3：编辑、重复与批量事务

- [ ] ToolCatalogTest和新AdvisorToolControllerTest覆盖保留toolId/绑定、If-Match冲突、重复create/update/skip、同名注册冲突、同幂等键不重复保存。
- [ ] 注入批次中途失败，断言工具、凭据引用和选择关系整批回滚；零工具不返回成功。
- [ ] 定义变更复检连接；仅权限变更兼容且不执行目标操作；失败保留旧配置。

### T4：统一执行和测试面板

- [ ] 新ToolHttpExecutorTest覆盖path/query/header/cookie、数组style/explode、JSON/form/multipart、API Key/Bearer/Basic、参数校验、业务4xx/5xx、有界响应。
- [ ] 新ToolTestServiceTest确认测试与正式执行请求完全相同、deny/ask不能绕过、同runId不重复写、超时不自动重试、停止释放资源。
- [ ] 前端ToolTestPanel.test.tsx验证明确的保存/未保存来源、参数错误、真实结果、分页、取消与关闭清理。

### T5：MCP编辑与更新

- [ ] 新McpUpdateServiceTest覆盖新增/同名schema变化/移除、网络失败不清空工具、候选重启恢复、确认时远端再变化409。
- [ ] 保留serverId/toolId/原权限/绑定；取消差异不批准新增，移除工具不能继续调用；整服务订阅只得到已确认项目。
- [ ] 连接编辑失败保留旧连接；每条stdio/SSE/HTTP成功/失败路径检查临时客户端关闭。

### T6：关联、删除与运行更新

- [ ] 影响查询、版本确认、不可见对象、删除事务、同名别名和选择关系事务测试通过。
- [ ] 新ToolRuntimeCoordinatorTest验证只构建受影响agent、保存与应用分开、构建失败保留旧版本、两轮分别使用固定版本。
- [ ] 注入正在运行的MCP调用，更新不提前close；结束/取消/异常后租约归零；删除/deny阻止旧上下文发起下一次工具执行。
- [ ] 新增应用运行生命周期回归，确认AG-UI事件、Think、用量和历史顺序不变。

### T7：前端交互与错误恢复

- [ ] 新ToolsPage.test.tsx、ToolImportDialog.test.tsx、ToolEditorPanel.test.tsx、McpChangesDialog.test.tsx覆盖导入/编辑/确认/取消/重复/删除影响/失败保留输入。
- [ ] 调整api/tool.ts契约和现有工具选择页面状态：待配置/失效不能选择，原选择关系保存不丢失，EN/ZH键与插值一致。
- [ ] StrictMode、保存冻结、快速切换、未保存离开、测试取消和长响应分页验证通过；不新增动画库或页面后台无限轮询。

### T8：迁移、集成与交付

- [ ] AdvisorSchema幂等加列/表；旧HTTP/MCP/declared记录映射正确，原权限与关联保持；PostgreSQL真实迁移验证与H2回归分别报告。
- [ ] 新`frontend/e2e/tool-mcp-lifecycle.e2e.ts`覆盖无手填地址导入、编辑保留绑定、测试失败恢复、MCP差异确认、删除影响；使用模拟服务和headless浏览器。
- [ ] 更新`docs/v2/{en,zh}/docs/others/technical-advisor-login.md`及相关工具示例，描述检查与调用的区别，不承诺全量规范支持。
- [ ] 完整自检、聚焦复审和最终门禁；报告实际结果与部署/迁移状态。

## 8. 验证命令与回滚

仓库根目录：

```bash
mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=ToolImportTest,ToolCatalogTest,ToolDefinitionServiceTest,ToolConnectionProbeTest,ToolCredentialStoreTest,AdvisorToolControllerTest,ToolHttpExecutorTest,ToolTestServiceTest,McpUpdateServiceTest,ToolRuntimeCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -T1 -pl technical-advisor-app/backend -am test
mvn -T1 -pl technical-advisor-app/backend -am spotless:check
mvn -B -T1 clean verify
```

目标测试必须确认实际执行，不能用跳过测试掩盖门禁失败。前端目录：

```bash
npm test -- src/features/tool
npm test
npm run lint
npm run build
npm run test:e2e -- e2e/tool-mcp-lifecycle.e2e.ts
```

浏览器只用于上述必要、限定、模拟接口的集成验收，不重复巡视或发真实付费模型请求。修改生效与回收使用可控SDK客户端和模拟服务器做故障注入，不以本地构建通过代替正在运行场景证据。

兼容与回滚：

1. 原toolId/serverId/智能体关系和旧permission PATCH保留；旧导入成功数组格式保持。旧无执行绑定导入行为改为明确失败，属于用户本次确认的变化。
2. 迁移前备份原目录配置和关系；新字段默认“未检查”，不将旧工具批量变成已测试成功。旧可运行工具保持可运行，编辑后执行新检查规则。
3. 凭据迁移使用独立密钥并校验可解密；切换旧程序前先按受控迁移将引用恢复为旧格式，不能把密文占位符传给旧客户端。密钥未就绪不进行不可恢复的凭据转换。
4. 应用失败可在当前程序按前一配置版本重新发布；所有更新保留原ID/绑定。退回旧程序前停止新测试和配置变更，等待租约释放，并完成凭据格式兼容处理。
5. 新增辅助表暂保留，不直接丢弃待应用操作/配置历史；恢复新程序后继续处理失败任务。数据库恢复、应用恢复和凭据密钥必须保持同一可恢复组合。

## 9. 方案交付状态

- [x] 用户四项要求与全部查漏项均映射到技术职责和TDD检查点。
- [x] 地址留空规则、自动连接检查与主动测试调用已区分。
- [x] 编辑/身份保留、MCP差异、认证、事务、定向生效和失败恢复已明确。
- [x] 前端草图、接口、文件范围、兼容和回滚已纳入。
- [ ] 用户审查后开始实施，按T1～T8更新真实验收结果。

本次仅新增方案文档，未实现接口、修改前后端代码、运行实现门禁、调用真实业务服务或执行迁移。

参考规范：[OpenAPI服务器与参数定义](https://spec.openapis.org/oas/v3.0.3.html)、[MCP工具及列表变化](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)。具体支持边界以第4.3节为准。
