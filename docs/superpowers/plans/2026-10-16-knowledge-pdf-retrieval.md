# PDF 逐页解析与精准检索技术实施方案

> 执行方式：按任务顺序实现、测试和复核。用户已授权先落技术方案再编码；保留工作区其他任务的改动，不提交、不重启服务、不启动浏览器。

**Goal:** 普通、扫描及混合 PDF 按页解析，保留结构与来源；检索可追溯到原页，不能把漏页、模型猜测当作成功结果。

**Architecture:** 在 Technical Advisor 知识库内增加 PDF 专用解析与结构存储，复用现有 PDFBox、模型消息、JDBC 和 PgVectorStore。向量检索与中文/英文关键词候选做 RRF 融合；可选模型重排。现有框架 PDFReader 公共契约不变。

**Tech Stack:** Java 17、PDFBox（rag-simple 已传递引入）、Jackson、Reactor Model/ImageBlock、PostgreSQL/pgvector、React/TypeScript、TanStack Query、现有 Dialog/DataTable/i18next。

**Spec:** 本文实现用户已认可的“逐页分类、多模态处理难页、结构化分页、混合检索、来源定位”方案。

## 1. 已核实的现状

以 2026-10-16 当前工作区源码为准：

| 文件（相对仓库根目录） | 实际行为 | 本次调整 |
| --- | --- | --- |
| `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/knowledge/DocumentTexts.java` | PDFReader 返回字符串切片 | 保留非 PDF 路径；PDF 走专用解析 |
| `agentscope-extensions/agentscope-extensions-rag/agentscope-extensions-rag-simple/src/main/java/io/agentscope/core/rag/reader/PDFReader.java` | PDFBox 提取各页后拼接；未保留页码；图片提取为占位能力 | 不修改框架公共接口 |
| `knowledge/DocumentIngest.java`（下文 backend 路径同上） | 单线程整文档解析、向量化，只有 ready/failed | 逐页进度、结构持久化、partial、失败页重试、关闭资源 |
| `knowledge/ChunkIndex.java`、`PgChunkIndex.java` | Passage/Hit 只有正文、向量/得分 | 增加兼容构造器和来源元数据，写入 PgVectorStore 已支持的 payload |
| `knowledge/KnowledgeSearch.java` | 向量检索，按得分合并 | 关键词候选、RRF、可选重排、来源与有限相邻上下文 |
| `knowledge/KnowledgeRetrievalTool.java` | 工具和时间线只有库名/文件名/分数/段落 | 返回真实页码、位置、来源类型和解析警告 |
| `db/AdvisorSchema.java` | JDBC 幂等建表，无独立 migration 框架 | 沿用幂等建表及 ADD COLUMN IF NOT EXISTS |
| `model/ChatModelSource.java` | 支持按 endpointId 取得已保存 chat 模型 | 显式配置 PDF 视觉模型 ID；不按模型名称猜多模态能力 |
| 前端 `features/knowledge/components/DocumentManagerTab.tsx`、`RetrievalBenchTab.tsx` | 文档表格、处理状态、命中测试 | 进度/部分完成、逐页预览与失败页重试、来源页入口 |

当前没有可证实的 OCR 服务、模型多模态能力标记或重排服务。本次不虚构已有集成，不默认上传原文到任意第三方。管理员显式配置视觉模型后，才通过既有模型端点发送所需页图。

首次真实 PDF 回归测试进一步证实：`ReaderInput.fromFile` 会先按 UTF-8 读文件，当前 `DocumentTexts` 的 PDF 路径因此在进入 PDFReader 前抛 `MalformedInputException`。专用 PDFParser 直接使用 `Loader.loadPDF`，同时修复该入口问题；RED 日志为 `/tmp/knowledge-pdf-red.log`，不是模型模拟证据。

## 2. 约束与边界

- 中级应用功能改造：限制在 Technical Advisor；不改 core/harness 公共 API，不新增 Maven 模块或向量数据库。
- 兼容原文档/检索接口；新增字段及接口，保留原有构造调用。旧切片无来源时显示“页码不可用”，重新处理后才获得真实页码。
- 一页一个解析单元，一页可以有多个语义切片；页码为 1 起始的 PDF 物理序号，印刷页码单独保存（无法确认则空）。
- 模型 OCR 是模型转录，不能等同经人工核验文字。记录 `native` / `vision` 来源及 warnings；模型描述独立于转录，不补齐不可辨认的数值或单位。
- 不承诺本轮完成通用财务计算引擎或任意跨页表格自动合并。表格保留表头/单位、页和结构，跨页相邻上下文可回溯；只有明确结构才能关联，不猜合并关系。
- 精确坐标：原生文字由 PDFBox TextPosition 得到；模型坐标必须校验在页内并标记为模型定位，非精确 OCR 置信度。不伪造识别置信分。
- 默认限制：最多 300 页、单页渲染最长边 2000px、单页模型调用 90 秒、结构响应最多 256KiB、每页最多 500 块。限制可配置；渲染前约束像素，逐页释放图片。256KiB 是解码后响应上限，不宣称底层网络原始字节限额。
- 非 PDF 继续原路径；不启动浏览器，不改任何正在运行的服务。

