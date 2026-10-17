# PDF 吞吐与恢复实施结果

按已批准四步实施，未引入依赖或新解析服务，未提交/推送/重置其他任务更改。

## 实现

1. 向量并发：EmbeddingWork 使用所有文档共享的有界线程池，默认3个请求（ADVISOR_EMBEDDING_WORKERS，1—8），每篇维持有限在途窗口；结果按原顺序汇总，失败取消其余真实订阅。空闲60秒退出，服务关闭清理。当前兼容代理未确认批能力，未盲发多条数组请求。
2. 短块合并：同页/同标题区内合并普通短文本，不跨新标题、表格、图片解释或页面；Source 新增兼容 spans，保留块ID/类型/坐标，检索预览可高亮多个原始区域。原页块不被改写。
3. 页级兜底：视觉单次45秒/总90秒/最多1次瞬态重试，与向量25秒/总60秒/最多2次分开；解析共享最多2个在途调用。适用的整页失败后，只在中部附近连续空白分隔带上尝试一次两区解析，按PDF声明旋转校正图像再映射回原坐标，不递归/不强切；鉴权、限流、取消不触发分区。两区都需通过完整性校验，否则仍失败。
4. 向量检查点：新增内部、不可检索的 advisor_document_embedding_checkpoints。按文件/解析及切片版本、模型身份、维度、内容与来源摘要复用；模型或文件变化拒绝发布。成功向量逐片保存，失败后只补未完成部分。发布成功的同一事务清理内部检查点，删除文档级联清理，失败保留旧完整版本。API新增 embeddedChunks/totalChunks，列表显示已向量化x/y；日志独立记录解析、嵌入耗时、总数/复用量和安全重试类别。

保留已成功页面的原协议签名，因为此次仅为失败页增加兜底，成功页结构不变；不会因升级功能强制将旧成功页再发送模型。切片/向量采用独立的新指纹。

## 真实文件与能力边界

只读渲染确认07C第7页确有90°声明旋转，文字表格侧向；不能据此断言它是供应商超时的唯一原因。分区校正是有限兜底，仍不承诺模型永不超时或无识别错误。官方火山多模态输入不能当作独立样本批处理；此次采用已兼容的单条请求并发，没有假定 coding 代理批处理能力。

未自动重新处理用户PDF、未发额外真实模型请求、未启动浏览器。

## 验证

- 四步均有行为RED及对应GREEN证据：单篇串行不能同时进入3片；6个短块未合并；超时不能分区恢复；失败向量未复用。日志 `/tmp/pdf-throughput-step{1,2,3,4}-red.log`。
- 第一轮完整链路58项通过：PdfPipelineTest14、PdfDocumentTest15（含0/90/180/270°）、PdfModelReaderTest16、KnowledgeModelHttpTest4、PdfAccessTest6、真实独立PostgreSQL的PgChunkIndexTest3。日志 `/tmp/pdf-throughput-regression.log`。
- EmbeddingWorkTest额外2项验证跨文档全局并发、顺序和失败取消；KnowledgeLibraryTest另5项通过。本次覆盖65个不同后端用例。
- 最后package定向31项通过。只读审查发现贴边旋转坐标可能出现微小负数；边界回归先复现失败，再按已有Positions写法clamp到0，最终PdfDocumentTest15项及package再次通过。日志 `/tmp/pdf-throughput-final-package.log`、`/tmp/pdf-throughput-edge-red.log`、`/tmp/pdf-throughput-edge-final-package.log`。
- 前端build通过、局部ESLint无错误或警告。PDF预览/进度等定向9项通过（3项未匹配选择条件）。日志 `/tmp/pdf-throughput-frontend-build.log`、`/tmp/pdf-throughput-lint.log`、`/tmp/pdf-throughput-frontend-targeted.log`。
- 两文件完整前端回归有2项上传旧断言失败：既有断言期待选中文件立即上传，而工作区并行改造已经增加KnowledgeUploadDialog确认流程。本任务未修改上传实现或弱化断言。不得将前端全部测试描述为通过。
- 临时PostgreSQL使用独立55439实例，测试后已关闭；之前尝试复用55438旧实例因角色不匹配，亦已关闭，未修改业务数据库认证。

## 本地生效

更新前确认处理中0、运行会话0，正常关闭旧后端PID1292并启动新包；启动日志 `/tmp/pdf-throughput-runtime.log`。本次数据库结构仅增加检查点及进度字段，不把旧失败文档改成成功。需用户主动重新处理07C等文档后，才能验证真实供应商环境下的最终效果。
