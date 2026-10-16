# 用户角色下拉选择实施计划

## 范围与方案

- 用户管理的分配角色、新建用户角色字段改用共享 `AppSelect`，替换平铺按钮。
- 扩展现有下拉组件的可选 `multiple` 模式，保留所有单选调用与样式；使用既有 Radix 复选菜单项处理多选和键盘操作，不新建第二套选择器。
- 展示已选角色名称；多选时选中选项后保持菜单打开，支持清除。保留已有角色、授权权限检查及服务端 `roleIds` 保存接口。
- 不修改后端角色或权限模型。角色查询、保存失败处理沿用现有逻辑。

## 验证与文档

- 行为测试覆盖已有角色回显、多选提交、取消不保存、清除；相邻用户/角色页面及共享选择器使用方执行回归。
- 执行前端测试、Lint、TypeScript/Vite 构建与 `git diff --check`。
- 同步 docs/v2 EN/ZH 用户角色管理说明。不启动浏览器，不启停后端，不提交代码。

## 执行结果

### 追加：角色搜索

- 在共享下拉组件内增加可选搜索区，复用现有输入组件；输入防抖约 200ms，角色名称通过 `GET /api/advisor/roles?q=...&purpose=user-assignment` 在后端匹配，不在前端过滤完整角色列表。
- 继续使用已有后端接口和 TanStack Query，通过关键词隔离查询结果。搜索不修改已勾选角色，保留已选名称；加载、空结果与失败重试分别展示。
- 覆盖后端请求、跨搜索保留选择并保存、空结果与失败重试，运行共享组件相关回归、Lint 与构建。仍不启动浏览器或启停后端。

搜索实现已完成，角色管理行为测试 17 条通过，Lint 0 error、TypeScript/Vite 构建与 diff 检查通过。全量回归当次为 712/713，通过之外的一项是共享工作区个人设置页的重复文案定位断言；该文件随后已有其他改动修正，单独重跑 13/13 通过，见 `/tmp/user-role-search-account-recheck.log`。本次未修改个人设置页面或其测试。日志另见 `/tmp/user-role-search-targets.log`、`/tmp/user-role-search-all-tests.log`、`/tmp/user-role-search-lint.log`、`/tmp/user-role-search-build.log`。

- 已扩展共享 `AppSelect` 多选模式，替换用户创建与角色分配的平铺按钮；已选名称回显、连续勾选、清除、取消和保存沿用现有授权接口。
- 新增多角色提交和清除后取消不保存的回归测试；前端全量 82 个文件、713 条用例通过。
- Lint 0 error（45 条存量 warning）、TypeScript/Vite 构建、`git diff --check` 均通过。
- 未启动浏览器，未启停后端，未提交或推送。验证日志：`/tmp/user-role-dropdown-all-tests.log`、`/tmp/user-role-dropdown-lint.log`、`/tmp/user-role-dropdown-build.log`。
