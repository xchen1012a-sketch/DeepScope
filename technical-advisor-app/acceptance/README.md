# 阶段四真实验收模块

该模块独立保存可重复使用的验收用例，不启动浏览器、不模拟模型。使用已启动的本地后端及其真实模型配置，检查新建与复制、自由模式逐轮分派、普通访客批准内置提案返回 403，以及 F08 的提案、编辑草稿、批准落盘、下一轮新子智能体执行和真实来源结果。

运行前在仓库根目录通过后端门禁并生成启动包：

```sh
mvn -T1 -pl technical-advisor-app/backend -am package
```

从 `technical-advisor-app/backend` 启动该包，加载本地 `config/application.yaml`，使用 `ADVISOR_DATABASE_USERNAME=juvon`。为保持原运行数据，明确设置 `--advisor.data-dir=/Users/agent/agentscope-java/technical-advisor-app/data`。其他环境需按实际工作区调整该绝对路径。

在本模块设置环境变量后运行 `npm test`：

- `ADVISOR_ADMIN_USERNAME`：管理员账号，默认 `admin`。
- `ADVISOR_ADMIN_PASSWORD`：管理员密码，必填，不写入源码。
- `ADVISOR_BASE_URL`：默认 `http://localhost:18080`。
- `ADVISOR_DATABASE_URL`、`ADVISOR_DATABASE_USERNAME`、`ADVISOR_DATABASE_PASSWORD`：与后端保持一致，默认本地 technical_advisor 数据库与 juvon 账号。
- `ADVISOR_DATA_DIR`：与后端数据目录保持一致，默认本应用的 `data` 目录。

需要 Node.js、JDK 17+ 和已生成的后端包，不需要安装额外 npm 依赖。用例从真实前端 package.json 取验收依据，通过正常账号存储创建临时普通访客，测试结束删除本次创建的会话、智能体工作区、访客账号和临时解包目录。管理员既有数据不删除。

模型输出可能受服务状态影响；失败会保留真实断言，不用模拟数据替代，不自动重复模型请求。HTTP 用例覆盖前端使用的审批契约，界面渲染由前端单元测试和构建门禁覆盖。
