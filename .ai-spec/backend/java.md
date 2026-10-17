# Java 工程规范

适用模块：`deepscope-core`、`deepscope-harness`、`deepscope-extensions`、`deepscope-app/backend`、`deepscope-examples`、`deepscope-dependencies-bom`、`deepscope-distribution`。

技术基线：Java 17（禁止 preview 特性与 switch 模式匹配）、Maven 多模块（`-T1` 构建）、Reactor、JUnit 5 + Mockito + StepVerifier；**core 与 harness 保持 Spring 无关**；extensions 中 `deepscope-spring-boot-starters/*` 及部分 channel/protocol 扩展基于 Spring，属设计内选择，但 Spring 依赖不得反向带入 core/harness；`deepscope-app/backend` 作为应用层宿主使用 Spring Boot。

## 门禁规则（工具强制）

完整命令清单见 [gates.md](gates.md)。

- **【门禁】格式**：Spotless 统一使用 google-java-format（AOSP 风格，4 空格缩进），含 import 排序、禁止通配符 import、禁止未使用 import；`mvn -T1 -pl <module> -am spotless:check` 必须通过。
- **【门禁】License 头**：所有源码文件必须带 Apache 2.0 许可证头，CI 通过 skywalking-eyes（`.licenserc.yaml`）强制检查。
- **【门禁】编译与测试**：`mvn -T1 -pl <module> -am test`；跨模块或公共契约变更必须 `mvn -B -T1 clean verify` 全量通过（Ubuntu 与 Windows 双平台语义，禁止为 Windows 偶发问题弱化断言）。
- **【门禁】依赖版本**：所有第三方依赖版本必须收敛在既有 BOM/POM 层级（`deepscope-dependencies-bom` → distribution BOM → 模块 POM），不得在业务模块 POM 中散落硬编码版本号。
- **【门禁】模块同步**：新增/调整扩展模块（`deepscope-extensions/*`）后，parent 模块、`deepscope-distribution` 与 BOM 必须同步更新，且 `.github/scripts/check-shade-and-bom-sync.sh` 必须通过。

## 人工规范（评审约束）

### 架构边界

- **【人工】分层归属**：可复用的 Agent 行为放入 `deepscope-core`；workspace、memory、subagents、channels、`HarnessAgent` 等高层能力放入 `deepscope-harness`；Provider 特定实现（模型、存储、协议、channel、sandbox）放入 `deepscope-extensions`；应用装配与业务编排代码放入 `examples` 与 `deepscope-app/backend`。
- **【人工】依赖方向**：core 不依赖 Harness 与任何具体 extension；底层库模块不得反向依赖 `deepscope-app/*` 与 `deepscope-examples/*`；传输层 handler 保持薄（decode → call → map）。
- **【人工】扩展方式**：Agent 行为扩展只能通过 `MiddlewareBase` / `MiddlewareChain` 组合，禁止 fork 或继承改写 Agent 核心类。

### 兼容性

- **【人工】公共契约**：公共 builder、接口签名、序列化状态格式、事件 payload 必须保持向后兼容；`call()` 与 `streamEvents()` 的终态结果必须一致。涉及变更即为高级改动（见 [../change-levels.md](../change-levels.md)）。
- **【人工】响应式纪律**：不得阻塞响应式事件循环线程；保持 Reactor 取消、错误传播、资源清理语义与中间件顺序、权限检查链不被绕过。

### 安全红线

- **【人工】禁止硬编码凭据**：API Key、密码、Token 等敏感信息禁止出现在源码与配置文件中，必须通过环境变量或外部密钥管理注入；测试使用 fake 凭据。
- **【人工】依赖安全**：新引入的第三方依赖不得存在已知高危 CVE（CVSS ≥ 7.0）；升级依赖时须确认无新引入的高危漏洞。
- **【人工】输入边界**：来自外部的输入（用户消息、工具返回、HTTP 请求参数）不得未经校验直接拼入命令、SQL、文件路径或模板；使用参数化方式或既有的校验/转义工具。
- **【人工】工具执行门禁**：具备写操作、系统变更或外部副作用的工具（如 Catalog Tool / MCP 调用）必须提供确认机制或拦截门禁，禁止无约束自动执行。

### 状态与会话

- **【人工】运行时身份**：用户/会话身份一律通过 `RuntimeContext` 传递，禁止通过 Agent 实例字段共享会话状态。
- **【人工】持久化**：状态存取必须走已配置的 state store 与 Harness workspace 抽象，禁止绕过抽象直接操作存储。

### 统一写法（既有惯例，评审对照）

以下写法已在代码中形成事实统一（证据：`ToolCallParam`、`SubAgentConfig`、`AgentSkill` 等），新代码必须跟随，防止风格分裂：

- **【人工】公开配置/参数类用 Builder**：私有构造 + `public static Builder builder()`；演进时只往 Builder 加字段，不加公开构造函数或 setter——这是公共 API 兼容性的落地形式。
- **【人工】事件 payload 序列化兼容**：事件类继承 `AgentEvent`（`XxxEvent` 命名），序列化字段一律显式 `@JsonProperty`；字段改名/删除即破坏事件契约，属高级改动。
- **【人工】日志**：新代码统一 slf4j（`LoggerFactory.getLogger(Xxx.class)`），不新增 `System.out`（存量使用不主动清理）；不引入新日志门面。
- **【人工】库模块禁用 Lombok**：core / harness / extensions 不引入 Lombok，传递依赖须显式 exclude（参考 model-dashscope、rag-simple POM 现有写法）；examples/documentation 为存量例外。
- **【人工】不可变优先**：值对象与多态结果用 `record`（可参考 `SessionAnswer` 的 sealed interface + record 模式）；可选返回用 `Optional` 而非 null 约定。
- **【人工】错误响应格式统一**：对外 API 的错误响应须遵循统一结构（错误码 + 人可读消息 + 可选详情），禁止各接口自行发明错误格式；错误码体系变更属于公共契约变更，按高级改动处理。

### 测试质量

- **【人工】测试要求**：Bug 修复必须附带回归测试；新功能必须有行为测试。优先使用 JUnit 5、Mockito、Reactor `StepVerifier`、fake 实现与本地 fixture。
- **【人工】可移植性**：测试使用临时目录与可移植路径；禁止使用真实凭据或真实 Provider 服务。
- **【人工】文档同步**：用户可见行为变化必须同步更新 `docs/v2` 对应 EN 与 ZH 页面及受影响示例（EN/ZH 保持对齐）。
