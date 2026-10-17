/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.model;

import io.deepscope.core.agent.AgentBase;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.ImageBlock;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ThinkingBlock;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.ChatUsage;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Resolves the chat model on each call. A saved current endpoint wins; otherwise the local model is
 * used. One call keeps the model it started with.
 */
public final class SwitchingChatModel implements Model {
    private static final Logger log = LoggerFactory.getLogger(SwitchingChatModel.class);

    /** RuntimeContext attribute key controlling whether deep thinking is enabled for a run. */
    public static final String THINKING_KEY = "advisorThinking";

    /** RuntimeContext attribute key carrying the selected reasoning-effort tier for a run. */
    public static final String EFFORT_KEY = "advisorReasoningEffort";

    /** Allowed reasoning-effort tiers; anything else falls back to the default {@code high}. */
    public static final Set<String> REASONING_EFFORTS =
            Set.of("low", "medium", "high", "xhigh", "max");

    private final ChatModelSource source;
    private final Model fallback;
    private final ModelCatalog catalog;

    public SwitchingChatModel(ChatModelSource source, Model fallback, ModelCatalog catalog) {
        this.source = source;
        this.fallback = fallback;
        this.catalog = catalog;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(
                ctxView -> {
                    RuntimeContext runtime =
                            ctxView.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
                    Model model = resolveForRun(runtime);
                    if (model == null) {
                        return Flux.error(new IllegalStateException("没有对话模型"));
                    }
                    int limit;
                    try {
                        limit = catalog.usage().turnTokenLimit();
                    } catch (Exception error) {
                        limit = 0;
                    }
                    int cap = limit;
                    RuntimeContext rc = ctxView.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
                    boolean deepThinking = isDeepThinking(rc);
                    var thinkingOptions =
                            GenerateOptions.builder()
                                    .reasoningEffort(resolveReasoningEffort(rc, deepThinking));
                    String lowerModel =
                            model.getModelName() == null
                                    ? ""
                                    : model.getModelName().toLowerCase(Locale.ROOT);
                    // DeepSeek separates its mode toggle from the reasoning effort control.
                    if (lowerModel.startsWith("deepseek")) {
                        thinkingOptions.additionalBodyParam(
                                "thinking", Map.of("type", deepThinking ? "enabled" : "disabled"));
                    }
                    GenerateOptions effectiveOptions =
                            GenerateOptions.mergeOptions(thinkingOptions.build(), options);
                    boolean knownTextOnly =
                            lowerModel.startsWith("deepseek")
                                    && !lowerModel.contains("vl")
                                    && !lowerModel.contains("vision");
                    boolean hasImages = containsImageBlocks(messages);
                    List<Msg> effectiveMessages =
                            (knownTextOnly && hasImages) ? stripImageBlocks(messages) : messages;
                    long started = System.nanoTime();
                    var firstOutput = new AtomicBoolean();
                    Flux<ChatResponse> primaryStream =
                            model.stream(effectiveMessages, tools, effectiveOptions);
                    if (!knownTextOnly && hasImages) {
                        primaryStream =
                                primaryStream.onErrorResume(
                                        error -> {
                                            if (firstOutput.get()) {
                                                return Flux.error(error);
                                            }
                                            log.info(
                                                    "Model '{}' rejected multimodal ImageBlocks"
                                                        + " ({}), falling back to sliced OCR text",
                                                    model.getModelName(),
                                                    error.getMessage());
                                            return model.stream(
                                                    stripImageBlocks(messages),
                                                    tools,
                                                    effectiveOptions);
                                        });
                    }
                    return primaryStream
                            .<ChatResponse>handle(
                                    (response, sink) -> {
                                        ChatUsage usage = response.getUsage();
                                        if (cap > 0
                                                && usage != null
                                                && usage.getTotalTokens() > cap) {
                                            sink.error(new IllegalStateException("这一轮的用量超过了上限"));
                                            return;
                                        }
                                        sink.next(
                                                deepThinking ? response : stripThinking(response));
                                    })
                            .doOnNext(
                                    response -> {
                                        if (!firstOutput.get()
                                                && hasVisibleOutput(response, deepThinking)
                                                && firstOutput.compareAndSet(false, true)) {
                                            log.info(
                                                    "Chat first visible output: model={},"
                                                            + " thinking={}, elapsedMs={}",
                                                    model.getModelName(),
                                                    deepThinking,
                                                    (System.nanoTime() - started) / 1_000_000);
                                        }
                                    });
                });
    }

