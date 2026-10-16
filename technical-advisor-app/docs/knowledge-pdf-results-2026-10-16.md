# PDF 知识库实现与验证记录

日期：2026-10-16。实施依据：`docs/superpowers/plans/2026-10-16-knowledge-pdf-retrieval.md`。本记录只描述实际源码、运行结果及尚未验证的能力。

## 实现结果

- 修复真实 PDF 在 `ReaderInput.fromFile` 被按 UTF-8 读取而失败的入口。应用 PDF 专用路径直接使用已有 PDFBox，不修改框架 PDFReader 公共契约。
- 逐页提取原生文字/位置、物理页序号、PDF 页标签，区分 ready/blank/review/failed；扫描、图文、内联图片、Form 和复杂布局进入视觉解析或明确提示未完成。页旋转坐标映射到原图；非常规文字方向不伪造位置。
- 视觉解析使用显式配置的已保存 chat 端点与真实 ImageBlock 消息；不回退默认聊天模型。JSON、坐标、块类型、截断/过滤、响应大小和调用时间有校验。模型转录和图表解释分开标记，不声称已经人工验证。
- 页结果及结构切片写入 JDBC 表；页级进度和 partial 状态可见；只重试失败/复核页并重建索引。删除级联清理结构，发布通过文档行锁避免删除后回写。
- PgVectorStore payload 保存来源并支持新实例回读；旧索引没有页码时不补猜。中文/编号关键词候选与向量候选按 RRF 融合，按实际 recallLimit 返回；可选模型重排只接受完整候选排列，不允许改写原文。
- API 提供页面分页、单页结构、认证原图、真实文件信息、失败页重试与用户主动页面复核。校验文档所属库和访问权限；页面复核沿用查看/检索权限，重试沿用编辑权限。
- 前端复用既有 Dialog/Button/DataTable，提供定高原图/解析对照、位置高亮、页码切换、partial 和进度、失败页重试。图像经认证请求生成 Blob URL，切页及关闭释放。来源链接打开对应物理页。EN/ZH 文案和应用文档同步。

## 主要文件

以下路径相对 `technical-advisor-app/`：

- `backend/src/main/java/io/agentscope/advisor/knowledge/PdfParser.java`、`PdfPageGraphics.java`、`PdfContent.java`：PDF 读取、绘制检测和结构切片。
- 同目录 `PdfModelReader.java`：视觉转录、严格响应校验、可选重排/页面复核。
- 同目录 `DocumentStructureStore.java`、`KnowledgeTerms.java`：页及切片持久化、关键词候选与有限邻接上下文。
- 同目录 `DocumentIngest.java`、`KnowledgeSearch.java`、`ChunkIndex.java`、`PgChunkIndex.java`、`MemoryChunkIndex.java`、`KnowledgeRetrievalTool.java`、`DocumentTexts.java`、`KnowledgeCatalog.java`：入库、融合、来源传递及兼容。
- 同目录 `PdfDocumentController.java`、`AdvisorKnowledgeController.java`；上级 `AdvisorConfiguration.java`、`db/AdvisorSchema.java`、`access/WorkspaceAuthorization.java`：API、配置、增量建表及权限。
- `frontend/src/features/knowledge/components/PdfPageDialog.tsx`、`DocumentManagerTab.tsx`、`RetrievalBenchTab.tsx`、`LibraryDetailPage.tsx`、`features/knowledge/types.ts` 及 i18n EN/ZH 字典：交互与来源展示。

其他任务原有的未提交改动保留，没有提交、推送或启动浏览器，没有重启现有应用服务。

## 实际验证

为响应用户“不要过度验证”，完成已发现问题后只执行一轮最终定向验证，没有扩大整仓回归；最终结果如下。

