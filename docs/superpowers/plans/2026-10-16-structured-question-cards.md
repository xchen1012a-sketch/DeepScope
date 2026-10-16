---
title: 结构化追问卡片技术实现方案
description: 复用现有追问工具、会话事件和消息链路，实现底部选择卡片及可靠的回答关联。
---

# 结构化追问卡片技术实现方案

日期：2026-10-16。状态：已按用户授权实现；本功能相关验证通过，全工作区门禁有其他并行改动导致的失败，详见实施记录。

**目标：** 智能体需要用户补充信息时，在输入框上方显示可选择的追问卡片；用户选择或填写答案后，系统保存回答并继续处理。

**架构：** 复用 `report_question`、现有 AG-UI SSE 和会话消息接口。问题与答案通过稳定编号关联，以持久化事件判定是否待答；回答后沿用现有下一轮执行流程。

**技术栈：** Java / Spring Boot / Reactor / PostgreSQL；React / TypeScript / TanStack Query / Tailwind / 项目现有 UI 组件。

**需求依据：** 用户要求将当前纯文字追问改为底部选择卡片；卡片标题随当前问题动态变化，选项前使用圆形数字编号；保留自定义回答，简单操作一步完成，复用现有组件。

**分级：** 当前文档为设计交付；后续实现属于应用前后端联动的中级改动。下面标为“拟新增/拟扩展”的契约均为本方案设计，不代表项目已有能力。

## 1. 已核实的现状

以下路径以仓库根目录为基准。

| 位置 | 当前能力 | 本次调整 |
| --- | --- | --- |
| `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/session/ReportActions.java` | `report_question(text, suggestions)` 保存问题并发送 `advisor` 自定义事件 | 扩展结构化选项，后端生成问题与选项编号 |
| `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/session/TimelineStore.java` | 保存 `question`、`user_message` 等事件；已有会话行锁 | 在同一事务内关联回答、检查重复提交和更新状态 |
| `technical-advisor-app/backend/src/main/java/io/agentscope/advisor/session/AdvisorSessionController.java` | 会话详情、`POST /api/advisor/sessions/{sessionId}/messages` | 详情补待答问题；消息接口兼容结构化回答 |
| `technical-advisor-app/frontend/src/features/chat/useAdvisorConversation.ts` | `lastIsQuestion` 仅判断最后一条事件；先保存访客消息，再路由和启动模型 | 使用服务端待答状态，统一提交与失败重试 |
| `technical-advisor-app/frontend/src/features/chat/ConversationPanel.tsx` | 追问作为普通过程行；输入框通过提示文案引导回答 | 输入框上方固定显示当前待答卡片 |
| `technical-advisor-app/frontend/src/features/chat/aguiClient.ts` | 现有 `/agui/run/{agentId}` SSE 请求，每次生成新的 `runId` | 继续复用；不把重复保存回答误认为运行幂等 |

已确认的两个缺口：

- `suggestions` 已有后端数据，但前端没有选项交互。
- 追问之后的说明或助手正文可能使最后事件不再是 `question`，现有前端待答判断及会话状态更新会遗漏仍未回答的问题。

参考项目：`/Users/agent/deepseek-harness/packages/client/ui-user-questions/src/client/QuestionComposer.tsx` 以及 `packages/interaction/tool-ask-user/src/index.ts`。仅参考其结构化问题、选项、自定义回答和提交反馈；不引入它的插件、组件或状态框架。

## 2. 实现边界与技术选择

这项能力属于 HITL（人机协同），具体交互是结构化追问卡片，数据由 Tool Calling 提供。

| 决策 | 采用方式 | 原因 |
| --- | --- | --- |
| 问题来源 | 模型调用 `report_question`，返回结构化参数 | 避免从自然语言正文解析选项 |
| 实时通知 | 复用现有 SSE 自定义事件 | 无需 WebSocket 或额外模型调用 |
| 卡片位置 | 对话容器底部、`ComposerDock` 上方，参与布局 | 保持可见，不覆盖输入区，不使用模态弹窗 |
| 回答提交 | 扩展现有消息接口 | 复用消息保存、鉴权和下一轮发送路径 |
| 状态持久化 | 复用 `advisor_events`，增加 JSON 载荷字段 | 第一版不新增独立问题表，不批量改写历史 |
| 智能体继续 | 当前回合正常结束，回答后开始下一轮 | 与现有追问语义一致，不引入 SDK 中断协议变更 |

