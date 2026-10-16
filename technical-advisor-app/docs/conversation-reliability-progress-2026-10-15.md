# 会话稳定性修复执行记录

计划：`docs/superpowers/plans/2026-10-15-conversation-reliability-remediation.md`。

## 当前状态（2026-10-15，优先于下方历史接续记录）

C01—C13对应代码、最终只读复核及自动化复验已完成，不再有此前的占位方法。最终记录见`conversation-reliability-results-2026-10-15.md`。未重启应用、未提交代码、未使用真实模型或浏览器。下方“当前接续点”均为过程历史，不作为当前待办。

- 任务内所有业务文件由主代理串行修改；子智能体只读审查。
- 前端最终回归：52个文件、476项通过；lint为0错误、50个既有警告。构建首次碰到其他任务正在编辑的AgentDetailPage.tsx临时不完整状态，未改动该文件；其写入完成后重跑构建已通过。
- 后端最终完整回归：308项通过，0失败、0错误、0跳过；含会话17、保存12、Timeline11、清空5、命令6、租约8、应用上下文2及智能体24。相关SDK最终复验processor23、协调器12、MVC14、文件工具21，共70项通过。
- C10/C11：同seq多个part按稳定anchor定位；按终态与完整度合并子任务，不再只比较数量。C13此前本次执行的固定规模3000条过程投影中位数由29.2925ms降到0.9840ms；该计时是本机对照，不是生产延迟承诺。
- C12：文件工具注册入口返回明确ToolResultBlock状态，旧String入口保留；注册调用与“Error:”正常文件内容回归通过，文件工具21项通过。
- 最终审查发现的旧初始化误停新run、停止解除清空占位、恢复中刷新重开确认卡已补RED/GREEN。断连/超时/发送失败/heartbeat进一步统一取消入口，保持占位到清理结束；未启动的结果仅release，不中断共享Agent。
- C09对账成功后释放本地过程副本，失败仍保留；回归从20项扩展到含刷新确认的24项。
- C07后台清理使用旧runId；新增旧round检查后被新round替换的回归先RED后GREEN。生产恢复路径复用MVC空闲占位，避免检查与清理之间插入新运行。
- 首轮clean verify在backend测试目录清理报错；修正测试收尾后后端308项及BOM/分发构建分段verify通过，未重复整仓clean verify。精确命令与日志见结果文档。计划中的浏览器视觉验收按用户偏好未运行，未将组件测试当作现场验收。
- 最终只读审查确认admission与取消的最后一处竞态已关闭；admission-fence-red.log/green.log保留实际RED/GREEN。两份docs/v2中英说明已同步；任务文件定向diff检查通过。

Ruling：全量验证只作为门禁，不据此修改其他任务模块。仅修复本任务引入的问题；其他任务的临时写入或已有失败单独记录。

Ruling（门禁失败后补充）：首轮clean verify中会话测试及核心/扩展模块通过，backend的智能体历史测试在JUnit删除临时目录时与异步memory写入竞争，308项中0断言失败、1清理错误；该测试文件任务前无未提交修改。单独复跑1项通过，检查代码确认测试未等待原生MemoryBackgroundTasks。仅给该测试类增加AfterEach并调用已有awaitQuiescence且断言成功，不修改智能体业务逻辑、不跳过目录清理、不放宽断言。该测试生命周期修正是本任务相邻门禁的最小必要增量。

用户已授权按方案修复，并要求修改前重新核对、同模块串行处理、保留其他任务修改。

## 执行约束与基线

- 使用现有develop工作区，保留混合未提交变更，不提交、不重启、不启动浏览器。
- 当前可见同仓库会话只有本任务处于active；已有审查子智能体已完成。仅此任务写业务文件，后续如需审查只读。
- 修改前副本：`/tmp/advisor-reliability-ZFDFmP`，包含frontend-src、backend-src、agui-src、starter-src、harness-tools和harness-tool-tests。不得整目录覆盖恢复。
- Ruling：复用用户当前包含已授权改造的工作区，不创建缺少这些未提交改动的新checkout；每个补丁前核对对应文件，独立完成一个问题的RED/GREEN再继续。
- 复核时发现原方案提到的projectedTokens fixture已被其他修改补齐，不再次修改该fixture。

## 实施清单

- [x] C01 运行身份与异步收尾隔离
- [x] C02 鉴权后准入、拒绝隐式替换
- [x] C03 初始化取消与资源清理（补充竞态已通过最终审查）
- [x] C08 断流终态识别
- [x] C09 保留分析过程并完成停止对账
- [x] C04 工具确认与resume
- [x] C05 队列消费门禁
- [x] C07 保存与详情读取一致性
- [x] C06 清空真实上下文
- [x] C10 时间线稳定身份
- [x] C11 子任务状态更新
- [x] C13 过程投影扫描优化
- [x] C12 文件工具失败语义
- [x] 门禁执行与失败修正复验、只读审查、双语文档及结果记录

