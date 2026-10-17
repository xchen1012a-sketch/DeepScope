# 代码审查不规范问题修复方案与清单

> **执行规范**：按批次与依赖顺序推进；每完成一个批次执行自动化回归验证；严禁未授权提交代码与分支推送。

**目标**：彻底解决项目全量审查中发现的 7 处不合规与阻断问题，恢复后端全量单测通过率（100%），统一 API 响应格式，消除脏数据与多会话串扰隐患，完成项目标识收敛。

---

## 修复清单与执行批次

### 第一批次：解阻后端单测与 CI 门禁（P0 致命阻塞）
- [x] **Task 1: 修复 TaskBoardActions 构造器依赖注入缺失**
  - **文件**：`deepscope-app/backend/src/main/java/io/deepscope/advisor/task/TaskBoardActions.java`
  - **原因**：类标注了 `@Component` 但定义了两个构造器，均未加 `@Autowired`，导致 Spring 无法自动判定，初始化报 `BeanInstantiationException: No default constructor found`，造成 45+ 单测及 Spring 上下文启动直接崩溃。
  - **修复**：主构造器增加 `@Autowired` 注解，声明依赖注入路径。
  - **验证**：运行 `mvn test -pl deepscope-app/backend -Dtest=TechnicalAdvisorApplicationTest` 确保上下文启动正常。

- [x] **Task 2: 修复 PgChunkIndexTest 破坏测试可移植性规则**
  - **文件**：`deepscope-app/backend/src/test/java/io/deepscope/advisor/knowledge/PgChunkIndexTest.java`
  - **原因**：无条件执行 `assertNotNull(System.getenv("ADVISOR_TEST_PGVECTOR_URL"))`，违反 `.ai-spec/backend/java.md`（离线环境不得强依赖外部数据库）。
  - **修复**：类级别增加 `@EnabledIfEnvironmentVariable(named = "ADVISOR_TEST_PGVECTOR_URL", matches = ".+")`，有环境变量时跑全量向量集成测试，无环境变量时优雅跳过。
  - **验证**：不配置环境变量下运行 `mvn test -pl deepscope-app/backend -Dtest=PgChunkIndexTest` 结果为 SKIP 而非 FAIL。

- [x] **Task 3: 消除 WaitAsyncResultsToolTest 冗余 120s 阻塞**
  - **文件**：`deepscope-harness/src/test/java/io/deepscope/harness/agent/tool/WaitAsyncResultsToolTest.java`
  - **原因**：`timeoutClampedToMax` 传入 600 被 clamp 到 120 秒，且向空总线轮询，导致单测死等 2 分钟。
  - **修复**：将 `normalizeTimeout` 提为可见方法直接断言边界值（0ms），集成超时测试改用 1s 超时，彻底消除 120 秒等待。
  - **验证**：运行 `mvn test -pl deepscope-harness -Dtest=WaitAsyncResultsToolTest`，全量测试应在 5 秒内完成。

---

### 第二批次：状态安全与接口规范一致性（P1 架构规范）
- [x] **Task 4: 修复 TaskBoard 跨会话串扰隐患与持久化闭环**
  - **文件**：`deepscope-app/backend/src/main/java/io/deepscope/advisor/task/TaskBoardActions.java`
  - **原因**：`resolveSessionId` 在会话 ID 为空时回退到静态 `"default-session"`，导致无会话或并发请求串入同一块看板。
  - **修复**：移除 static fallback，会话缺失时抛出显式异常拒绝操作，严格保障会话隔离；确保与 `TaskBoardStore` 持久化生命周期配合。
  - **验证**：运行 `mvn test -pl deepscope-app/backend -Dtest=TaskBoardActionsTest` 并补充缺少会话 ID 的拦截断言。

- [x] **Task 5: 统一 AuthController 错误响应结构契约**
  - **文件**：
    - `deepscope-app/backend/src/main/java/io/deepscope/advisor/auth/AuthController.java`
    - `deepscope-app/frontend/src/pages/RegisterPage.tsx`
  - **原因**：`AuthController` 注册接口返回 `{"error": ...}`，而 `AdvisorAccessController` 与全站规范采用 `{"message": ...}`，违反统一错误结构规范。
  - **修复**：统一响应结构包含 `"message"`，同时兼容保留 `"error"` 字段避免破坏现有前端调用；前端 `RegisterPage` 增加对 `message` 字段的解构。
  - **验证**：运行 `mvn test -pl deepscope-app/backend -Dtest=AuthControllerTest` 及前端 `npm test -- RegisterPage.test.tsx`。

---

### 第三批次：目录清理与资产收敛（P2 代码卫生）
- [x] **Task 6: 清理残留软链接与配置 `.gitignore` 规则**
  - **文件**：
    - 根目录 `.gitignore`
    - 清理根目录残留软链接 `technical-advisor-app`
  - **原因**：重命名遗留的软链接未清理；相对路径配置导致测试在后端目录生成 `data/` 未被根级 `.gitignore` 命中。
  - **修复**：删除无用软链接，在 `.gitignore` 增加通配 `**/data/`，确保任何子模块运行测试产生的临时持久化目录均不污染工作区。
  - **验证**：执行 `git status` 确认工作区无意外产生的 untracked 文件。

- [x] **Task 7: 统一前端工程包名为 deepscope-frontend**
  - **文件**：`deepscope-app/frontend/package.json`（及 `package-lock.json`）
  - **原因**：前端 package.json 的 name 仍为 `technical-advisor-frontend`。
  - **修复**：修正为 `deepscope-frontend`，与项目统一命名一致。
  - **验证**：执行 `npm run build` 确保前端构建与类型检查通过。

---

## 终态回归验证标准
1. `mvn -T1 spotless:check` 通过
2. `mvn -T1 test` 核心模块全通
3. 前端 `npm run lint`、`npm run build`、`npm test` 全通
4. `git status` 状态干净，无非预期脏文件
