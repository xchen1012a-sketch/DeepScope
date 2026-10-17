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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionProcessProjectorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepsOppositeRecommendations() throws Exception {
        var sections = List.of(Map.of("items", List.of(Map.of("recommendation", "升级依赖"))));
        var opposite =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", Map.of("sections", sections)),
                                entry(2, "advisor_message", Map.of("text", "不要升级依赖"))),
                        json);
        assertThat(opposite).hasSize(2);
        assertThat(opposite.get(1)).containsEntry("summary", "- 升级依赖");
    }

    @Test
    void deduplicatesRepeatedOldReports() throws Exception {
        var sections = List.of(Map.of("items", List.of(Map.of("recommendation", "升级依赖"))));
        var repeated =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", Map.of("sections", sections)),
                                entry(2, "deliverable", Map.of("sections", sections))),
                        json);
        assertThat(repeated).hasSize(1);
        assertThat(repeated.get(0)).containsEntry("summary", "- 升级依赖");
    }

    @Test
    void hidesMetadataOnlyDeliverablesAndPreservesUniqueConclusionsAsMessages() throws Exception {
        var cards =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", Map.of("title", "Archive metadata")),
                                entry(
                                        2,
                                        "deliverable",
                                        Map.of(
                                                "sections",
                                                List.of(
                                                        Map.of(
                                                                "title",
                                                                "Conclusion",
                                                                "body",
                                                                "Unique conclusion",
                                                                "items",
                                                                List.of(
                                                                        Map.of(
                                                                                "text",
                                                                                "Issue",
                                                                                "recommendation",
                                                                                "Upgrade",
                                                                                "conflict",
                                                                                "Version"
                                                                                    + " conflict")))),
                                                "evidence",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "F1",
                                                                "fact",
                                                                "Confirmed",
                                                                "source",
                                                                "https://example.com/report.pdf"))))),
                        json);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0))
                .containsEntry("kind", "advisor_message")
                .containsEntry("anchor", "flow-1");
        assertThat((String) cards.get(0).get("summary"))
                .contains(
                        "Unique conclusion",
                        "Upgrade",
                        "Version conflict",
                        "https://example.com/report.pdf")
                .doesNotContain("F1");
    }

    @Test
    void dropsRepeatedBodyButKeepsUniqueRecommendation() throws Exception {
        var sections =
                List.of(
                        Map.of(
                                "body",
                                "Conclusion",
                                "items",
                                List.of(Map.of("text", "Issue", "recommendation", "Upgrade"))));
        var cards =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", Map.of("sections", sections)),
                                entry(2, "advisor_message", Map.of("text", "Conclusion\n\nIssue"))),
                        json);
        assertThat(cards).hasSize(2);
        assertThat(cards.get(1))
                .containsEntry("kind", "advisor_message")
                .containsEntry("summary", "- Upgrade");
    }

    @Test
    void projectsCapturedAndUncapturedOldConclusionsWithMatchingPartAnchors() throws Exception {
        for (boolean captured : List.of(true, false)) {
            Map<String, Object> payload =
                    Map.of("sections", List.of(Map.of("body", "Unique conclusion")));
            Map<String, Object> legacy =
                    Map.of("seq", 2, "kind", "deliverable", "payload", payload);
            var finalPart = Map.of("id", "final-result", "kind", "text", "text", "Final summary");
            var parts =
                    captured
                            ? List.of(
                                    Map.of("id", "old-result", "kind", "event", "event", legacy),
                                    finalPart)
                            : List.of(finalPart);
            var cards =
                    SessionProcessProjector.projectTimeline(
                            List.of(
                                    entry(1, "user_message", Map.of("text", "Question")),
                                    entry(2, "deliverable", payload),
                                    entry(
                                            3,
                                            "advisor_message",
                                            Map.of("partsVersion", 1, "parts", parts))),
                            json);
            assertThat(cards).hasSize(3);
            assertThat(cards)
                    .allSatisfy(card -> assertThat(card.get("kind")).isNotEqualTo("deliverable"));
            assertThat(cards)
                    .anySatisfy(
                            card ->
                                    assertThat(card)
                                            .containsEntry("summary", "Unique conclusion")
                                            .containsEntry(
                                                    "anchor",
                                                    captured
                                                            ? "flow-old-result"
                                                            : "flow-legacy-deliverable-2"));
        }
    }

    @Test
    void deduplicatesAgainstActualPartsWithoutTrustingUnusedAggregateText() throws Exception {
        var payload = Map.of("sections", List.of(Map.of("body", "Unique conclusion")));
        var repeated =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", payload),
                                entry(
                                        2,
                                        "advisor_message",
                                        Map.of(
                                                "partsVersion",
                                                1,
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "answer",
                                                                "kind",
                                                                "text",
                                                                "text",
                                                                "Unique conclusion"))))),
                        json);
        assertThat(repeated).hasSize(1);
        var unique =
                SessionProcessProjector.projectTimeline(
                        List.of(
                                entry(1, "deliverable", payload),
                                entry(
                                        2,
                                        "advisor_message",
                                        Map.of(
                                                "text",
                                                "Unique conclusion",
                                                "partsVersion",
                                                1,
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "answer",
                                                                "kind",
                                                                "text",
                                                                "text",
                                                                "Actual summary"))))),
                        json);
        assertThat(unique).hasSize(2);
        assertThat(unique)
                .anySatisfy(card -> assertThat(card).containsEntry("summary", "Unique conclusion"));
    }

    @Test
    void projectsSubagentsFromTaskDispatchAndAgentSpawn() throws Exception {
        var subagents =
                SessionProcessProjector.projectSubagents(
                        List.of(
                                entry(
                                        1,
                                        "tool_call",
                                        Map.of(
                                                "id",
                                                "c1",
                                                "name",
                                                "task_dispatch",
                                                "args",
                                                "{}",
                                                "result",
                                                "Dispatched 1 task(s) to RUNNING: [[task-1] 依赖分析]"
                                                        + " (available slots remaining: 1)")),
                                entry(
                                        2,
                                        "tool_call",
                                        Map.of(
                                                "id",
                                                "c2",
                                                "name",
                                                "agent_spawn",
                                                "args",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "agent_id",
                                                                "reviewer",
                                                                "name",
                                                                "代码审查员",
                                                                "task",
                                                                "审查并发模型")),
                                                "status",
                                                "done")),
                                entry(
                                        3,
                                        "tool_call",
                                        Map.of(
                                                "id",
                                                "c3",
                                                "name",
                                                "task_update",
                                                "args",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "task_id",
                                                                "task-1",
                                                                "status",
                                                                "SUCCEEDED",
                                                                "result_summary",
                                                                "无版本冲突"))))),
                        json);

        assertThat(subagents).hasSize(2);
        var task1 =
                subagents.stream()
                        .filter(s -> "task-1".equals(s.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertThat(task1).containsEntry("name", "依赖分析");
        assertThat(task1).containsEntry("task", "依赖分析");
        assertThat(task1).containsEntry("status", "done");
        assertThat(task1).containsEntry("summary", "无版本冲突");

        var reviewer =
                subagents.stream()
                        .filter(s -> "reviewer".equals(s.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertThat(reviewer).containsEntry("name", "代码审查员");
        assertThat(reviewer).containsEntry("task", "审查并发模型");
    }

    private TimelineStore.TimelineEntry entry(int seq, String kind, Map<String, ?> payload)
            throws Exception {
        return new TimelineStore.TimelineEntry(
                seq, 1, kind, json.writeValueAsString(payload), seq * 1000L);
    }
}
