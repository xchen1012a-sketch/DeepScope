# 模型服务商与发现完整性修复记录

日期：2026-10-16。计划：`docs/superpowers/plans/2026-10-16-model-discovery-completeness.md`。

## 完成内容

- 发现链路从一次 `/models` 读取升级为通用有界枚举：兼容根数组、`data`、`models` 三种列表形状；支持 Anthropic 游标及同源同路径 next/Link 分页；禁止重定向、跨域、路径漂移和循环。
- 增加边界：单页响应最多2MiB、单页20秒、总预算60秒、最多20页、最多2000个唯一ID、单个ID最多1024字符。响应尾随JSON、未知分页字段、非法嵌套分页、畸形链接均明确失败，不静默当作完整列表。
- `DiscoveryResult` 保存 `items/complete`；`detailed=true` 返回实际数量、完整性和空结果状态。鉴权、限流、接口不存在、不支持、超时、格式和分页错误转换为可本地化的稳定错误码，不回传上游正文或密钥。
- 发现缓存按模型类型、协议、地址和密钥摘要隔离，最多128个连接、5分钟有效。滚动分页携带发现快照，缓存过期或重新发现时返回冲突并从第一页重载，避免旧结果与新结果混排。
- 模型ID下拉使用服务端分页：首次30条，滚动接近底部加载下一页；输入关键词重置为第一页；显示预设建议、已发现结果、部分结果和总匹配数，不把前30条冒充完整目录。
- 滚动分页请求带发现快照；快照过期或重新获取后返回 `model_options_changed`，前端丢弃旧页并从第一页重新加载，不把两批结果拼在一起。
- 服务商矩阵由原有21项扩展为29项，增加已核实来源的智谱/Z.ai、StepFun常规、Gemini OpenAI兼容、xAI、火山常规/Coding、百度千帆等，并保留普通API/Coding/Token/Step Plan的独立地址。详见 [model-provider-discovery-matrix-2026-10-16.md](/Users/agent/deepscope-java/technical-advisor-app/docs/model-provider-discovery-matrix-2026-10-16.md)。

## 验证

- 传输客户端先RED后GREEN：56项传输测试、5项原探测测试通过；完整性边界追加9项通过，日志 `/tmp/model-discovery-transport-green.log`、`/tmp/model-discovery-completeness-green.log`。
- 后端模型管理与缓存在快照/滚动分页整合后通过：模型相关98项通过，日志 `/tmp/model-discovery-backend-final2.log`；Spotless限定任务文件通过。
- 前端设置相关21项通过；最终全量70文件637项通过，构建通过，Lint 0错误、43个已有警告，日志 `/tmp/model-discovery-frontend-final2.log`、`/tmp/model-discovery-build-final2.log`、`/tmp/model-discovery-lint-final2.log`。未启动浏览器。

本地HTTP fixture验证不等于真实供应商账号连通。未使用真实API Key，未对厂商发起真实枚举或推理请求。服务商矩阵中的“官方已核实枚举”表示文档契约已查到，不表示当前账号可用。

## 边界

当前工作区包含其他并行功能的未提交改动；未清理、重置或改写这些文件。未运行全仓Java验证，未重启服务、打包部署或执行浏览器E2E。若上游只返回前30条且没有安全分页信号，系统会标记不完整并允许手动输入，不猜测隐藏分页参数。
