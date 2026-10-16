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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.session.log.DurableSession;
import io.agentscope.core.session.log.MessageDerivation;
import io.agentscope.core.session.log.SessionEventData;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A recorded message is stored as typed event data. A JSON payload string would force a second
 * shape, and content blocks would lose the discriminator a reader needs to rebuild them.
 */
class RecordedPayloadTest {

    @Test
    @DisplayName("an appended assistant message keeps its thinking block as typed data")
    void appendedAssistantMessageKeepsThinking() {
        var store = new InMemorySessionLogStore();
        var key = new SessionKey("u", "recorded", "typed");
        var context = RuntimeContext.builder().userId("u").sessionId("typed").build();
        try (DurableSession recorder = store.openWriter(key, "run-1", "turn-1", context)) {
            recorder.append(
                    new SessionEventData.AssistantMessageData(
                            1L,
                            1L,
                            "a1",
                            List.of(
                                    ThinkingBlock.builder().thinking("because").build(),
                                    TextBlock.builder().text("answer").build()),
                            List.of(),
                            null,
                            false,
                            null));
        }

        var log = store.readKernel(key, context);
        var event =
                log.events().stream()
                        .filter(candidate -> "assistant/message".equals(candidate.type()))
                        .findFirst()
                        .orElseThrow();
        var data = assertInstanceOf(SessionEventData.AssistantMessageData.class, event.data());
        assertTrue(data.content().stream().anyMatch(ThinkingBlock.class::isInstance));
        assertFalse(
                JsonUtils.getJsonCodec().toJson(data).contains("\"payloadJson\""),
                "the stored event must not keep a JSON payload field");
        assertTrue(
                MessageDerivation.history(log).stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .anyMatch(ThinkingBlock.class::isInstance),
                "a reopened transcript must still carry the thinking block");
    }
}
