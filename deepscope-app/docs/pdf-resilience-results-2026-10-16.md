# PDF 解析质量与异常兜底修复记录

## 已实施

- 完整性与定位说明分离。普通坐标估算不再误判整篇失败；区域坐标无效时保留正文和物理页，去掉无效区域。内容缺失、无法辨认、未知警告、明确 complete=false 仍阻止整篇发布。旧自由文本仅对整条已知纯定位句式豁免，混合缺失描述不可绕过。
- 新提示支持 complete、issues 和结构化表格列头/行；校验各行列数，保留空值、原始字符串，转义竖线后生成 Markdown。兼容旧表格文本；不补猜数值、合并关系或跨页内容。
- JSON/字段契约错误仅从同一原图重新请求一次，仍错误则终止。只剥离完整代码围栏，不修补截断 JSON。网络故障不叠加格式补发。
- `ADVISOR_PDF_JSON_OUTPUT` 默认 false，仅确认端点支持后启用 OpenAI 兼容 JSON 模式；不根据模型品牌推断代理端点能力。
- 共用知识库 HTTP 层保留原有限退避和超时预算，增加安全 HTTP 分类和供应商请求编号；401/403/400 不作网络重试。页错误保留安全原因，取消立即传播，不吞成普通页失败。
- 每次文档处理带 attempt/document 上下文，页失败记录页码、模型、错误类别、请求编号、耗时和异常类型，不记录上游响应正文/凭据。文件日志按 10MB 滚动，历史 7 天、归档上限 70MB。
- 新增 `advisor_documents.parse_signature`，以文件路径/长度/修改时间、协议、渲染及模型端点版本识别内部 checkpoint。只复用同配置成功页；每页保存前、解析后、向量化后发布前校验一致性，变化先使缓存签名失效再拒绝发布。
- 继续整篇原子入库，任何必需页面未完成都不发布半成品；重新处理失败保留上次完整版本。页面错误及文档失败摘要使用现有字段，无新增逐页重试入口。

## 主要文件

`knowledge/PdfPageOutput.java`、`PdfModelReader.java`、`PdfParser.java`、`DocumentIngest.java`、`KnowledgeCatalog.java`；`model/KnowledgeModelFailure.java`、`KnowledgeModelHttp.java`、`KnowledgeChatClient.java`、`ModelCatalog.java`；`AdvisorSchema.java`、`AdvisorConfiguration.java`、`application.yaml`。保留同文件其他任务既有更改。中英文使用说明同步更新。

## 验证证据

- 第一次定向执行：PdfPipelineTest 10、PdfModelReaderTest 15、PdfDocumentTest 9、KnowledgeModelHttpTest 3，共 37 项通过。日志 `/tmp/pdf-resilience-test.log`。
- 增加取消传播、JSON模式显式开关测试后：相关两类 15 项通过并打包。日志 `/tmp/pdf-resilience-package.log`。
- 一次只读审查发现定位描述混合缺失、配置变化缓存未清空两项问题；新增回归实际复现 2 项失败，修复后通过。RED 日志 `/tmp/pdf-resilience-review-red.log`。
- 最终受影响三类：PdfPipelineTest 11、PdfModelReaderTest 16、KnowledgeModelHttpTest 4，共 31 项，0 失败/错误/跳过；Spotless 和 package 成功。日志 `/tmp/pdf-resilience-final.log`。连同此前 PdfDocumentTest 的 9 项，本次共覆盖 40 个不同用例；没有重复全仓或浏览器回归。
- 两项审查问题已由同一只读审查者复核收口。

## 本地生效与限制

更新前查询：运行会话 0、处理中 PDF/其他文档 0。已正常关闭旧后端 PID 73015，新后端 PID 83748 于本地 2026-10-16 19:23:48 启动，监听 18080。启动日志 `/tmp/pdf-resilience-runtime.log`，持久日志默认 `${advisor.data-dir}/logs/backend.log`。

没有自动重新处理三份用户 PDF，没有以真实模型调用成功或 OCR 准确率作为本次完成依据。07A 已证实的定位备注误判已修复；07B/07C 历史具体异常未被旧日志保留，不能虚构其根因。重新处理后若仍失败，可从安全错误类别及持久日志继续定位。未保证供应商永不超时或模型绝不漏识别。
