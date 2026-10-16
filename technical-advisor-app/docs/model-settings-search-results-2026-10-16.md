# 模型设置优化实施记录

日期：2026-10-16。计划：`docs/superpowers/plans/2026-10-16-model-settings-search.md`。

GLM缺项修正：用户确认指智谱GLM。已确认旧预设缺失智谱，新增普通API、Coding、Z.ai Coding三个独立入口，总预设21个。普通API地址和模型ID实际核对 `https://docs.bigmodel.cn/cn/api/introduction`、`https://docs.bigmodel.cn/cn/guide/start/model-overview`；Coding地址和ID核对CC Switch源码，不虚构跨供应商枚举。获取模型增加真实数量反馈，空结果与失败分开提示。回归先RED后GREEN：`/tmp/glm-provider-red.log`（缺项total为0）、`/tmp/model-fetch-feedback-red.log`（成功无反馈）；最终后端模型包通过见 `/tmp/glm-provider-green.log`，前端全量70文件633项通过（`/tmp/glm-models-frontend-tests.log`），构建通过、Lint0错误43警告（`/tmp/glm-models-{build,lint}.log`）。未使用用户密钥验证真实GLM可用模型，未重启运行中的后端，未启动浏览器。

后续修正：新增连接时服务商和地址留空，未选择服务商前不启用模型ID；新增与编辑抽屉初始焦点放在抽屉容器，避免自动聚焦字段后弹出候选。主动点击/聚焦字段仍可搜索，编辑保留已有值。两项新回归先RED后GREEN，设置相关16项通过；最终全量70文件631项通过（`/tmp/model-drawer-initial-all.log`），构建通过（`/tmp/model-drawer-initial-build.log`），Lint 0错误/43警告（`/tmp/model-drawer-initial-lint.log`）。未启动浏览器。

## 完成内容

1. 去掉顶部默认模型大卡片，保留列表行中的默认标识。
2. 标准列表增加API地址、密钥状态；向量列表增加已记录的维度。最近测试结果和时间仍来自真实测试记录，不伪装实时在线。
3. 配置抽屉的服务商增加18个后端预设，支持名称/别名即时搜索、kind范围及结果上限。预设与底层协议分开，不修改数据库和模型驱动。
4. 模型ID换为可输入搜索下拉框，聚焦加载选项，约200ms防抖发后端搜索；支持手动ID、方向键、回车、Escape、小号箭头。前端展示服务器返回结果，不自行filter。弹层复用已有Radix Popover，放在所属抽屉内，防止嵌套模态的指针事件隔离阻断点击。
5. 连接设置直接展开，抽屉按模型信息、连接设置分组，测试、取消、保存放固定底栏。既有密钥保留、测试、保存和放弃确认不变。
6. “获取模型”才请求真实厂商；结果最多2000个ID、最多128个连接、有效5分钟，按kind/协议/地址/密钥摘要隔离。输入搜索只查后端预设/缓存，无额外上游请求。缓存或预设都不承诺某个账号有访问权限。

预设来源：本次实际读取的CC Switch `src/config/openclawProviderPresets.ts`、`codexProviderPresets.ts` 和 `universalProviderPresets.ts`，只录入已核对的兼容地址和模型ID，去掉推广参数。运行时不下载或执行外部配置。腾讯条目标记为实际来源的“腾讯云 Token Plan”，不杜撰通用混元地址；向量预设保守限定于已有核实支持项。

## 文件

- 后端：修改 `model/AdvisorModelController.java`；新增 `ModelProviderPresets.java`、`DiscoveredModelCache.java`；扩展 `ModelManagementTest.java`，新增 `DiscoveredModelCacheTest.java`。
- 前端：修改 `api/model.ts`、`settings/SettingsPage.tsx`、`settings/components/ModelEditorDrawer.tsx`；新增私有 `ModelSearchSelect.tsx` 及其测试，扩展 `SettingsPage.test.tsx`。
- 文档及文案：EN/ZH `app.json`、`docs/v2/{en,zh}/docs/others/technical-advisor-login.md` 和计划。

## 验证

| 检查 | 实际结果 | 日志 |
| --- | --- | --- |
| 前端全量 | 70文件629项通过 | `/tmp/model-settings-final-tests.log` |
| 前端构建/类型 | 成功，既有大包提示保留 | `/tmp/model-settings-final-build.log` |
| 前端Lint | 0错误、43项存量警告 | `/tmp/model-settings-final-lint.log` |
| 后端完整模型包 | 36项通过，0失败/错误/跳过；相关Spotless通过 | `/tmp/model-settings-provider-key-green.log` |
| 最后方向键边界调整后 | 设置相关14项通过 | `/tmp/model-settings-final-targeted.log` |

先RED后GREEN证据：后端新接口3项预期失败见 `/tmp/model-settings-backend-red.log`；密钥链接缺失见 `/tmp/model-settings-provider-key-red.log`；前端去卡片/字段失败见 `/tmp/model-settings-red.log`，选择器缺失见 `/tmp/model-select-red.log`；审查发现的供应商密钥链接与高亮不滚动见 `/tmp/model-review-red.log`，修复后14项通过 `/tmp/model-review-green.log`；首次向上键边界见 `/tmp/model-keyboard-red.log`，修复后见最终相关测试日志。

后端命令在仓库根目录，未与本任务其他Maven并行：

```sh
mvn -T1 -pl technical-advisor-app/backend -am test '-Dtest=io.agentscope.advisor.model.**' -Dsurefire.failIfNoSpecifiedTests=false '-DspotlessFiles=.*(AdvisorModelController|ModelProviderPresets|DiscoveredModelCache|ModelManagementTest|DiscoveredModelCacheTest)\.java'
```

已做独立只读审查，修复已保存兼容供应商的密钥链接误指向OpenAI、长列表键盘高亮移出视野两项P2。前端用真实抽屉和选择器组件验证后端查询参数及选择生效；结果故意包含不匹配输入的服务端条目，用以证明前端没有再次筛选。

未启动浏览器、未运行浏览器E2E、未使用真实厂商密钥。未重启服务、未打包后端JAR、未部署或提交代码。未运行全仓Java验证；相关结果不等于真实厂商连通或视觉观感验收。现有默认模型规则和已有端点协议不可修改限制保留。