第一版覆盖单个待答问题、单选、多选、自定义输入、刷新恢复、提交失败重试与回答回放。暂不支持一张卡片内多问题分页、复杂条件表单、倒计时自动选择、自动替用户作答或暂停原工具后原地恢复。

## 3. 用户交互

```text
聊天记录

┌──────────────────────────────────────┐
│ 你想重点分析什么？                    │
│                                      │
│  ① 技术方案                          │
│  ② 业务问题                          │
│  ③ 代码实现                          │
│  ④ 其他…                             │
└──────────────────────────────────────┘
[ 输入消息…                           ]
```

- 动态标题：卡片直接以当前问题的 `text` 为标题。例如本次是“你想重点分析什么？”，下一次可以是“你希望采用哪种部署方式？”。不写死为示例问题或固定“请选择”，无需额外新增标题字段。
- 圆形编号：选项前显示圆形的阿拉伯数字 `1`、`2`、`3`、`4`，按实际选项顺序递增；上图的圈号仅用于草图，实际以现有 `Badge` 配合圆形样式呈现数字，不使用图片或 Unicode 圈号代替组件。
- 编号数量随选项数量变化，不固定生成四项；“其他”排在最后并接续编号。编号与选项文字同行，描述放在文字下方，不另占一行编号。
- 编号用于辅助识别；多选仍保留复选语义，选中状态通过现有高亮、边框或勾选样式表达。提交仍使用选项 ID，不把展示数字作为答案 ID。
- 单选：点击一个选项立即提交，所有选项进入提交中状态，避免二次确认。
- 多选：选项带选中状态，底部显示“确认”；至少选择一项或填写补充后才能提交。
- 其他：在卡片内展开项目现有 `Textarea`，填写后点“提交”；不另外弹窗。
- 无选项：卡片直接展示问题与文本输入框，不强行生成选项。
- 单选时，选择具体选项与填写“其他”为互斥路径；多选允许选项与补充文字共同提交。
- 模型仍在收尾时可展示卡片，但禁用提交；执行结束并刷新待答状态后启用。
- 卡片可以折叠成一行，折叠不等于已回答；第一版不提供会默默结束追问的关闭按钮。
- 普通输入框仍可回答当前问题，提交时必须通过同一回答关联逻辑，不能产生两套状态。
- 回答成功后移除待答卡片，在聊天记录保留“问题＋答案”的简洁记录。
- 保存失败时保留选择及输入，显示卡片内错误与重试入口；不清空已有普通消息草稿。

问题和选项由模型根据当前上下文提供；“其他”“确认”“提交”等界面文案经 i18next 的 EN / ZH 词典输出。模型生成内容按原文显示，不作为词典键。

## 4. 问题数据契约

### 4.1 模型工具参数：拟扩展 `report_question`

```json
{
  "text": "你想重点分析什么？",
  "answer_mode": "single",
  "options": [
    { "label": "技术方案", "description": "分析架构和技术选型" },
    { "label": "业务问题", "description": "分析目标、流程和取舍" },
    { "label": "代码实现", "description": "分析实现方式或具体代码" }
  ]
}
```

规则：

- 保留旧 `text`、`suggestions` 参数，新增 `options` 和 `answer_mode`，不新增另一个职责重叠的提问工具。
- `answer_mode` 为 `single`、`multiple`、`text`；缺省为有选项时单选、无选项时文本。
- 新格式 `options` 优先于旧 `suggestions`。旧建议按顺序转换成选项，缺少描述时不补写。
- 问题编号由后端生成 UUID；选项编号由后端按归一化后的顺序生成 `opt-1`、`opt-2` 等，生成后持久化，不在前端重新编号。
- 圆形展示序号由前端按持久化选项数组顺序计算为 `index + 1`，与后端选项 ID 分开；“其他”使用下一个展示序号，提交时仍走 `customText`。不向模型要求生成编号前缀，也不把数字拼进选项标签。
- 问题不能为空；最多 6 个选项，标签不得重复；问题最多 1000 字符，标签最多 80 字符，可选描述最多 160 字符。
- 新格式 `options` 需要 2–6 个有效选项；不足两个、未知模式或超限输入返回工具参数错误，要求模型重新提交，不截断语义。旧 `suggestions` 允许只有一个建议，兼容已有调用。
- `text` 模式不携带选项。所有模式都允许用户自定义答案，不要求模型专门生成“其他”选项。
- 同一会话同时只保留一个待答问题。相同内容的重复工具调用返回已有问题；有待答问题时提交不同问题返回“已有问题待回答”，不悄悄替换。

