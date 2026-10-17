# 模型设置体验改造结果（2026-10-15）

已实现用户批准的模型列表与右侧配置抽屉；按供应商带入地址、建议或手动输入模型ID、主动发现远端模型、真实连接测试、测试状态失效、保存前服务端验证、编辑保留ID与密钥、关闭草稿确认、默认/删除确认及错误反馈。

新增模型默认不激活；设置页只管理系统默认，会话选模独立持久化并可跟随默认。运行开始固定模型与容量。向量模型已有索引数据时阻止直接替换；默认与已被会话引用模型阻止删除，数据库引用限制保护并发删除。

Anthropic复用仓库现有模型模块，探测与SDK对root及/v1地址统一请求/v1/messages；OpenAI o系列/GPT-5探测兼容max_completion_tokens。供应商协议以本地HTTP fixture验证，未用真实外部密钥。

## 验证

- 前端全量：53文件，487测试通过。日志 `/tmp/model-settings-all-tests.log`。
- 构建通过；Lint 0错误、56项存量警告。日志 `/tmp/model-settings-build.log`、`/tmp/model-settings-lint.log`。
- Playwright 2/2通过（API fixture）：新增/测试/编辑/默认确认、桌面和390px抽屉、草稿保留。日志 `/tmp/model-settings-e2e.log`。
- 后端模型包26/26通过，定向模型/会话/压缩33/33通过（部分模型类重叠，不相加计数）。日志 `/tmp/model-settings-backend.log`、`/tmp/model-session-final.log`。
- 最终后端全量327项，326通过，1项时序失败：`AgentCreationGenerationTest.duplicateSubmissionWithSameKeyDoesNotCallModelAgain`。同一测试单独复跑1/1通过；其异步首请求尚未进入模型时读取 callsAfterFirst=0，随后首请求启动变为1，导致重复请求计数断言竞争。未修改该无关测试。日志 `/tmp/model-settings-backend-full.log`、`/tmp/model-settings-unrelated-retry.log`。
- 后端全量Spotless 144文件clean；本次文件diff空白检查通过。
- 只读审查发现的两项供应商兼容问题均已修复并复核通过。

## 运行边界

未提交或推送，未重启正在运行的服务。新增后端接口、Anthropic应用依赖及增量schema需重新打包并重启后端后才作用于现有运行实例。未进行真实供应商付费调用。

截图：`/tmp/model-settings-list.png`、`/tmp/model-settings-drawer.png`、`/tmp/model-settings-mobile.png`。
