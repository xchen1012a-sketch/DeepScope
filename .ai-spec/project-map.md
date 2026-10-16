# 项目地图

模块职责与文档索引。来源：根目录 `AGENTS.md` §2（官方）。改动前先在此定位：改哪个模块 → 看哪个规范 → 读哪份背景文档。

## 模块

| 模块 | 职责 | 技术栈 | 适用规范 |
|---|---|---|---|
| `agentscope-core/` | Agent 核心 API、ReAct 循环、消息、事件、工具、中间件、状态 | Java 17 / Reactor（Spring 无关） | [backend/java.md](backend/java.md) |
| `agentscope-harness/` | workspace、memory、subagents、channels、`HarnessAgent` | Java 17（Spring 无关） | [backend/java.md](backend/java.md) |
| `agentscope-extensions/` | model / storage / protocol / channel / sandbox / 框架集成 + spring-boot-starters | Java 17（部分扩展基于 Spring，设计内） | [backend/java.md](backend/java.md) |
| `technical-advisor-app/` | 技术参谋演示应用（`frontend/` 为原平台控制台整体剥离的 React 底子） | React 18 / Vite 6 / Tailwind 4 | [frontend/spec.md](frontend/spec.md) |
| `agentscope-examples/` | 可运行示例（不被任何库模块依赖） | Java | [backend/java.md](backend/java.md) |
| `agentscope-dependencies-bom/` | 第三方依赖版本 | Maven BOM | [backend/java.md](backend/java.md) |
| `agentscope-distribution/` | 发布用 BOM 与聚合分发 | Maven | [backend/java.md](backend/java.md) |
| `docs/` | Mintlify 文档站（EN/ZH 双语对齐） | Mintlify | [docs/README.md](../docs/README.md) |

## 按需阅读

| 场景 | 文档 |
|---|---|
| 贡献规则 | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| Harness 行为 | [docs/v2/en/docs/harness/architecture.md](../docs/v2/en/docs/harness/architecture.md) |
| Channel 词汇表 | [CONTEXT.md](../CONTEXT.md) |
| 文档工作 | [docs/README.md](../docs/README.md) |

命令与版本的事实来源始终是 POM、manifest 与 GitHub workflows；本地图仅作导航，两者冲突时以 POM/workflows 为准。