### 4.2 持久化与 SSE：继续使用 `question` 事件

```json
{
  "version": 2,
  "questionId": "d596c5c3-0de9-4a16-b0a2-c59f47a3374c",
  "text": "你想重点分析什么？",
  "answerMode": "single",
  "allowCustom": true,
  "options": [
    { "id": "opt-1", "label": "技术方案", "description": "分析架构和技术选型" },
    { "id": "opt-2", "label": "业务问题", "description": "分析目标、流程和取舍" },
    { "id": "opt-3", "label": "代码实现", "description": "分析实现方式或具体代码" }
  ],
  "suggestions": ["技术方案", "业务问题", "代码实现"]
}
```

`suggestions` 为旧客户端兼容字段。事件外层继续使用现有 `seq`、`turn` 和 `kind`，不改 AG-UI 公共事件结构。先持久化并提交事务，再发送 SSE；持久化失败不得展示“已成功提问”。

### 4.3 会话详情：拟增加 `pendingQuestion`

`GET /api/advisor/sessions/{sessionId}` 的现有字段保留，新增可空 `pendingQuestion`。非空时包含归一化问题、原事件的 `seq` 和 `turn`；无待答问题返回 `null`。

前端通过现有 TanStack Query 会话详情缓存恢复待答卡片。后端字段暂不存在时可从既有事件做兼容投影，不能仅判断最后一条事件类型。

## 5. 回答提交契约

### 5.1 扩展现有消息接口

```http
POST /api/advisor/sessions/{sessionId}/messages
Content-Type: application/json
```

```json
{
  "role": "visitor",
  "text": "技术方案",
  "questionReply": {
    "questionId": "d596c5c3-0de9-4a16-b0a2-c59f47a3374c",
    "requestId": "9b67efb7-ef61-4f92-aa04-76e5414283ef",
    "selectedOptionIds": ["opt-1"],
    "customText": ""
  }
}
```

- `questionReply` 可选；缺省时保留现有普通消息用法。
- 单选只允许一个选项且没有自定义文字，或不选选项而填写自定义文字；多选允许选项与文字共同存在；文本模式只允许文字。
- 自定义文字去首尾空白后最多 4000 字符；重复选项、未知选项、空答案拒绝提交。
- `requestId` 在一次提交开始时生成，网络重试复用；修改答案后使用新的编号。
- 卡片可省略顶层 `text`，后端从问题快照和所选选项生成规范文字。现有普通访客消息仍要求非空 `text`。
- 后端根据持久化的选项生成最终 `payload.text`，不相信客户端传入的选项文案。
- 回答快照存入同一条 `user_message.payload.questionReply`，包含问题编号、请求编号、原问题事件序号、问题文本、选项快照及用户答案。后续修改提示词或选项不改变历史回答。
- 实施补充：消息返回顶层 `replayed` 区分首次保存和幂等返回；详情增加可空 `pendingQuestionReply` 作为尚无执行输出的已存回答恢复候选，继续前仍核对运行状态。`questionReply.contextText` 由服务端生成问题与答案，客户端不能覆盖。
- 附件回答的 `questionReply.attachmentContext` 保存原上传文件的普通用户输入上下文（可选文本，最多240000字符），幂等比较包含该字段。首次启动和刷新后继续均使用同一份已保存上下文；不提升为系统指令。
- 返回现有 `EventEntry` 结构；新增信息位于 `payload.questionReply`，不改变旧接口返回外壳。

### 5.2 提交与下一轮启动

前端新增统一的 `answerQuestion` 入口，最终仍进入现有 `send` 生命周期：

