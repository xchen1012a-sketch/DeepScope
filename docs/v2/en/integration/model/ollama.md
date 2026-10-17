---
title: Ollama
zh_link: /v2/zh/integration/model/ollama
---

`deepscope-extensions-model-ollama` integrates locally hosted Ollama models. It is useful for local development, private deployments, and offline model serving.

## Add the dependency

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-model-ollama</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

## ModelRegistry

Use the `ollama:<model>` id. `OLLAMA_BASE_URL` is optional and defaults to the local Ollama endpoint when omitted.

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("ollama:llama3") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need a non-default Ollama endpoint, formatter, transport, proxy, or Ollama options:

```java
import io.deepscope.extensions.model.ollama.OllamaChatModel;

OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .baseUrl("http://localhost:11434")
    .build();
```

## Spring Boot

Spring Boot applications can use the Ollama starter:

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-ollama-spring-boot-starter</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

Configure the local Ollama model with `deepscope.model.provider=ollama`. The base URL
is optional and defaults to `http://localhost:11434`:

```yaml
deepscope:
  model:
    provider: ollama
  ollama:
    model-name: llama3
    # base-url: http://localhost:11434
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/v2/en/docs/building-blocks/model).
