# 会话智能体选择器交付记录

日期：2026-10-16。依据用户确认方案及追加呈现要求完成。

- 输入区模型选择旁加入智能体入口，欢迎页/已有会话/问题卡替代输入时均可使用。个人默认可被本次选择覆盖，明确选择自由模式不会被默认值覆盖。
- 搜索200ms防抖后请求后端，数据库过滤可见性、关键词并分页，每页5个智能体；没有全量清单本地filter。选中名称通过可见单条查询获取。
- 卡片随真实结果数量收短，最大340px及视口可用高度，名称12px摘要11px；无左右翻页按钮，无“仅使用此智能体”提示。
- 滚轮有效上下翻页均请求后端，回翻同样重查，首尾不越界；触控滑动及PageUp/PageDown同样支持后端翻页，搜索进行中不误翻。滚轮拦截在卡片内，不带动页面。
- 选择只改变待提交值；普通消息、排队、回答和重试捕获本次选择，当前轮继续原运行时，工具确认恢复不切换。明确重选后重试可以使用新智能体。
- 后端route优先处理显式string/null选择，非法或不可见选择拒绝且不改绑定；省略字段保留旧行为。真正改变绑定复用已有withIdleThread，活动运行/待工具确认不可被另一标签直接重绑。
- 能力范围复用已有AgentProvisioner按绑定挂载及AG-UI绑定/归属校验；没有新增越界工具或修改框架公共运行时。
- 新建输入容器max-w-2xl，空白输入高度从104px缩到72px；长文本200px后内部滚动。

主要文件：前端AgentPicker.tsx及测试、ComposerDock/ChatPage/ConversationPanel、useAdvisorConversation、api/agent.ts、newConversation及中英词典；后端AdvisorAgentController、AgentStore及相关测试。混合工作区其他会话/问答/恢复改动保留，未作为本任务成果归因。

## 实际验证

日志根目录：/tmp/conversation-agent-picker-20261016。

| 检查 | 结果 |
| --- | --- |
| 后端行为RED | backend-red.log正常锁定切换无效及自由选择未落库；page-backend-red.log无分页响应 |
| 选择器RED | picker-red.log四项缺失功能；picker-retry-red.log重选后重试仍用旧参数 |
| 后端定向最终 | review-backend-tests.log，带-am测试29项通过，含绑定/归属拒绝、显式切换与自由、后端分页搜索、活动线程拒绝变更 |
| 前端最终全量 | frontend-tests-final2.log，84文件752项通过（当前混合工作区总数） |
| 类型与构建 | frontend-build-final2.log，tsc和Vite通过 |
| Lint | frontend-lint-final2.log，0错误46警告，无禁用规则 |
| 文档脚本 | doc-tests.log，12项通过 |
| 审查 | 只读审查发现的当前名解析、空闲线程保护、搜索焦点翻页问题已修复；未额外调用真实外部模型/MCP |

边界：控制器活动互斥测试替换了运行时边界，证明拒绝时原绑定不变；已有withIdleThread真实实现经源码核对，未新增浏览器/真实任务集成验收。未启动浏览器，不将组件测试当作视觉验收。

后端单模块verify加载本地旧依赖，缺少现有withIdleThread，报NoSuchMethodError；该轮不算通过。带-am的定向测试已证明当前源码依赖下通过。随后全依赖verify在运行依赖测试时，按用户“不要过度验证”的指示主动终止（退出143），未再追加重跑，因此没有后端完整verify/整仓clean verify成功结论。

临时测试PostgreSQL已停止；应用服务未重启、未部署；未提交、推送或修改他人工作。前后端源码更新完成不代表运行服务已加载最新版本。
