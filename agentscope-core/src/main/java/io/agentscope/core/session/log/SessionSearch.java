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
package io.agentscope.core.session.log;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Text search over stored sessions.
 *
 * <p>The query is literal, case-insensitive data a person typed, never search syntax: quotes and
 * operators are matched as characters rather than interpreted. Whitespace inside the query is
 * flexible, so a phrase survives being retyped with different spacing. Use a ranked full-text backend
 * when a deployment needs relevance ordering at scale; this scans the text a reader would recognise.
 *
 * <p>Search reads the whole log rather than the current surface, so a message that a later
 * replacement shadowed is still findable.
 */
public final class SessionSearch {

    /** Longest reported snippet, in characters. */
    public static final int SNIPPET_MAX_CHARS = 240;

    private static final int ELLIPSIS_RESERVE = 4;

    private final SessionQuery query;

    /**
     * Search one query surface.
     *
     * @param query the surface to read sessions through
     */
    public SessionSearch(SessionQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    /**
     * One matching event.
     *
     * @param sessionId the session holding the match
     * @param seq the matching event's position
     * @param snippet a bounded excerpt of the matched text
     */
    public record Hit(String sessionId, SessionSeq.Seq seq, String snippet) {}

    /**
     * Search every readable session.
     *
     * @param text the literal text to find
     * @return the hits, grouped in session order then sequence order
     */
    public List<Hit> search(String text) {
        return search(text, Integer.MAX_VALUE);
    }

    /**
     * Search every readable session, reporting at most a bounded number of hits.
     *
     * @param text the literal text to find
     * @param limit maximum reported hits
     * @return the hits
     */
    public List<Hit> search(String text, int limit) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || limit <= 0) {
            return List.of();
        }
        String needle = normalize(text);
        if (needle.isEmpty()) {
            return List.of();
        }
        for (String sessionId : query.listSessionIds()) {
            for (SessionEvent event : query.readEvents(sessionId)) {
                if (hits.size() >= limit) {
                    return List.copyOf(hits);
                }
                String content = searchableText(event);
                if (content == null) {
                    continue;
                }
                String haystack = normalize(content);
                int at = haystack.indexOf(needle);
                if (at >= 0) {
                    hits.add(new Hit(sessionId, event.seq(), snippet(content, needle)));
                }
            }
        }
        return List.copyOf(hits);
    }

    /** Collapse whitespace runs to one space and lowercase, for a flexible literal comparison. */
    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase();
    }

    /**
     * A bounded excerpt centred on the match.
     *
     * <p>The window is computed on the original text so a multi-byte character is never split, and
     * the result is truncated to the cap after the ellipses are added.
     *
     * @param content the text the match was found in
     * @param needle the normalized query
     * @return an excerpt no longer than {@link #SNIPPET_MAX_CHARS}
     */
    private static String snippet(String content, String needle) {
        int matchAt = content.toLowerCase().indexOf(needle);
        if (matchAt < 0) {
            return content.length() <= SNIPPET_MAX_CHARS
                    ? content
                    : content.substring(0, SNIPPET_MAX_CHARS - ELLIPSIS_RESERVE) + "...";
        }
        int budget = SNIPPET_MAX_CHARS - ELLIPSIS_RESERVE;
        int start = Math.max(0, matchAt - (budget - needle.length()) / 2);
        int end = Math.min(content.length(), start + budget);
        start = Math.max(0, end - budget);

        StringBuilder excerpt = new StringBuilder();
        if (start > 0) {
            excerpt.append("...");
        }
        excerpt.append(content, start, end);
        if (end < content.length()) {
            excerpt.append("...");
        }
        return excerpt.length() <= SNIPPET_MAX_CHARS
                ? excerpt.toString()
                : excerpt.substring(0, SNIPPET_MAX_CHARS);
    }

    /** The text of one event a reader would recognise, or null when it carries none. */
    private static String searchableText(SessionEvent event) {
        SessionEventData data = event.data();
        if (data instanceof SessionEventData.UserMessageData user) {
            return textOf(user.content());
        }
        if (data instanceof SessionEventData.AssistantMessageData assistant) {
            return textOf(assistant.content());
        }
        if (data instanceof SessionEventData.SystemMessageData system) {
            return textOf(system.content());
        }
        if (data instanceof SessionEventData.DeveloperMessageData developer) {
            return textOf(developer.content());
        }
        if (data instanceof SessionEventData.ToolResultData result) {
            return textOf(result.content());
        }
        return null;
    }

    /** Concatenated text of a message's text-bearing blocks, or null when it carries none. */
    private static String textOf(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else if (block instanceof ToolResultBlock toolResult) {
                for (ContentBlock output : toolResult.getOutput()) {
                    if (output instanceof TextBlock outputText) {
                        text.append(outputText.getText());
                    }
                }
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}
