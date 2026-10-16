# 知识库统一内容预览

已实现统一“查看内容”入口，PDF 保持原页对照。非 PDF 复用现有 Dialog、Markdown、Pagination 和权限校验，固定 84dvh 并内部滚动；可切换文档内容/实际入库片段、格式化/原始文本。TXT 保留换行，Markdown 保留普通引用标记，HTML 用惰性 template 解析并仅以 React 重建白名单标签、不复制源属性，DOCX 复用 WordReader 提取正文与表格，不伪造物理页和 Word 排版。

新增 GET 文档 content/chunks；原 PDF pages/image/verify 仍限制 PDF。chunks 复用 JdbcPage，从已发布切片表服务端分页读取，不重新解析、向量化或调用模型。预览 UTF-8 正文限制 1MiB；上传源文件限制 25MiB，错误有明确提示，不暴露存储路径。

定向验证：

- 首先复现非 PDF 入口缺失、content 404，再实现并通过。
- 后端 PdfAccessTest 6 项通过，包含 Markdown/TXT/HTML/DOCX、已发布片段分页、跨用户访问拒绝及超限；Spotless 和 package 成功。日志 `/tmp/document-preview-backend-final.log`。
- 前端 DocumentManagerTab + PDF 预览首轮 9 项通过；补充 HTML 白名单与 TXT/片段测试后 DocumentManagerTab 8 项通过。
- 只读审查发现聊天 Markdown 外部图片请求及引用清理不适合文档预览。新增可选 documentMode，默认不改聊天；文档模式禁图片请求并保留原文标记。图片问题已复现 RED，修复后 DocumentManagerTab + Markdown.references 11 项通过。日志 `/tmp/document-preview-image-red.log`、`/tmp/document-preview-markdown-final.log`。
- 最终前端 build 成功；变更 TSX 文件 ESLint 0 错误/警告。日志 `/tmp/document-preview-build-final.log`、`/tmp/document-preview-lint-final.log`。
- 未启动浏览器、未重新解析文档、未增加依赖或修改数据结构。

运行状态：先等待扫描 PDF 自然结束，确认处理中及运行会话均为 0 后更新后端。新进程 PID 87078 于 2026-10-16 19:34:30（Asia/Shanghai）正常启动并监听 18080；未登录访问认证接口返回预期 401。日志 `/tmp/document-preview-runtime.log`。无需重新上传或重新处理现有文档，前端开发服务可直接加载新入口。

只读审查对 Markdown 两项问题已复核收口；未发现对应修复遗漏。实际页面的浏览器视觉验证未执行。
