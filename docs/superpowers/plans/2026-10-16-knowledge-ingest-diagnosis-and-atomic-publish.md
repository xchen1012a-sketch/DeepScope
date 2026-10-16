# 知识库处理阻塞排查与整篇发布方案

日期：2026-10-16，Asia/Shanghai。仅排查及方案，没有修改业务代码、配置或文档状态，没有重启服务或调用收费模型。

## 已取得的现场证据

当前后端进程 PID 51274，17:44:24 启动。使用只读 PostgreSQL 查询和一次 jcmd Thread.print 定位；线程快照 `/tmp/knowledge-ingest-diagnosis-20261016-threads.txt`。

1. 17:55 左右第一次查询中，9 个上传文档均为 parsing，6 个通用文件 page_count=0；3 个 PDF 均已完成 9 页解析。故不能把 parsing 一概理解为还在解析正文。
2. 唯一处理线程 pool-4-thread-1 的栈为 SSL 握手读取 → OkHttp → OpenAI SDK EmbeddingService.create → OpenAITextEmbedding 的 Mono.fromCallable → DocumentIngest.process 第100行。现场没有数据库锁等待；查询到的业务连接均 idle。
3. DocumentIngest 使用 newSingleThreadExecutor。所有文件上传即记 parsing，排队与正在解析/正在向量化不区分；一份文档网络阻塞让后续全部显示处理中。
4. OpenAITextEmbedding 内部同步调用 client.embeddings().create，在订阅线程执行；未配置本次任务要求的显式网络连接、读取、整次调用期限。DocumentIngest 外层 block(Duration.ofSeconds(90)) 无法可靠中断在订阅阶段同步阻塞的网络调用。没有整篇任务截止、心跳或运行时过期收敛；只有启动时 failInterrupted。
5. 排查期间任务继续推进，后一次读取为：5 个通用文本文件 ready；混合 PDF partial（92 个已发布切片）；扫描 PDF failed；Word failed；原生 PDF parsing（用户触发重新处理后的 updated_at 为17:53:10）。所以并非9份永远不动；是长等待与队头阻塞，且失败原因不清。不得保证这些易变状态仍是之后时刻的状态。
6. Word 当前路径 WordReader.read(ReaderInput.fromFile(file))；ReaderInput.fromFile先Files.readString把二进制DOCX当UTF-8读。该入口不正确，PDF专用读取已绕过同类问题，但DOCX尚未修正。当前failure仅为“向量化没有完成”，错误归因被 reason() 抹平；本轮没有重跑该Word文件或伪称拿到其原始运行日志。

## 模型配置与实际职责

只读查询确认当前embedding为 `doubao-embedding-vision`，2048维；chat为 `deepseek-v4.1-flash`。两者配置地址均为 `https://ark.cn-beijing.volces.com/api/coding/v3`。

已查最新官方页面 https://docs.volcengine.com/docs/ark/coding-plan-personal-embedding-memory?lang=zh ，明确Coding Plan支持 `doubao-embedding-vision` 和此OpenAI兼容地址，不能仅凭名称认定必须切到普通按量计费多模态接口，更不能无依据替换模型/密钥。现场阻塞发生在TLS握手，未取得远端错误响应，不能判定为额度不足、参数不支持或供应商服务故障。

“模型复核本页”的链路：PdfDocumentController.verify → KnowledgeSearch.verifyPage → PdfModelReader.verify → ask(visionEndpoint, question, ImageBlock) → ChatModelSource.forEndpoint。必须显式配置支持图片的chat大模型，配置项 `advisor.knowledge.pdf.vision-endpoint-id`，环境变量 `ADVISOR_PDF_VISION_ENDPOINT_ID`。

启动命令、本地附加配置与该进程相关环境变量均未设置视觉模型ID。持久化页记录也明确出现“此页需要视觉识别，请配置 PDF 视觉模型后重试”。当前点击复核会得到未完成/配置错误，既不调用embedding，也不自动调用当前chat。即使embedding名字含vision，它仍只返回向量，不能承担文字转录和问答。

## 修复方案

### P0 让每次处理可靠结束

- 在真实HTTP客户端配置连接、读取、整次请求的明确期限；减少并约束SDK与应用双层重试，统一总预算。使用可取消的网络调用/异步订阅，不能只包一个超时Future而遗留失控请求。
- 设每文档总体截止及最后进度时间；到期结束为failed并取消底层请求。失败释放执行槽，避免所有后续任务长期等待。
- 队列有界，有限并发，不无限堆积；UI区分排队、解析、向量化、入库阶段与真实进度。过载明确拒绝；任务丢失、进程重启或异常退出后有恢复/过期收敛。
- 修正DOCX二进制读取入口，保留真实段落和表格；按阶段返回可处理的原因（读取失败、视觉模型缺失、网络超时、向量化失败、索引提交失败），日志用文档ID/阶段/脱敏错误，不能输出正文或密钥。
- 文本只需向量模型；扫描/复杂图文PDF在开始处理前校验视觉模型配置，缺少则明确失败，不能先花时间向量化一部分再当全文成功。

