# DeepScope

面向个人与团队的智能体工作台，把模型、工具、技能和知识库组织到同一个工作空间中，完成从对话到任务执行的完整流程。

由 [xchen1012a-sketch](https://github.com/xchen1012a-sketch) 维护。项目仓库：[DeepScope](https://github.com/xchen1012a-sketch/DeepScope)。

## 主要功能

- **智能对话**：流式回复、执行过程展示、模型与智能体切换、结构化追问、会话历史与上下文压缩。
- **智能体管理**：创建与配置智能体，编写工作要求，绑定工具、技能、知识库和子智能体。
- **工具与 MCP**：导入与编辑工具、连接 MCP 服务、测试调用，并按权限执行或等待确认。
- **技能管理**：管理技能包、编辑技能内容与发布状态，并同步到智能体工作空间。
- **知识库**：上传与处理文档，查看解析内容，配置向量检索；支持 PDF 页面处理与来源查看。
- **模型管理**：配置模型供应商、发现远端模型、测试连接，管理对话与向量模型。
- **账号与权限**：用户、角色和权限管理，以及个人资料、偏好与登录会话管理。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17+、Spring Boot、Maven |
| 前端 | React、TypeScript、Vite、Tailwind CSS |
| 数据存储 | PostgreSQL、pgvector |
| 智能体运行时 | DeepScope Java、AG-UI、MCP |

## 项目目录

```text
technical-advisor-app/
  backend/                 应用后端
  frontend/                Web 工作台
  docs/                    应用设计与验证记录
deepscope-core/           智能体基础能力
deepscope-harness/        工作空间与任务运行能力
deepscope-extensions/     模型、工具、协议与框架扩展
docs/                     技术文档与设计方案
.ai-spec/                 工程规范
```

现有模块路径沿用代码中的名称，应用入口位于 `technical-advisor-app`。

## 本地运行

准备 Java 17+、Maven、支持前端依赖的 Node.js/npm，以及安装了 pgvector 扩展的 PostgreSQL。先创建应用数据库和对应用户。

### 1. 配置环境

后端从环境变量读取配置，下面的值需替换为本机实际配置：

```sh
export ADVISOR_DATABASE_URL='jdbc:postgresql://localhost:5432/technical_advisor'
export ADVISOR_DATABASE_USERNAME='technical_advisor'
export ADVISOR_DATABASE_PASSWORD='<数据库密码>'
export ADVISOR_ADMIN_USERNAME='<初始管理员用户名>'
export ADVISOR_ADMIN_PASSWORD='<初始管理员密码>'
```

如使用环境配置的默认对话模型，再设置 `MODEL_API_KEY`、`MODEL_BASE_URL` 和 `ADVISOR_MODEL`。模型也可在工作台的模型设置中管理。

需要保存工具凭据时，配置 `ADVISOR_TOOL_CREDENTIAL_KEY`，其值为 Base64 编码的 32 字节密钥，并保持稳定。PDF 图片识别与重排模型在配置后启用。

完整配置见 [application.yaml](technical-advisor-app/backend/src/main/resources/application.yaml)。本地密钥、数据库、上传文件与运行日志不纳入版本管理。

### 2. 启动后端

在仓库根目录执行：

构建会运行测试；pgvector 集成测试需要通过 `ADVISOR_TEST_PGVECTOR_URL`、`ADVISOR_TEST_PGVECTOR_USER`、`ADVISOR_TEST_PGVECTOR_PASSWORD` 指定独立测试数据库，请勿使用应用数据库。

```sh
mvn -T1 -pl technical-advisor-app/backend -am package
java -jar technical-advisor-app/backend/target/technical-advisor-backend-2.0.5-SNAPSHOT.jar
```

后端默认监听 `18080`。运行数据默认存放在 `technical-advisor-app/data/`。

### 3. 启动前端

另开终端执行：

```sh
cd technical-advisor-app/frontend
npm ci
npm run dev
```

通过终端显示的本地地址访问工作台，默认端口为 `5173`；开发服务器会将 API 请求代理至本地后端。

## 开发检查

前端在 `technical-advisor-app/frontend` 下执行 `npm test`、`npm run lint` 和 `npm run build`。后端测试与格式检查按 [.ai-spec/backend/gates.md](.ai-spec/backend/gates.md) 执行。

项目规范见 [AGENTS.md](AGENTS.md)，应用说明见 [功能文档](docs/v2/zh/docs/others/technical-advisor-login.md)。

## 许可证与基础组件

DeepScope 在 DeepScope Java 基础上开发，保留底层组件的版权声明。许可证见 [Apache License 2.0](LICENSE)。
