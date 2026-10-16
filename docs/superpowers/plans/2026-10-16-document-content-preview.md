# 所有知识库文档的查看内容入口

已批准：所有格式提供“查看内容”；PDF 复用原页对照，Markdown/HTML/DOCX/TXT 使用相同 Dialog 外壳，固定高度、内部滚动，不虚构页码，不调用模型。

非 PDF：读取受限大小的上传文件，Markdown 格式化、TXT 保留换行、HTML 只渲染白名单标签且不复制属性或执行脚本；DOCX 复用 WordReader 的正文/Markdown 表格提取，明确不是 Word 原版式。另提供“入库片段”，服务端分页读取已发布的 advisor_document_chunks，不重新解析或嵌入。空结果明确展示，不把未入库文档伪装成已就绪。

API 在原文档 Controller 增加 content/chunks，复用知识库可见性和文档归属校验；PDF 原有 pages/image/verify 接口继续仅允许 PDF。文件读取最多 25MiB，预览文本最多 1MiB UTF-8，超限显式报错；无外部请求、新依赖或数据库改动。

验证限于文档访问/越权、实际片段分页、预览格式、安全 HTML、列表入口的定向用例，以及前端 build/变更文件 lint、后端 Spotless/定向测试。不启动浏览器、不自动重新处理文档、不重启正在解析的后端。
