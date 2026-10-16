# 会话智能体选择器实施计划

用户已确认：在输入框底部、模型选择旁放常驻智能体入口；支持搜索、名称/职责摘要和自由模式。新建与已有会话共用选择器。选中后严格限定该智能体及其已挂载能力，不自动切换其他智能体；运行中修改只影响下一条提交消息，当前轮不打断。欢迎页输入框收紧。

用户追加交互以此为准：选择卡片随当前页真实条目数量收短，最大不超过340px及视口可用高度；字体12px、摘要11px，后端每页5条。无左右分页按钮，滚轮下/上滑请求后端下一/上一页（回翻同样重查），首尾不越界；搜索200ms防抖后走后端q条件分页，禁止下载全量本地filter。去掉“仅使用此智能体”常驻和悬停提示，锁定由图标/名称表达。新增available分页与单条可见摘要查询，旧无参数数组接口继续兼容。

## 实施边界

- 复用现有可用智能体接口、Popover/Input/Button、会话agent_id绑定、AgentProvisioner挂载与AG-UI会话归属/绑定校验，不建立第二套路由或权限系统。
- 现状：route只在原绑定失效时处理selectedAgentId；有正常绑定时忽略显式切换。修正该分支，非法/不可见选择拒绝，保持原绑定；显式null解除绑定进入原有自由模式，省略字段保持兼容。新增isLocked返回标记仅作补充，不改变旧响应字段。
- routeSession及SendOptions允许selectedAgentId为string/null/undefined：string锁定、null显式自由、undefined保持原绑定规则。选择器切换时只更新待发送值；提交时捕获进普通消息、问题回答与排队快照。重试保留当次选择，恢复工具确认继续原运行时不切换。
- 新建页默认使用个人默认智能体，用户显式选择优先；切回自由不被默认值覆盖。已有会话首次取持久化绑定，运行中显示当前与下一轮的不同选择。选择器仅列当前账号available接口结果，错误/空态不当作自由模式成功；未知或已删除绑定显示不可用，必须显式重选。
- 新available GET带page/size/q返回既有PageResult的最小摘要，无参数保留旧数组；带agentId只读返回可见单个摘要或空数组。大小默认5，沿用JdbcPage 1–100校验，数据库先owner/lifecycle过滤再COUNT/search/LIMIT，不在浏览器或Controller全量截取。弹框回翻gcTime0+staleTime0实际重查，不使用placeholder旧页结果。
- 审查补充：直接route跨标签切换可能破坏awaiting_action恢复，因此绑定变更复用AguiMvcController.withIdleThread，已运行/确认中的线程返回409；不修改framework实现。当前运行agent独立查询名称，避免自由模式切到下一智能体后误显示不可用；PageUp/Down在整个Popover处理。
- 欢迎页容器从max-w-3xl收紧到max-w-2xl，空白输入区高度104px收紧到72px，保留自动长文本滚动和普通会话输入高度。

## 顺序与验证

1. 后端补正常锁定切换、自由转锁定、不可见选择不改绑定、显式自由和运行时不能跨绑定的回归，实际RED后改路由。
2. 前端补选择器搜索/选择/错误/默认项/运行中更改，消息排队及重试选择快照的回归；复用组件实现。
3. 同步中英词典和docs/v2；前端完整test/lint/build，后端模块测试/Spotless/打包和只读业务审查。只执行一次对应最终门禁，修复失败后再补必要复验。
4. 保留混合工作区，串行修改共享聊天文件，不提交/推送/重启，不启动浏览器；未做现场视觉/E2E验收如实标注。

## 交付与验证记录

实现已完成：输入框常驻入口、每页5条后端搜索/滚动查询、自适应高度上限340px、无左右分页及限制提示、正常锁定切换/显式自由模式、队列/重试选择快照、当前与下一轮名称区分、空闲线程绑定保护及欢迎输入框缩小。双语使用文档已同步。

实际证据在/tmp/conversation-agent-picker-20261016：frontend-tests-final2.log完整84文件752项通过；frontend-build-final2.log构建通过；frontend-lint-final2.log为0错误46警告；review-backend-tests.log真实依赖构建下AdvisorAgentControllerTest 29项通过；doc-tests.log文档脚本12项通过。只读业务审查发现的运行中绑定变更、当前名称、搜索框焦点分页均已修复；picker-retry-red.log额外复现明确重选后重试仍使用旧选择，之后修复已纳入前端最终全量。

后端单模块verify因本地旧依赖包缺少仓库现有withIdleThread报NoSuchMethodError，不算通过；带-am定向测试已通过。随后启动的全依赖verify耗时较长，用户明确要求避免过度验证后已停止（退出143），不再追加全量重跑，不宣称整仓/后端完整门禁完成。本任务临时PostgreSQL已停止；未启动浏览器、未重启或部署、未提交。结果详见technical-advisor-app/docs/conversation-agent-picker-results-2026-10-16.md。
