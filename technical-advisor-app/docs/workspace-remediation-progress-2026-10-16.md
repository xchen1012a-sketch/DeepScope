# 工作空间四模块修复执行记录

计划：docs/superpowers/plans/2026-10-16-workspace-four-modules-remediation.md。

用户已授权实施，先复核、串行修改、非必要不启动浏览器。基线HEAD为ca9324e，工作区干净；基线记录位于/tmp/workspace-remediation-20261016。

使用coding-standards、test-driven-development、systematic-debugging和verification-before-completion。仅主执行者写代码，不自动提交、重启或部署。

## 当前进度

- [x] R03 模块权限与调用兼容（真实HTTP角色矩阵回归通过）
- [x] R01 技能受管路径（含符号链接与HTTP错误映射）
- [x] R02 MCP确认归属（含旧格式事务、删除版本及行锁）
- [x] R08 ZIP限额（含目录payload与整包事务回滚）
- [x] R04 保存最新编辑
- [x] R09 发布唯一性（并发、崩溃恢复及发布中状态回归通过）
- [x] R05 配置物化与半成品隔离（能力验证、准备行不可见）
- [x] R06 索引恢复（独立PostgreSQL+pgvector实际RED/GREEN）
- [x] R07 MCP执行撤销（五种撤销场景及原生确认回归通过）
- [x] R10 文档轮询（状态错配回归通过）
- [x] 只读业务审查与中英说明同步
- [x] 修复后整仓clean verify与最终结果记录（全站文档237项既有缺失单独记录）

## 复核与实施决定

十项关键路径均仍存在，旧审查复现不能代替本轮仓库回归。各项按先RED再GREEN推进，完整日志在上述临时目录。

Ruling：R03使用应用层MVC拦截器统一落实已批准的路由权限矩阵，再由现有Controller继续核验资源归属，避免每个重载入口遗漏授权；选择器上下文由服务端核验后解析，权限接口仍复用AccessGuard，不引入新权限系统。

R03：新增两项真实HTTP权限回归，先失败201/200，再修复；相关应用测试通过，前端6文件44项通过。日志r03-red/green/front.log。
R01：越界改名与历史异常live_dir删除两项实际RED→GREEN，SkillSync及技能Controller共12项通过。
R04：首次在途保存期间输入B回归RED→GREEN，6项通过。保存确认按本地编辑版本，flush串行排空待保存修改。
R10：初次测试使用了错误英文提示，修正为词典后取得实际“解析状态不消失”RED；轮询加入当前页parsing条件。
并发任务：复核后另一个任务开始修改session/会话展示文件，其临时格式和缺失类导致多次Java门禁尚未进入测试。未修改其文件；定向Spotless仅约束本任务范围，最终仍执行完整门禁。R02已取得行为RED，R08还未取得有效RED，不得提前实现。

## 后续实施与最新接续

- R08后来已取得有效行为RED，加入实际读取限额。审查追加目录条目payload和多技能非法名称两项RED→GREEN；又以数据库约束复现第二项失败后残留第一项，改为同事务保存并清理文件。
- R04审查追加保存失败时页面回退旧draftId仍发布的RED，页面现要求有效flush结果；11项前端回归通过。
- R09/R05：唯一发布由数据库锁草稿行控制，增加activated/publishing与staging状态。绑定和准备行同事务创建，成功才开放active；并发两Service、失败保留草稿、删除publishing、初始化恢复孤立发布均已补测试。部分resources缺乏既有执行结构，明确422拒绝而不忽略。
- R06已在临时独立PostgreSQL 17 + pgvector中取得实际RED→GREEN；端口55436，目录/tmp/workspace-remediation-20261016/pgdata，仅用于本任务测试，最终需停止。没有连接应用运行数据库。
- R07旧runtime deny/delete/unbind/server-delete通过；审查追加allow→ask实际RED，旧runtime现在拒绝该变化的新调用，提示新一轮按最新权限确认。保留原MCP工具的checkPermissions、schema、规则与结果转换。
- 独立只读审查workspace_security_review发现的问题均已串行补回归修复，最终增量只读复核未发现新的明确问题；不以静态审查替代整仓测试。
- 前端最终完整65文件583项通过，build通过，lint 0错误44警告；backend-all.log完整依赖及backend378项是中间结果，之后有补充修改，不当作最终门禁。

Ruling：启动恢复复用本应用schema初始化的单节点启动边界，清理本任务可核验的staging关系并恢复草稿重试，沿用预分配ID。该恢复不支持多节点滚动启动时抢占其他节点活动发布；部署要求先停止接收发布且不混跑节点，结果文档须明确此边界。
Ruling：allow改ask后旧工具不能可靠用旧权限快照证明已确认，因此拒绝旧调用并要求新一轮使用最新runtime；既有ask工具仍走框架原生确认，不伪造批准。
Ruling：本轮引入AgentActivationStore集中发布事务边界，避免将跨Store事务散落在Controller；属于计划已有职责分离的应用内实现，不新增发布系统。

最终门禁首轮：整仓clean verify在backend的AgentCreationGenerationTest.cancelGenerationMarksOperationCanceled失败（预期canceled实际failed），其余已执行模块通过。已新增确定性“cancel后late failed”回归并取得RED；生成操作终态更新改为数据库条件写入，取消先持久化再中断Future，避免worker覆盖。该额外修复属于相邻草稿生命周期门禁必需增量。
同测试类的重复提交断言原先在异步首请求尚未开始时读取calls=0，具有另一已知时序不稳定；改为在3秒边界内等待首请求进入模型再比较，不放宽“重复请求不增加调用”的断言。
最新前端整套65文件583项全部通过；工作空间与本地化26文件164项通过，构建通过、lint零错误44警告。文档测试12项通过；docs check存在237项既有Service导航目标缺失，未改动这些无关站点路径。

最终收尾：修复后从头执行的full-clean-verify-final.log为BUILD SUCCESS，退出码0，89模块全部SUCCESS；后端59个测试报告共381项，零失败、零错误、零跳过，真实PgChunkIndexTest通过。耗时10分21秒，结束于2026-10-16 09:52:03 +08:00。完整命令与日志索引见结果文档。最终只读增量复核未发现明确回归，git diff --check通过；独立测试PostgreSQL已停止，未重启应用、未启动浏览器、未提交或部署。
