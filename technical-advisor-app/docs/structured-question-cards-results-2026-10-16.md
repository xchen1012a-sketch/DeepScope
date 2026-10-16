# 结构化追问卡片实施记录

计划：`docs/superpowers/plans/2026-10-16-structured-question-cards.md`。

状态：功能实现与相关验证完成；全工作区有其他并行改动引起的测试失败，不代表全仓门禁通过或部署结果。

执行约定：沿用当前 `feat/advisor-next` 分支和已有工作，不提交、不推送、不重置；全部使用补丁。后端与独立卡片组件分工，发送与恢复由主任务整合，不并行运行 Maven。

实施裁决：会话详情增加可空 `pendingQuestionReply`（已保存但尚无后续助手回合的回答），消息返回可带 `replayed` 标记；用于刷新恢复与防止重复保存后无条件启动新模型调用。事件载荷保存 `contextText`，用于给模型提供服务端核实的问题与答案，界面正文仍展示简洁答案。

验证记录随实际执行更新；未启动浏览器，未重启服务。

## 实现结果

- 追问显示为输入框上方的卡片，动态问题标题、实际选项、圆形数字编号及最后的“其他”。单选直接提交，多选确认，自定义和无选项问题可填写；卡片可收起并独立滚动。
- 新问题有稳定编号；问题与回答在服务端关联，选项、模式、长度和权限均校验。同一会话行锁内保存答案、访客事件与状态；同请求同答案返回已有事件，不同答案或第二个请求返回冲突。
- 旧问题与 `suggestions` 保持兼容；追问后的助手说明不再覆盖待答状态。卡片及普通输入框使用同一回答链路，失败保留草稿，保存成功即确认，不等待模型全部生成才清空草稿。
- 刷新恢复待答问题及尚无执行输出的已存答案；继续前检查运行时与回合内容，复用既有访客消息。路由记录不视为模型已经输出，实际工具或模型内容则阻止盲目重新启动。
- 附件上下文随回答保存，首次运行、失败重试与刷新后继续均使用同一份输入；路由只使用问题和答案，避免把完整附件重复发送给路由判断。
- EN/ZH 界面文案与使用说明同步；服务端机器错误映射为简短提示，不向用户输出原始响应体。

实施裁决补充：`QuestionService` 使用纯业务 helper，问题保存和待答查询留在既有 `TimelineStore`，不新增 Spring 装配；`attachmentContext` 是最多240000字符的普通用户附件文本，不改变系统指令或权限。

## 代码范围

- 后端：`QuestionService`（新增）、`TimelineStore`、`ReportActions`、`AdvisorSessionController`；相关 `QuestionServiceTest`、`ReportActionsTest`、`AdvisorSessionControllerTest`。
- 前端：`api/advisor.ts`、`useAdvisorConversation.ts`、`ConversationPanel.tsx`、新 `QuestionCard.tsx` 与 `model/questions.ts`；新增卡片、模型、发送和真实面板集成测试；EN/ZH 词典的 `questionCard` 命名空间。
- 文档：原实施方案及 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md`。

## 验证证据

| 范围 | 实际结果 | 日志 |
| --- | --- | --- |
| 前端对话相关回归 | 31文件400项通过 | `/tmp/question-chat-gate-tests.log` |
| 最后问题错误作用域修正后 | hook/模型17项通过，`tsc --noEmit`成功 | `/tmp/question-final-targeted.log` |
| 前端构建及类型检查 | 成功；既有大包提示保留 | `/tmp/question-gate-build.log` |
| 前端Lint | 0错误、43警告 | `/tmp/question-gate-lint.log` |
| 后端会话包及相邻持久化 | 104项通过，0失败/错误/跳过 | `/tmp/question-backend-session-final2.log` |
| 本任务直接相关后端测试 | QuestionService15、ReportActions10、TimelineStore11、Controller20，合计56项通过 | 同上 |
| 后端相关Spotless | 任务相关文件格式通过 | 同上 |

后端最终命令（仓库根目录）：

```sh
mvn -T1 -pl technical-advisor-app/backend -am test '-Dtest=io.agentscope.advisor.session.**' -Dsurefire.failIfNoSpecifiedTests=false '-DspotlessFiles=.*(QuestionService|TimelineStore|ReportActions|AdvisorSessionController)(Test)?\.java'
```

RED→GREEN证据：后端版本字段、非法角色、继续候选与附件快照分别先失败再修复，见 `/tmp/question-backend-{red,role-red,continuation-red,attachment-red}.log`。前端卡片11项先RED后GREEN；hook7项先RED后GREEN；审查恢复与缓存边界见 `/tmp/question-review-red2.log`、`/tmp/question-review-final-green.log`；附件恢复见 `/tmp/question-attachment-red.log`；保存即确认见 `/tmp/question-save-ack-red.log`、`/tmp/question-save-ack-green.log`；错误映射见 `/tmp/question-error-mapping-red.log` 和最终对话测试日志。

## 全工作区门禁边界

- 中途前端全量69文件616项通过（`/tmp/question-frontend-all-final.log`）；后续其他任务更改选择列表与词典后，全量记录为618项中607通过、11失败（`/tmp/question-frontend-gate-tests.log`）。失败位于 AgentManagement、AgentSections、i18n 字典和正文键检查；本任务未修改相关选择列表或其测试。最终对话相关测试单独运行并通过，不把较早的全量绿色当作当前全量结果。
- 完整后端应用测试409项中403通过、6失败（`/tmp/question-backend-module-final2.log`）；失败全部位于其他任务新建的 `SelectionPaginationTest`。最初无隔离 pgvector 环境导致的知识库测试失败，已使用独立测试库实际通过；两次临时库均查询确认删除，没有使用业务库。
- 并行工作期间还遇到无关分页测试格式失败和共享 `target` 编译竞争。未格式化无关文件、未清理产物或停止其他任务进程。后端最终会话包重跑成功。
- JaCoCo 提示其他任务修改的 `ToolCatalog$Selection` 执行数据不匹配；不宣称覆盖率结果。

## 审查与剩余验证

前后端均做了只读审查。前端发现的路由后继续无响应、运行中丢草稿、缓存失败重新关联旧问题、附件刷新丢上下文均补用例后修复，窄范围复审未发现新的P1/P2；另补保存成功即确认的行为测试。后端审查未发现可复现P1/P2。

未启动浏览器、未执行浏览器E2E或真实Provider调用，卡片视口、原生键盘及滚动观感尚未人工验收。未重启服务、未重新打包后端运行JAR、未部署、未提交或推送代码。答案保存可幂等，模型启动不承诺“恰好一次”。
