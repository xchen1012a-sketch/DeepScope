你是接手「技术顾问 App」工具 / MCP 模块的 AI。工作目录 `/Users/agent/agentscope-java`，业务代码只在 `technical-advisor-app/`。全程中文汇报，**结论先行、说重点，不要长篇抽象**。

## 先读，再动手

1. `docs/superpowers/plans/2026-10-13-tool-mcp-lifecycle.md` —— 总方案，12 项功能
2. `technical-advisor-app/docs/handoff-2026-10-14.md` —— 上一轮交接：已完成 / 未完成清单、关键决定、避坑

再读项目规范（**硬约束，不是建议**）：`.ai-spec/backend/java.md`、`.ai-spec/backend/gates.md`、`.ai-spec/change-levels.md`、`.ai-spec/collaboration.md`。
要点：Spotless = google-java-format AOSP 4 空格、必须有 License 头、JUnit 5、简洁优先、外科手术式编辑（只碰任务相关的行）、无关死代码只提及不删除、**未授权不 commit 不 push**。

## 第一件事：补跑没跑完的门禁，别先写新代码

```
mvn -T1 -pl technical-advisor-app/backend -am test
mvn -T1 -pl technical-advisor-app/backend spotless:check
```

```
cd technical-advisor-app/frontend && npm test && npm run lint && npm run build
```

**重点看整模块测试。** 上一轮新增了 `ToolCredentialStore` Bean，**Spring 上下文能不能起来从未验证过**（`TechnicalAdvisorApplicationTest` 就在这一类里）。有失败先修根因，不许靠跳过或弱化断言让门禁"通过"。修完再往下走。

## 范围纪律（最重要）

**只补齐"已经动手但没做完"的部分，不要开工新功能。**

- **可以碰**：导入前预览与逐项诊断、MCP 服务的连接编辑、执行入口的第二条路（绑定一个已有的 MCP 工具）
- **不要碰**（除非用户明确要求）：测试调用、MCP 刷新差异确认、删除影响提示、定向更新与运行租约、请求幂等、前端那 6 个新界面
- 数据库里之前加好但**没人读**的列和表（防重复提交表、配置版本历史表、`provider_name`、`lifecycle_status`、`pending_json`）**保持原样**，只提及不删除

拿不准某块算不算"已经开始"时，**问用户，不要猜**。

## 别踩

- 工作区混着**三批互不相关的未提交改动**（本轮的、之前智能体管理/斜杠命令的、以及两个 docs 文件）。不要 commit、不要 push，也不要顺手清理或"规整"别人的改动
- 不要改 `agentscope-core` / `agentscope-harness`
- 新 Java 文件先 `spotless:apply` 再过门禁，否则格式检查必挂
- 改前端文案必须中英词典同步（`i18n/locales/{en,zh-CN}/app.json`）
- H2 测试 URL 必须带 `DB_CLOSE_DELAY=-1`；前端 TS target 是 ES2020，没有 `.at()`
- 新增 Spring Bean 时 `ObjectMapper` 参数名用 `advisorObjectMapper`

## 汇报格式

改了哪些文件、跑了哪些检查及**实际结果**、还剩哪些缺口。
**跳过的测试就是缺口，必须明说。** 中间结论不要报成最终结果。
