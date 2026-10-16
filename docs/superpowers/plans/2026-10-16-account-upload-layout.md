# 个人中心与知识库上传布局

已确认：统一浅灰背景、紧凑宽度、白色分组卡片和导航身份概览。点击头像直接选图，移除头像收进更多菜单。安全、偏好、资产、聊天记录沿用真实数据，不增加虚构设备或评分。

所有知识库上传入口先打开共用 Dialog。虚线框包含选择/拖放/粘贴入口、待上传文件及真实上传状态；选中文件先排队，点击上传才发请求，完成后保留在框内，不自动关闭。失败项独立重试，成功项不重复上传；文本粘贴转 TXT。上传成功与解析成功分开。复用现有上传接口和刷新，不改后端。

- [x] 共享个人中心外壳、导航、分组与各页层次。
- [x] 头像直接上传及更多菜单移除。
- [x] 上传弹窗与队列，替换全局粘贴/拖入直接上传。
- [x] 定向账户和上传回归、构建/格式检查，同步 EN/ZH 文档。不启动浏览器、不启停服务、不提交。

验证：个人中心19项、上传hook3项通过（包含虚线框内保留成功文件、失败项单独重试）；知识库页面6项定向回归。前端构建通过，定向ESLint 0错误、2条既有fast-refresh警告；diff格式检查通过。初轮个人中心2项失败分别为角色重复显示与资产链接无障碍名称，已移除重复角色行并保留链接可读名称后通过。证据 `/tmp/account-upload-final-tests.log`、`/tmp/account-upload-knowledge-final.log`、`/tmp/account-upload-build.log`、`/tmp/account-upload-lint.log`。未启动浏览器、未启停服务。

后续修复：用户明确复现的是同一弹窗内选中文件、移除、重新选择同一文件，而非删除后端文档。原文件 input 在模态弹窗外，userEvent.upload 回归可见 pointer-events 隔离错误；现将 input 放入 DialogContent。移除时重建 native input，打开选择器前及 change 后清空 value，并先复制 FileList，避免同文件选择失效。关闭重开时取新 input，不复用已卸载节点。上传hook6项、知识库页面7项共13项通过，包含真实userEvent同文件重选及上传/删除后端文档/再次上传流程。日志 `/tmp/upload-remove-same-final.log`、`/tmp/upload-remove-same-build.log`；未启动浏览器。
