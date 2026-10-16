# 工具测试说明布局、AI 填参与错误诊断计划

- 复用 ToolTestPanel（HTTP/MCP 共用）和既有模型辅助调用。工具说明置于固定高度滚动区，长文不挤压参数区。
- 新增 `POST /api/advisor/tools/{toolId}/test-input`，权限为 tool.test + 工具可见性，服务端只按已保存工具的名称、说明和 schema 生成 JSON 参数，不执行工具、不传凭据，不接受客户端替换 schema。返回 `{input: object}`；404 不可见、403 禁用、422 schema 无效、502 模型/生成参数失败。
- AI 填参使用当前配置模型、30 秒超时、输出长度限制和既有参数 schema 校验。失败抛 ToolProblem，前端显示错误且保留原参数；不静默用伪造参数替代 AI。调用数据作为非可信数据与系统指令分离。
- 前端增加 AI 智能填入按钮，期间锁定输入及运行按钮。关闭/切换工具取消请求并隔离旧响应，完成后填入格式化 JSON，用户仍须点击测试。
- 修正普通模板对所有类型填空字符串的问题：优先 default/example/enum/const，保留值类型，仅对需要填写的字段建立模板。验证 JSON 顶层必须是对象。
- 复用现有测试 POST 与目标工具 method 分离机制，不把测试接口改成 GET。数据库只读确认 get_current_temperature 是 HTTP GET，对应 Open-Meteo，经纬度 number 与 current 枚举参数均映射 query。
- 真实 400 来源需依据响应判定，不用猜测替换方法。通过本地可控 HTTP 服务证明 POST 测试入口按工具定义发出 GET/query，异常/远端 HTTP 错误与 MCP errorCode 在 UI 清晰显示；保留原始已脱敏结果供诊断。
- 沿用测试执行审批、停止、运行 ID 去重、凭据脱敏和后端权限。新一轮普通测试生成新 runId，确认续用原 ID。
- 只运行相关 ToolTestPanel、AI 服务与工具控制器/HTTP 映射定向测试，以及受影响模块格式和类型检查；不启动浏览器，不启停用户后端，不提交。同步 EN/ZH 文档和真实诊断边界。

## 实施结果与验证边界

- 已实现固定高度说明区、AI 智能填参、生成期间输入锁定、关闭后的旧响应隔离及失败提示；HTTP 和 MCP 复用同一测试面板。
- 已复现 400：前端未传可选 `confirmed`，后端基本类型 `boolean` 在当前 Jackson 配置下反序列化失败，尚未进入工具执行。改为 `Boolean` 并仅接受显式 `true` 授权，前端首次测试明确发送 `false`。未修改全局解析配置。
- 本地 HTTP 服务回归证明 POST 测试入口按定义发出 GET/query；Open-Meteo 真实只读 GET 返回 200。未对运行中后端部署或执行测试入口。
- 前端最终定向测试 10/10 通过；此前构建通过，lint 无错误（46 条既有警告）。后端定向测试 17/17 通过（AI 服务 3、测试服务 12、控制器 2）。格式检查与 `git diff --check` 通过。
- 随后补充拒绝尾随 JSON 内容的校验与用例，最终后端复验被知识库 `DocumentStructureStore.java` 的两处编译错误阻断（`JdbcPage.Query.parameter` 不存在及 `ChunkIndex.Hit` 构造参数不匹配），未运行到测试；不将此前通过结果作为该最后改动的运行证明。
- AI 流程使用测试模型验证，未进行真实模型供应商联调；未启动浏览器、全量测试或启停后端。代码生效需重新构建并由用户重启后端。