    private static boolean containsImageBlocks(List<Msg> messages) {
        if (messages == null) return false;
        for (Msg msg : messages) {
            if (msg != null && msg.getContent() != null && msg.hasContentBlocks(ImageBlock.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Msg> stripImageBlocks(List<Msg> messages) {
        if (messages == null) return List.of();
        List<Msg> out = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null
                    || msg.getContent() == null
                    || !msg.hasContentBlocks(ImageBlock.class)) {
                out.add(msg);
                continue;
            }
            List<ContentBlock> kept = new ArrayList<>();
            for (ContentBlock block : msg.getContent()) {
                if (!(block instanceof ImageBlock)) {
                    kept.add(block);
                }
            }
            if (kept.isEmpty()) {
                kept.add(TextBlock.builder().text(msg.getTextContent()).build());
            }
            out.add(msg.withContent(kept));
        }
        return out;
    }

    private static boolean hasVisibleOutput(ChatResponse response, boolean thinking) {
        if (response.getContent() == null) return false;
        for (var block : response.getContent()) {
            if (block instanceof TextBlock text
                    && text.getText() != null
                    && !text.getText().isBlank()) return true;
            if (block instanceof ToolUseBlock) return true;
            if (thinking
                    && block instanceof ThinkingBlock thought
                    && thought.getThinking() != null
                    && !thought.getThinking().isBlank()) return true;
        }
        return false;
    }

    static boolean isDeepThinking(RuntimeContext rc) {
        if (rc == null) {
            return false;
        }
        Object direct = rc.get(THINKING_KEY);
        if (direct != null) {
            return Boolean.TRUE.equals(direct) || "true".equalsIgnoreCase(String.valueOf(direct));
        }
        Object forwarded = rc.get("agui.forwardedProps");
        if (forwarded instanceof Map<?, ?> props) {
            Object flag = props.get("thinking");
            return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
        }
        return false;
    }

    /** Resolves the reasoning-effort tier for a run; deep thinking off keeps the legacy low tier. */
    static String resolveReasoningEffort(RuntimeContext rc, boolean deepThinking) {
        if (!deepThinking) {
            return "low";
        }
        if (rc != null) {
            Object direct = rc.get(EFFORT_KEY);
            if (direct instanceof String tier && REASONING_EFFORTS.contains(tier)) {
                return tier;
            }
            Object forwarded = rc.get("agui.forwardedProps");
            if (forwarded instanceof Map<?, ?> props
                    && props.get("reasoningEffort") instanceof String tier
                    && REASONING_EFFORTS.contains(tier)) {
                return tier;
            }
        }
        return "high";
    }

    static ChatResponse stripThinking(ChatResponse response) {
        List<ContentBlock> content = response.getContent();
        if (content == null || content.isEmpty()) {
            return response;
        }
        boolean hasThinking = false;
        for (ContentBlock block : content) {
            if (block instanceof ThinkingBlock) {
                hasThinking = true;
                break;
            }
        }
        if (!hasThinking) {
            return response;
        }
        List<ContentBlock> filtered = new ArrayList<>(content.size());
        for (ContentBlock block : content) {
            if (!(block instanceof ThinkingBlock)) {
                filtered.add(block);
            }
        }
        return ChatResponse.builder()
                .id(response.getId())
                .content(filtered)
                .usage(response.getUsage())
                .metadata(response.getMetadata())
                .finishReason(response.getFinishReason())
                .build();
    }

    @Override
    public String getModelName() {
        return source.current()
                .or(() -> Optional.ofNullable(fallback))
                .map(Model::getModelName)
                .orElse("none");
    }

    /** Returns the selected model's real capability; the harness handles unknown-window budgets. */
    @Override
    public int getContextWindowSize() {
        return currentModel().map(Model::getContextWindowSize).orElse(0);
    }

    private Optional<Model> currentModel() {
        return source.current().or(() -> Optional.ofNullable(fallback));
    }

    private static final String PINNED_MODEL = "advisor.pinnedChatModel";

    private Model resolveForRun(RuntimeContext context) {
        if (context == null) return currentModel().orElse(null);
        synchronized (context) {
            if (context.get(PINNED_MODEL) instanceof Model model) return model;
            String sessionId = context.getSessionId();
            if (context.get("advisorSessionId") instanceof String id) sessionId = id;
            Model model = source.forSession(sessionId).orElse(fallback);
            if (model == null) throw new IllegalStateException("No chat model configured");
            context.put(PINNED_MODEL, model);
            return model;
        }
    }

    /** Captures one immutable endpoint for every call, tool iteration and compaction in this run. */
    public Model forRun(RuntimeContext context) {
        Model selected = resolveForRun(context);
        if (selected == null) throw new IllegalStateException("No chat model configured");
        return new SwitchingChatModel(() -> Optional.of(selected), null, catalog);
    }

    public Model forEndpoint(String endpointId) {
        Model selected = source.forEndpoint(endpointId).orElse(fallback);
        if (selected == null) throw new IllegalStateException("No chat model configured");
        return new SwitchingChatModel(() -> Optional.of(selected), null, catalog);
    }

    /** Resolves once so budgets and streaming for a single call share an immutable selection. */
    public Model forCurrentCall() {
        Model selected =
                currentModel()
                        .orElseThrow(() -> new IllegalStateException("No chat model configured"));
        return new SwitchingChatModel(() -> Optional.of(selected), null, catalog);
    }
}
