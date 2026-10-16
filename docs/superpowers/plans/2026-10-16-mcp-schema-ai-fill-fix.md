# MCP 参数结构与 AI 填入修复

只读数据库确认 query-docs 和 resolve-library-id 的 input_schema 保存为 SDK JsonSchema.toString 文本，而非 JSON，导致 AI 填参与测试在模型调用前校验失败。修复发现源头为 ObjectMapper JSON 序列化。既有异常记录仅在使用时从所属同用户且已接入的 MCP 重新读取真实 schema，供 AI 与测试共用；不解析不可靠的 toString、不以空对象降级、不自动执行工具或批量改数据库。后续主动同步可按既有差异确认流程保存正确 schema。

按用户后续要求移除个人中心“我的资源”整个页面和导航，旧地址回资料页；工作空间资源与工具认证配置保留。AI 智能填入按钮仅显示文字，生成期间保留禁用与状态文字。

定向回归验证 SDK schema 序列化、旧记录只读恢复/失败、校验类型与模型错误、前端按钮与账户资源展示；不启动浏览器、不启停服务、不使用真实密钥。

实施结果：序列化回归先复现 JsonParseException，修复后 FrameworkMcpToolSourceTest、ToolTestInputServiceTest、ToolTestServiceTest 共17项通过，含旧记录从真实发现数据恢复、拒绝跨用户/禁用服务器及原始记录保持不变。前端工具面板10项通过，个人中心19项通过（移除页面后的旧测试已更新为重定向和无资源请求）。构建、scoped Spotless、定向 ESLint（0错误）和 diff 格式检查通过。日志 `/tmp/mcp-schema-red.log`、`/tmp/mcp-schema-ai-green.log`、`/tmp/mcp-ai-account-front.log`、`/tmp/mcp-ai-account-front-final.log`、`/tmp/mcp-ai-account-build.log`、`/tmp/mcp-ai-account-lint.log`。未验证真实模型或远端MCP往返，未重启服务，需重新构建重启后生效。
