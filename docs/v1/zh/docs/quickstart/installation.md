---
title: 安装
en_link: /v1/en/docs/quickstart/installation
---

DeepScope Java 支持多种模型、RAG 后端和扩展功能，各自需要不同的第三方 SDK。把所有依赖打包到一起会让项目变得臃肿，所以我们提供了两种引入方式：

- **All-in-one**：一个依赖搞定，默认带常用模型扩展（OpenAI / Gemini / Anthropic / DashScope / Ollama）和 MCP SDK，快速上手
- **Core + 扩展**：最小化核心包，按需加扩展模块，适合对依赖有严格要求的场景

大多数情况下用 all-in-one 就够了，需要精细控制依赖时再换成 core + 扩展。

**要求：JDK 17+**

## 依赖选择

| 方式 | 适用场景 | 特点 |
|-----|---------|------|
| **all-in-one** | 快速开始、大多数用户 | 单一依赖，默认带常用模型扩展与 MCP SDK |
| **core + 扩展** | 精细控制依赖 | 按需引入，依赖最小化 |

---

## All-in-One（推荐）

**Maven：**
```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope</artifactId>
    <version>2.0.0</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.deepscope:deepscope:2.0.0'
```

### 默认包含的依赖

All-in-one 包（`io.deepscope:deepscope`）已打包常用模型扩展及其所需第三方 SDK，**不必再额外引入模型厂商 SDK**：

- OpenAI 模型支持（`deepscope-extensions-model-openai`，原生 HTTP，无需 `openai-java`）
- Google Gemini 模型支持（含 Google GenAI SDK）
- Anthropic 模型支持（含 Anthropic Java SDK）
- DashScope 模型支持（通义千问系列）
- Ollama 模型支持
- MCP SDK（模型上下文协议）
- Reactor Core、Jackson、SLF4J（基础框架）

### 额外功能的依赖

下列能力在使用 **core + 扩展** 时，或 all-in-one 未覆盖的运行时驱动 / 可选组件时，才需要按需补充。All-in-one 用户使用上方已列出的模型时，**无需**再手动添加 OpenAI / Gemini / Anthropic / DashScope / Ollama SDK。

