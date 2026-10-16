# 用户中心后端交付结果

日期：2026-10-16。范围：用户授权的`2026-10-16-account-center.md` Phase 1。后端接口、回归与构建已完成；前端页面与会话默认偏好应用属于后续阶段。

## 已交付接口

全部`/api/me/*`必须登录，只操作本人，不需要模块管理权限。

| 接口 | 交付行为 |
| --- | --- |
| PUT /api/me/profile | 昵称去首尾空白、最多40字符、允许清空；不改管理员备注与登录名；返回更新后me |
| PUT /api/me/password | 验当前密码、新密码8–256字符；同事务更新密码并撤销其他登录，保留当前登录；失败回滚 |
| POST /api/me/sessions/revoke-others | 独立退出本人其他设备，不影响当前登录及他人 |
| POST /api/me/avatar | multipart file，PNG/JPEG/WebP、最多512 KiB、每边最多1024像素；服务端文件名，替换清理旧文件 |
| GET /api/me/avatar | 鉴权图片，private缓存、Vary Authorization、nosniff；无头像404 |
| DELETE /api/me/avatar | 清除本人头像，恢复默认；历史受管目录外路径不得读取或删除 |
| GET /api/me/preferences | 默认Think false、模型/智能体空串；initialized区分未初始化与明确保存false |
| PUT /api/me/preferences | 可选字段部分更新、严格类型与可用范围校验；If-None-Match: *仅首次写入，已有偏好412；并发写入锁账号行 |
| GET /api/me/overview | 只统计本人资源，即使管理员也不跨用户；无模块查看权限数量为null；凭据只返回toolId/name/origin/configured |
| GET /api/me/about | DeepScope及现有Version解析的构建版本，未硬编码版本号 |
| GET /api/auth/me | 第一批扩展nickname/avatarUrl/avatarUpdatedAt，保留其余字段 |

默认模型沿用现有模型选择边界：chat.use可选当前聊天模型，其他聊天模型还需settings.manage范围；embedding拒绝。智能体需chat.use及既有可见性，staging不可选。保存偏好不更改系统默认或已有会话。

## 文件归属

新增：

- backend/src/main/java/io/agentscope/advisor/account/AccountSelfServiceController.java
- 同目录AccountPreferencesStore.java、AvatarStore.java、WebpDimensions.java
- backend/src/test/java/io/agentscope/advisor/account/AccountSelfServiceTest.java、AccountPreferencesStoreTest.java
- backend/src/test/java/io/agentscope/advisor/auth/AccountPasswordConcurrencyTest.java

本轮增量修改：

- auth/AccountStore.java：登录验密与创建会话同事务锁账号行；changePassword事务；头像更新时间单调递增。保留第一批及其他任务既有改动。
- auth/ApiAuthenticationFilter.java：复用现有Bearer解析，开放给自助控制器；/api/me保护来自第一批。
- 计划/设计文档：补退出其他设备、模型可用范围、迁移条件请求与当前状态。
- docs/v2/en及zh对应technical-advisor-login.md：只追加后端自助接口说明。

第一批AuthController、AdvisorSchema和相关账号测试沿用并补验；未重做。未修改access、模型、ToolCatalog、其他模块Controller或前端文件。工作区其他任务修改未回滚、未提交。

## 测试与实际结果

日志根目录：`/tmp/account-center-backend-20261016`。

| 检查 | 结果与证据 |
| --- | --- |
| 原编译阻塞 | test-compile.log，BUILD SUCCESS；旧ModelManagementTest阻塞已解除 |
| 第一批补验 | first-batch.log，AccountStoreTest 6、AuthControllerTest 7，共13项通过；不宣称历史第一批遵守先RED顺序 |
| 接口缺失RED | self-service-red.log，14项中13项失败，预期200/204/400实际404；鉴权项通过 |
| 第一轮GREEN | self-service-green.log，27项通过 |
| 改密并发RED | password-race-red.log，旧密码验密后暂停写入，改密后再插入仍有效；断言预期失效实际有效 |
| 缓存RED | race-green-cache-red.log，改密并发GREEN、头像缺Vary头失败 |
| 动画兼容RED | animation-red-rollback-green.log，合法libwebp动画应200实际400；密码失败回滚2项通过 |
| 初始化迁移RED | migration-red.log，未返回initialized字段 |
| 账号定向GREEN | all-account-green.log，37项通过；之后补充偏好并发2项及明确false断言，均纳入最终verify |
| 最终模块及依赖 | backend-verify.log，19模块全部SUCCESS，退出码0，后端544项，零失败/错误/跳过；70个Surefire XML报告核对一致 |
| 账号最终覆盖 | AccountStoreTest 6、AuthControllerTest 7、AccountPasswordConcurrencyTest 2、AccountSelfServiceTest 22、AccountPreferencesStoreTest 2，共39项；其余为当前混合工作区的后端回归 |
| 格式与打包 | verify包含Spotless及打包，通过；未用skipTests/禁用规则；格式化仅限本任务显式路径 |
| 文档脚本 | docs-tests.log，12项通过；docs-check.log为237项既有Service导航/链接缺失，不作为通过 |
| 差异空白检查 | git diff --check通过 |

最终命令在仓库根目录执行：

```bash
ADVISOR_TEST_PGVECTOR_URL=jdbc:postgresql://127.0.0.1:55437/account_test \
ADVISOR_TEST_PGVECTOR_USER=account_test ADVISOR_TEST_PGVECTOR_PASSWORD='' \
mvn -B -T1 -pl technical-advisor-app/backend -am verify -Dnet.bytebuddy.experimental=true
```

该次耗时6分34秒，2026-10-16 14:14:37 +08:00结束。pgvector回归实际使用本轮独立PostgreSQL17端口55437和临时pgdata，未连接应用数据库；验证后pg_ctl已停止该实例。

独立只读审查发现的三项问题已修复，增量复核未发现新的确定代码缺陷；复核指出的偏好并发证据已补入最终模块测试。没有把审查意见当作运行结果。

## 边界与后续

- WebP使用RIFF/帧头及尺寸验证，含静态和动画；没有引入WebP像素解码器，不宣称验证完整压缩码流。PNG/JPEG使用ImageIO实际解码。
- 头像操作同一Store内串行；文件和数据库不能跨介质原子提交，旧文件清理失败记录日志，不承诺多节点共享文件并发或进程崩溃后自动清理孤立文件。
- configured表示凭据引用或MCP环境/请求头配置存在，不证明凭据有效；响应不包含密文、引用或远端配置。
- 前端Think迁移、个人默认项应用、头像blob显示、页面/侧边栏及语言交互未实现。本人会话列表与删除继续复用原接口。
- 未运行前端测试、浏览器或E2E；未调用真实模型/外部MCP；未运行整仓89模块clean verify。本轮中级应用后端范围执行19个依赖模块verify。
- 应用未重启或部署，数据库字段尚不代表已更新到运行实例。未commit、push、stash、reset或清理他人修改。
