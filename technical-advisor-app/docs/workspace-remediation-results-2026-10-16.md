# 工作空间四模块修复结果

对应计划：docs/superpowers/plans/2026-10-16-workspace-four-modules-remediation.md。

日期：2026-10-16。当前状态：十项源码修复、只读复核及修复后完整整仓门禁完成；前端583项、后端381项通过，不代表服务已经更新。全站文档检查的既有237项缺失仍保留为独立缺口。

## 业务变化

| 问题 | 已实现处理 | 主要文件 |
| --- | --- | --- |
| R03 模块权限 | MVC统一授权；资源归属继续单独核验；聊天摘要和资源选择使用明确上下文，选择器不授予资源编辑权 | WorkspaceAuthorization、四模块Controller、前端API与选择器 |
| R01 技能路径 | 名称、受管目录和符号链接校验；发布、更新、撤回、读取、同步均核验路径，历史异常目录不自动删除 | SkillPaths、SkillCatalog、SkillSync |
| R02 MCP变更 | 确认集合、服务、所有者、名称、指纹与revision校验；服务和工具行锁；旧格式同连接写入，失败整批回滚 | McpUpdateService、ToolCatalog |
| R08 ZIP限额 | 单条16 MiB、累计64 MiB、512条目、64技能；目录payload/忽略项也计量；完整预检后事务创建，失败清理本批文件 | SkillPackage、SkillCatalog、AdvisorSkillController |
| R04 草稿保存 | 编辑版本归属、单个在途请求、多个flush等待者串行重查；保存失败不能发布旧版本 | useAgentCreationDraft、AgentCreationPage |
| R09 唯一发布 | 数据库锁定草稿占位；不同请求键及并发Service得到同一结果；已激活草稿退出未完成列表；删除和编辑不可穿透发布占位 | AgentActivationStore、Draft/Operation Store、AgentOverviewStore、AdvisorSchema |
| R05 完整发布 | 绑定与staging记录同事务准备；实际工具挂载验证；注册准备完成后统一转active；失败补偿并保留草稿 | AgentCreationService、AgentActivationStore、AgentStore、AgentProvisioner |
| R06 索引恢复 | 按已保存维度打开持久化索引；新实例可直接检索和删除已有内容；显式关闭资源 | PgChunkIndex |
| R07 MCP撤销 | 订阅时检查目录和绑定；deny、删除、解绑、服务删除拒绝新调用；allow改ask拒绝旧快照，要求新轮确认；原MCP协议和权限逻辑继续委托 | GuardedCatalogTool、CatalogExecutionGuard、AgentProvisioner |
| R10 文档刷新 | 当前页parsing或全库数量非零均继续轮询；库摘要的ready/parsing数量使用同次SQL统计 | DocumentManagerTab、KnowledgeCatalog、AdvisorKnowledgeController |

文档同步：docs/v2/en/docs/others/technical-advisor-login.md及对应zh页面。

## 已取得的验证

- 各核心缺陷已有实际行为RED→GREEN，日志保存在/tmp/workspace-remediation-20261016；编译和格式失败没有作为行为RED。
- final-targeted.log：55项通过，零失败、零错误、零跳过；覆盖权限、发布、技能包、路径、MCP及真实pgvector。之后的补充角色与库摘要调整已纳入最终完整门禁。
- workspace-final-front.log：工作空间及本地化26文件164项通过；front-final-build2.log构建通过；front-final-lint2.log为0错误、44项警告。最终front-all-recheck.log完整65文件583项通过。
- backend-all.log：依赖模块完整测试及当轮backend378项通过；后续有审查修复，不作为最终整仓结果。
- PgChunkIndexTest使用本机临时PostgreSQL 17与pgvector，独立端口55436与临时目录。已实际复现旧实现新实例查不到持久化数据，再验证修复后直接检索和删除。最终完整门禁中该测试也通过；未连接应用运行数据库，测试数据库已停止。
- 只读审查workspace_security_review发现的发布恢复、保存失败误发布、MCP权限变化、ZIP部分创建、草稿删除竞争、旧格式事务、准备工具缺失与删除版本竞争均已处理；审查者最新静态复核未报告确定剩余问题。
- 前端整套曾因另一任务正在修改的ThinkingLine出现3项失败；本任务的本地化静态检查失败已修复，后续完整前端回归已全部通过。
- 文档脚本测试12项通过；全站docs check报告237项Service导航/链接目标缺失。相关docs.json和检查脚本未改动，其中Service index在基线提交也不存在；没有为本任务修改站点导航。该项保留为文档站检查缺口。
- 首轮整仓clean verify在应用生成取消测试失败（380项中1失败）。已新增稳定的终态覆盖回归，修正数据库条件写入与先保存取消再中断的顺序；生成与发布21项通过。修复后重新从头执行完整clean verify，89模块全部SUCCESS，后端381项零失败、零错误、零跳过，未用分段复验冒充全量通过。

