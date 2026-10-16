# 模型设置体验实施计划

**Goal:** 实现已批准的模型列表、接入/配置抽屉、真实连接测试、显式默认切换，以及会话独立选模。
**Architecture:** 保留 ModelCatalog/ModelProbe、TanStack Query、Radix UI、i18next；增量扩展应用内部接口和存储，模型 endpointId 保持不变。运行开始时固定会话选中的模型。
**Tech Stack:** Java 17+ / Spring / JDBC；React 18 / TypeScript / Tailwind / Vitest / Playwright。
**Spec:** 本聊天用户已批准的 2026-10-15 Markdown 草图（模型列表、右侧抽屉、编辑与密钥更换、默认范围说明）。

## 范围与约束

- 中级应用改动：仅 technical-advisor-app 模型管理、会话模型选择及必要的知识库保护；不修改 SDK 公共契约。
- 用户已批准本方案，按本计划直接实施；不 commit/push，不覆盖工作区原有改动。
- UI 文案中英文同步，简洁专业；不展示真实密钥或原始异常中的凭据。
- 新增默认不激活；测试不保存配置；失败保留表单及原生效配置；连接参数修改使前端测试状态失效，服务端保存重新校验。
- 默认模型切换只影响后续未指定模型的运行；会话选择独立持久化，运行中保持所用模型。
- 向量模型存在已索引知识库时阻止直接更换，明确提示重建需要；相同维度不代表兼容。

## 内部接口契约

- GET /api/advisor/models：保留 chat/embedding/usage；模型增加 displayName、lastTestStatus (passed/failed/null)、lastTestAt、lastTestElapsedMs、lastTestMessage、dimensions。
- POST /api/advisor/models：kind/provider/modelName/baseUrl/secret/displayName/makeCurrent；不传 makeCurrent 默认 false。保存前真实测试。
- PUT /api/advisor/models/{id}：displayName/modelName/baseUrl/secret (省略保留)/makeCurrent；编辑保留 ID，失败不改变旧配置。
- POST /api/advisor/models/test：kind/provider/modelName/baseUrl/secret/endpointId (编辑时可复用已保存密钥)；返回 ok/message/elapsedMs/dimensions；不保存草稿。
- POST /api/advisor/models/{id}/test：保存最近测试结果，不激活；同上返回结构。
- POST /api/advisor/models/discover：provider/baseUrl/secret/endpointId；返回模型 ID 字符串数组。手动触发；不支持时允许继续手填。
- POST /api/advisor/models/{id}/current：保留现有接口，测试通过才切默认；向量索引不兼容返回 409 与简短 message。
- DELETE /api/advisor/models/{id}：默认或被会话引用的模型返回 409 并说明，其他可删除；前端二次确认。
- 会话选模接口由会话子任务在现有所有权访问控制中实现，只改变对应会话，模型管理列表仍遵循现有权限；不得通过全局 current 接口实现。
- 错误保持 HTTP 400/403/404/409/502 与 {message}；可用的凭据绝不出现在响应中。

## Task 1: 模型管理服务（并行子任务）

文件：backend/model/ModelCatalog、AdvisorModelController、OpenAiEndpointClient、ModelProbe，新模型管理测试；数据库字段由父任务集中添加。
- [x] RED：新增不切默认、编辑保密且失败回滚、真实测试与发现、最近测试持久化、默认/引用模型删除保护、向量切换保护。
- [x] GREEN：按上述契约扩展服务；维持现有 Endpoint/EndpointDraft record 构造兼容。
- [x] 验证：模型相关 Maven 测试；受影响 Java 文件 Spotless。

## Task 2: 会话选模隔离（并行子任务）

文件：会话控制器/存储/运行上下文、SelectedModelMiddleware/SwitchingChatModel，以及 ModelPicker 与调用链、相关测试。勿改模型管理文件及共享词典，新增文案交父任务。
- [x] RED：A 会话选模不改变全局/B；运行固定模型；权限与失效模型处理。
- [x] GREEN：选模持久化、请求初始化绑定、前端传入会话标识。
- [x] 验证：后端会话/模型定向测试，前端选模与相邻会话测试。

## Task 3: 设置页面与抽屉（父任务）

文件：frontend/api/model.ts、features/settings/SettingsPage.tsx、components/ModelEditorDrawer.tsx、modelProviders.ts、测试及中英文词典。
- [x] RED：列表筛选显式提交；新增不切默认；测试反馈与草稿失效；编辑保留密钥；关闭确认；默认/删除确认与错误反馈。
- [x] GREEN：复用 DataTable、AppSelect、Radix 对话框；窄屏全宽抽屉，滚动内容固定底栏；连接参数默认折叠；模型建议+手输与主动获取列表。
- [x] 验证：定向 Vitest，build，lint，Playwright 真实交互及截图检查。

## Task 4: 集成、文档与审查

- [x] 补齐增量 schema，整合会话文案。
- [x] 更新 docs/v2 EN/ZH 模型设置与会话选择说明。
- [x] 运行受影响后端测试与格式检查、前端测试/build/lint、定向 E2E。
- [x] 按 requesting-code-review 做只读审查，修复实际问题；报告本次通过证据与无关基线失败。


## 集成检查记录

- 已完成代码只读审查与修复：Anthropic root/v1 地址规范化，OpenAI reasoning 模型探测参数兼容。
- 浏览器测试覆盖连续保存再配置的焦点恢复问题、桌面/390px抽屉、保存不激活、测试失效和默认二次确认。
- 供应商协议使用本地 HTTP fixture 验证，未使用真实外部密钥。

- 最终结果及门禁边界见 `technical-advisor-app/docs/model-settings-results-2026-10-15.md`；后端全量有1项无关异步计数时序失败，单独复跑通过，不标记该次全量为通过。