## 当前接续点

基线测试通过且构建成功。C01新增3项回归全部RED→GREEN，发送测试13项通过；日志位于基线目录c01-red.log/c01-green.log。状态更新按捕获的runInstanceId过滤，收尾对账期间保持忙碌，出队不再依赖50毫秒定时器。

Ruling：先串行完成阶段一前端C08/C09，再切到框架C02/C03，减少同一文件反复切换；阶段一整体目标不变，无并行写入。

## 已验证增量

- C08：EOF无终态的回归RED→GREEN；RUN_ERROR不会被后续RUN_FINISHED覆盖，增加双语断流提示。
- C09：仅分析+停止+服务端保存不可用的回归RED→GREEN；保留thinking/parts，停止请求完成后再对账。前端两文件27项通过（c09-green.log）。
- C02/C03：新增不替换当前流、初始化期间停止、准入前校验/只清理一次，先复现再通过。处理器测试23、MVC测试8、租约测试7全部通过，日志app-lifecycle-green.log。
- 新增AguiRuntimeContextResolver默认validate/release钩子，保持函数式接口和原resolve调用兼容；MVC先做无副作用validate再占位，应用resolve在准入后获取资源。release由processor在终态/取消/准备失败按实例调用一次。
- Ruling：复用resolver增加默认生命周期钩子，代替另造运行准备SPI或新引擎；应用提取同一校验实现供纯预检/资源准备复用。WebFlux/direct processor仍保持原resolve权限检查，并获得release清理。
- 运行租约增加runId身份；旧run释放新租约的回归RED→GREEN，保留旧方法兼容。
- Maven定向测试使用-Dnet.bytebuddy.experimental=true支持当前JDK25，未跳过测试。格式使用限定文件的Spotless；spotlessFiles为正则，曾误用glob导致失败，已改正并通过。

## 当前接续点（更新）

正在补C03的expectedRunId：MVC新增兼容重载目前是占位委托，测试aStaleStopRequestDoesNotCancelTheCurrentRun正在跑RED（stop-identity-red.log）。接下来实现按runId比较取消、接入AdvisorSessionController可选请求体，取消完成后再释放运行槽，随后记录GREEN，再处理C04/C05。

注意：不要将当前interrupt(threadId,expectedRunId)占位重载当作已完成实现；AguiMvcController、处理器、resolver、AdvisorConfiguration和ToolRuntimeCoordinator都是本任务修改。前端测试fixture的projectedTokens来自任务开始前，保留。

## 继续执行增量

- expectedRunId占位已被实际比较取消替换；取消时保留运行槽到同步清理结束，回调不会递归移除。应用interrupt入口支持可选请求体并保留旧Java重载。
- stop-identity-green.log：processor23、MVC9、应用会话15、租约7通过。
- C04：完整interrupt数组解析、resume发送且messages为空；hook恢复不新建访客消息、不重路由；确认卡片逐项明确允许/拒绝后提交，未知类型仅可取消。刷新快照从协调器保留原runId/agentId/interrupts并经owned会话详情返回。
- C04前端36项通过；后端processor/resume35项、会话15项通过。c04-back-red.log先遇缺少Map导入，修正后重新取得断言RED再恢复GREEN，不把编译错误当行为RED。
- C05：失败/受限/待确认暂停队列，“引导”等待旧运行停止与缓存对账；runCompletions只记录本地完成通知。19项hook测试通过（c05-steer-green.log）；阶段中前端build已通过。
- C07进行中：saveAdvisorRound已用现有session行FOR UPDATE和同连接事务保存工具/用量/子任务及主回复，按runId幂等与冲突检查；兜底与客户端已开始接入。记录原turn，旧回复不idle新turn，late markFlushed/lease release带runId。
- C07初始4项行为RED已取得（c07-red.log），首轮GREEN中TimelineStore10项和persister9项通过；Controller旧“GET变更stale状态”用例需按已批准的只读GET设计调整，不能保留GET清理副作用。

## 当前接续点（最新）

正在执行c07-background-red.log：新增真实双TimelineStore实例并发保存测试，以及后台清理不影响活跃会话的测试。
AguiRoundPersister.reconcileStale(long)目前仍是空占位，需看到背景测试断言RED后实现：添加SessionStore.staleRunning只读查询、复用persister scheduler后台检查，activeRunCheck来自懒获取MVC.hasActiveRun，删除list/detail GET上的自愈副作用。
然后补客户端/兜底迟到保存与幂等/失败重试，完成C07。后续仍有C06、C10、C11、C13、C12及整体审查门禁，不要宣称全部完成。

最终审查重点：初始化被取消且新run已准入时，旧初始化晚到不能重新覆盖租约（当前仅release已按runId保护，acquire仍可覆盖，需补fence）；Java公共API Javadoc/Spotless；前端finally缩进与内存清理；原生中断过期校验、停止后未知确认的处理。
