/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.deepscope.examples.jev;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentResultEvent;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.model.ChatModelBase;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.permission.PermissionContextState;
import io.deepscope.core.permission.PermissionMode;
import io.deepscope.core.rag.Knowledge;
import io.deepscope.core.rag.model.Document;
import io.deepscope.core.rag.model.DocumentMetadata;
import io.deepscope.core.rag.model.RetrieveConfig;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.extensions.judge.jev.Answer;
import io.deepscope.extensions.judge.jev.JevExecution;
import io.deepscope.extensions.judge.jev.JevGuardrail;
import io.deepscope.extensions.judge.jev.JevJudge;
import io.deepscope.extensions.judge.jev.NoulAnswer;
import io.deepscope.extensions.judge.jev.NoulQuestion;
import io.deepscope.extensions.judge.jev.ScoreAnswer;
import io.deepscope.extensions.judge.jev.ScoreQuestion;
import io.deepscope.extensions.judge.jev.SystemOneRequest;
import io.deepscope.extensions.judge.jev.SystemOneResult;
import io.deepscope.extensions.judge.jev.application.JevCandidateSelector;
import io.deepscope.extensions.judge.jev.evaluation.Evaluator;
import io.deepscope.extensions.judge.jev.evaluation.JevEvaluationRunner;
import io.deepscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.deepscope.extensions.judge.jev.integration.JevKnowledge;
import io.deepscope.extensions.judge.jev.integration.JevKnowledgeTool;
import io.deepscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline end-to-end tool retrieval, content screening, draft refinement and evaluation. */
@SuppressWarnings("removal")
public final class JevIntegrationExample {
    public static void main(String[] args) {
        var execution =
                new JevExecution.Options(
                        JevExecution.Mode.ENFORCE,
                        Duration.ofSeconds(2),
                        "offline-v1",
                        (ctx, r) -> System.out.println(r.purpose() + " " + r.reason()));
        var selector = new JevCandidateSelector(JevIntegrationExample::answer, execution, .2, .8);
        Knowledge source =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> docs) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String q, RetrieveConfig c) {
                        return Mono.just(
                                List.of(
                                        new Document(
                                                new DocumentMetadata(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "Refunds require an unused"
                                                                            + " order within seven"
                                                                            + " days.")
                                                                .build(),
                                                        "policy",
                                                        "1"))));
                    }
                };
        var knowledge =
                new JevKnowledge(
                        source,
                        selector,
                        execution,
                        (ctx, d) -> "demo-user".equals(ctx.getUserId()),
                        10);
        var toolkit = new Toolkit();
        toolkit.registerTool(
                new JevKnowledgeTool(knowledge, RetrieveConfig.builder().limit(3).build()));
        var judge = new JevJudge(JevIntegrationExample::answer, Duration.ofSeconds(2));
        var definition =
                new JevJudge.Definition(
                        "offline-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "correct",
                                        new NoulQuestion(
                                                "Does the answer retain the refund conditions in"
                                                        + " the prompt evidence?",
                                                null),
                                        true,
                                        .2,
                                        .8)));
        var guard = JevGuardrail.defaults(JevIntegrationExample::answer);
        var response =
                JevResponseMiddleware.builder()
                        .guardrails(guard, guard, execution)
                        .quality(judge, definition, execution)
                        .build();
        AtomicInteger calls = new AtomicInteger();
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int n = calls.incrementAndGet();
                        if (n == 2) {
                            boolean found = false;
                            for (Msg m : messages)
                                for (ContentBlock c : m.getContent())
                                    if (c instanceof io.deepscope.core.message.ToolResultBlock t)
                                        for (ContentBlock o : t.getOutput())
                                            if (o instanceof TextBlock text
                                                    && text.getText().contains("seven days"))
                                                found = true;
                            if (!found)
                                throw new IllegalStateException(
                                        "retrieval did not return evidence");
                        }
                        if (n == 3 && !tools.isEmpty())
                            throw new IllegalStateException("revision must not have tools");
                        ContentBlock block =
                                n == 1
                                        ? new ToolUseBlock(
                                                "search-1",
                                                "search_knowledge",
                                                Map.of("query", "refund conditions"),
                                                "{\"query\":\"refund conditions\"}",
                                                null)
                                        : TextBlock.builder()
                                                .text(
                                                        n == 2
                                                                ? "Unconditional refund."
                                                                : "Refunds require an unused order"
                                                                        + " within seven days.")
                                                .build();
                        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("offline-support")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .middleware(response)
                        .build();
        var result =
                agent.streamEvents(
                                List.of(new UserMessage("Can I get a refund?")),
                                RuntimeContext.builder()
                                        .userId("demo-user")
                                        .sessionId("demo-session")
                                        .build())
                        .ofType(AgentResultEvent.class)
                        .single()
                        .block(Duration.ofSeconds(10));
        if (!result.getResult().getTextContent().contains("seven days") || calls.get() != 3)
            throw new IllegalStateException("integration expectation failed");
        System.out.println("Final answer: " + result.getResult().getTextContent());
        Evaluator evaluator = new JevEvaluator(judge, definition);
        var cases =
                List.of(
                        new JevEvaluationRunner.Case(
                                "refund",
                                new Evaluator.Request(
                                        "conditional",
                                        "refund",
                                        List.of("unused within seven days"),
                                        "Refunds require an unused order within seven days."),
                                true),
                        new JevEvaluationRunner.Case(
                                "refund",
                                new Evaluator.Request(
                                        "unsupported",
                                        "refund",
                                        List.of("unused within seven days"),
                                        "Unconditional refund."),
                                false));
        System.out.println(new JevEvaluationRunner().run(evaluator, cases, 1).block().overall());
    }

    private static Mono<SystemOneResult> answer(SystemOneRequest r) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        r.questions()
                .forEach(
                        (id, q) -> {
                            if (q instanceof ScoreQuestion)
                                answers.put(id, new ScoreAnswer(0d, null, null, null));
                            else if (id.equals("correct")) {
                                var state = (Map<?, ?>) r.state();
                                Object text =
                                        state.containsKey("answer")
                                                ? state.get("answer")
                                                : state.get("assistant_answer");
                                answers.put(
                                        id,
                                        new NoulAnswer(
                                                String.valueOf(text).contains("seven days")
                                                        ? .99
                                                        : 0));
                            } else
                                answers.put(id, new NoulAnswer(id.startsWith("item_") ? .99 : 0));
                        });
        return Mono.just(new SystemOneResult("offline", answers, null));
    }
}