1. 提交结构化回答，获得后端保存的访客 `EventEntry`。
2. 用该记录替换本地临时状态，失效并刷新会话详情。
3. 后续路由与模型输入使用后端确认的问题和答案文字；保持现有逐轮路由策略。
4. 沿用 `retryEntry` 路径复用已保存访客记录，不再次调用消息保存接口。
5. 调用现有 `runAdvisorStream`，将问题与答案作为普通用户上下文交给模型。

模型输入由服务端问题快照和用户答案组成，例如：

```text
对上一条追问的回答
问题：你想重点分析什么？
选择：技术方案
```

这段内容是普通用户输入，不提升为系统指令。单选无额外确认；多选及自定义输入仅由用户明确提交触发发送。

## 6. 状态、事务与并发

### 6.1 状态来源

问题状态由已持久化的问题和回答事件推导，不维护第二份可独立修改的前端待答状态。

| 状态 | 服务端含义 | 界面行为 |
| --- | --- | --- |
| 待回答 | 有问题，没有关联回答 | 显示卡片 |
| 提交中 | 前端 mutation 尚未返回 | 禁用重复提交，保留草稿 |
| 已回答 | 关联访客消息已提交 | 卡片退出，显示回答记录 |
| 等待继续 | 回答已保存，模型尚未成功开始 | 显示“继续处理”，不重新询问或重复存答案 |
| 处理中 | 会话运行时确认有活动执行 | 展示正常执行过程 |

“提交中”是本地请求状态；“等待继续”结合已保存访客记录、运行时状态及该回答所属回合的执行记录判断，不新增全局会话状态枚举。

### 6.2 原子提交

`QuestionService` 负责业务校验与规范化；事件读写放在现有 `TimelineStore`，复用数据库会话行锁。新增专用的事务方法，一次完成：

```text
锁定当前会话行
→ 检查同一 questionId + requestId 的已存回答
→ 若命中且答案相同，直接返回已有访客事件
→ 校验会话权限、问题待答状态与选项
→ 计算新 turn / seq，保存关联访客消息
→ 在同一连接、同一事务内更新会话状态
→ 提交并返回
```

同编号不同答案返回冲突；另一个请求已回答该问题时也返回冲突。不得仅靠 `synchronized`、按钮禁用或事务外的“先查询再写入”实现去重。

已有 `TimelineStore.append` 存在事件提交后单独更新会话状态的写法。涉及追问的追加路径应统一在同一事务内计算状态，防止迟到的助手说明把待答状态覆盖为 `idle`。服务端读取待答问题时不依赖 `session.status` 字符串。

新数据按问题编号关联；查询先定位最近问题，再在其后的事件范围检查回答，利用现有 `(session_id, seq)` 主键，不取完整会话到前端过滤。第一版不增加独立问题表；真实查询计划显示瓶颈时再补针对性索引。

### 6.3 回答保存与模型运行分别处理

数据库保存答案可以保证同一问题只接受一次回答；数据库事务与 SSE 模型启动不是一个原子操作，不承诺模型调用“恰好一次”。

- 保存失败：仍待回答，保留草稿，重试沿用 `requestId`。
- 保存成功、路由或启动明确失败：答案保留，用同一访客记录重试后续处理。
- 启动请求超时、是否执行不明：先刷新会话运行时和该回答所属回合的记录。已有活动执行或已产出结果时不再次启动；无法判断时提示用户确认重试，不自动重发模型请求。
- 多标签页：服务端事务保证答案只存一份；另一标签页冲突后刷新卡片。运行启动仍服从现有会话并发控制，不能因消息接口返回幂等成功就无条件再启动一次。
- 服务重启或刷新：已回答的问题不重新变为待答；需要继续时恢复“继续处理”入口。

## 7. 错误与兼容

拟扩展消息接口的错误格式沿用当前 `error` 字段，新增以下机器可读值，前端映射为简短本地化文案。

| HTTP | error | 用户处理 |
| --- | --- | --- |
| 400 | `question_reply_invalid` | 修正答案；草稿保留 |
| 404 | `question_not_found` | 会话或问题不可访问；关闭失效卡片并刷新，不泄露其他用户数据 |
| 409 | `question_already_answered` | 刷新为已回答记录 |
| 409 | `question_request_conflict` | 同请求编号对应不同内容；保留草稿并重新确认 |
| 409 | `session_running` | 等待当前执行收尾后再提交 |

