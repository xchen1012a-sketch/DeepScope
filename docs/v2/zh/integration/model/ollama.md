---
title: Ollama
en_link: /v2/en/integration/model/ollama
---

`deepscope-extensions-model-ollama` 接入本地托管的 Ollama 模型，适合本地开发、私有化部署和离线模型服务。

## 添加依赖

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-model-ollama</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

## ModelRegistry

使用 `ollama:<model>` 字符串 id。`OLLAMA_BASE_URL` 是可选环境变量，不设置时默认使用本地 Ollama endpoint。

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("ollama:llama3") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要非默认 Ollama endpoint、formatter、transport、proxy 或 Ollama options 时，使用 builder：

```java
import io.deepscope.extensions.model.ollama.OllamaChatModel;

OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .baseUrl("http://localhost:11434")
    .build();
```

## Spring Boot

Spring Boot 应用可以使用 Ollama starter：

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-ollama-spring-boot-starter</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

通过 `deepscope.model.provider=ollama` 配置本地 Ollama 模型。base URL 为可选项，默认是
`http://localhost:11434`：

```yaml
deepscope:
  model:
    provider: ollama
  ollama:
    model-name: llama3
    # base-url: http://localhost:11434
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。