### 缺陷复现索引

以下日志均相对/tmp/workspace-remediation-20261016。只列实际行为断言，不将编译或格式失败算作缺陷复现；后续补测与最终门禁覆盖修复后的代码。

| 编号 | 实际RED表现 | RED日志 | 对应GREEN日志 |
| --- | --- | --- | --- |
| R03 | 期望403，创建仍201、管理读取仍200 | r03-red.log | r03-green.log、final-targeted.log |
| R01 | 越界改名和历史异常目录删除没有抛出拒绝异常 | r01-red.log | r01-green.log、final-targeted.log |
| R02 | 跨所有者工具删除未被拒绝 | r02-red.log | r02-green.log、final-targeted.log |
| R08 | 高解压体积/过多条目未被拒绝 | r02-green-r08-red-final.log | r08-green-r09-r05-red.log中的SkillPackageTest、final-targeted.log |
| R04 | 在途保存后未提交更新版本，冲突被误标saved | r04-red.log | r04-green.log、front-all-recheck.log |
| R09 | 同草稿使用不同请求键得到不同正式ID | r08-green-r09-r05-red.log | r09-r05-green.log、generation-cancel-green.log |
| R05 | 发布成功但实际能力绑定为空 | r08-green-r09-r05-red.log | r09-r05-green.log、generation-cancel-green.log |
| R06 | 新实例应检索1条，实际0条 | r06-red.log | r06-green.log、final-targeted.log |
| R07 | 撤销后远端调用计数应为0，实际1 | r07-red.log | r07-green.log、final-targeted.log |
| R10 | 解析计数为0后不再轮询，可见解析状态未消失 | r10-red.log | r10-green.log、front-all-recheck.log |

## 明确的兼容与运行边界

- 生命周期字段通过现有AdvisorSchema幂等扩展，旧正式智能体默认active；没有引入第二套迁移框架、权限系统或发布引擎。
- 发布崩溃恢复使用单节点应用初始化边界。部署前应停止接收发布并避免新旧节点混跑；不支持滚动启动时接管其他节点正在进行的发布。
- 原草稿表仍保留，操作历史不因激活级联删除；历史只有一个可靠成功目标时关联既有结果，多个目标或无效目标保留needs_review，不自动删除重复智能体。
- 草稿resources没有完整、既有的物化结构；非空但不受支持的内容明确422拒绝，不能发布成功后忽略。本轮没有虚构知识文件/子智能体导入能力。
- allow改ask的旧工具调用被拒绝；下一轮使用新运行时进入原生确认。已经发到外部的请求不承诺可撤销。
- 文件与数据库不能跨介质原子提交；数据库批次回滚后清理受管文件，清理失败保留异常证据，不宣称自动消除已有越界副作用。
- 未启动浏览器、未执行Playwright或真实模型/外部MCP任务；MCP用受控客户端替身。不能把单元/HTTP测试说成现场验收。
- 工作区中有另一个任务并发修改会话展示与session文件。本任务不重置、不格式化或替其修复；门禁结果如被其阻塞需单独说明。
- 未提交、推送、重启或部署应用。源码、自动化、服务更新和现场验收分开交付。

## 最终门禁记录

修复后完整重跑：退出码0，BUILD SUCCESS，89模块全部SUCCESS；后端59个测试报告、381项，零失败、零错误、零跳过。耗时10分21秒，结束时间2026-10-16 09:52:03 +08:00。包含全仓Spotless检查、测试与打包，未跳过测试。实际命令：

    ADVISOR_TEST_PGVECTOR_URL=jdbc:postgresql://127.0.0.1:55436/workspace_test ADVISOR_TEST_PGVECTOR_USER=workspace_test ADVISOR_TEST_PGVECTOR_PASSWORD='' mvn -B -T1 clean verify -Dnet.bytebuddy.experimental=true

首轮日志：/tmp/workspace-remediation-20261016/full-clean-verify.log；修复后完整重跑日志：/tmp/workspace-remediation-20261016/full-clean-verify-final.log。后端计数同时由target/surefire-reports的59个XML报告核对。git diff --check通过。

独立测试PostgreSQL已用pg_ctl针对本任务pgdata停止，端口55436不再监听。应用后端18080、前端5173未重启；未提交、推送或部署。