兼容策略：

1. 旧 `question` 没有编号时，读侧生成仅在该会话内有效的 `legacy-{seq}`；旧 `suggestions` 按原顺序映射选项，无建议则显示文本回答。
2. 历史问题后存在访客消息，按既有“下一条消息回答追问”的语义视为已答；助手确认文字不算用户回答。无需改写历史记录。
3. 新客户端在卡片待答时从普通输入框发送文字，也携带该问题的回答关联；旧客户端缺少关联时，服务端将非空访客文字规范化为当前待答问题的自定义回答。
4. 新字段只对 `role=visitor` 生效；助手消息携带 `questionReply` 拒绝处理，避免错误关闭待答问题。
5. 更新模型可见工具说明：选项应简短且有区分度，能直接回答的问题不发起追问；发问后正常结束本回合，不另加“问题已发给你”的冗余说明。旧模型可能产生的说明不得被误判为用户已回答。
6. 本轮工具调用正常返回并完成收尾，不在工具线程中阻塞等待用户。原有权限确认卡片与 `AguiResumeInput` 保持各自用途。

## 8. 文件职责与实施清单

拟新增文件中的名称为实施约定；如现有同职责组件能够覆盖，应复用并相应精简清单。

### 阶段一：问题契约与待答投影

- [x] 新增后端 `session/QuestionService.java`，作为纯业务校验、兼容投影和答案快照 helper；数据库事务和待答查询集中在已有 `TimelineStore`，不增加 Spring 装配。
- [x] 扩展 `ReportActions.reportQuestion`，保留旧参数，归一化问题后在 `TimelineStore.appendQuestion` 内保存，再沿用现有事件发送方式。
- [x] 扩展 `TimelineStore`：受会话行锁保护的问题追加、待答查询、回答追加及关联回放。
- [x] 扩展 `AdvisorSessionController` 的详情响应，新增 `pendingQuestion`。
- [x] 先补 `ReportActionsTest` 与新增 `QuestionServiceTest` 的失败用例，再做最小实现；后者含 Store 并发、回滚及兼容用例，既有 `TimelineStoreTest` 一并回归。

交付条件：问题有稳定编号；旧数据可读；发问后的助手说明不会清除待答状态；两个并发提问不会产生两张活跃卡片。

### 阶段二：回答保存与继续执行

- [x] 扩展现有消息接口及前端 `src/api/advisor.ts` 的 `PostMessageInput`、`SessionDetail` 类型。
- [x] 在 `QuestionService.answer` 中按上述契约校验，在 `TimelineStore` 中原子保存答案及访客记录。
- [x] 扩展 `useAdvisorConversation.ts` 的 `SendOptions` 与统一回答入口，沿用既有发送生命周期、`retryEntry`、路由、运行时检查和错误处理；答案保存即确认，模型继续生成时可及时清空已提交草稿。
- [x] 新增 `src/features/chat/model/questions.ts`，负责纯数据归一化与旧格式兼容；前端不把它当成服务端校验的替代。
- [x] 新增对应的 `src/features/chat/model/questions.test.ts`，覆盖新旧问题读取、选项编号、无选项与已答状态，供后续卡片复用。
- [x] 补 `AdvisorSessionControllerTest`、`QuestionServiceTest`、独立的 `useAdvisorConversation.questions.test.tsx` 的重复提交、失败重试与回放用例，并回归既有 hook 测试。

交付条件：一次回答只保存一条访客记录；模型收到对应问题与规范答案；保存与启动失败不会混成重新提问。

### 阶段三：底部卡片与历史展示

- [x] 新增 `src/features/chat/components/QuestionCard.tsx`，复用 `Card`、`Button`、`Textarea`，选项使用原生单选/复选控件及现有样式，不新增 UI 库。
- [x] 标题绑定当前问题 `text`；选项前复用 `Badge` 绘制圆形数字标签，按实际顺序编号，“其他”接续。禁止写死标题、选项名称或四个固定选项。
- [x] 在 `ConversationPanel.tsx` 中把卡片放在滚动消息区之外、`ComposerDock` 上方；窄屏控制高度、卡片内部滚动。
- [x] 现有追问过程行保留为历史入口；活跃问题完整内容只在底部卡片呈现，避免重复两张卡片。回答后展示只读问答摘要。
- [x] 草稿只保存选择、补充文字及卡片折叠状态，并按会话和问题编号隔离；服务端待答数据继续使用 TanStack Query。刷新可恢复待答卡片，第一版不承诺恢复未提交草稿。
- [x] 补 `QuestionCard.test.tsx` 和真实 `ConversationPanel.questions.test.tsx` 集成测试；同步 `src/i18n/locales/{en,zh-CN}/app.json`。