| 范围 | 实际结果 | 日志 |
| --- | --- | --- |
| 知识库：KnowledgeLibraryTest / PdfPipelineTest / PgChunkIndexTest / PdfModelReaderTest / PdfDocumentTest / PdfAccessTest | 33 项通过，0 跳过 | `/tmp/knowledge-pdf-backend-final.log` |
| 相邻 WorkspacePermissionTest | 5 项通过 | 同上 |
| 相邻 ManagementPaginationTest | 6 项中 4 通过、2 失败，见下文 | 同上 |
| 本次涉及的 Java 文件 Spotless | 最终 test 生命周期格式检查通过；只限定知识库、AdvisorConfiguration、AdvisorSchema、WorkspaceAuthorization | 同上及 `/tmp/knowledge-pdf-format-final.log` |
| 前端知识库与 i18n 定向测试 | 8 个文件，46 项通过 | `/tmp/knowledge-pdf-frontend-final.log` |
| 前端类型检查及生产构建 | 通过；Vite 提示已有大包体积警告 | `/tmp/knowledge-pdf-build.log` |
| 前端 lint | 0 errors，46 warnings；未为本次任务清理无关警告 | `/tmp/knowledge-pdf-lint.log` |

后端最终命令总体 **exit 1**，不能称为后端整体验证通过：共 44 项，42 通过、2 失败。两处失败是本次没有修改的工具/技能业务分页断言：

- `ManagementPaginationTest.toolSearchPermissionAndOwnershipAreAppliedBeforePaging`：管理员总数期望 4，实际 2。
- `ManagementPaginationTest.skillStatusAndOwnerAreFilteredInSql`：管理员总数期望 2，实际 1。

本轮未改变 ToolCatalog/SkillCatalog 的查询和这两处断言，也未以弱化断言让检查通过。其根因未在本次 PDF 任务中进一步调查，需由对应任务处理，不能凭本记录认定其原因。

PgChunkIndexTest 使用本轮独立临时 PostgreSQL 17 + pgvector，验证来源 payload、chunkId、坐标、方法/警告在新索引实例下回读一致，以及删除行为。端口 55438；测试数据库已关闭，不影响应用数据库。测试模型是 fake，不会调用收费外部服务。

最终命令（保留退出码，没有将 Maven 输出管道到 tail）：

```bash
ADVISOR_TEST_PGVECTOR_URL=jdbc:postgresql://127.0.0.1:55438/postgres \
ADVISOR_TEST_PGVECTOR_USER=pdf_test ADVISOR_TEST_PGVECTOR_PASSWORD='' \
mvn -B -T1 -pl technical-advisor-app/backend -am test \
  -Dtest=PdfDocumentTest,PdfModelReaderTest,PdfPipelineTest,PdfAccessTest,PgChunkIndexTest,KnowledgeLibraryTest,WorkspacePermissionTest,ManagementPaginationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true \
  '-DspotlessFiles=.*/knowledge/.*\.java|.*/AdvisorConfiguration\.java|.*/AdvisorSchema\.java|.*/WorkspaceAuthorization\.java'

# 在 technical-advisor-app/frontend 执行
npm test -- src/features/knowledge src/i18n/bodyLocalization.test.tsx
npm run build
npm run lint
```

## 配置与实际能力边界

- 扫描页识别需要 `ADVISOR_PDF_VISION_ENDPOINT_ID`，值为模型管理中真实支持图片的 chat 端点 ID。凭据沿用 ModelCatalog，未配置时明确记录需要视觉识别。
- 可选 `ADVISOR_PDF_RERANK_ENDPOINT_ID`；默认使用 RRF。默认最大 300 页、图片最长边 2000，可通过 `ADVISOR_PDF_MAX_PAGES` / `ADVISOR_PDF_MAX_IMAGE_EDGE` 调整。
- 本轮证明的是文件解析、存储、检索、权限与交互链路；没有使用真实扫描件语料和真实视觉模型评测识别准确率、检索召回率或引用准确率。不能把 fake 的返回当作实际 OCR 精度证据。
- 原生布局/复杂页判定是启发式，未达到托管文档解析服务的版面识别覆盖度。模型位置/转录仍需复核。不会猜测跨页表格合并，也没有通用跨表计算引擎。
- 关键词 SQL 当前在选中库的结构切片范围内扫描，数据量大时需性能专项评估；未声称已拥有中文全文索引。
- 数据变更增量兼容，旧文档不会自动发起收费解析；主动重新处理后才有来源页。尚未部署/重启，因此当前正在运行的后端不会因单元测试自动更新。

## 只读审查

按 requesting-code-review 技能进行了一次只读审查，确认并修复：不确定 blank 被误计成功、内联图片漏识别、旋转页坐标错误、无相邻上下文时缺原页链接、扩大候选后 recallLimit 失效，以及页面复核的查看权限不一致。对应回归已包含在上述最终测试中。没有再次派发重复审查或运行浏览器。
