# 模型设置列表与配置抽屉优化

日期：2026-10-16。用户已要求执行六项优化。范围为应用模型管理，复用现有组件，不启动浏览器，不提交或重置已有工作。

## 设计与契约

去掉默认模型大卡片，默认标识放列表行。列表增加API地址、密钥状态，向量列表增加维度。抽屉保留单页，按模型信息、连接设置分组，连接始终展开，底部固定测试、取消与保存。

服务商预设与底层协议分离，保留现有 `openai/anthropic/dashscope/custom` 数据与驱动。新增厂商通过 OpenAI 兼容配置接入，无数据库迁移。预设参考已读取的 CC Switch 源码：`https://github.com/farion1231/cc-switch/blob/main/src/config/openclawProviderPresets.ts`。仅录入核实的官方/聚合地址，去掉推广参数，不引入不支持的原生协议。列表中的供应商名称按协议与预设URL识别，自定义URL保持通用名称。

新增管理权限接口：

- `GET /api/advisor/models/providers?kind=chat&q=deep&limit=30`：返回 `{items:[{id,name,provider,baseUrl,keysUrl}],total}`。按服务端预设名称及别名匹配，kind限制聊天/向量；limit默认30最大50。
- `POST /api/advisor/models/options`：请求已有连接字段（kind/provider/baseUrl/endpointId/secret可选）及 `presetId/q/limit`，返回 `{items:["model-id"],total}`。只查询服务端静态预设和显式获取后缓存的真实模型列表；不因输入关键词调用上游。前端不做filter，聚焦立即展示，输入约200ms防抖后发后端。
- 既有 `POST /discover` 手动获取厂商列表后在服务端有界缓存；按协议、地址和凭据摘要隔离，最长5分钟、最多128项，每项最多2000模型ID，关闭后端缓存不持久化秘密。options拿不到有效缓存时回退预设，支持手动模型ID，不伪造远端可用状态。
- 既有模型列表响应补 `providerName/presetId/providerKeysUrl` 展示字段（按地址识别）；保存仍用真实底层provider和用户确认的baseUrl。模型预设仅是输入建议，保存/测试仍走实际连通校验。

选供应商自动带出地址，不自动覆盖密钥；编辑既有配置保留供应商不可更改规则。自定义模型ID可输入。下拉采用现有Radix基础能力和样式，小号ChevronDown，加载、无结果、错误重试和键盘行为完整；迟到的旧搜索结果不得覆盖新连接。

## 实施步骤与验证

- [x] 后端预设/搜索/有界发现缓存，先RED后GREEN，覆盖权限、关键词、kind、limit、凭据隔离及缓存过期，禁止每次搜索请求上游。
- [x] 前端可搜索选择组件、移除默认大卡片、扩展列表、优化抽屉与常显连接设置，覆盖聚焦加载、关键词请求、手动输入、保存密钥及失败保留。
- [x] 同步EN/ZH文案与使用说明，运行模型相关测试、前端测试/Lint/build、后端模型包和Spotless。
- [x] 只读审查并修复密钥链接指向及键盘高亮滚动问题，交付结果文档；未重启服务，未启动浏览器或调用真实厂商验收。

结果记录：`technical-advisor-app/docs/model-settings-search-results-2026-10-16.md`。

后续交互修正：新增连接时服务商和地址留空，先选择服务商再填写模型ID；打开新增/配置抽屉时把初始焦点留在抽屉本身，避免Radix自动聚焦输入框触发下拉。主动聚焦、点击或方向键仍可展开候选；既有配置保留原值。