### P1 按单文档执行整篇成功或失败

采用用户最新要求，替代此前partial与逐页重试设计：页面结果可以作为内部临时进度/诊断，不是可检索发布结果。任一必需页解析失败、明确不完整或向量化/入库失败，则整个文档失败，所有新片段不可检索。真正空白页可以正常完成，通用“模型转录未经人工核验”提示本身不等于处理失败，不应机械地令所有模型输出失败。

长耗时解析和模型请求不放在数据库长事务内。先生成完整的候选版本（正文、来源、结构与向量），全部完成后在短事务中发布有效版本指针；所有检索分支（向量、关键词、相邻上下文）只读取有效版本。失败删除/过期清理暂存版本，不能遗留可检索的半成品。

当前PgChunkIndex通过独立PgVectorStore连接先删再写，不是与DocumentStructureStore同一事务。若不升级为版本化发布，不能声称“已通过一个JDBC事务保证正文与向量全原子”。需要让向量记录和结构均带版本，或复用同一数据库连接做最终发布，并验证故障回滚。

默认以“一份文档”为事务边界，多文件上传各自成功/失败，一份损坏文档不撤销其他文档；不默认把一次上传的9个文件绑定成全批事务。重新处理失败时保留旧的完整有效版本并说明新版本失败，不能先删掉旧索引。

移除“重试未完成页面”和单独“重试”入口，取消partial对外可检索状态。保留一个统一的“重新处理”操作（整篇重跑），失败状态显示具体原因。这不是把异常当成功，也不是要求失败后只能删除重传。已有partial文档不能直接改为ready，需标为需重新处理并撤销其不完整发布可见性。

### P2 页面复核入口

它是入库后的额外视觉问答，不能修复上传解析或修改入库状态。如保留，界面显示实际视觉模型名称与配置情况，未配置时禁用并明确提示，不能把配置失败藏在点击后的泛化错误里。若知识库页面只承担资料管理，可移除“针对本页的问题/模型复核本页”，将问答留在会话，页面预览只保留原文与解析对照。此项尚未替用户修改。

## 必要验收

## 已授权实施细化

实施已完成并于18:24:58更新本地后端。验收及当前状态详见 `technical-advisor-app/docs/knowledge-ingest-atomic-results-2026-10-16.md`；不是将前述诊断时刻的状态当作当前状态。PgChunkIndex最终使用同一个发布事务创建表与写入，不再通过另一连接初始化；来源页单独保留发布快照。没有自动重跑整批文件或自动指定未经验证的模型。

用户已授权立即实现。应用内接口增量：模型保存请求/响应新增 knowledgePdf 布尔值，只允许 chat，启用前发送随机图中文字识别探针并验证真实图像理解（不按模型名猜能力）；同一时刻仅一个PDF模型。模型配置动态持久化，PdfModelReader按每次调用解析指定模型，不依赖重启；抽屉开启时明确显示替换当前PDF模型提示。PDF预览增加实际模型信息，未配置禁用复核。

处理池默认2工作线程、32排队容量，线程空闲60秒回收，文档20分钟总期限、单次模型请求60秒，临时网络/429/5xx最多重试2次并退避。进度写入stage（queued/parsing/embedding/publishing）及updated_at。全篇错误立即失败，不再发布partial；历史partial在启动迁移为失败，保留诊断页面但禁止检索。整篇“重新处理”保留，删除逐页/失败两套重试入口。

最终发布复用同一PostgreSQL JDBC事务写向量表、结构切片、维度与ready状态，不新增向量框架；PgChunkIndex增加应用内部事务连接写入能力，旧replace调用也改为事务。模型网络调用在事务外。重处理保留旧完整切片，失败回退旧ready状态并记录失败说明。DOCX用已有POI二进制读取，不改框架ReaderInput公共接口。

仅定向覆盖：慢连接/同步阻塞能真实超时并释放槽位；后续文本继续执行；真实DOCX可解析；未配视觉模型快速失败；单页失败整篇不可检索；向量写入中途失败不发布；重新处理失败保留旧完整版本；关键词/向量/上下文均不能读暂存版本；UI不再有两套重试。没有用重复全量回归代替上述行为证据，不启动浏览器。
