# 知识库模型配置与整篇异步处理交付记录

日期：2026-10-16。方案：`docs/superpowers/plans/2026-10-16-knowledge-ingest-diagnosis-and-atomic-publish.md`。用户授权立即实施，本记录区分源码、自动化验证和当前运行环境事实。

## 已实现并更新本地后端

- 现有聊天模型配置抽屉增加“用于知识库 PDF 解析”。保存前用随机六位数字图片做真实识别探针；探针失败不修改角色、不保存失败模型。唯一角色存于 `advisor_model_roles`，与模型保存同一事务；界面切换动态生效，不更改默认聊天/向量模型。非聊天模型不能承担该角色；模型删除通过外键清空角色。保存时的图片请求/替换行为在抽屉提示。
- PDF 解析/页面复核读取该角色；未指定时兼容旧环境变量。预览请求 `GET /api/advisor/knowledge/pdf-model`，显示实际模型名，未配置时禁用复核。网络输入为单页图及问题，不是向量问答。
- 文档处理改为有界 ThreadPoolExecutor，默认 2 工作线程、32 排队位，空闲 60 秒回收。总期限 20 分钟且包含排队；取消/超时/关闭取消实际模型等待并收敛状态，关闭时清理执行器和定时任务。每阶段写入 queued/parsing/embedding/publishing，文档列表展示相应文案。
- 应用知识库模型调用改用 Java HttpClient 的可取消异步调用，连接10秒、单次请求25秒、总预算60秒；网络IO临时故障、408/429/5xx最多2次退避重试，400/配置错误不重试。响应在接收过程中限制1MiB，视觉正文另限256KiB；不会仅超时外层却遗留原HTTP等待。修复取消时 CompletionException 进入 onErrorDropped 的日志问题。
- DOCX 入口传递文件路径给既有 WordReader，不再用 ReaderInput.fromFile 将二进制按UTF-8读；真实POI生成的Word段落与表格回归通过。
- 任一必需PDF页识别失败或不完整则全文failed，新片段不发布；真正空白页允许成功。来源诊断页与已发布来源快照分开，避免重处理失败后给旧片段配上新解析上下文。
- PostgreSQL 最终事务包含向量表创建、向量替换、结构切片、发布页快照、维度及ready状态。模型网络请求在事务外。不再依赖独立PgVectorStore连接先删后写；查询继续复用既有框架Store及payload。首次失败的DDL同样回滚，避免未发布表固定旧维度。
- 重新处理失败保留旧完整可检索版本并显示失败说明；旧partial在启动时转failed，删除其不完整结构切片，向量检索同样按ready过滤。未直接把partial改成ready。
- 删除两套“重试”操作，界面仅保留整篇“重新处理”；旧逐页重试API对有权限调用者返回410并提示使用整篇处理，不继续页级任务。

## 文件范围

相对 `technical-advisor-app/`：

- 新增 `backend/src/main/java/io/agentscope/advisor/model/KnowledgeModelHttp.java`、`KnowledgeEmbeddingModel.java`、`KnowledgeChatClient.java`；修改 `ModelCatalog.java`、`ModelProbe.java`、`AdvisorModelController.java`、`OpenAiEndpointClient.java`。
- 修改 `backend/src/main/java/io/agentscope/advisor/knowledge/` 内 DocumentIngest、DocumentStructureStore、PgChunkIndex、ChunkIndex、KnowledgeCatalog、DocumentTexts、ConfiguredVectorModelSource、PdfModelReader、PdfParser、PdfDocumentController、AdvisorKnowledgeController；新增 KnowledgeModelController。
- 修改 AdvisorConfiguration、AdvisorSchema、application.yaml，复用现有增量建表机制。
- 前端修改 `api/model.ts`、模型抽屉、知识库文档管理与PDF预览、EN/ZH词典和相关测试。
- 并行工作区“思考强度”变更导致构建类型不一致：仅将 `api/account.ts` 的 reasoningEffort 从宽泛string收敛到已有5档联合类型，并补齐 ChatPage 两个测试fixture的high默认值；没有重写该任务的产品逻辑。
- docs/v2 EN/ZH PDF部分按新行为更新。未改动真实账号、密钥、模型选择或默认模型；未提交、推送或启动浏览器。

