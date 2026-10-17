# 前端自动化门禁清单

所有命令在 `deepscope-app/frontend` 目录执行；门禁失败必须修复后重跑，禁止跳过、禁用规则或弱化断言。

| 场景 | 命令 |
|---|---|
| Lint | `npm run lint`（`eslint src --ext ts,tsx`） |
| 单元测试 | `npm test`（`vitest run`） |
| 构建 + 类型检查 | `npm run build`（`tsc --noEmit && vite build`） |
| E2E 测试 | `npm run test:e2e`（Playwright，关键用户流程变更时必跑） |

## 门禁执行要求

- TypeScript 编译错误（`tsc --noEmit`）与 ESLint error 一律阻断，不允许 `any` 逃逸 lint 规则之外的类型谎言（`@typescript-eslint/no-explicit-any` 当前为 off，评审时按人工规范把关真实类型收敛）。
- 提交前按 [../change-levels.md](../change-levels.md) 的验证矩阵选择命令级别执行。
