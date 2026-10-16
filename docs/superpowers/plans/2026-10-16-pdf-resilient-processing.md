# PDF 解析质量与异常兜底实施方案

用户授权按官方服务做法适量改造，保持现有单文档原子发布、权限、线程池和HTTP总预算；不引入新服务，不增大重试次数，不自动重复收费解析。

## 契约与边界

1. 新页响应增加 `complete` 布尔值、`issues` 数组（code/severity/message），将内容完整性与展示备注分开；定位估算/阅读顺序说明只展示。明确不可辨认、内容缺失、complete=false均不得发布。旧响应兼容，但未知警告保守待复核，不能凭空假定完整。定位无效可降级为无区域而非丢掉有效正文，仍有真实物理页；完整性失败不能如此降级。
2. JSON输出只对已验证支持的端点启用：应用配置显式开关默认false，不根据DeepSeek名称推断火山兼容端点能力。默认使用严格提示与字段校验；完整代码围栏可剥离，不修补截断JSON或猜数字。格式/契约错误最多补发一次原页加格式要求，网络故障不叠加此修正；所有调用仍受已有任务总期限。
3. 可选结构表格 `table.columns/rows` 由程序校验并生成Markdown，保留空值，不推断跨页关联。旧Markdown表格保留兼容；不以任何模型自报概率作为准确率证据。
4. 共用模型HTTP错误按类别、状态码、供应商request-id脱敏传递；响应正文/凭据不进入日志或用户错误。页失败保留错误码，日志记录attemptId/documentId/page/model/异常类/耗时；取消立即传播，不吞为普通识别失败。文件日志滚动限量保存。
5. 页面checkpoint以不可变上传文件路径、解析协议版本、渲染配置和已选端点updatedAt指纹为键；复用同签名成功页，失败页重跑。模型/文件/协议变化自动失效；任务中签名变化则拒绝发布，避免混用模型。仍只有整篇重新处理入口，不检索半成品。
6. 空白/无效结构/缺失结果、超限、截断、限流、超时、取消均有明确终态；请求失败不清除旧完整索引。缓存不替代整篇发布校验。

## 范围和验证

应用 backend 的 PdfModelReader/PdfParser/DocumentIngest/KnowledgeModelHttp/KnowledgeChatClient，少量类型与checkpoint字段，现有初始化和配置机制；API既有字段保留，新增响应字段兼容。定向RED/GREEN：坐标备注通过、真实缺失拦截、未知警告不绕过、错误坐标降级、结构表格空值/错列、格式一次恢复/二次终止、网络错误不补发、取消传播、同签名checkpoint/签名变化、脱敏HTTP分类。现有页/文档/模型链路测试与格式门禁，不跑全仓或浏览器。部署前确认无活动任务；不擅自重跑用户文档。

## 官方依据（已于本会话查阅）

- AWS状态/警告分开：https://docs.aws.amazon.com/textract/latest/APIReference/API_GetDocumentAnalysis.html
- Google页结构和质量：https://docs.cloud.google.com/document-ai/docs/handle-response
- Azure结构表格：https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/prebuilt/layout?view=doc-intel-4.0.0
- Azure退避/Retry-After：https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/service-limits?view=doc-intel-4.0.0
- DeepSeek JSON模式与截断/空值边界：https://api-docs.deepseek.com/guides/json_mode/