## 3. 解析与切片

1. PDFBox 打开文件，校验页数、权限和损坏情况。按页读取文字与位置、图像存在情况和印刷页标签。
2. 正文可用且布局简单：原生提取；按行/段落保存矩形区域、阅读顺序。检测空白、替代字符比例、疑似双栏/图文页，不能仅因整个文档存在文字而跳过扫描页。
3. 扫描/乱码页：必须视觉转录。图文/复杂页：配置视觉模型时做结构解析；未配置时保存原生正文并标注需复核，不宣称表格/图表识别完整。确认为空白的页面记录 blank，不要求模型生成内容。
4. 模型输入为当前页图和严格结构提示，输出块类型、原文、表格 Markdown、图表解释、位置和警告。JSON 不合法/输出截断/空响应记该页失败，不吞错为成功。文档中的指令视作数据，不执行工具。
5. 同页使用一个主转录来源，避免 native/OCR 双写重复；native 结果留档用于核对。图表解释独立存储，并在检索输出标注。
6. 切片不跨页硬拼；同页按段落/标题边界分块，保留块 ID、物理页、页标签、区域、方法、解释标记。大表格按行分组，重复表头；超长单行仍有长度上限。
7. 保存每页结果后更新已处理/失败页数。存在可检索正文且有失败/复核页为 partial；全部成功为 ready；没有可用正文为 failed。blank 页不算漏页。
8. 重试只重新解析失败/复核页，再依据全部成功页重建向量索引；强制整篇重新处理仍沿用已有 reprocess 接口。重试前标 parsing，重复请求拒绝；单文档互斥，删除与写入通过数据库文档行锁协调，避免删后回写。

## 4. 数据与存储

沿用 `AdvisorSchema.initialize`：

- `advisor_documents` 增加 `page_count`、`processed_pages`、`failed_pages`（默认 0）。增加状态 `partial`；readyCount 包含可检索 partial，筛选可单独查看。
- `advisor_document_pages(document_id, page_number, page_json)`：复合主键，外键关联文档并级联删除。Page 包含页尺寸/标签、nativeText、状态、method、warnings、blocks。
- `advisor_document_chunks(document_id, chunk_id, library_id, content, search_text, source_json)`：复合主键，外键级联删除。保存精确原文和来源，作为关键词检索/相邻片段扩展依据。
- PgVectorStore payload 保存 `source`；保留原 doc_id/chunk_id；search 还原 source。未知旧 payload 兼容为空，不生成页码。

结构存储与索引发布使用文档行锁保护；只有完成索引发布才改终态。失败时文档不可作为 ready 检索；页级结果保留供重试。索引与业务表无跨连接分布式事务，发布失败必须标 failed，后续整篇重建替换，不能宣称两库原子提交。

## 5. 检索链路

1. 权限继续按当前库/智能体选择约束；再次校验 hit.documentId 所属库和可检索状态。
2. 向量取有限候选，原 `scoreThreshold` 只约束向量分支。
3. 关键词分支：英数词、编号及中文连续字串/双字片段，参数化 SQL 做文本匹配并按覆盖度排序，最多 100 候选；不使用英文分词假装支持中文。不默认创建 pg_trgm 扩展，初版在所选库范围内扫描结构切片，数据量扩大后应专项评估索引性能。
4. 按文档与切片标识去重，RRF `1/(60+rank)` 融合，再受 recallLimit 限制。返回 vectorScore（可空）与 rankingMethod，避免把融合排名分当作余弦相似度百分比。
5. 可选配置 rerank endpoint，模型只能返回候选 ID 顺序，严格校验，不允许生成/改写来源正文；失败退回 RRF 并标注实际方法。
6. 命中后补同页相邻片段和边界相邻页上下文，长度有上限，不替换原命中文本，逐片保留来源。
7. 图表命中可由视觉模型针对当前原页与当前问题复核；结果独立字段，不替换原证据。只处理有限命中页，默认不重复发送整本 PDF。本轮不自动计算跨表总计。

## 6. API 契约与交互

沿用认证过滤器和知识库 visible/editable 校验，隐藏不可见文档，禁止凭 documentId 跨库访问：

| 方法/路径（前缀 `/api/advisor/knowledge/{libraryId}/documents/{documentId}`） | 作用 | 响应 |
| --- | --- | --- |
| GET `/info` | 来源深链接读取真实文件名与页数 | filename、pageCount、documentId |
| GET `/pages?page=1&size=10` | 后端页码分页，不一次返回整本 | items、page、size、total |
| GET `/pages/{pageNumber}` | 单页结构 | Page JSON；无页 404 |
| GET `/pages/{pageNumber}/image` | 原页渲染 PNG | image/png；不可用 422；不携带 token 到 URL |
| POST `/retry-pages` | 重试失败/复核页 | 202；parsing 冲突 409；无编辑权 403/不可见 404 |
| POST `/pages/{pageNumber}/verify` | 用户主动对当前页提问；按知识库查看权限校验，与搜索一致 | verification、method、pageNumber；模型未配置/失败 502；无页 404 |

