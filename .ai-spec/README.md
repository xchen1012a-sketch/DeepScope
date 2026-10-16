# .ai-spec — 工程规范唯一可信源（SSOT）

本目录集中存放 AgentScope Java 的全部工程规范。根目录的 `AGENTS.md` 与 `CLAUDE.md`（两者内容相同）是轻量导航入口，规范内容只在本目录维护，禁止在别处复制副本。

## 目录结构

| 路径 | 内容 |
|---|---|
| [change-levels.md](change-levels.md) | 改动分级定义与各级处理流程（通用） |
| [collaboration.md](collaboration.md) | AI 协作与沟通原则 |
| [project-map.md](project-map.md) | 项目地图：模块 → 职责 → 技术栈 → 适用规范，及按需阅读文档 |
| [backend/java.md](backend/java.md) | Java 侧工程规范（core / harness / extensions / examples） |
| [backend/gates.md](backend/gates.md) | 后端自动化门禁清单（Java + CI） |
| [frontend/spec.md](frontend/spec.md) | 前端工程规范（technical-advisor-app/frontend） |
| [frontend/gates.md](frontend/gates.md) | 前端自动化门禁清单 |

## 项目地图

模块职责、技术栈与适用规范的完整对照见 [project-map.md](project-map.md)（源自从 AGENTS.md §2）。

## 规范分级

所有规范条目分为两级，各规范文件内以标记区分：

- **【门禁】**：可由工具自动检测与强制执行（格式、编译、类型检查、Lint、测试、生成物一致性）。AI 完成改动后必须运行对应门禁命令并使其通过；门禁失败必须修复，禁止跳过、弱化断言或绕过工具。
- **【人工】**：无法由机器自动检测的规则（架构边界、兼容性、统一写法）。**AI 编码时必须主动遵循**，评审作为兜底约束。

### AI 使用路径

1. **改动前**：读 [change-levels.md](change-levels.md) 判定改动等级，按等级执行对应流程（低级直接编码；中级先产出实施计划；高级须人工评审方案后才能动手）。
2. **编码中**：遵循改动涉及领域的规范文件（[backend/java.md](backend/java.md) / [frontend/spec.md](frontend/spec.md)）；其中"统一写法"条目直接决定生成代码的模式选择。
3. **完成后**：运行 [backend/gates.md](backend/gates.md) 或 [frontend/gates.md](frontend/gates.md) 中对应等级的命令，全部通过才算完成。
4. **规范未覆盖的问题**：跟随改动所在模块既有代码的写法（局部一致性），不发明新模式。

### 自约束原则（控制规范重量）

- 工具能管的（格式、import 顺序、lint 规则覆盖的风格问题）不写入人工规范，避免与门禁重复。
- 人工规范只收录"违反会导致架构腐化或兼容性破坏"的最小集合，不追求穷举。
- 未收录的风格问题遵循**局部一致性**：跟随所在模块既有写法，不发起无关的风格统一。

## 内容边界（四不原则）

本目录仅收录工程规范本身（门禁规则、编码标准、质量要求、改动分级）。以下内容禁止写入：

1. 不包含限制 AI 能力的内容，不提供具体操作流程指导（step-by-step 教程）。
2. 不包含业务逻辑规则或具体业务实现细节。
3. 不作为项目记录文档，不记录历史变更、决策过程或进度。
4. 不重复其他文档：AI 协作与沟通原则见本目录 [collaboration.md](collaboration.md)，文档编写规范见 `docs/README.md`。工程规范如有冲突，以本目录为准。

## 技术栈基线

规范内容基于以下实际技术栈制定（技术栈或工具链变更时，必须同步更新对应规范文件）：

- **Java**：Java 17（无 preview 特性）、Maven 多模块（`-T1` 构建）、Reactor、JUnit 5 + Mockito、Spotless（google-java-format，AOSP 风格）
- **前端**：React 18 + TypeScript 5.7 + Vite 6 + Tailwind CSS 4 + Radix UI + TanStack Query 5 + i18next、Vitest + ESLint（flat config）+ Playwright
- **CI**：`mvn -B -T1 clean verify`（Ubuntu + Windows 双平台）、Apache License 头检查、BOM/shade 同步检查、前端 lint/test/build
