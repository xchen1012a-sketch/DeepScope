---
title: A2A
zh_link: /v2/zh/integration/protocol/a2a
---

`deepscope-extensions-a2a` implements the [A2A protocol](https://a2aproject.github.io/A2A/) and ships two sub-modules:

- `deepscope-extensions-a2a-client`: wraps a remote A2A Agent as a local `Agent` you can `call(...)` directly.
- `deepscope-extensions-a2a-server`: exposes a local `ReActAgent` as an A2A Server.

The two modules are independent — use either one alone.

## Client: call a remote A2A Agent

### Add the dependency

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-a2a-client</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

### Pass an AgentCard directly

```java
import io.a2a.spec.AgentCard;
import io.deepscope.core.a2a.agent.A2aAgent;

AgentCard card = AgentCard.builder()
    .name("remote-translator")
    .url("http://other-service:8080")
    // ...
    .build();

A2aAgent remote = A2aAgent.builder()
    .name("remote-translator")
    .agentCard(card)
    .build();

Msg result = remote.call(new UserMessage("Translate to English: 你好")).block();
```

### Auto-discover via well-known

```java
import io.deepscope.core.a2a.agent.card.WellKnownAgentCardResolver;

WellKnownAgentCardResolver resolver = new WellKnownAgentCardResolver(
    "http://127.0.0.1:8080",
    "/.well-known/agent-card.json",
    Map.of()
);

A2aAgent remote = A2aAgent.builder()
    .name("remote")
    .agentCardResolver(resolver)
    .build();
```

`A2aAgent` is a subclass of `AgentBase`, so it composes naturally with Pipeline, MsgHub, Subagent, etc.

## Server: expose a ReActAgent as an A2A Server

### Add the dependency

```xml
<dependency>
    <groupId>io.deepscope</groupId>
    <artifactId>deepscope-extensions-a2a-server</artifactId>
    <version>${deepscope.version}</version>
</dependency>
```

### Build the server

```java
import io.deepscope.core.a2a.server.DeepScopeA2aServer;
import io.deepscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportProperties;

ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("backend-agent")
    .model(model);

DeepScopeA2aServer server = DeepScopeA2aServer.builder()
    .agentBuilder(agentBuilder)
    .transportProperties(new JsonRpcTransportProperties())
    // .agentCard(customCard)
    // .agentRegistry(myRegistry)
    .build();

// Delegate inbound requests to the transport wrapper from your web framework
TransportWrapper wrapper = server.getTransportWrapper("JSONRPC");
// ... Spring/Quarkus controller forwards to wrapper.handle(...)

server.postEndpointReady();   // call after the web server is listening — triggers registration etc.
```

`DeepScopeA2aServer` does not bind a port or expose endpoints itself; it only assembles components and the request-handling chain. You wire transport into Spring Boot, Quarkus, Vert.x, etc. as you prefer.

### Optional components

- `TaskStore` / `QueueManager`: task and event queue stores; in-memory by default, swap for persistent versions in production.
- `PushNotificationConfigStore` / `PushNotificationSender`: outbound notifications.
- `AgentRegistry`: register `AgentCard` to an external registry such as Nacos (see [Nacos](/v2/en/integration/infrastructure/nacos)).

## Spring Boot Starter

If you're on Spring Boot, prefer `deepscope-spring-boot-starter-a2a-server` — it auto-configures the server and controller. See [Quickstart](/v2/en/docs/quickstart).
