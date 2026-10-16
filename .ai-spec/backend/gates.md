# 后端自动化门禁清单

所有门禁命令在对应目录执行；门禁失败必须修复后重跑，禁止跳过、禁用规则或弱化断言。CI（`.github/workflows/maven-ci.yml`）会强制执行同等检查。

## Java（仓库根目录执行）

| 场景 | 命令 |
|---|---|
| 模块测试 | `mvn -T1 -pl <module> -am test` |
| 单个测试类 | `mvn -T1 -pl <module> -am test -Dtest=<TestClass>`（执行后确认确实运行） |
| 格式检查 | `mvn -T1 -pl <module> -am spotless:check` |
| 全量校验 | `mvn -B -T1 clean verify`（跨模块、公共契约、发布前必跑） |
| 扩展模块结构同步 | `.github/scripts/check-shade-and-bom-sync.sh`（新增/调整 `agentscope-extensions/*` 后） |

CI 额外强制：Apache License 头与第三方依赖 license 检查（skywalking-eyes，配置 `.licenserc.yaml`）；Ubuntu + Windows 双平台 `clean verify`。

## 门禁执行要求

- 提交前按 [../change-levels.md](../change-levels.md) 的验证矩阵选择命令级别执行。
- 定位到单个失败测试时使用精确的 `-Dtest=` / 包路径重跑，禁止用整段跳过（`-DskipTests` 等）方式让门禁"通过"。
- 本地无法复现的平台特有失败（如 Windows）不得通过弱化断言规避，须修复根因。
