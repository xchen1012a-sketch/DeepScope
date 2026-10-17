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
package io.deepscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.event.AgentResultEvent;
import io.deepscope.core.event.ModelCallStartEvent;
import io.deepscope.core.event.TextBlockDeltaEvent;
import io.deepscope.core.message.AssistantMessage;
import io.deepscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationPresentationTest {
    @Test
    void whitespaceOnlyBlockDoesNotBecomeAFinalIdentityOrStartTheVisibleAnswerClock() {
        var presentation = new ConversationPresentation("run");
        presentation.accept(null, List.of(new AguiEvent.RunStarted("session", "run")), 1000);
        presentation.accept(new ModelCallStartEvent("call"), List.of(), 2000);
        presentation.accept(
                new TextBlockDeltaEvent("call", "blank", "\n"),
                List.of(new AguiEvent.TextMessageContent("session", "run", "call-blank", "\n")),
                3000);
        presentation.accept(
                new TextBlockDeltaEvent("call", "body", "正文"),
                List.of(new AguiEvent.TextMessageContent("session", "run", "call-body", "正文")),
                83000);
        presentation.accept(
                new AgentResultEvent(
                        AssistantMessage.builder()
                                .content(
                                        List.of(
                                                TextBlock.builder().text("\n").build(),
                                                TextBlock.builder().text("正文").build()))
                                .build()),
                List.of(),
                90000);
        presentation.accept(null, List.of(new AguiEvent.RunFinished("session", "run")), 95000);
        var metadata = presentation.snapshot();
        assertEquals(List.of("call-body"), metadata.get("finalMessageIds"));
        assertEquals(83000L, metadata.get("finalStartedAt"));
        var parts =
                List.<Map<String, Object>>of(
                        Map.of(
                                "runId",
                                "run",
                                "messageId",
                                "call-blank",
                                "kind",
                                "text",
                                "text",
                                "\n"),
                        Map.of(
                                "runId",
                                "run",
                                "messageId",
                                "call-body",
                                "kind",
                                "text",
                                "text",
                                "正文"));
        assertDoesNotThrow(() -> ConversationPresentation.validate(1, metadata, parts));
    }

    @Test
    void unknownOutcomeCanBeRecordedWithoutInventingAnUnobservedServerClock() {
        var metadata = Map.of("runId", "run", "outcome", "unknown", "finalMessageIds", List.of());
        var validated =
                assertDoesNotThrow(() -> ConversationPresentation.validate(1, metadata, List.of()));
        assertEquals(false, validated.containsKey("runStartedAt"));
    }

    @Test
    void iterationLimitSummaryHasFinalIdentityWithoutClaimingNormalSuccess() {
        var presentation = new ConversationPresentation("run");
        presentation.accept(null, List.of(new AguiEvent.RunStarted("session", "run")), 1000);
        presentation.accept(new ModelCallStartEvent("summary"), List.of(), 2000);
        presentation.accept(
                new TextBlockDeltaEvent("summary", "body", "实际结果总结"),
                List.of(
                        new AguiEvent.TextMessageContent(
                                "session", "run", "summary-body", "实际结果总结")),
                83000);
        presentation.accept(
                new AgentResultEvent(
                        AssistantMessage.builder()
                                .generateReason(
                                        io.deepscope.core.message.GenerateReason.MAX_ITERATIONS)
                                .content(TextBlock.builder().text("实际结果总结").build())
                                .build()),
                List.of(),
                90000);
        presentation.accept(null, List.of(new AguiEvent.RunFinished("session", "run")), 95000);
        var metadata = presentation.snapshot();
        assertEquals("limited", metadata.get("outcome"));
        assertEquals("MAX_ITERATIONS", metadata.get("terminationReason"));
        assertEquals(List.of("summary-body"), metadata.get("finalMessageIds"));
        assertEquals(83000L, metadata.get("finalStartedAt"));
        assertDoesNotThrow(
                () ->
                        ConversationPresentation.validate(
                                1,
                                metadata,
                                List.of(
                                        Map.of(
                                                "runId",
                                                "run",
                                                "messageId",
                                                "summary-body",
                                                "kind",
                                                "text",
                                                "text",
                                                "实际结果总结"))));
    }
}
