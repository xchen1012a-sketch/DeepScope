# 模型供应商预设与发现核查（2026-10-16）

本轮从原有 21 项预设出发，补齐已核实的官方兼容入口，形成 29 项目录（含自定义）。这是当前协议能力下的供应商覆盖清单，不是所有商业供应商或模型的全集。静态模型 ID 只是可搜索建议；账号实时可用范围以该连接的发现结果和实际调用为准。

核查方式：阅读 `ModelProviderPresets.java`、发现客户端及 CC Switch 公开预设，通过普通 HTTP 获取官方文档；没有打开浏览器，没有提供真实 API Key，也没有向供应商发起带凭据的模型枚举或推理请求。下表的“官方已核实枚举”仅表示官方文档说明该接口，不代表当前账号实测通过。

## 29 项预设矩阵

`O` 为现有 OpenAI 兼容协议，枚举时使用 `Authorization: Bearer`；`A` 为 Anthropic 协议，使用 `x-api-key` 与 `anthropic-version`；`D` 为现有 DashScope 连接。除 Anthropic 根地址补 `/v1` 外，发现请求在所选基址后追加 `/models`。**未核实枚举**表示本轮未从所获取官方文档确认该入口的列表契约，不能由“支持 Chat Completions”推导“必定支持模型枚举”。

