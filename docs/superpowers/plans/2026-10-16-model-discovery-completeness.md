# 服务商与模型发现完整性修复

日期：2026-10-16。用户已要求完成整体机制，GLM仅是示例。中级应用改动，不修改SDK、不迁移数据库、不启动浏览器、不提交或重置既有工作。

## 问题与目标

当前发现只请求一次 `/models`、只读 `data[].id`，缓存静默限制2000条，前端候选只展示前30条却无范围说明，预设与实际发现未作来源区分。修复整个发现链路并审核现有预设，不以单独添加某个供应商作为完成标准。

供应商范围：核对现有21项及CC Switch中与当前OpenAI兼容/Anthropic驱动直接匹配的主要官方入口，区分普通API和Coding套餐。未支持的新原生协议不伪装成已支持。支持矩阵记录真实来源、模型列表接口约定和未实测边界。

## 契约

- `ModelProbe` 增加应用内部 `DiscoveryResult(items,complete)` 和 `discoverResult` 缺省适配，保留既有 `discover()->List`。新增 typed `DiscoveryException(code)`；代码仅白名单，不回传上游正文/密钥。
- 保持旧 `POST /models/discover` 默认数组兼容；请求 `detailed:true` 时返回 `{items,total,complete,status}`，status为`discovered/empty`。完整性表示服务端枚举是否结束，不代表模型可调用。405/501明确`unsupported`，404为接口不存在或地址错误，鉴权/限流/超时/格式错误各自返回现有error+message结构。
- `/models/options` 追加 `source:preset|discovered`、`complete`、`hasMore`、`page`、`limit`；请求page默认1、limit默认30最大50。total为服务端本次关键词匹配总数。按用户补充要求，模型ID下拉接近底部时请求下一页并追加，搜索切换后从第一页重新查询，不只提示搜索也不一次性加载全部结果。
- 获取失败不降级为“已获取预设”，也不继续展示旧远端缓存伪装本次成功。缓存保留有界5分钟128连接，但保存完整性，达到2000ID或页数上限返回`complete:false`。
- 搜索只查服务器缓存/预设，不对每次输入请求厂商。

## 枚举规则

统一OpenAI兼容Bearer及Anthropic x-api-key/version鉴权；仅按已知协议选择接口，尊重用户base URL，不尝试将密钥发往其他host。数据兼容`data`数组、`models`数组及根数组的字符串/对象id，若数据形状不认识就报格式错误，不把异常当空数组。

分页优先支持官方Anthropic `has_more/last_id/after_id` 与同源明确next链接/Link rel=next，next只允许原始请求相同origin/path，不跟随跨域或重定向，不接受循环。最多20页、2000个唯一ID、总60秒；单页请求20秒以内。达到边界返回部分结果标记不完整；分页失败不得报完整成功。限制每页响应体读取，防止无界内存。

## 步骤

- [x] 审核服务商/协议/枚举来源，补支持矩阵及可验证缺项。
- [x] TDD实现统一发现客户端，覆盖鉴权、不同形状、分页、循环/跨域、防重定向、超限、取消和错误。
- [x] 统一控制器、完整性缓存与旧接口兼容，覆盖失败缓存及搜索来源。
- [x] 前端明确实际结果、来源/范围、部分结果与错误；模型ID下拉滚动请求下一页，快照变化自动从第一页重载。
- [x] 前后端相关测试、前端全量/Lint/build、Spotless、中英文文档、独立只读审查与结果记录。

结果记录：`technical-advisor-app/docs/model-discovery-completeness-results-2026-10-16.md`。完成后端传输、完整性、快照分页和前端滚动分页；未使用真实凭据或浏览器。

## 完成标准

所有注册预设在矩阵内有实际协议和验证依据；所有厂商走同一套已测枚举逻辑，分页及截断可见；枚举不支持、失败、空、部分、完整五种情况不混淆。真实厂商账号可用性与代码/本地HTTP fixture证据分开，不宣称全球所有厂商已连通。
