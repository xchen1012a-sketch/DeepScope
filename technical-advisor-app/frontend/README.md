# 技术参谋前端

实际应用目录为 `technical-advisor-app`：`backend` 运行单体后端，`frontend` 用于开发和构建界面。仓库中的 DeepScope 模块提供底层能力。

## 本地开发

在此目录执行 `npm install`，再运行 `npm run dev`。开发服务器将 `/api` 代理到 `http://localhost:18080`，与后端配置一致。`npm run build` 生成本目录下的 `dist`。

## 中文界面

默认语言为简体中文，顶栏可切换英文，语言选择会在刷新后保留。页面、组件、状态和执行事件的展示文案统一使用现有 i18next；中英文词条维护在 `src/i18n/locales/{en,zh-CN}/app.json` 与 `common.json`。新增文案应同步补齐两种语言，遵循仓库 `.ai-spec/frontend/spec.md`。

用户填写的名称、描述、对话内容，以及标识、路径和协议字段保持原值。

## 验证与接手记录

运行 `npm test`、`npm run lint` 和 `npm run build`。国际化检查覆盖中英文词条一致性、插值参数、代码中的静态词条引用，以及总览、对话和事件展示。

本轮补齐了此前未完成的词典，修复工具组件翻译函数被变量遮蔽的问题，并将旧构建输出目录改为 `dist`。浏览器使用项目 `scripts/docs/fixtures.mjs` 的模拟数据检查中文主体、语言切换和刷新持久化；真实后端接口联调需另行验证。
