---
title: Overview
zh_link: /v2/zh/integration/overview
---

This section collects the DeepScope Java extensions that connect to third-party systems and ecosystem services. Each extension is an independent Maven module under `deepscope-extensions/` — pull in only what you need.

The extensions are grouped by topic:

## Model Providers

All model providers have moved to independent model extension modules, while `deepscope-core` keeps only the shared model contracts. See [Model](/v2/en/docs/building-blocks/model) for the full creation paths, Spring Boot setup, formatters, credentials, and advanced registry behavior.

| Provider | Maven artifact | `ModelRegistry` id | Standard environment variable | Docs |
|----------|----------------|--------------------|-------------------------------|------|
| OpenAI | `deepscope-extensions-model-openai` | `openai:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/openai">OpenAI</a> |
| OpenAI Official | `deepscope-extensions-model-openai-official` | `openai-official:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/openai-official">OpenAI Official</a> |
| DeepSeek | `deepscope-extensions-model-openai` | `deepseek:<model>` | `DEEPSEEK_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/deepseek">DeepSeek</a> |
| GLM | `deepscope-extensions-model-openai` | `glm:<model>` | `ZAI_API_KEY` / `GLM_API_KEY` / `ZHIPUAI_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/glm">GLM</a> |
| Kimi | `deepscope-extensions-model-openai` | `kimi:<model>` | `MOONSHOT_API_KEY` / `KIMI_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/kimi">Kimi</a> |
| MiniMax | `deepscope-extensions-model-openai` | `minimax:<model>` | `MINIMAX_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/minimax">MiniMax</a> |
| DashScope | `deepscope-extensions-model-dashscope` | `dashscope:<model>` / `qwen*` | `DASHSCOPE_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/dashscope">DashScope</a> |
| Gemini | `deepscope-extensions-model-gemini` | `gemini:<model>` | `GEMINI_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/gemini">Gemini</a> |
| Anthropic | `deepscope-extensions-model-anthropic` | `anthropic:<model>` | `ANTHROPIC_API_KEY` | <a class="reference internal" href="/v2/en/integration/model/anthropic">Anthropic</a> |
| Ollama | `deepscope-extensions-model-ollama` | `ollama:<model>` | `OLLAMA_BASE_URL` optional | <a class="reference internal" href="/v2/en/integration/model/ollama">Ollama</a> |


<Note>

`deepscope-extensions-model-e2e-tests` is a repository test module, not a user-facing model integration dependency.

</Note>


## Distributed Storage (Distributed Store)

Full-stack distributed storage components for multi-replica production deployments. Configure agent state, workspace filesystem, sandbox snapshots, and concurrency locks with a single `DistributedStore`.

- [Distributed Storage Overview](/v2/en/integration/distributed/index) — `DistributedStore` API, capability matrix, mixed stores
- [Redis](/v2/en/integration/distributed/redis) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
- [JDBC](/v2/en/integration/distributed/jdbc) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
- [Alibaba Cloud OSS](/v2/en/integration/distributed/oss) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## Sandbox Execution Environments

Isolated code execution stores. Docker is built-in; the rest are standalone extension modules.

- Docker — built-in default, no extra dependency
- [Kubernetes](/v2/en/docs/harness/sandbox) — `deepscope-extensions-sandbox-kubernetes`
- [AgentRun (Alibaba Cloud)](/v2/en/docs/harness/sandbox) — `deepscope-extensions-sandbox-agentrun`
- [Daytona](/v2/en/docs/harness/sandbox) — `deepscope-extensions-sandbox-daytona`
- [E2B](/v2/en/docs/harness/sandbox) — `deepscope-extensions-sandbox-e2b`

## Memory

Persist user preferences and facts across sessions. All implementations satisfy the `LongTermMemory` interface.

- [Mem0](/v2/en/integration/memory/mem0)
- [Bailian Memory](/v2/en/integration/memory/bailian)
- [ReMe](/v2/en/integration/memory/reme)

## RAG Knowledge Base

Plug different retrieval stores behind the unified `Knowledge` interface.

- [Simple (DIY embedding + vector store)](/v2/en/integration/rag/simple)
- [Bailian Knowledge](/v2/en/integration/rag/bailian)
- [Dify](/v2/en/integration/rag/dify)
- [HayStack](/v2/en/integration/rag/haystack)
- [RAGFlow](/v2/en/integration/rag/ragflow)

## Skill Repository

Multiple storage implementations of `AgentSkillRepository`.

- [Git Skill Repository](/v2/en/integration/skill/git-repository)
- [MySQL Skill Repository](/v2/en/integration/skill/mysql-repository)
- [PostgreSQL Skill Repository](/v2/en/integration/skill/postgresql-repository)
- See also [Nacos Skill Repository](/v2/en/integration/infrastructure/nacos#skill-repository)

## Channel Adapters

Connect your Agent to messaging platforms through the Harness Channel interface.

- [DingTalk](/v2/en/integration/channel/dingtalk)
- [Feishu / Lark](/v2/en/integration/channel/feishu)
- [GitHub](/v2/en/integration/channel/github)
- [GitLab](/v2/en/integration/channel/gitlab)
- [WeCom](/v2/en/integration/channel/wecom)

## Agent Protocols

Standardized ways for the Agent to talk to the outside world.

- [A2A (Agent-to-Agent)](/v2/en/integration/protocol/a2a)
- [AG-UI](/v2/en/integration/protocol/agui)
- [Agent Protocol](/v2/en/integration/protocol/agent-protocol)

## Infrastructure / Middleware

Plug Agents into your enterprise infrastructure.

- [Higress AI Gateway](/v2/en/integration/infrastructure/higress)
- [Nacos](/v2/en/integration/infrastructure/nacos)
- [Scheduler (Quartz / XXL-Job)](/v2/en/integration/infrastructure/scheduler)

## Ecosystem

Runtime, language, debugging, and training extensions.

- [Chat Completions Web](/v2/en/integration/ecosystem/chat-completions-web)
- [DeepScope Studio](/v2/en/integration/ecosystem/studio)
- [Online Training](/v2/en/integration/ecosystem/training)


<Note>

For Spring Boot users, most of the above extensions ship a matching `deepscope-spring-boot-starter-*` for one-line integration that removes the manual wiring.

</Note>