| 功能                   | 依赖                                                                                       | Maven 坐标                         |
|----------------------|------------------------------------------------------------------------------------------|----------------------------------|
| **Mem0 长期记忆**        | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **ReME 长期记忆**        | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **百炼 RAG**           | [百炼 SDK](https://central.sonatype.com/artifact/com.aliyun/bailian20231229)               | `com.aliyun:bailian20231229`     |
| **Qdrant RAG**       | [Qdrant Client](https://central.sonatype.com/artifact/io.qdrant/client)                  | `io.qdrant:client`               |
| **PgVector RAG**     | [PostgreSQL Driver](https://central.sonatype.com/artifact/org.postgresql/postgresql) + [pgvector](https://central.sonatype.com/artifact/com.pgvector/pgvector) | `org.postgresql:postgresql` + `com.pgvector:pgvector` |
| **Dify RAG**         | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **RAGFlow RAG**      | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **HayStack RAG**     | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **Elasticsearch RAG** | [Elasticsearch Java Client](https://www.elastic.co/docs/reference/elasticsearch/clients/java)   |`co.elastic.clients:elasticsearch-java` |
| **MySQL Session**    | [MySQL Connector](https://central.sonatype.com/artifact/com.mysql/mysql-connector-j)     | `com.mysql:mysql-connector-j`    |
| **Redis Session**    | [Jedis](https://central.sonatype.com/artifact/redis.clients/jedis)                       | `redis.clients:jedis`            |
| **PDF 处理**           | [Apache PDFBox](https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox)          | `org.apache.pdfbox:pdfbox`       |
| **Word 处理**          | [Apache POI](https://central.sonatype.com/artifact/org.apache.poi/poi-ooxml)             | `org.apache.poi:poi-ooxml`       |
| **文档 处理** | [Apache Tika Core](https://central.sonatype.com/artifact/org.apache.tika/tika-core) + [Apache Tika Parsers](https://central.sonatype.com/artifact/org.apache.tika/tika-parsers-standard-package) | `org.apache.tika:tika-core` + `org.apache.tika:tika-parsers-standard-package` |
| **Nacos注册中心**        | [Nacos Client](https://central.sonatype.com/artifact/com.alibaba.nacos/nacos-client)     | `com.alibaba.nacos:nacos-client` |

#### 示例：用 Qdrant RAG + PDF 处理

```xml
<!-- 在 deepscope 基础上加（仅当对应传递依赖未满足运行时需要时） -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
```

### Studio 集成

接入 [DeepScope Studio](https://github.com/modelscope/deepscope) 做可视化调试，需要加这些依赖：

| 依赖 | Maven 坐标 |
|-----|-----------|
| [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| [Socket.IO Client](https://central.sonatype.com/artifact/io.socket/socket.io-client) | `io.socket:socket.io-client` |
| [OpenTelemetry API](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-api) | `io.opentelemetry:opentelemetry-api` |
| [OpenTelemetry OTLP Exporter](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-exporter-otlp) | `io.opentelemetry:opentelemetry-exporter-otlp` |
| [OpenTelemetry Reactor](https://central.sonatype.com/artifact/io.opentelemetry.instrumentation/opentelemetry-reactor-3.1) | `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1` |

完整配置：

```xml
<!-- 在 deepscope 基础上加 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
<dependency>
    <groupId>io.socket</groupId>
    <artifactId>socket.io-client</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-reactor-3.1</artifactId>
</dependency>
```

---

## Core + 扩展

需要精细控制依赖时，用 `deepscope-core` 配合扩展模块：

**Maven：**
```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-core</artifactId>
    <version>2.0.0</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.deepscope:deepscope-core:2.0.0'
```

### 扩展模块

#### 长期记忆

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-mem0](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-mem0) | Mem0 长期记忆 | `io.deepscope:deepscope-extensions-mem0` |
| [deepscope-extensions-reme](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-reme) | ReME 长期记忆 | `io.deepscope:deepscope-extensions-reme` |

#### RAG

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-rag-bailian](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-rag-bailian) | 百炼 RAG | `io.deepscope:deepscope-extensions-rag-bailian` |
| [deepscope-extensions-rag-simple](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-rag-simple) | 简单 RAG (Qdrant, Milvus, PgVector, 内存存储, Elasticsearch) | `io.deepscope:deepscope-extensions-rag-simple` |
| [deepscope-extensions-rag-dify](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-rag-dify) | Dify RAG | `io.deepscope:deepscope-extensions-rag-dify` |
| [deepscope-extensions-rag-ragflow](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-rag-ragflow) | RAGFlow RAG | `io.deepscope:deepscope-extensions-rag-ragflow` |
| [deepscope-extensions-rag-haystack](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-rag-haystack) | HayStack RAG | `io.deepscope:deepscope-extensions-rag-haystack` |

#### Session 存储

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-session-mysql](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-session-mysql) | MySQL Session | `io.deepscope:deepscope-extensions-session-mysql` |
| [deepscope-extensions-session-redis](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-session-redis) | Redis Session | `io.deepscope:deepscope-extensions-session-redis` |

#### 多智能体协作

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-a2a-client](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-a2a-client) | A2A 客户端 | `io.deepscope:deepscope-extensions-a2a-client` |
| [deepscope-extensions-a2a-server](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-a2a-server) | A2A 服务端 | `io.deepscope:deepscope-extensions-a2a-server` |

#### 调度

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-scheduler-common](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-scheduler-common) | 调度通用模块 | `io.deepscope:deepscope-extensions-scheduler-common` |
| [deepscope-extensions-scheduler-xxl-job](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-scheduler-xxl-job) | XXL-Job 调度 | `io.deepscope:deepscope-extensions-scheduler-xxl-job` |
| [deepscope-extensions-scheduler-quartz](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-scheduler-quartz) | Quartz 调度 | `io.deepscope:deepscope-extensions-scheduler-quartz` |

#### 用户界面

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [deepscope-extensions-studio](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-studio) | Studio 集成 | `io.deepscope:deepscope-extensions-studio` |
| [deepscope-extensions-agui](https://central.sonatype.com/artifact/io.deepscope/deepscope-extensions-agui) | AG-UI 协议 | `io.deepscope:deepscope-extensions-agui` |

扩展模块会自动带上所需的第三方依赖，不用手动加。

#### 示例：Core + Mem0 扩展

```xml
<!-- 在 deepscope-core 基础上加 -->
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-mem0</artifactId>
    <version>2.0.0</version>
</dependency>
```

## 框架集成

### Spring Boot

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

其他 Starter：

| Starter | 功能 | Maven 坐标 |
|---------|------|-----------|
| deepscope-a2a-spring-boot-starter | A2A 集成 | `io.deepscope:deepscope-a2a-spring-boot-starter` |
| deepscope-agui-spring-boot-starter | AG-UI 集成 | `io.deepscope:deepscope-agui-spring-boot-starter` |
| deepscope-chat-completions-web-starter | Chat Completions Web 集成 | `io.deepscope:deepscope-chat-completions-web-starter` |
| deepscope-nacos-spring-boot-starter | Nacos 集成 | `io.deepscope:deepscope-nacos-spring-boot-starter` |

### Quarkus

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-quarkus-extension</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Micronaut

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-micronaut-extension</artifactId>
    <version>2.0.0</version>
</dependency>
```
