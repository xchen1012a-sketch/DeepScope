# 用户、角色编号与编辑修正

按用户本轮六项要求直接实施，中级应用内改动。

状态：已完成；验证记录见 `technical-advisor-app/docs/access-numbers-and-editors-results-2026-10-16.md`。

## 契约与兼容

- 在 accounts 增加数据库生成的唯一 user_number，在 advisor_roles 增加 role_number，均从 1 起。已有记录在初始化迁移时分配；后续编号持久化，不随筛选、分页或删除重新计算。
- API 保留原 UUID userId/roleId 以及关联，新增数字 userNumber/roleNumber。数字列仅用于用户可见编号，不替换现有资源路由与外键。
- PUT /api/advisor/users/{userId} 接收 username、nickname、remark。username 可修改且唯一；未提交 roleIds 时不修改授权。空账号或冲突返回 400，不修改其他资料。
- 内置管理员更名后，启动引导使用已有内置身份判断是否已初始化，防止按旧配置重复创建管理员及恢复旧密码。
- POST/PUT /api/advisor/roles 接收 name、code、sort、status，在同一条写入语句中保存；未提交描述或状态时保留旧值。空名称/标识、冲突标识、非法状态返回 400。
- 角色操作固定为编辑、删除、更多；更多只包含菜单权限。内置角色编辑、删除禁用，原有内置账号/角色及最后超级管理员保护保留。

## 实施与验证

1. 先补 H2 迁移、稳定编号、账号更名与登录、冲突回滚、角色四字段原子更新和内置保护的后端失败测试。
2. 更新 AdvisorSchema、AccountStore、AccessCatalog、AdvisorAccessController，保持现有框架和参数化 SQL，不引入依赖。
3. 修改 UsersPage、RolesPage 与既有组件测试：数字编号、编辑回填及请求内容、操作顺序、菜单权限入口和失败保留。
4. 运行相关后端测试与 Spotless，前端测试、Lint、Build；同步 docs/v2 中英文说明。必要时在隔离 PostgreSQL 验证编号迁移；不启动浏览器，不重启或停止用户自行管理的后端。

完成标准：六项要求可由行为测试对应验证，旧权限/会话/关联保持，提交与启停均由用户自行处理。