## 实际验证

只运行受影响的定向验证；已有通过的未改行为没有再跑全仓。以下测试次数有重叠，不能相加当作独立用例数。

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| 第一轮 KnowledgeLibrary/PdfPipeline/PdfModelReader/PdfDocument/PdfAccess/KnowledgeModelHttp/ModelManagement | 67项通过 | `/tmp/knowledge-atomic-backend.log` |
| 收尾改动回归 PgChunkIndex/KnowledgeModelHttp/ModelManagement/PdfPipeline | 46项通过，含3项真实PostgreSQL/pgvector测试 | `/tmp/knowledge-atomic-final.log` |
| 前端模型设置与PDF预览最终回归 | 2文件23项通过 | `/tmp/knowledge-atomic-model-ui-final.log` |
| 文档管理修正文案后回归 | 1文件5项通过 | `/tmp/knowledge-atomic-frontend-fix.log` |
| 文档管理与ChatPage兼容回归 | 2文件7项通过 | `/tmp/knowledge-atomic-ui-compat.log` |
| 前端类型检查与生产构建 | 通过；Vite有大包体积提示 | `/tmp/knowledge-atomic-build-final.log` |
| 定向lint | 0 errors、3条未在本任务清理的已有warnings | `/tmp/knowledge-atomic-lint-final.log` |
| 本任务Java格式及定向diff检查 | 通过 | `/tmp/knowledge-atomic-format.log` |
| 后端及依赖打包 | BUILD SUCCESS；`-DskipTests`仅用于已有定向测试通过后的打包，未重复执行测试 | `/tmp/knowledge-atomic-package.log` |

验证内容包括：真实Word二进制段落/表格、两个文档同时进入执行、总期限取消embedding并允许下一份完成、429重试而400不重试、真实慢HTTP整体超时、超大响应接收即取消、PDF模型角色唯一与图片校验门禁、图片探针失败不指定角色、单页失败全文不发布、实际PG批写失败回滚并保留旧版本、首次DDL失败回滚后可以采用不同维度、来源payload跨索引实例回读、统一重新处理入口和模型名显示。图片识别能力采用保存时的真实探针；自动化测试本身使用fake/本地HTTP，不证明任意真实PDF识别准确率。

只读审查发现并修复的两处问题：1MiB检查在完整缓冲后才执行；向量表DDL在独立连接导致首次事务回滚遗留维度。二者均加入以上回归，没有重复派发审查。

## 当前运行环境

18:24:58本地后端新进程PID64710启动完成，端口18080；`/api/auth/me`返回401，前端5173返回200。重启前只读核对11个会话均idle，之后只终止原PID51274，未强杀或重启其他服务。运行日志 `/tmp/knowledge-atomic-runtime.log`，持续PTY会话99726。

启动后只读确认文档状态为5份ready、4份failed，没有parsing或partial。文件仍保留，未自动重跑文档、未自动调用收费模型。当前knowledge_pdf角色为空，尚需用户在模型抽屉选择实际支持图片的聊天模型并勾选保存；不会把未验证的现有chat或embedding自动设成视觉模型。

真实pgvector测试使用独立端口55438，测试完成后已经停止。应用正式数据库5432未停止。没有做浏览器现场验收或扫描语料精度评测。

## 用户下一步

刷新页面 → 模型配置抽屉勾选“用于知识库 PDF 解析”并保存通过图片验证 → 对失败文档点统一的“重新处理”。Word修复已生效，可直接重新处理；含扫描/图表页面的PDF应先指定视觉模型。测试原生/扫描/混合三个等价PDF时仍建议分库，避免更容易的副本替代扫描件回答。
