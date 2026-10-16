# 技术参谋后端

单体应用，运行智能体、AG-UI 对话和框架管理接口。前端开发时通过 localhost:18080 访问此服务。

管理员首次创建：启动时设置 ADVISOR_ADMIN_USERNAME 与 ADVISOR_ADMIN_PASSWORD。账号和登录会话保存到配置的 PostgreSQL 数据库；重启不会重置密码。现有账号无需再次设置引导环境变量。勿把明文密码加入源码或配置提交。

详细说明见 [管理员登录](../../docs/v2/zh/docs/others/technical-advisor-login.md)。当前登录、身份、个人资料读取、单空间范围与登出接口已接入。原平台的议题、对话列表及其他业务接口仍需后续迁移，不能把登录成功视为整个平台已对接。

验证：在仓库根目录执行 mvn -T1 -pl technical-advisor-app/backend -am test 和 spotless:check。认证测试使用临时数据库与测试凭据；代码评审发现的管理路径参数/servlet前缀和opaque令牌显示问题均有回归测试。