文档列表新增 pageCount/processedPages/failedPages，旧字段保留。搜索命中新增 libraryId/documentId/source/context/vectorScore/rankingMethod/verification/warnings，原 library/document/passage/score 保留，score 表示实际排名分，不在 UI 中误标相似度。

前端复用现有 Dialog、Button、DataTable：PDF“查看页面”打开定高窗口，左侧原页图、右侧结构文字与警告，页码按钮请求后端；渲染图经带认证 fetch 转 Blob URL，切页/关闭释放。文档表格显示页级进度/partial；允许失败页重试；命中测试提供来源页入口。会话工具结果/时间线保留同样来源；本轮不另造 PDF 编辑器。

只读复核确认的边界纳入实现：实际绘制流检测内联图片与 Form 图像；物理页面旋转 90/180/270 度时转换来源坐标，非常规文字方向不返回未经转换的坐标；模型报告空白但同时给出不确定警告时保留 review，不能计为成功空白；RRF 扩大候选后仍逐库遵守 recallLimit；原页链接每个命中生成一次，不依赖相邻上下文是否存在。分页预览之外，页面复核为用户显式操作，不自动产生额外视觉请求。

## 7. 配置

新增 `advisor.knowledge.pdf` 配置：vision-endpoint-id（空即不调用视觉模型）、rerank-endpoint-id（空即 RRF）、max-pages=300、max-image-edge=2000。显式端点必须存在且 kind=chat；未配置不落回默认聊天模型。凭据继续由 ModelCatalog 读取，日志不输出内容/凭据。

## 8. 顺序任务与验收

- [x] T1 结构类型/分页解析与切片已实现；真实生成的原生、空白、扫描、混合、页数限制、内联图、旋转、表格切片测试通过。真实乱码及复杂版面语料精度尚未评测。
- [x] T2 视觉适配已实现；ImageBlock、JSON/坐标、截断/超限、未配置模型、解释标记、不确定空白页测试通过。90 秒截止有代码限制，未进行真实模型超时/精度验收。
- [x] T3 JDBC 持久化、进度、partial/重试、删除发布锁与兼容构造已实现；级联删除、删后发布保护、真实 pgvector 来源回读测试通过。未进行多进程并发压测。
- [x] T4 关键词/向量融合、scope 校验、recallLimit、重排排列校验、相邻上下文/来源链接已实现并有定向回归；真实外部重排质量尚未评测。
- [x] T5 API、页面对照/定位、进度/partial、重试与来源深链接完成；EN/ZH 同步，前端定向测试、类型检查及构建通过。
- [ ] T6 当前知识库 33 项和相邻权限 5 项通过，格式门禁通过；前端 46 项、lint/build 通过。相邻 ManagementPaginationTest 的工具/技能管理员总数断言有 2 项失败，本次未修改其逻辑，详见结果记录。不能标为后端整体门禁通过，不继续扩大或重复验证。真实扫描识别准确率仍需后续接入真实模型与代表性语料评测。
- [x] T7 一次只读复核发现的 6 项边界已修复并纳入最终回归；结果与配置说明已写入 `technical-advisor-app/docs/knowledge-pdf-results-2026-10-16.md`，docs/v2 EN/ZH 已同步。

测试命令：`mvn -B -T1 -pl technical-advisor-app/backend -am test -Dtest='*knowledge.*,WorkspacePermissionTest' -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true`（具体测试类按新增文件明确列出）；Spotless 仅本任务修改文件以绝对路径正则限定。前端 `npm test -- <相关测试>`、`npm run lint`、`npm run build`。不得将被跳过的外部模型、真实复杂 PDF 评估或部署验证描述成已通过。

## 9. 发布与回滚

本轮只改源码、不部署。数据库变更均增量，回滚应用时保留新增表/列，不破坏旧数据；partial 为新状态，回滚前需要将其显式重处理或标失败，旧应用不能正确展示该状态。旧数据不后台自动收费重解析，由用户主动“重新处理”。视觉端点置空即停止后续视觉请求，不影响原生 PDF。

## 10. 官方参考与适用范围

此前方案已查阅的官方资料（能力属于相应厂商，不表示本项目已集成其服务）：

- Google 文档解析/布局切片：https://docs.cloud.google.com/generative-ai-app-builder/docs/parse-chunk-documents
- Google Document AI 布局解析：https://docs.cloud.google.com/document-ai/docs/layout-parse-quickstart
- Azure Content Understanding（部分能力为 preview）：https://learn.microsoft.com/en-us/azure/search/search-how-to-semantic-chunking-content-understanding
- Azure 混合检索 RRF：https://learn.microsoft.com/en-us/azure/search/hybrid-search-ranking
- AWS 多模态解析：https://docs.aws.amazon.com/bedrock/latest/userguide/kb-advanced-parsing.html
- AWS 分层切片：https://docs.aws.amazon.com/bedrock/latest/userguide/kb-chunking.html

本方案借鉴逐页/结构/融合/引用思路，复用项目已有能力；不声称等同厂商托管解析器的识别精度或吞吐。
