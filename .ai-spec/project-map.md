# 项目地图

模块职责与文档索引。来源：根目录 `AGENTS.md` §2（官方）。改动前先在此定位：改哪个模块 → 看哪个规范 → 读哪份背景文档。

## 模块

| 模块 | 职责 | 技术栈 | 适用规范 |
|---|---|---|---|
| `deepscope-core/` | Agent 核心 API、ReAct 循环、消息、事件、工具、中间件、状态 | Java 17 / Reactor（Spring 无关） | [backend/java.md](backend/java.md) |
| `deepscope-harness/` | workspace、memory、subagents、channels、`HarnessAgent` | Java 17（Spring 无关） | [backend/java.md](backend/java.md) |
| `deepscope-extensions/` | model / storage / protocol / channel / sandbox / 框架集成 + spring-boot-starters | Java 17（部分扩展基于 Spring，设计内） | [backend/java.md](backend/java.md) |
| `deepscope-app/backend/` | 技术参谋应用后端（宿主 HarnessAgent、AG-UI 聊天 API、管理面、RAG 与工具治理） | Java 17 / Spring Boot / Maven | [backend/java.md](backend/java.md) |
| `deepscope-app/frontend/` | 技术参谋 Web 工作台（聊天界面、工具/智能体交互面板） | React 18 / Vite 6 / Tailwind 4 | [frontend/spec.md](frontend/spec.md) |
| `deepscope-examples/` | 可运行示例（不被任何库模块依赖） | Java | [backend/java.md](backend/java.md) |
| `deepscope-dependencies-bom/` | 第三方依赖版本 | Maven BOM | [backend/java.md](backend/java.md) |
| `deepscope-distribution/` | 发布用 BOM 与聚合分发 | Maven | [backend/java.md](backend/java.md) |
| `docs/` | Mintlify 文档站（EN/ZH 双语对齐） | Mintlify | [docs/README.md](../docs/README.md) |

## 按需阅读

| 场景 | 文档 |
|---|---|
| 协作与提交规则 | [collaboration.md](collaboration.md) |
| Harness 行为 | [docs/v2/en/docs/harness/architecture.md](../docs/v2/en/docs/harness/architecture.md) |
| Channel 词汇表 | [CONTEXT.md](../CONTEXT.md) |
| 文档工作 | [docs/README.md](../docs/README.md) |

命令与版本的事实来源始终是 POM、manifest 与 GitHub workflows；本地图仅作导航，两者冲突时以 POM/workflows 为准。
