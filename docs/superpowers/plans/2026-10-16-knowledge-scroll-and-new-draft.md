# 知识库列表内部滚动与新建智能体临时编辑修复

用户授权范围：知识库主列表固定滚动；新建智能体随意输入再删除不自动产生侧栏草稿。保留其他任务改动，不启动浏览器。

## 实施

1. 知识库主列表使用 Page fill；工具栏不收缩；DataTable 增加可选 scrollable，列表区内滚动、表头固定、分页不收缩。默认行为不变，其他列表不自动启用。
2. AppShell 只在知识库主列表路由将 main 设为高度受限容器；知识库详情沿用既有布局。
3. useAgentCreationDraft 增加 deferInitialSave 可选参数，新建页启用：无服务端草稿时不因输入触发 createDraft，主动 flush 才保存；已存在草稿继续 800ms 自动保存。空白新建页 flush 返回 null，不生成空草稿；删空后清除 dirty。复制模式允许主动 flush 创建副本，不能被空字段判断阻断。
4. 保留现有离开页面的保存/放弃机制，不自动删除用户已有草稿。只在用户明确操作时创建服务端记录。
5. 草稿行为做定向 RED/GREEN 回归；样式调整复用现有列表/表格与应用布局测试，不另写模拟 CSS 的测试。运行相关测试、构建及涉及文件 lint 一次，失败只重跑对应项。EN/ZH 文档同步。

## 完成与证据

- 知识库列表已启用 Page fill、DataTable 可选 scrollable、应用主区域高度约束；表头 sticky，列表内部 overflow-y-auto/overscroll-contain，分页 shrink-0。其他 DataTable 调用默认行为不变。
- 新建页启用 deferInitialSave，输入不触发首次服务端保存；已有草稿仍自动保存。无内容且非复制的 flush 返回 null，不创建空白记录；删空时 dirty 对外为 false。复制仍保留显式保存路径，现有草稿不被自动删除。
- RED 新增测试复现临时输入自动创建问题，日志 `/tmp/new-agent-draft-red.log`。失败测试遗留的一次性 mock 队列通过 beforeEach mockReset 隔离，未弱化业务断言。
- 修复后一次定向测试：创建页、草稿 hook、知识库页、DataTable 及状态、AppShell 共 6 文件 39 项通过。日志 `/tmp/knowledge-scroll-draft-tests.log`。
- 类型检查/生产构建通过，日志 `/tmp/knowledge-scroll-draft-build.log`；涉及 7 个 TS/TSX 文件 lint 通过且无输出，日志 `/tmp/knowledge-scroll-draft-lint.log`。diff 空白检查通过。
- 文档 EN/ZH 已同步。未启动浏览器、未重启服务、未做整仓验证、未提交推送。CSS 布局已完成代码与构建检查，未声称浏览器实测。
