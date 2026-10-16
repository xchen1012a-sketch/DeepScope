# 智能体创建需求描述改为可选

用户已授权实现。保持现有创建 API、字段 purpose 和路由策略不变。

## 复核与改动

当前 CreationPurposeForm 展示“设定用途”，AgentCreationPage 顶部和底部创建按钮均用 purpose 非空控制；后端的正式创建校验要求名称及工作要求，不要求 purpose。

1. 文案改为“描述需求，AI 帮你生成”，辅助说明为“可选；仅用于 AI 辅助生成，不填写也可手动创建”。EN/ZH 同步；字段沿用 purpose，不迁移已有草稿。
2. 两个创建按钮仅在正在创建时禁用；空用途不再阻断，名称/工作要求错误继续通过既有 checks 展示。完全空白创建仍按原逻辑提示缺少基本信息。
3. AI 生成仍需要需求描述，未填时不发起模型请求。简介继续用于自动选择，工作要求继续约束执行。
4. 定向回归：用途为空，手填名称与工作要求，分别点击顶部/底部创建并完成 checks、activation；空表单不误创建。后端已有激活成功用例增加空用途参数覆盖，不改业务逻辑。
5. 只跑相关前端测试、构建/文件 lint 及后端相应测试类，不启动浏览器、不做无关回归。同步 docs/v2 EN/ZH 说明。

## 实际结果

已完成。前端创建页两处按钮解除 purpose 必填限制，EN/ZH 标签和可选提示同步；AI 生成仍要求描述非空。后端业务原已允许空用途，无需修改。

- 前端创建页与草稿 hook 定向测试：2 个文件、15 项通过，日志 `/tmp/agent-purpose-optional-tests.log`。覆盖顶部/底部在无用途时手动填写名称和工作要求，保存、检查、激活和导航完成，不调用 AI 生成。
- 后端仅 `AgentCreationActivationAndPreviewTest#activationSucceedsAndPreallocatedAgentIsMaterialized`：正常用途、空字符串、纯空格 3 项均通过，真实创建正式智能体、注册运行时及幂等重试断言保留。日志 `/tmp/agent-purpose-optional-backend.log`，BUILD SUCCESS。
- 前端生产构建/类型检查通过，日志 `/tmp/agent-purpose-optional-build.log`；3 个涉及 TSX 文件 lint 退出码 0、无输出，日志 `/tmp/agent-purpose-optional-lint.log`。
- 后端改动测试文件 Spotless 通过，定向 diff 空白检查通过。没有启动浏览器或重启应用，没有提交或推送。

RED 阶段新增文案用例在旧实现下失败。首次失败后未消费的 mockResolvedValueOnce 暴露出测试之间只 clearAllMocks 未清空返回队列的问题，因此为涉及 API mock 增加 mockReset，防止失败用例影响后续用例；未弱化业务断言。
