---
title: HayStack
zh_link: /v2/zh/integration/rag/haystack
---

`deepscope-extensions-rag-haystack` connects DeepScope to a [HayStack](https://haystack.deepset.ai/) RAG service. Document management and indexing happen on the HayStack side; DeepScope only invokes its retrieval API.

## When to use

- You already run RAG on HayStack (indexing pipeline, ChromaDB, rerankers, etc.).
- You want to reuse HayStack's end-to-end retrieval capability.

## Add the dependency

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-rag-haystack</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.deepscope.core.rag.integration.haystack.HayStackConfig;
import io.deepscope.core.rag.integration.haystack.HayStackKnowledge;
import io.deepscope.core.rag.model.RetrieveConfig;

HayStackConfig config = HayStackConfig.builder()
    .baseUrl("http://localhost:8080")  // your HayStack service
    .topK(10)
    .build();

HayStackKnowledge knowledge = HayStackKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "What is AI?",
    RetrieveConfig.builder().limit(5).build()
).block();
```

## Wire into an Agent

```java
ReActAgent agent = ReActAgent.builder()
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

## Document management is not handled here

`addDocuments(...)` throws `UnsupportedOperationException`. To add or update documents:

1. Place source files in HayStack's pipeline source directory.
2. Trigger / re-run HayStack's indexing pipeline.
3. Once indexing completes, this plugin can retrieve the new documents.

This separation prevents inconsistent indexing states across two sides.

## Key parameters

| Field | Notes |
| --- | --- |
| `baseUrl` | HayStack service URL (required) |
| `topK` | Default top-K |
| `filterPolicy` | Filter policy (see `FilterPolicy`) |

`HayStackConfig` also exposes timeouts, custom headers, and API keys to fit your HayStack deployment's auth scheme.