| 预设 ID | 名称/用途 | 协议 | 基址 | 枚举核查与来源 |
| --- | --- | --- | --- | --- |
| `openai` | OpenAI 常规 API | O | `https://api.openai.com/v1` | 未核实枚举；本轮官方页面 HTTP 403，保留原预设。[官方列表文档](https://developers.openai.com/api/reference/resources/models/methods/list) |
| `anthropic` | Anthropic 常规 API | A | `https://api.anthropic.com` | 未核实枚举；本轮官方页面 HTTP 403，保留原预设；客户端按 Anthropic 游标响应处理分页。[官方列表文档](https://platform.claude.com/docs/en/api/models/list) |
| `dashscope` | 阿里云百炼常规 API | D | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 未核实枚举；[官方 OpenAI 兼容 Chat 文档](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)已获取，页面强调地域 Key 与 endpoint 匹配，不能将其他地域自动视为相同入口。 |
| `deepseek` | DeepSeek 常规 API | O | `https://api.deepseek.com/v1` | 官方已核实枚举：[Lists Models](https://api-docs.deepseek.com/api/list-models)明确 `GET /models`。 |
| `zhipu` | 智谱 GLM 常规 API | O | `https://open.bigmodel.cn/api/paas/v4` | 未核实枚举；[官方 SDK 文档](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction.md)核实基址及 `glm-5.3` 的 Chat 调用。 |
| `zhipu-coding` | 智谱 GLM Coding 套餐 | O | `https://open.bigmodel.cn/api/coding/paas/v4` | 未核实枚举；[官方套餐快速开始](https://docs.bigmodel.cn/cn/coding-plan/quick-start.md)单列 OpenAI Chat Completion 地址。 |
| `zai-coding` | Z.ai GLM Coding 套餐 | O | `https://api.z.ai/api/coding/paas/v4` | 未核实枚举；[官方套餐快速开始](https://docs.z.ai/devpack/quick-start.md)单列此 Chat 地址，与 Responses `/api/v1` 分开。 |
| `kimi` | Kimi 国内常规 API | O | `https://api.moonshot.cn/v1` | 未核实枚举；[官方平台文档](https://platform.moonshot.cn/docs/api/models)实际返回快速开始内容，确认 Chat 基址与 `kimi-k3`，不足以确认列表契约。 |
| `kimi-global` | Kimi 国际常规 API | O | `https://api.moonshot.ai/v1` | 未核实枚举；保留既有 CC Switch 来源入口，本轮未单独核实国际站列表。 |
| `siliconflow` | 硅基流动国内 API | O | `https://api.siliconflow.cn/v1` | 未核实枚举；本轮尝试官方列表文档路径返回 404，不能当作服务 API 不支持。 |
| `siliconflow-global` | 硅基流动国际 API | O | `https://api.siliconflow.com/v1` | 未核实枚举；保留既有来源入口。 |
| `openrouter` | OpenRouter 聚合 API | O | `https://openrouter.ai/api/v1` | 未核实枚举；本轮官方文档请求返回 403/404，保留既有入口。此为原有条目，本轮未新增转售商。 |
| `minimax` | MiniMax 国内常规 API | O | `https://api.minimax.cn/v1` | 未核实枚举；[国内官方 OpenAI SDK 文档](https://platform.minimaxi.com/docs/api-reference/text-openai-api)核实 Chat 兼容性与 `MiniMax-M3`，未独立核实本基址列表。 |
| `minimax-global` | MiniMax 国际常规 API | O | `https://api.minimax.io/v1` | 未核实枚举；[国际官方 OpenAI SDK 文档](https://platform.minimax.io/docs/api-reference/text-openai-api)核实 Chat 兼容性，未独立核实列表。 |
| `stepfun` | 阶跃星辰 Step Plan 国内套餐 | O | `https://api.stepfun.com/step_plan/v1` | 未核实枚举；[官方 Step Plan 说明](https://platform.stepfun.com/docs/zh/step-plan/overview)核实套餐地址与独立额度。保留原 ID/URL，仅明确显示名称。 |
| `stepfun-global` | StepFun Step Plan 国际套餐 | O | `https://api.stepfun.ai/step_plan/v1` | 未核实枚举；保留原 ID/URL，国际套餐路径来自 CC Switch；不能用国际常规 `/v1/models` 文档证明此路径。 |
| `groq` | Groq 常规 API | O | `https://api.groq.com/openai/v1` | 官方已核实枚举：[OpenAI compatibility](https://console.groq.com/docs/openai)页面携带 API 定义，明确 `/openai/v1/models` 与 Bearer。 |
| `tencent-token-plan` | 腾讯云 Token Plan 套餐 | O | `https://api.lkeap.cloud.tencent.com/plan/v3` | 未核实枚举；本轮未取得可解析的官方列表内容，保留既有入口，不替换为混元常规 API。 |
| `nvidia` | NVIDIA NIM API | O | `https://integrate.api.nvidia.com/v1` | 未核实枚举；[官方 API 文档](https://docs.api.nvidia.com/nim/reference/llm-apis)本轮 HTTP 403；不填未经核实的模型建议。 |
| `mistral` | Mistral 常规 API | O | `https://api.mistral.ai/v1` | 官方已核实枚举：[Models API](https://docs.mistral.ai/api/endpoint/models)明确 `GET /v1/models`、Bearer 与 `data` 数组。 |
| `custom` | 自定义 OpenAI 兼容服务 | O | 用户提供 | 未核实枚举；按用户实际地址请求，只能报告实际成功、失败或不完整，不能假定所有自建服务支持列表。 |
| `zai` | Z.ai GLM 国际常规 API（新增） | O | `https://api.z.ai/api/paas/v4` | 未核实枚举；[官方快速开始](https://docs.z.ai/guides/overview/quick-start.md)明确基址与 `glm-5.3` Chat 示例。 |
| `stepfun-api` | 阶跃星辰国内常规 API（新增） | O | `https://api.stepfun.com/v1` | 官方已核实枚举：[查询 Model 列表](https://platform.stepfun.com/docs/zh/api-reference/models)明确 GET、Bearer、`data[].id`；示例包含 `step-5-preview`、`step-3.7-flash`、`step-3.5-flash`。 |
| `stepfun-api-global` | StepFun 国际常规 API（新增） | O | `https://api.stepfun.ai/v1` | 官方已核实枚举：[List Models](https://platform.stepfun.ai/docs/en/api-reference/models)明确 GET、Bearer、`data[].id`；本轮示例核实 `step-5-preview`，不从国内文档推定国际其他模型。 |
| `gemini` | Gemini OpenAI 兼容入口（新增） | O | `https://generativelanguage.googleapis.com/v1beta/openai` | 官方已核实枚举：[OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai?hl=en)明确此基址、普通 Gemini API Key、`client.models.list()`，Chat 示例为 `gemini-3.8-flash`。 |
| `xai` | xAI / Grok 常规 API（新增） | O | `https://api.x.ai/v1` | 官方已核实枚举：[Models](https://docs.x.ai/developers/rest-api-reference/inference/models.md)明确 GET `/v1/models`；[Chat Completions](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions.md)明确 Bearer 与 `grok-4.7`。 |
| `volcengine` | 火山方舟/豆包常规 API（新增） | O | `https://ark.cn-beijing.volces.com/api/v3` | 未核实枚举；[官方 Chat API](https://www.volcengine.com/docs/82379/1494384)明确 POST `/chat/completions`、Bearer，示例为 `doubao-seed-2-1-pro-260915`。 |
| `qianfan` | 百度千帆常规 API（新增） | O | `https://qianfan.baidubce.com/v2` | 未核实枚举；[官方 Responses/Chat 对照](https://cloud.baidu.com/doc/qianfan-docs/s/4mi400l1m)包含 Chat `/chat/completions`、Bearer 与 `deepseek-v3.2`；[Chat API](https://cloud.baidu.com/doc/qianfan-api/s/3m7of64lb)再次确认路径。 |
| `volcengine-coding` | 火山方舟 Coding Plan 套餐（新增） | O | `https://ark.cn-beijing.volces.com/api/coding/v3` | 未核实枚举；[官方接入说明](https://www.volcengine.com/docs/82379/1928261)明确 OpenAI 兼容基址及 `ark-code-latest` 配置方式，警告常规 `/api/v3` 会额外计费。 |

## 发现结果的产品含义

- 预设只负责帮助选择供应商、地址和常见模型，不证明 Key 权限，也不以固定几十个模型冒充实时列表。
- 发现与所选基址、协议、凭据绑定；普通 API、Coding/Token/Step Plan 各自使用原地址，不为查列表跨套餐、跨域或跨地区回退。
- “完整”只表示本次接口返回的列表已按客户端支持的分页方式读取完，不能理解为供应商所有模型、所有账号权限或所有业务类型均可使用。列表内模型仍可能不支持当前 Chat/Embedding 用途。
- 没有官方列表证据的入口仍可尝试发现；接口拒绝、未实现或格式不兼容时应如实展示失败，并允许手动填写模型 ID。缓存结果也不能升级成当前请求发现成功。
- 分页/限额/失败分类等传输行为应由本地 HTTP fixture 测试证明；这些测试不等同于上表供应商生产接口验收。测试结果由本轮实施报告记录，本核查不虚报执行结果。

## 覆盖边界及来源

CC Switch 是候选入口的交叉参考，不是本应用的可用性保证。公开源：[OpenClaw 预设](https://github.com/farion1231/cc-switch/blob/main/src/config/openclawProviderPresets.ts)、[Codex 预设](https://github.com/farion1231/cc-switch/blob/main/src/config/codexProviderPresets.ts)、[Universal 预设](https://github.com/farion1231/cc-switch/blob/main/src/config/universalProviderPresets.ts)。本轮下载源副本后逐项比对；新增地址、模型选择以以上官方文档中明确出现的值为依据，不复制推广参数。

没有新增需要全新 SDK、云签名、部署名路由、OAuth 登录或 Gemini 原生协议的入口；Gemini 仅接入已存在的 OpenAI 兼容方式。没有批量导入 CC Switch 的转售、联盟推广或非官方代理条目。火山 Agent Plan、百度 Coding/TokenPlan、BytePlus 等虽然在 CC Switch 中出现，尚未在本轮核实其独立地址与适用契约，因此未混入这 29 项；后续核实后可按相同规则扩展。

所有真实账号权限、套餐配额、地区连通性、当前模型上线状态与端到端推理均未实测；部分官方文档因 403/404 或代理限制未取得。缺少文档证据与生产 API 不支持是两种结论，本清单明确区分。
