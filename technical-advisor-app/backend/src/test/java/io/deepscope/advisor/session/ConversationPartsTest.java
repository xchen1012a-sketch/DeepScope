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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.deepscope.core.agui.event.AguiEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationPartsTest {
    @Test
    void repeatedProcessEventsUpdateTheirOriginalPosition() {
        var parts = new ConversationParts();
        parts.accept(
                new AguiEvent.Custom(
                        "session",
                        "run",
                        "advisor_event",
                        Map.of("kind", "compaction", "seq", 7, "status", "running")),
                Map.of());
        parts.accept(
                new AguiEvent.Custom(
                        "session",
                        "run",
                        "advisor_event",
                        Map.of("kind", "compaction", "seq", 7, "status", "done")),
                Map.of());
        var saved = parts.snapshot();
        assertEquals(1, saved.size());
        assertEquals("run:part:1", saved.get(0).get("id"));
        assertEquals("done", saved.get(0).get("status"));
    }
}
