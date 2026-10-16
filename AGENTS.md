# AgentScope Java — AI 导航入口

本文件是导航入口，全部规范与项目信息集中在 `.ai-spec/`（SSOT），不在本文件维护内容。

## 快速路径（任何改动前）

1. **定位**：[.ai-spec/project-map.md](.ai-spec/project-map.md) — 模块 → 职责 → 技术栈 → 适用规范，及按需阅读文档
2. **判级**：[.ai-spec/change-levels.md](.ai-spec/change-levels.md) — 改动分级（低/中/高）、通用改动流程、验证矩阵
3. **编码**：遵循涉及领域的规范（backend/java.md · frontend/spec.md）；"统一写法"条目直接决定代码模式；规范未覆盖的跟随模块既有写法
4. **门禁**：运行 backend/gates.md 或 frontend/gates.md 中对应等级的命令，全部通过才算完成

协作与汇报方式见 [.ai-spec/collaboration.md](.ai-spec/collaboration.md)。

## 文档索引

| 文件 | 内容 |
|---|---|
| [.ai-spec/README.md](.ai-spec/README.md) | 规范体系索引、门禁/人工分级、AI 使用路径 |
| [.ai-spec/project-map.md](.ai-spec/project-map.md) | 项目地图 |
| [.ai-spec/change-levels.md](.ai-spec/change-levels.md) | 改动分级与流程 |
| [.ai-spec/collaboration.md](.ai-spec/collaboration.md) | AI 协作与沟通原则 |
| [.ai-spec/backend/](.ai-spec/backend/) | java.md / gates.md |
| [.ai-spec/frontend/](.ai-spec/frontend/) | spec.md / gates.md |

其他：贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)；文档规范见 [docs/README.md](docs/README.md)；命令与版本的事实来源是 POM、manifest 与 GitHub workflows。