交付条件：单选点选即提交；多选和其他输入可用；无选项也能回答；错误不丢输入；鼠标、键盘均可完成；聊天滚动不把待答卡片卷走。

### 阶段四：文档与门禁

- [x] 更新 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md` 的追问行为说明。
- [x] 完成事务、旧数据、权限、并发及运行启动不确定状态的自检与前后端只读审查。
- [x] 运行受影响测试及应用门禁，实际结果见 `technical-advisor-app/docs/structured-question-cards-results-2026-10-16.md`；不把全工作区无关失败说成通过。

## 9. 验证方案

优先验证以下用户行为，避免只检查组件样式或模拟实现细节。

| 场景 | 必须证明的结果 |
| --- | --- |
| 问题后出现助手说明、刷新 | 卡片仍待答且问题相同 |
| 单选、多选、自定义、无选项 | 规范答案正确，完整问题上下文进入下一轮 |
| 更换问题、改变选项数量 | 标题随问题变化；圆形编号按实际顺序递增，“其他”接续；提交关联仍使用正确选项 ID |
| 卡片回答与普通输入框回答 | 使用同一关联和关闭规则 |
| 网络重试、快速双击、双标签页 | 仅保存一份答案；冲突请求不会覆盖已答内容 |
| 保存成功后运行启动失败 | 回答不重复保存，显示继续处理入口 |
| 模型启动结果不明 | 先确认运行时，已有运行或结果不重复启动 |
| 非法选项、跨会话问题、越权访问 | 服务端拒绝，不接受客户端伪造文案 |
| 旧问题、旧客户端、旧助手说明 | 兼容读写，不重新弹出已回答问题 |
| 会话切换、卡片折叠、请求失败 | 问题和草稿不串会话，不误标已答 |

实施时的针对性命令（仓库根目录）：

```sh
mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=QuestionServiceTest,ReportActionsTest,TimelineStoreTest,AdvisorSessionControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

应用后端门禁（仓库根目录）：

```sh
mvn -T1 -pl technical-advisor-app/backend -am test
mvn -T1 -pl technical-advisor-app/backend -am spotless:check
```

前端针对性与最终门禁（`technical-advisor-app/frontend`）：

```sh
npm test -- src/features/chat/components/QuestionCard.test.tsx src/features/chat/useAdvisorConversation.test.ts src/features/chat/model/questions.test.ts
npm test
npm run lint
npm run build
```

测试以先 RED 后 GREEN 推进。运行后确认测试数量及退出码，不能以零用例或跳过代替通过。涉及真实数据库的并发用例使用独立测试数据库，不修改业务库。

实际实施已执行组件、接口及应用测试，结果见实施记录。按用户偏好未启动浏览器或执行浏览器 E2E，真实视口、动画和原生键盘观感尚未人工验收；不将组件断言作为视觉验证结论。

## 10. 完成标准与回退

- [x] 用户能在底部卡片完成回答，无需手动复述问题和选项。
- [x] 标题由当前问题动态提供，选项使用圆形数字编号，不写死标题或选项数量。
- [x] 刷新可恢复未答问题，已答问题不再出现操作卡片。
- [x] 同一问题只接受一次答案，模型启动失败有独立恢复路径。
- [x] 历史文字追问、普通聊天与现有权限确认通过相关回归测试。
- [x] EN / ZH 文案、相关测试与门禁已执行，实际结果单独记录。
- [ ] 全工作区前后端测试全部通过：存在并行选择列表改动的无关失败，未处理或掩盖。

第一版数据变化均为事件 JSON 的可选字段，无破坏性表结构变更。需要回退界面时可恢复纯文本提问和普通输入，既存问题、选项及回答快照保留；服务端的回答关联、去重与兼容校验继续有效。回退不能删除历史事件或重置已答问题。
