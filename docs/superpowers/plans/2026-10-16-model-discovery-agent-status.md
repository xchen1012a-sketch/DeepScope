# 模型发现与智能体启停实施计划

## 范围与契约

- 向量模型获取及后端缓存搜索保留服务商返回的全部模型 ID，不再依赖名称或 embedding 能力标记。向量模型服务商与对话模型共用同一目录；静态建议复用已有 ID，不虚构新模型。保存时仍通过真实向量响应验证能力和维度，列表别名展示保持原样。
- 向量维度列表表头和数值均不换行，使用 DataTable 的列样式扩展。
- 智能体新增持久化 `enabled` 布尔状态（数据库 INTEGER 默认 1），与创建流程的 lifecycle 分离；历史记录默认启用。管理列表返回真实状态；草稿显示未完成，不能启停。
- 复用 PATCH `/api/advisor/agents/{agentId}`，增加可选 `enabled: boolean`；非法类型 400，不可见 404，无编辑权限 403。启用重建运行时，停用注销并按现有租约机制延后关闭运行中的连接；新选择、路由和运行入口拒绝停用智能体。正式列表增加编辑、删除、启用/停用三项，删除复用现有确认组件及 DELETE；草稿复用原草稿删除接口。

## 执行清单

- [x] 先调整模型发现、服务商测试及新增启停回归，确认旧行为失败。
- [x] 去掉发现结果分类过滤，复用服务商目录；调整向量维度列。
- [x] 增加数据库列、AgentRow兼容构造、状态保存与返回；管理保留停用项，聊天与运行时排除停用项，配置重建跳过停用项。
- [x] 标准列表增加状态标签与三个操作，处理等待、错误、删除确认及缓存刷新；保留原别名展示。
- [x] 运行受影响模型/智能体后端、管理前端定向测试及格式、类型门禁，补 EN/ZH 文档。仅在新失败或后续修改影响下复验，不启动浏览器、不启停后端、不提交。

## 验收与兼容

不含 embedding 字样的火山方舟部署 ID 能在获取与搜索结果中出现；两个模型类型的服务商目录一致。停用状态重启后保留，停用智能体可编辑、删除、重新启用，但不能启动新执行。已有会话与资源不删除；当前执行沿用已有连接退役机制，不由本次操作强制取消。旧 AgentRow 构造保持兼容，新增 API 字段为增量；撤回代码时新增列可保留，恢复默认启用须单独明确操作。

## 实施及验证记录

- 模型发现及服务商测试、启停测试先出现预期失败：缺少 enabled、服务商被过滤、未分类 ID 被过滤。前端新增启停与删除确认回归也先确认失败。
- 后端定向 ModelManagementTest、DiscoveredModelCacheTest、ModelDiscoveryClientTest、AdvisorAgentControllerTest、ToolRuntimeCoordinatorTest 通过，日志 `/tmp/model-agent-status-green.log`；后续 overview、分页回归通过，日志 `/tmp/model-agent-overview-final.log`。分页旧用例中管理员可跨用户读取工具/技能的两个期望已按此前确认的 owner-only 规则修正。
- 前端 SettingsPage 20 项、AgentManagement 最终 21 项通过，日志 `/tmp/model-agent-status-front-green.log` 与 `/tmp/agent-status-front-final.log`；构建通过，定向 ESLint 0 error、7 条既有 warning。没有浏览器或全量验证。
- 静态审查补出停用时文件保存与工具绑定仍调用 register 的问题，改为停用时仅保存、启用时加载；文件保存回归已复现原错误。对应日志 `/tmp/agent-disabled-edit-red.log`、`/tmp/agent-disabled-edit-green.log`。
- Java 使用 scoped Spotless，最终检查 `git diff --check`。未对真实火山方舟凭据执行联调，未改运行中数据库或重启服务；重启新后端时自动添加 enabled 列并为历史记录默认启用。
