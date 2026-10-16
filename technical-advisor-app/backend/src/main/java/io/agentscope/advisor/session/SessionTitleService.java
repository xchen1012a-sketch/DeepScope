/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.advisor.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.context.ContextModelCalls;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Auto-names a session after its first advisor reply, the way Claude/Codex do: a short model
 * call turns the first question-answer pair into a concise title. The call is fire-and-forget —
 * the chat flow never waits for it — and a failed call falls back to truncating the question.
 *
 * <p>Only sessions with a blank title are named, and at most one attempt runs per session, so a
 * visitor renaming the sidebar entry is never overwritten.
 */
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private static final int MAX_TITLE_LENGTH = 20;
    private static final int MAX_PROMPT_EXCERPT = 600;

    private final Model model;
    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final ObjectMapper json;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public SessionTitleService(
            Model model, SessionStore sessions, TimelineStore timeline, ObjectMapper json) {
        this.model = model;
        this.sessions = sessions;
        this.timeline = timeline;
        this.json = json;
    }

    /**
     * Names the session asynchronously if it is still untitled. Safe to call on every advisor
     * message; untitled sessions that already named themselves are tracked in memory.
     */
    public void nameIfNeeded(SessionStore.SessionRow session) {
        if (session == null || session.title() != null && !session.title().isBlank()) {
            return;
        }
        String sessionId = session.sessionId();
        if (!inFlight.add(sessionId)) {
            return;
        }
        titleMono(session)
                .doFinally(ignored -> inFlight.remove(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        title -> log.info("Auto-named session {} as '{}'", sessionId, title),
                        error ->
                                log.warn(
                                        "Auto-naming failed for session {}: {}",
                                        sessionId,
                                        error.getMessage()));
    }

    private Mono<String> titleMono(SessionStore.SessionRow session) {
        String sessionId = session.sessionId();
        return firstVisitorQuestion(sessionId)
                .flatMap(
                        question ->
                                generateTitle(question)
                                        .onErrorResume(
                                                error -> {
                                                    log.warn(
                                                            "Title model call failed, falling back"
                                                                    + " to question excerpt: {}",
                                                            error.getMessage());
                                                    return Mono.just(sanitize(excerpt(question)));
                                                })
                                        .flatMap(
                                                title ->
                                                        Mono.fromCallable(
                                                                        () ->
                                                                                sessions.rename(
                                                                                        sessionId,
                                                                                        session
                                                                                                .ownerUserId(),
                                                                                        title))
                                                                .filter(Boolean::booleanValue)
                                                                .map(renamed -> title)));
    }

    private Mono<String> generateTitle(String question) {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        TextBlock.builder()
                                                .text(
                                                        "你是对话标题生成器。根据用户问题生成一个不超过"
                                                                + MAX_TITLE_LENGTH
                                                                + "字的会话标题，概括用户意图。"
                                                                + "只输出标题本身，不要引号、句号或任何解释。")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(excerpt(question)).build())
                                .build());
        return ContextModelCalls.auxiliary(model, messages)
                .reduce(
                        new StringBuilder(),
                        (buffer, response) -> {
                            List<ContentBlock> blocks = response.getContent();
                            if (blocks != null) {
                                for (ContentBlock block : blocks) {
                                    if (block instanceof TextBlock text && text.getText() != null) {
                                        buffer.append(text.getText());
                                    }
                                }
                            }
                            return buffer;
                        })
                .mapNotNull(buffer -> sanitize(buffer.toString()))
                .filter(title -> !title.isBlank());
    }

    private Mono<String> firstVisitorQuestion(String sessionId) {
        return Mono.fromCallable(
                        () ->
                                timeline.listEntries(sessionId).stream()
                                        .filter(
                                                entry ->
                                                        TimelineStore.USER_MESSAGE.equals(
                                                                entry.kind()))
                                        .findFirst()
                                        .map(TimelineStore.TimelineEntry::payload)
                                        .orElse(null))
                .flatMap(
                        payload -> {
                            String text;
                            try {
                                Object value =
                                        json.readValue(
                                                        payload,
                                                        new TypeReference<Map<String, Object>>() {})
                                                .get("text");
                                text = value == null ? null : value.toString();
                            } catch (Exception error) {
                                text = null;
                            }
                            return text == null || text.isBlank()
                                    ? Mono.<String>empty()
                                    : Mono.just(text);
                        });
    }

    /** Strips wrapping quotes and punctuation noise the model sometimes adds. */
    private static String sanitize(String raw) {
        String title = raw.strip().replaceAll("^[\"'“”]+|[\"'“”]+$", "").strip();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private static String excerpt(String text) {
        String stripped = text.strip();
        return stripped.length() > MAX_PROMPT_EXCERPT
                ? stripped.substring(0, MAX_PROMPT_EXCERPT)
                : stripped;
    }
}
