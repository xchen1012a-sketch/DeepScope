# 会话模块稳定性修复结果

日期：2026-10-15。对应计划：`docs/superpowers/plans/2026-10-15-conversation-reliability-remediation.md`。

当前状态：计划内源码修复及只读复核已完成。前端、后端完整测试通过；首轮全量门禁的测试清理错误已修正，后端及后续分发构建分段复验通过。没有更新当前运行服务，尚无现场验收结论。

## 修复范围

| 问题 | 当前行为 | 主要落点 |
| --- | --- | --- |
| C01 旧异步收尾覆盖新运行 | 回调、保存、对账与出队按运行实例校验归属 | `useAdvisorConversation.ts` |
| C02 新请求取消旧任务 | 先校验再准入；同会话忙碌时拒绝，不自动取消 | `AguiMvcController`、`AdvisorConfiguration` |
| C03 初始化取消与资源归属 | 资源登记与停止串行；晚到初始化仅释放自身资源；停止、断连、超时与心跳清理完成后才开放下一次运行 | processor、MVC、resolver、`ToolRuntimeCoordinator` |
| C04 原生工具确认 | 保留中断数组，明确允许、拒绝或取消；使用resume且不新建访客消息；刷新时按服务端等待状态恢复确认 | `aguiClient.ts`、hook、确认卡、协调器快照 |
| C05 队列过早消费 | 成功且保存对账完成后出队；失败、停止、受限、待确认保留队列；立即发送等待旧运行收尾 | hook |
| C06 清空错误上下文 | 收集绑定及实际路由的智能体，清理对应user/session原生状态；与发送共享准入占位，失败不返回cleared | `SessionCompactionService`、两个清空入口 |
| C07 读取和保存竞争 | GET只读；客户端与兜底走同一事务、按runId去重；冲突返回409；旧运行不清掉新累积器 | `TimelineStore`、persister、session API |
| C08 缺少终态的EOF | 明确报连接中断，保留部分内容；RUN_ERROR不被后续完成事件覆盖 | SSE解析与双语错误提示 |
| C09 停止后丢失局部过程 | 未完成对账时保留分析和parts；完整回放后释放本地副本 | hook收尾与快照 |
| C10 时间线定位混淆 | 优先使用稳定anchor；旧seq+kind仅在唯一匹配时回退 | `ProcessPanel.tsx` |
| C11 子任务状态不刷新 | 同数量记录也合并生命周期与完整内容，终态不被旧运行态覆盖 | `ProcessPanel.tsx` |
| C12 文件错误显示成功 | 注册工具返回显式ToolResultBlock状态，旧String入口兼容；正文“Error:”不当作失败依据 | `FilesystemTool`、过程文案 |
| C13 大过程投影扫描 | 用Set代替重复集合扫描，保留顺序、折叠、耗时和高亮规则 | `model/blocks.ts` |

没有新增独立运行引擎、运行表或数据库迁移。原公开调用方式保留，通过默认方法、新方法和重载扩展协议生命周期。

## 已取得的验证证据

- 前端：`npm test`，52个文件、476项通过。`npm run lint`，0错误、50个警告。`npm run build`通过。
- 构建第一次恰遇其他任务编辑`AgentDetailPage.tsx`的临时未闭合标签；未修改该文件，其写入完成后重跑构建通过。
- 后端定向：`review-fixes-green.log`中processor23、MVC13、应用59，共95项通过；后续admission回归将MVC扩展到14项，和processor23项一起通过。首轮全量中AG-UI协调器12、处理器23、MVC14、文件工具21项全部通过。
- 文件工具注册、结构化失败、兼容入口与合法Error前缀内容：21项通过，见`c12-green.log`及`backend-adjacent.log`实际测试记录。
- C13同环境、同固定数据规模对照：500/1000/3000条中位耗时从1.9998/7.9478/29.2925ms降为0.1975/0.3489/0.9840ms。不把本机计时当成生产延迟保证。
- 最终只读审查发现并关闭：旧初始化误停新运行、停止解除清空占位、刷新恢复中重复确认、断连先释放占位、占位后资源登记竞态。对应新增回归均取得实际断言RED，再修复为GREEN。
- 保存链路补充集成测试：兜底后迟到提交与新运行并存、409冲突、数据库写失败保留快照并重试，均通过；最终会话Controller17项、persister12项。
- `mvn -B -T1 clean verify -Dnet.bytebuddy.experimental=true`首轮在backend失败：308项中0断言失败、1个JUnit临时目录清理错误。之前的核心、Harness及扩展模块均通过；失败后分发子模块未执行。不能把该命令写成一次全量通过。
- 单独复跑该用例通过后，检查确认缺少对原生异步记忆写入的等待。只给`AdvisorAgentControllerTest`补9行测试收尾，使用已有`MemoryBackgroundTasks.awaitQuiescence`并断言成功，未改业务代码或禁用清理。
- 修正后的backend完整308项通过，0失败、0错误、0跳过；其中智能体Controller24项通过。`backend-and-downstream-verify.log`构建成功，同时验证依赖BOM和分发父模块；该轮按`%regex[io/agentscope/advisor/.*Test.class]`运行完整应用测试，不重复已通过的其他模块测试。
- 首轮未执行的`agentscope-all`和`agentscope-bom`已单独连同依赖完成verify，构建成功；该轮重跑AG-UI处理器23、协调器12、文件工具21，共56项通过，见`distribution-verify.log`。MVC不在该分发包依赖中，其14项已在`full-verify.log`和`admission-fence-green.log`通过；相关SDK回归合计70项。修复后没有再次从头执行整仓clean verify，不将分段结果表述成一次全量通过。

最终复验命令（仓库根目录）：

```sh
mvn -B -T1 -pl technical-advisor-app/backend,agentscope-dependencies-bom,agentscope-distribution -am verify '-Dtest=%regex[io/agentscope/advisor/.*Test.class]' -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true
mvn -B -T1 -pl agentscope-distribution/agentscope-all,agentscope-distribution/agentscope-bom -am verify -Dtest=AguiRequestProcessorTest,AguiResumeCoordinatorTest,AguiMvcControllerTest,FilesystemToolTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true
```

日志及任务前副本保存在`/tmp/advisor-reliability-ZFDFmP`。该目录是临时证据，系统可能清理；长期结论以本文件和执行记录为准。

## 交付边界

- 未重启前后端、未提交或推送、未调用真实模型、未修改运行数据库；当前进程可能仍使用此前构建。
- 遵循“非必要不要启动浏览器”，未执行Playwright、滚动/律动视觉验收或真实供应商长任务验收；组件测试不能替代这些现场证据。
- 数据库并发与回滚测试使用独立H2实例，尚无本次真实PostgreSQL部署验收。
- 后端重启无法保证恢复内存里的原生确认；无法核验的恢复请求会拒绝，不能静默批准。
- 跨原生状态存储与会话数据库不能承诺分布式原子事务。清空失败会保留时间线并报错，但部分原生状态可能已经删除，重试用于收敛。
- 任务前工作区包含大量其他修改。未整目录恢复、未修复其他任务文件的格式问题；本任务所改文件的定向`git diff --check`通过，工作区整体检查仍报告其他文件的末尾空行。

## 接入运行环境

完成门禁后，如需更新当前服务，应先确认无在途会话，再采用新构建重启后端并刷新前端。源码、自动化验证、服务更新与现场验收是四个独立状态；本文件不将后两项写成已完成。
