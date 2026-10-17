1. # DeepScope「简单总结」范围与「简单消息跑题/幻觉」调查报告

   > 性质：仅调查，未改动代码、未提交、未开 PR。 范围：`technical-advisor-app`（Spring Boot 后端 + React 前端）及其依赖的 `deepscope-harness`、`deepscope-core`。 写作原则：每条结论都标注 【已核实】（直接读代码得出）或 【推测】（有代码依据，但没有运行验证）。外部产品资料标注来源；查不到原文的标 【未核实】。

   ------

   ## 0. 结论速览

   话题一：「简单总结」为什么像在总结整段对话

   - 【已核实】应用里没有任何"总结"专用逻辑，比如意图识别、"上一条输出"标记或专门的总结提示词。用户说"简单总结"时，模型拿到的是当前智能体在本会话里保存的全部历史（如果发生过压缩，则是压缩摘要加上最近的消息），再加上这句话。"总结整段对话"是模型面对这种输入的自然默认理解，不是 bug。
   - 【已核实】有三个机制会放大这种现象：
     1. 自由模式下每轮都重新路由。"简单总结"可能被分给另一个智能体，而这个智能体的历史和上一轮回答你的那个不一样。
     2. 自动或手动压缩会把对话改写成以 "Here is a summary of the conversation to date" 开头的 USER 消息。手动 `/compact` 连最后一条回答也会压进摘要。
     3. `memory_search` 和 `session_search` 这类工具会把其他会话或长期记忆的内容带进来。
   - 主流产品的做法：普遍把"压缩上下文"做成独立命令（`/compact`、`/summarize`、`/compress`），和用户口头说的"总结一下"分开。没有找到哪家公开文档规定"总结"默认只覆盖上一条输出。OpenAI Model Spec 的原则是：意图不明时给出稳妥的猜测并说明假设，避免问无关紧要的澄清问题。
   - 建议：业主"只总结上一条"的预期不一定对，用户群体中两种理解都常见。推荐的做法是"默认按就近原则，并明确说明范围"，再加上 UI 上"总结上一条 / 总结全部"的显式入口（见第 4 节）。

   话题二：简单消息触发搜索或跑题

   按可能性排序的候选原因，均有代码依据，但都没有运行验证：

   1. 【已核实是事实；推测是主因】个人智能体默认挂载的工具很多：联网搜索/抓取、长期记忆、跨会话搜索、子智能体、11 个 `report_*`、`retrieve_knowledge`、MCP 工具。OpenAI 和 Google 都建议同时可用的工具保持在 10–20 个以内。
   2. 【已核实】"简单问题不要调用工具"这条规则，只写进了没有 AGENTS.md 时生成的兜底种子文件。通过创建向导生成的智能体会先写入 AGENTS.md，所以拿不到这条规则。
   3. 【已核实】`retrieve_knowledge` 的词法召回没有分数阈值，中文按二元组（bigram）切词，几乎任何中文问题都能"命中"一些段落。模型一旦调用它，就会拿到看起来相关、其实无关的材料。
   4. 【已核实】每次调用结束都会把记忆写入工作区，并可通过 `memory_search` / `session_search` 跨会话取回。工具描述本身在鼓励"回答前先搜"。
   5. 【已核实】自由模式路由只看本轮文本，失败时退回第一个候选。像"好的""继续""简单总结"这类短消息很容易被分错智能体。
   6. 【推测】压缩摘要里的 "NEXT STEPS" 段可能让模型在简单消息上"继续执行旧任务"。
   7. 【已核实是事实；影响程度为推测】模型和参数：默认是 flash 级模型，未设置 temperature（用服务商默认值），关闭深度思考时 `reasoning_effort=low`。

   最可能见效的修复依次是：按智能体收紧默认工具面；把"简单消息不调工具"写进所有智能体都会带的系统前言；给词法召回加阈值；自由模式改为"粘滞路由"；给工具调用设预算。详见第 3.3 节和第 5 节。

   ------

   ## 1. 方法与限制

   - 方法：静态阅读后端、前端、harness、core 的代码，追踪一条用户消息从前端发出到模型请求组装的全过程；并查阅各家公开文档（第 5 节）。
   - 限制：本机没有 Maven，也没有依赖缓存和 `mvnw`，无法编译运行。因此没有抓取真实的模型请求（实际工具清单、最终系统提示词全文），也没有复现问题。所有"实际工具数量"都是依据构建器默认值推断的。建议在测试环境打开模型请求日志核对（见 3.4）。
   - 线上会话样本：没有拿到出问题的真实会话记录。唯一的旁证是仓库文档 `technical-advisor-app/docs/conversation-collapse-results-2026-10-15.md` 第 88 行附近记录的一次真实运行，出现了 16 次工具调用并触发 MAX_ITERATIONS。

   ------

   ## 2. 一条消息如何到达模型（两个话题共用的背景）

   ### 2.1 前端只发送最新一条用户消息【已核实】

   - `frontend/src/features/chat/aguiClient.ts` 约 332–344 行：`messages: [{ role: 'user', content: text }]`，另外通过 `forwardedProps` 传 `thinking` 和 `reasoningEffort`。历史不由前端拼接。
   - `frontend/src/features/chat/useAdvisorConversation.ts` 920–922 行：每次发送（恢复运行除外）都会先调用 `routeSession(...)`。

   ### 2.2 历史按「用户 × 智能体 × 会话」隔离在服务端【已核实】

   - `deepscope-core` 的 `ReActAgent.java` 约 376–379 行：会话键是 `SessionKey(userId, agentId, sessionId)`。
   - 推论：同一个会话里，如果先后由 A、B 两个智能体回答，A 看不到 B 的回答，反之亦然。只有 BasicChat 运行时会通过时间线拼一个 `prior-conversation` 上下文源（`agent/BasicChatRuntime.java` 100–152 行）。

   ### 2.3 路由：锁定模式和自由模式【已核实】

   - ```
     agent/AdvisorAgentController.java
     ```

      约 400–475 行（

     ```
     route
     ```

     ）：

     - 会话已绑定智能体（锁定模式）时，直接返回绑定的智能体，不调用模型。
     - 访客手动选了智能体时，"本轮仅使用其挂载能力"。
     - 自由模式下调用 `chooseAgent(candidates, routingQuestion(...))`。

   - `chooseAgent`（523–557 行）：用一次辅助模型调用，系统提示是"你是会话路由器……只输出该智能体的 id，不要任何解释"（559–585 行）。输出无法精确匹配某个 id，或调用异常时，退回第一个候选（`builtinFirst`）。

   - `routingQuestion`（591–603 行）：只取本轮文本，没有本轮文本就取时间线上最近一条访客消息，不带任何历史。

   ### 2.4 个人智能体的构建【已核实】

   `agent/AgentProvisioner.java` 209–243 行：

   java

   ```java
   HarnessAgent.builder()    .sysPrompt(agentPrompt).model(model)    .enablePendingToolRecovery(true)    .disableFilesystemTools().disableShellTool()    .disableWorkspaceContext().disableAtPathExpansion()    .middleware(new SelectedModelMiddleware(model))    .middleware(new ReportPresentationMiddleware())    .compaction(CompactionConfig.builder().triggerMessages(0).triggerTokens(800_000).build())    ...    .permissionContext(ToolPermissions.context(selected));
   ```

   - 没有调用 `disableWebTools()`、`disableMemoryTools()`、`disableMemoryHooks()`、`disableSubagents()`、`disableDynamicSubagents()`、`disableDynamicSkills()`。这几个开关在 `deepscope-harness/.../HarnessAgent.java` 约 1349–1425 行的构建器里默认都是 `false`，也就是默认启用。
   - 对比 `agent/BasicChatRuntime.java` 76–92 行：BasicChat 把这些开关全部关掉了，说明项目里已经有"精简工具面"的先例。
   - 383–385 行：总是额外注册 `KnowledgeRetrievalTool`（`retrieve_knowledge`）。
   - 系统提示词：`AgentIdentity` 的产品身份前言，加上 AGENTS.md 正文（截断到 20k 字符）。

   ### 2.5 系统消息合并【已核实】

   - `ReportPresentationMiddleware`（`session/ReportPresentationMiddleware.java` 31–58 行）在每次模型调用时追加一条 SYSTEM 消息（展示约定），并从工具列表里过滤掉 `report_deliverable`。
   - `HarnessContextBuilder.layout`（约 452–502 行）会把所有 SYSTEM 消息合并到最前面。所以这条"追加在末尾的系统消息"不会出现在用户消息之后，不构成"末尾指令干扰"。这一点已排除，不是原因。

   ------

   ## 3. 话题一：「简单总结」到底总结了什么

   ### 3.1 代码实际行为【已核实】

   1. 没有总结专用逻辑。 在后端源码中搜索"总结"和 "summar"，命中的都是报告动作、问题卡、压缩服务、子智能体草稿等其他功能，没有任何"识别总结意图 → 限定范围"的代码。`command/CommandCatalog.java` 68–95 行的内置命令只有 `compact` 和 `clear`。

   2. 模型看到的输入 = 系统提示（身份 + AGENTS.md + 展示约定 + harness 注入段落）+ 该智能体在本会话保存的全部历史（含工具调用与结果）+ 本轮"简单总结"。没有任何标记告诉模型哪一段是"上一条输出"。

   3. 自动压缩

      ：

      - 个人智能体只在 token 超过 800k 时才压缩（`AgentProvisioner` 225–230 行，`triggerMessages(0)` 表示关闭按条数触发）。日常对话很少触发。
      - 触发后，`ConversationCompactor.buildSummaryMessage`（约 556–569 行）生成一条内容为 "Here is a summary of the conversation to date: …"（或 "You are in the middle of a conversation that has been summarized…"）的消息，按 harness 约定作为 USER 消息注入（`CompactionMiddleware` 约 234–305 行）。
      - 摘要的结构来自 `CompactionConfig.DEFAULT_SUMMARY_PROMPT`（76–116 行）：SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS。默认保留最近 20 条消息（278–283 行 `keepMessages = 20`）。

   4. 手动 `/compact`：`session/SessionCompactionService.java` 180–186 行强制 `keepMessages(0)` 和 `keepTokens(0)`，全部历史（包括刚刚那条回答）都被压进摘要。之后用户再说"总结上一条"时，原文已经不在上下文里，模型只能依据摘要作答。

   5. 自由模式重路由：每轮都重新选智能体（2.3 节）。如果"简单总结"被路由到另一个智能体，它的历史里可能没有上一轮的回答，只有它自己更早处理过的内容（甚至是空的），于是会"总结"更早或别的内容，甚至编造。

   6. BasicChat 例外：BasicChat 通过 `prior-conversation` 看到的是时间线上路由边界之前的用户和助手消息（`BasicChatRuntime` 100–152 行），范围是"跨智能体的整段可见对话"。

   ### 3.2 为什么模型会选择"整段对话"【推测，有依据】

   - 中文"简单总结"本身有歧义：可以是"把刚才那段话压缩一下"，也可以是"把我们这次聊的内容概括一下"。当上下文里有多轮内容、又没有范围限定时，大模型通常理解为后者。这和第 5 节各产品的设计思路一致：大家都把"压缩/总结整个会话"当成一个有明确语义的操作。
   - 如果上一轮回答本身就很长、是一份完整报告，用户说"简单总结"多半指它。如果上一轮只是一句短话，用户多半指整段。模型需要上下文线索才能区分，而目前系统提示词里没有任何这方面的指引。

   ### 3.3 可选方案与取舍

   | 方案                                  | 做法                                                         | 优点                                                         | 代价/风险                                             |
   | :------------------------------------ | :----------------------------------------------------------- | :----------------------------------------------------------- | :---------------------------------------------------- |
   | A. 保持现状，只要求声明范围           | 在系统前言加一句："回答总结类请求时，开头用一句话说明你总结的范围" | 改动最小；不预设哪种理解正确；用户能马上纠正                 | 仍可能选错范围，只是可见了                            |
   | B. 就近默认 + 声明假设                | 系统前言写明："用户只说'总结/简单总结'且未指明范围时，默认总结上一条助手回答；若上一条很短或用户明显指整段，则总结整段；开头说明范围" | 符合业主预期，也符合 Model Spec"稳妥猜测并说明假设"的原则    | 需要调措辞；对"想要整段总结"的用户多了一次纠正        |
   | C. UI 显式入口                        | 在每条助手消息上加"总结此条"按钮，会话级加"总结全部"；按钮发送带明确范围的提示 | 没有歧义；类似 Claude Code 的 "Summarize from here / up to here" | 需要前端开发；口头说"简单总结"时仍需要 A 或 B 兜底    |
   | D. 先反问                             | 范围不明时先问"总结上一条还是整段？"                         | 不会选错                                                     | Model Spec 明确不鼓励问无关紧要的澄清问题，体验偏啰嗦 |
   | E. 修复放大因素（建议无论选哪种都做） | ① 自由模式改为粘滞路由，或把最近几轮带给路由器；② `/compact` 至少保留最后一轮原文，并支持焦点说明（如 `/compact 只保留结论`）；③ 跨智能体共享时间线摘要 | 消除"总结错对象"的结构性原因                                 | ①③ 改动路由和上下文组装，属中高风险；② 改动较小       |

   推荐：B + E①，有前端资源时再补 C。理由见第 5.3 节的对照。

   ------

   ## 4. 话题二：简单消息为什么会搜索或跑题

   ### 4.1 代码实际行为【已核实】

   (1) 默认工具面很大

   个人智能体除了关掉文件系统和 shell，以下 harness 默认能力全部保留（`HarnessAgent.java` 约 2734–2948 行注册中间件和工具；`HarnessAgentBuilderSupport.java` 210–217 行注册 general-purpose 子智能体）：

   - 联网：`web_search`（`tool/WebTools.java` 120–145 行，描述为 "Search the web for information. Requires TAVILY_API_KEY…"；没有配 key 时调用会抛错，但工具仍然对模型可见）、`web_fetch`（60–70 行）。
   - 长期记忆：`memory_search` 等（`tool/MemorySearchTool.java` 50–56 行，描述原文是 "Use before answering questions about prior work, decisions, dates, people, preferences, or todos."）。
   - 跨会话搜索：`session_search`、`session_history`（`tool/SessionSearchTool.java` 82–89 行，可以跨该智能体的其他会话，传 `agentId` 还能搜别的智能体）。
   - 子智能体：`agent_spawn` 一类工具，以及 general-purpose 子智能体模板（`middleware/SubagentsMiddleware.java` 94–134 行，`DynamicSubagentsMiddleware` 147–175 行）。
   - 应用侧：11 个 `report_*`（`session/ReportActions.java`）、`retrieve_knowledge`、用户挂载的 MCP 工具和技能。

   按构建器默认值推断，总数大约在 20–30+ 个，具体取决于挂载情况，未运行核实。

   (2) "简单问题直接回答"的规则覆盖不全

   - `AgentProvisioner.java` 415–438 行的 `seedPersonalWorkspace` 写有："日常问候、身份咨询或无需查阅资料即可直接回答的简单问题：直接用正文快速回复，不要调用 report_* 或 retrieve_knowledge 等工具。"
   - 但它只在 AGENTS.md 不存在时才写入；已存在时只调用 `AgentIdentity.topUp` 补身份。
   - `agent/AgentCreationService.java` 626–633 行：向导创建的智能体会先把 `draft.behavior()` 写入 AGENTS.md，再调用 `provisioner.prepare`。所以向导创建的智能体没有这条规则。
   - `agent/BehaviorDraftService.java` 110–161 行生成行为草稿的提示词，反而要求写出"何时派子智能体"之类的委派规则，倾向于让智能体更"主动"。
   - 这条规则也没有提到 `web_search`、`memory_search`、`session_search`、子智能体。
   - `ReportActions.java` 58–62 行：只有 `report_route` 的工具描述里写了"简单问题跳过"。

   (3) 知识检索的词法召回没有阈值

   - `knowledge/KnowledgeSearch.java` 70–128 行：向量检索带 `library.scoreThreshold()`；词法检索 `structures.lexical(...)` 不带阈值，两路结果用 RRF 合并后取 top-k。
   - `knowledge/DocumentStructureStore.java` 318–353 行：只要 `relevance > 0` 就返回，也就是任意一个词出现即可。
   - `knowledge/KnowledgeTerms.java` 35–51 行：中文串会被拆成全部相邻二元组。"帮我简单总结一下"会产生"简单""总结""一下"等词。这些常见词在技术文档里很容易出现，于是每次都会召回一批无关段落。

   (4) 记忆：每轮都写，可跨会话取

   - `middleware/MemoryFlushMiddleware.java` 47–62 行：默认 `FlushMode.ALWAYS`，每次调用结束都在后台抽取记忆。
   - `MemoryFlushManager` 62–87 行的默认抽取提示，会把内容写入工作区的 `memory/YYYY-MM-DD.md`，在该智能体的所有会话之间共享，并可通过 `memory_search` 取回。

   (5) 自由模式路由只看本轮文本，失败退回第一个候选（见 2.3 节）

   (6) 压缩摘要包含 "NEXT STEPS"（见 3.1 节第 3 点）

   (7) 模型和参数

   - `model/SwitchingChatModel.java` 86–101 行和 227–244 行：关闭深度思考时 `reasoningEffort = "low"`；DeepSeek 额外传 `thinking: disabled`。
   - 应用里没有任何地方设置 temperature，使用服务商默认值。
   - `application.yaml` 中默认模型是 `glm-5.3-flash`。
   - `ReActAgent` 的 `maxIters` 默认是 10。

   (8) 其他边角情况

   - `ToolResultEvictionConfig`（80k / 2k）在大结果被截断时会提示模型"用 read_file 读取"，但个人智能体已经 `disableFilesystemTools()`。模型可能尝试一个不存在的工具，或者在后续回答里提到"文件"。
   - general-purpose 子智能体不继承父智能体的系统提示和对话历史，委派出去的子任务容易偏离原意。

   ### 4.2 候选原因排序（按"简单消息 → 搜索/跑题"的可能性）

   | 排名 | 原因                                                         | 证据                                                       | 判断依据                                                     |
   | :--- | :----------------------------------------------------------- | :--------------------------------------------------------- | :----------------------------------------------------------- |
   | 1    | 工具面过大，且包含联网/记忆/跨会话这类"随时可用"的工具       | 4.1(1)；对照 OpenAI 建议 <20、Google 建议 10–20（第 5 节） | 工具越多，`auto` 模式下模型越倾向于"找点事做"。这是各家文档一致指出的风险，属推测 |
   | 2    | 向导创建的智能体缺少"简单问题不调工具"规则；草稿提示反而鼓励委派 | 4.1(2)                                                     | 已核实规则缺失；它对行为的影响属推测                         |
   | 3    | `retrieve_knowledge` 词法召回无阈值                          | 4.1(3)                                                     | 已核实。只要调用一次就几乎必然拿到无关材料，并被模型"顺带讲出来" |
   | 4    | 记忆每轮写入 + `memory_search` 描述鼓励先搜 + `session_search` 可跨会话 | 4.1(4)、工具描述原文                                       | 已核实。可以解释"讲起之前或别的会话的事"                     |
   | 5    | 自由模式路由只看本轮文本、失败退回第一个候选                 | 2.3                                                        | 已核实。对"好的/继续/总结一下"这类依赖上下文的短句尤其不稳   |
   | 6    | 压缩摘要的 NEXT STEPS 诱导继续旧任务                         | 3.1(3)                                                     | 推测。只在 800k 以上或手动 `/compact` 后出现                 |
   | 7    | flash 模型 + 默认温度 + low effort                           | 4.1(7)                                                     | 推测。Anthropic 文档指出低 effort 和长系统提示会影响搜索判断（第 5 节）；方向因模型而异 |
   | 8    | 截断提示引用了不存在的 `read_file`；子智能体丢失上下文       | 4.1(8)                                                     | 推测，属边角情况                                             |

   ### 4.3 修复建议（按预计效果排序）

   1. 按智能体收紧默认工具面

      ：在

      ```
      AgentProvisioner
      ```

       中默认

      ```
      disableWebTools()
      ```

      、

      ```
      disableMemoryTools()
      ```

      、

      ```
      disableMemoryHooks()
      ```

      （或把 flush 改成

      ```
      THROTTLED
      ```

      /

      ```
      NEVER
      ```

      ）、

      ```
      disableSubagents()
      ```

      /

      ```
      disableDynamicSubagents()
      ```

      ，改为在智能体配置里显式开启。BasicChatRuntime 已经是现成范例。仓库自己的规划文档

      ```
      docs/advisor_features_plan.md
      ```

       第 106 行也写了"精准挂载工具可减少幻觉"。

      - 取舍：确实需要联网或记忆的智能体要手动开启，需要配套 UI 或配置项。

   2. 把"简单消息不调工具"写进所有智能体都会带的系统前言

      （例如

      ```
      AgentIdentity.SYSTEM_PREAMBLE
      ```

      ，而不是只写在兜底 AGENTS.md 里），并点名覆盖

      ```
      web_search
      ```

      、

      ```
      memory_search
      ```

      、

      ```
      session_search
      ```

      、

      ```
      retrieve_knowledge
      ```

      、子智能体，同时说明"什么时候该搜"（时效性问题、知识库专有内容）。

      - 取舍：只靠提示词是软约束，flash 模型的服从度有限，所以需要和第 1、3 条一起做。

   3. 给词法召回加门槛

      ：例如要求

      ```
      relevance >= 2
      ```

       或命中比例达到一定值、过滤停用词/常见二元组，或者两路都低分时返回"无相关结果"。

      - 取舍：可能降低一些边缘召回，需要用知识库样本回归。

   4. 程序化意图门控

      ：对很短或明显是寒暄/确认的消息，本轮传

      ```
      tool_choice: none
      ```

      （或等价做法），或者先用小模型判断"是否需要工具"。类似豆包的 SearchIntention、Kimi 旧版的 judge model、Gemini 的动态检索阈值。

      - 取舍：多一次小模型调用的延迟；规则误判时会该搜不搜。

   5. 自由模式粘滞路由：上一轮已有智能体时，默认沿用；只有新问题明显换了主题才重新路由。或者把最近 1–2 轮带给路由器。也可以考虑在 `chooseAgent` 失败时沿用上一轮的智能体，而不是第一个候选。

   6. 工具调用预算：在提示词中写"简单问题最多 0 次、普通问题最多 N 次工具调用"（参考 GPT-5 提示指南），或在中间件里硬限制联网次数（参考 Claude `max_uses`）。

   7. 压缩相关：`/compact` 保留最后一轮原文、支持焦点说明；摘要中的 NEXT STEPS 改为"仅在用户继续该任务时参考"。

   8. 参数：评估把 temperature 设为较低值（Google 文档建议函数调用使用低温度），并在系统提示中放入当前日期（Anthropic 对搜索类工具的建议）。需要逐模型做 A/B 测试。

   9. 边角修复：截断提示不要再引用被禁用的 `read_file`；给 general-purpose 子智能体传入父任务的必要上下文。

   ### 4.4 建议的验证方式（尚未执行）

   - 在测试环境打开模型请求日志，抓一次个人智能体的完整请求，核对实际工具清单和最终系统提示词。
   - 构造一组固定样本，例如"你好""好的""简单总结一下""继续""谢谢"，以及 3–5 个真实技术问题，分别统计改动前后的工具调用次数、是否联网、回答是否跑题。

   ------

   ## 5. 主流产品做法对照（专节）

   > 说明：下表只采用公开文档、官方指南、官方博客或官方代码仓库中能查到的内容，并附链接。消费级 App 内部如何理解"简单总结"、claude.ai 内置搜索规则的具体措辞等，没有可核实的官方原文，统一标为【未核实】。

   ### 5.1 话题一：总结 / 压缩的范围

   | 产品                                                         | 公开做法                                                     | 来源                                                         |
   | :----------------------------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
   | Claude Code（Anthropic）                                     | `/compact [instructions]` 压缩整段对话，可以带焦点说明（如 `/compact focus on the auth bug fix`）；接近上限时自动压缩；`/rewind` 提供 "Summarize from here" / "Summarize up to here" 局部总结，原始消息仍保留在会话记录里 | [Commands](https://code.claude.com/docs/en/commands)、[Context window](https://code.claude.com/docs/en/context-window)、[Checkpointing](https://code.claude.com/docs/en/checkpointing) |
   | Anthropic 工程建议                                           | 压缩应保留架构决策、未解决的问题和实现细节，丢弃冗余工具输出；最轻量、最安全的做法是清除旧的工具结果；建议先追求召回再提精度 | [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)、[Context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing)、[Compaction](https://platform.claude.com/docs/en/build-with-claude/compaction-threshold) |
   | Cursor                                                       | 接近上限自动总结；CLI 提供 `/summarize`（别名 `/compress`）手动压缩；官方指出总结有损，关键状态应写入规则或文件 | [Slash commands](https://cursor.com/docs/cli/reference/slash-commands)、[Changelog 1.6](https://cursor.com/changelog/1-6)、[Dynamic context discovery](https://www.trycursor.com/blog/dynamic-context-discovery) |
   | Codex CLI（OpenAI）                                          | `/compact` 总结可见对话；`model_auto_compact_token_limit` 控制自动压缩阈值；`compact_prompt` 可自定义压缩提示；社区 issue 指出 `/compact` 目前不接受内联焦点说明 | [Developer commands](https://learn.chatgpt.com/docs/developer-commands)、[example-config.md](https://github.com/openai/codex/blob/rust-v0.63.0/docs/example-config.md)、[issue #21468](https://github.com/openai/codex/issues/21468) |
   | Gemini CLI（Google）                                         | `/compress`（别名 `/summarize`、`/compact`）；实现上总结较早历史、原样保留最近一段，摘要作为 user 消息插入；支持带焦点说明 | [CLI Commands](https://google-gemini.github.io/gemini-cli/docs/cli/commands.html)、[chatCompressionService.ts](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/context/chatCompressionService.ts)、[PR #5538](https://github.com/google-gemini/gemini-cli/pull/5538) |
   | OpenAI Model Spec（歧义处理原则）                            | 按意图而不是字面理解请求；意图不明时给出稳妥答案或安全猜测，说明假设，必要时澄清；避免不必要或无关紧要的澄清问题 | [Model Spec 2026-08-18](https://model-spec.openai.com/2026-08-18.html) |
   | ChatGPT / Claude / Gemini / Kimi / 豆包 / DeepSeek / 千问 消费级 App | 用户口头说"总结一下"时默认覆盖哪段内容：没有找到官方文档说明【未核实】。这些 App 的 API 都是无状态的，历史由调用方拼接（例如 [DeepSeek 多轮对话](https://api-docs.deepseek.com/guides/multi_round_chat)），范围取决于产品层自己的设计 | —                                                            |

   与 DeepScope 的对照

   - 共同点：DeepScope 也有自动压缩和手动 `/compact`，摘要以 user 消息注入。这和 Gemini CLI、Claude Code 的做法一致。
   - 差异：
     1. DeepScope 的手动 `/compact` 是 keep 0，不保留最近原文。Gemini CLI 保留最近一段，Claude Code 提供局部总结。
     2. 不支持焦点说明。Claude Code 和 Gemini CLI 支持。
     3. 没有"总结这一段"的局部入口。
     4. 自由模式每轮重路由，导致"总结的对象"可能根本不在当前智能体的上下文里。这是 DeepScope 特有的多智能体结构问题，上述单智能体产品没有。
   - 结论：业界并没有"总结默认只覆盖上一条"的公开约定。可借鉴的是 Model Spec 的"稳妥猜测 + 声明假设"原则，以及 Claude Code 的"显式指定范围"入口。这正是第 3.3 节推荐 B + C + E 的依据。

   ### 5.2 话题二：避免简单消息触发不必要的工具/搜索

   | 产品                             | 公开做法                                                     | 来源                                                         |
   | :------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
   | OpenAI API                       | 建议一轮开始时可用函数少于 20 个（软建议），不常用的工具用 tool search 延迟加载；按意图分组，每组约 10 个以内；`tool_choice` 有 `auto` / `required` / 指定 / `allowed_tools` / `none` | [Function calling](https://developers.openai.com/api/docs/guides/function-calling)、[Deployment checklist](https://developers.openai.com/api/docs/guides/deployment-checklist) |
   | GPT-5 提示指南                   | 降低"主动性"的方法：调低 `reasoning_effort`，明确探索标准，甚至设置固定的工具调用预算（例如"最多 2 次工具调用"） | [GPT-5 prompting guide](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide) |
   | ChatGPT                          | 问题看起来需要时效信息时可能自动搜索，也可以手动选择 Search；企业版可按工作区或角色关闭 | [Searching the web with ChatGPT](https://help.openai.com/en/articles/9237897-searching-the-web-with-chatgpt)、[Enterprise/Edu](https://help.openai.com/en/articles/10093903-chatgpt-search-for-enterprise-and-edu) |
   | Codex CLI                        | 联网默认是 `"cached"`（OpenAI 维护的索引，不实时抓取），可设为 `live` / `disabled`；官方说明这样可以降低提示注入风险 | [Config basics](https://learn.chatgpt.com/docs/config-file/config-basic)、[Web search](https://learn.chatgpt.com/docs/web-search) |
   | Anthropic                        | 工具要少而精、不重叠，用命名空间区分；工具描述要详细；大型工具库延迟加载；Web search 工具通过系统提示调节触发倾向，用 `max_uses` 做硬上限 | [Writing tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)、[Define tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)、[Web search tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool) |
   | Anthropic（Haiku 5.5 提示指南）  | 给搜索工具时同时给出当天日期；低 effort 和长系统提示下模型对是否该搜的判断会变差，需要额外提示；避免笼统的"总是搜索" | [Prompting Claude Haiku 5.5](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-haiku-5-5) |
   | claude.ai 内置搜索规则的具体措辞 | 网上流传的"泄露版"系统提示中的搜索判定规则【未核实】，不作为依据 | —                                                            |
   | Google Gemini / Vertex AI        | 函数调用模式 `AUTO` / `ANY` / `NONE`；建议同时活跃的工具最多 10–20 个，工具多时按上下文动态选择；建议函数调用使用低温度 | [Vertex AI function calling](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling)、[Gemini API function calling](https://ai.google.dev/gemini-api/docs/function-calling) |
   | Gemini 搜索接地                  | 旧版"动态检索"给每个提示打 0–1 的"是否需要搜索"分，超过阈值（默认 0.3）才搜；新模型改为由模型自己判断是否使用 `google_search` | [Google Developers Blog](https://developers.googleblog.com/en/gemini-api-and-ai-studio-now-offer-grounding-with-google-search/)、[Grounding with Google Search](https://ai.google.dev/gemini-api/docs/google-search) |
   | Kimi（月之暗面）                 | 旧版 `$web_search` 内置工具，官方论坛说明它由一个小型 judge 模型判断是否需要搜索（速度快，但不是主模型决策）；新方案 Formula 工具交给主模型决定；另有独立 REST 接口由调用方决定何时搜 | [使用 $web_search](https://platform.kimi.com/docs/guide/use-web-search)、[Kimi Forum 说明](https://forum.moonshot.ai/t/for-kimi-k2-6-should-new-integrations-use-formula-moonshot-web-search-latest-instead-of-built-in-web-search/552) |
   | 豆包 / 火山方舟（字节）          | `web_search` 工具由模型按时效性、知识盲区判断是否触发；另提供独立的 `SearchIntention` 接口做"是否需要联网"的意图判断，返回需要或不需要 | [联网搜索工具](https://ark.volcengine.com/region:cn-beijing/docs/web-search)、[智能体插件 API（SearchIntention）](https://www.volcengine.com/docs/82379/1263406) |
   | 千问 / 阿里云百炼                | `enable_search` 开启后默认由模型自行判断是否联网；`forced_search` 可强制每次都搜；联网检索 Agent 支持"自定义干预搜索的触发策略"，命中预设范围才检索，否则直接用模型的通用知识回答 | [百炼联网搜索](https://help.aliyun.com/zh/model-studio/web-search)、[千问联网检索 Agent](https://help.aliyun.com/zh/model-studio/web-search-agent-guide) |
   | DeepSeek                         | App/网页端的"联网搜索"由用户手动开启，用户协议写明"开启该功能后"才检索；API 侧工具调用由调用方定义 | [DeepSeek 用户协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-terms-of-use.html)、[联网搜索上线公告](https://api-docs.deepseek.com/zh-cn/news/news1210/)、[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) |

   与 DeepScope 的对照

   | 维度         | 主流做法                                                     | DeepScope 现状                                               | 差距                           |
   | :----------- | :----------------------------------------------------------- | :----------------------------------------------------------- | :----------------------------- |
   | 工具数量     | OpenAI <20、Google 10–20、Anthropic 少而精                   | 个人智能体估计 20–30+ 个，含联网/记忆/跨会话/子智能体（推断） | 偏多，且很多与该智能体职责无关 |
   | 联网触发方式 | 用户开关（DeepSeek）、意图门控（豆包 SearchIntention、Kimi judge 模型、Gemini 阈值）、可配置模式（Codex cached/disabled）、硬上限（Claude `max_uses`） | 工具常驻，完全由模型 `auto` 决定；没有意图门控、没有次数上限、没有按智能体的开关 | 缺少程序化约束                 |
   | 简单问题规则 | GPT-5 指南建议明确探索标准和预算；Anthropic 建议用系统提示调节搜索倾向 | 规则只写在兜底 AGENTS.md 里，向导创建的智能体没有；未覆盖联网/记忆/子智能体 | 覆盖不全                       |
   | 检索门槛     | Gemini 动态阈值；千问"命中预设范围才检索"                    | 向量检索有阈值，词法检索无阈值                               | 低质量召回会进入上下文         |
   | 参数         | Google 建议函数调用低温度；Anthropic 指出低 effort 影响判断  | 不设温度；思考关闭时 effort=low                              | 未针对工具调用调参             |

   依据对照得出的建议：第 4.3 节第 1 条（收紧工具面）对应 OpenAI、Google、Anthropic 的工具数量建议；第 2、6 条（系统前言规则 + 预算）对应 GPT-5 指南和 Claude `max_uses`；第 4 条（意图门控）对应豆包、Kimi、Gemini、千问的做法；第 3 条（检索门槛）对应 Gemini 阈值和千问的触发策略；第 8 条（温度、日期）对应 Google 和 Anthropic 的参数建议。

   ### 5.3 对照小结

   1. 总结范围：业界把"压缩会话"做成独立、语义明确的命令，口头"总结"的范围没有统一约定。业主的预期合理，但不是唯一合理的理解。推荐"就近默认 + 声明范围 + 显式入口"。
   2. 防跑题：业界的共同点是用程序手段而不只是提示词来约束工具使用：少量工具、意图门控、阈值、上限、开关。DeepScope 目前主要依赖模型自觉，且相关提示词覆盖不全。这是两个话题里差距最明显、也最值得优先投入的地方。

   ------

   ## 附录：主要代码位置索引

   | 主题                                    | 文件（相对 `technical-advisor-app/backend/src/main/java/io/deepscope/advisor/`，harness/core 另注） | 行                                 |
   | :-------------------------------------- | :----------------------------------------------------------- | :--------------------------------- |
   | 个人智能体构建                          | `agent/AgentProvisioner.java`                                | 209–243、383–385、415–438          |
   | BasicChat 精简范例 / prior-conversation | `agent/BasicChatRuntime.java`                                | 76–111、100–152                    |
   | 向导先写 AGENTS.md                      | `agent/AgentCreationService.java`                            | 626–633                            |
   | 行为草稿提示                            | `agent/BehaviorDraftService.java`                            | 110–161                            |
   | 路由                                    | `agent/AdvisorAgentController.java`                          | 405–473、523–557、559–585、591–603 |
   | 展示约定中间件                          | `session/ReportPresentationMiddleware.java`                  | 31–58                              |
   | 手动压缩 keep 0                         | `session/SessionCompactionService.java`                      | 180–186                            |
   | 内置命令                                | `command/CommandCatalog.java`                                | 68–95                              |
   | 知识检索                                | `knowledge/KnowledgeSearch.java`、`DocumentStructureStore.java`、`KnowledgeTerms.java` | 70–128、318–353、35–51             |
   | 模型参数                                | `model/SwitchingChatModel.java`                              | 86–101、227–244                    |
   | 压缩提示与默认值                        | harness `memory/compaction/CompactionConfig.java`            | 76–116、278–283                    |
   | 摘要消息                                | harness `memory/compaction/ConversationCompactor.java`       | 556–569                            |
   | 记忆写入                                | harness `middleware/MemoryFlushMiddleware.java`、`memory/MemoryFlushManager.java` | 47–62、62–87                       |
   | 工具描述                                | harness `tool/MemorySearchTool.java`、`SessionSearchTool.java`、`WebTools.java` | 50–56、82–89、60–70 / 120–145      |
   | 系统消息合并                            | harness `context/HarnessContextBuilder.java`                 | 452–502                            |
   | 构建器默认开关                          | harness `HarnessAgent.java`                                  | 1349–1425                          |
   | 会话键                                  | core `ReActAgent.java`                                       | 376–379                            |
   | 前端发送与路由                          | `frontend/src/features/chat/aguiClient.ts`、`useAdvisorConversation.ts` | 332–344、920–922                   |
