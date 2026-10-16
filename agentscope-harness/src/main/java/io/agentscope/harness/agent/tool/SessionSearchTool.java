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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.log.MessageDerivation;
import io.agentscope.core.session.log.SessionLogStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Read-only session discovery and snippet search over the authoritative native log.
 *
 * <p>Search returns at most one snippet per session (the best match), never full messages: each
 * hit carries a ~240-char snippet centered roughly one third before the match plus coordinates
 * (sessionId, messageId, index). Use {@code session_history} with {@code aroundIndex} to read the
 * full context around a hit. The current session is excluded — its content is already in context.
 */
public class SessionSearchTool {
    /** Characters per snippet (Unicode code points), mirroring deepseek-harness session-query. */
    private static final int SNIPPET_CHARS = 240;

    private final SessionLogStore store;
    private final String hostAgentId;

    public SessionSearchTool(WorkspaceManager workspace) {
        this(new WorkspaceSessionLogStore(workspace), null);
    }

    public SessionSearchTool(SessionLogStore store) {
        this(store, null);
    }

    /**
     * Scoped constructor: when the caller omits {@code agentId}, the search defaults to this host
     * agent's sessions instead of every accessible session.
     */
    public SessionSearchTool(SessionLogStore store, String hostAgentId) {
        this.store = Objects.requireNonNull(store);
        this.hostAgentId = hostAgentId;
    }

    /** Compatibility constructor; the supplied native store owns discovery and history. */
    public SessionSearchTool(WorkspaceManager workspace, SessionLogStore store) {
        this(store == null ? new WorkspaceSessionLogStore(workspace) : store, null);
    }

    public String sessionSearch(
            RuntimeContext runtimeContext, String query, String agentId, Integer maxResults) {
        return sessionSearch(runtimeContext, query, agentId, maxResults, null);
    }

    @Tool(
            name = "session_search",
            readOnly = true,
            description =
                    "Search past session messages and tool outputs for a keyword or phrase. Returns"
                        + " ONE short snippet per session (never full text) with coordinates: use"
                        + " session_history with aroundIndex to read around a hit. Scope defaults"
                        + " to this agent's sessions; pass agentId to search elsewhere. The current"
                        + " session is excluded.")
    public String sessionSearch(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "query",
                            description =
                                    "Literal phrase, or whitespace-separated keywords when"
                                            + " matchMode is all/any; no automatic Chinese word"
                                            + " segmentation")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "Agent ID to search (defaults to this agent)",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum number of sessions (default: 10)",
                            required = false)
                    Integer maxResults,
            @ToolParam(
                            name = "matchMode",
                            description =
                                    "phrase (default): exact substring; all: every keyword in the"
                                            + " same message; any: at least one keyword in that"
                                            + " message. Case-insensitive literal matching.",
                            required = false)
                    String matchMode) {
        if (query == null || query.isBlank()) return "Error: query is required";
        RuntimeContext rc = context(runtimeContext);
        int limit = positiveLimit(maxResults, 10);
        Predicate<String> matcher;
        try {
            matcher =
                    KeywordMatcher.compile(
                            query,
                            matchMode,
                            term -> {
                                String lowerTerm = term.toLowerCase(Locale.ROOT);
                                return text -> text.contains(lowerTerm);
                            });
        } catch (IllegalArgumentException error) {
            return "Error: " + error.getMessage();
        }
        String scope =
                agentId != null && !agentId.isBlank()
                        ? agentId
                        : hostAgentId != null && !hostAgentId.isBlank() ? hostAgentId : null;
        List<String> terms = searchTerms(query);
        String currentSession = rc.getSessionId();
        record Hit(SessionKey key, int index, Msg message, String text) {}
        List<Hit> hits = new ArrayList<>();
        for (SessionKey key : sessions(rc, scope)) {
            if (Objects.equals(key.sessionId(), currentSession)) {
                continue; // Current session content is already in context.
            }
            RuntimeContext sessionContext =
                    RuntimeContext.builder(rc).sessionId(key.sessionId()).build();
            var kernel = store.readKernel(key, sessionContext);
            var messages = MessageDerivation.history(kernel);
            for (int index = 0; index < messages.size(); index++) {
                Msg message = messages.get(index);
                String text = plainText(message);
                if (!matcher.test(text.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                hits.add(new Hit(key, index, message, text));
                break; // Best match per session is enough; keep scanning other sessions.
            }
            if (hits.size() >= limit) {
                break;
            }
        }
        if (hits.isEmpty()) {
            return "No matches. If the target session may belong to another agent, pass agentId"
                    + " explicitly.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Found ")
                .append(hits.size())
                .append(" matches (one snippet per session). Use session_history with")
                .append(" aroundIndex to read around a hit:")
                .append(System.lineSeparator());
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            out.append('[')
                    .append(i + 1)
                    .append("] agentId=")
                    .append(hit.key().agentId())
                    .append(" sessionId=")
                    .append(hit.key().sessionId())
                    .append(" index=")
                    .append(hit.index());
            if (hit.message().getId() != null) {
                out.append(" messageId=").append(hit.message().getId());
            }
            if (hit.message().getRole() != null) {
                out.append(" role=")
                        .append(hit.message().getRole().name().toLowerCase(Locale.ROOT));
            }
            out.append(System.lineSeparator());
            out.append("    ")
                    .append(snippet(hit.text(), firstHit(hit.text(), terms)))
                    .append(System.lineSeparator());
        }
        if (hits.size() >= limit) {
            out.append("Result limit reached (")
                    .append(limit)
                    .append(" sessions); narrow the query to see more.")
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    @Tool(
            name = "session_list",
            readOnly = true,
            description =
                    "List native sessions for an agent in the caller's workspace storage"
                            + " namespace.")
    public String sessionList(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID to list sessions for")
                    String agentId) {
        if (agentId == null || agentId.isBlank()) return "Error: agentId is required";
        RuntimeContext rc = context(runtimeContext);
        return JsonUtils.getJsonCodec().toJson(sessions(rc, agentId));
    }

    @Tool(
            name = "session_history",
            readOnly = true,
            description =
                    "Read committed conversation history including messages before context"
                            + " compaction. Pass aroundIndex (a coordinate from session_search) to"
                            + " read the neighborhood of a hit instead of the tail.")
    public String sessionHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID") String agentId,
            @ToolParam(name = "sessionId", description = "Session ID") String sessionId,
            @ToolParam(
                            name = "lastN",
                            description = "Number of recent messages (default: 20)",
                            required = false)
                    Integer lastN,
            @ToolParam(
                            name = "aroundIndex",
                            description =
                                    "Message index from session_search: return up to 25 messages"
                                            + " before and after it (instead of lastN)",
                            required = false)
                    Integer aroundIndex) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "Error: agentId and sessionId are required";
        }
        RuntimeContext rc = context(runtimeContext);
        RuntimeContext sessionContext = RuntimeContext.builder(rc).sessionId(sessionId).build();
        var kernel =
                store.readKernel(
                        new SessionKey(rc.getUserId(), agentId, sessionId), sessionContext);
        if (kernel.size() == 0) return "Session not found: " + sessionId;
        var messages = MessageDerivation.history(kernel);
        int from;
        int to;
        if (aroundIndex != null && aroundIndex >= 0) {
            from = Math.max(0, aroundIndex - 25);
            to = Math.min(messages.size(), aroundIndex + 26);
        } else {
            int limit = positiveLimit(lastN, 20);
            from = Math.max(0, messages.size() - limit);
            to = messages.size();
        }
        return JsonUtils.getJsonCodec()
                .toJson(
                        Map.of(
                                "asOfSeq",
                                kernel.size(),
                                "totalMessages",
                                messages.size(),
                                "messages",
                                messages.subList(from, to)));
    }

    private List<SessionKey> sessions(RuntimeContext rc, String agentId) {
        return store.list(rc).stream()
                .filter(
                        key ->
                                Objects.equals(
                                        normalizeUser(key.userId()), normalizeUser(rc.getUserId())))
                .filter(
                        key ->
                                agentId == null
                                        || agentId.isBlank()
                                        || key.agentId().equals(agentId))
                .toList();
    }

    /** Searchable plain text of one message: text blocks, tool calls and tool outputs. */
    private static String plainText(Msg message) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock> content = message.getContent();
        if (content == null) return "";
        for (ContentBlock block : content) {
            if (text.length() > 0) text.append('\n');
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText() == null ? "" : textBlock.getText());
            } else if (block instanceof ToolUseBlock use) {
                text.append(use.getName())
                        .append(' ')
                        .append(JsonUtils.getJsonCodec().toJson(use.getInput()));
            } else if (block instanceof ToolResultBlock result) {
                text.append(result.getName());
                for (ContentBlock output : result.getOutput()) {
                    if (output instanceof TextBlock textBlock) {
                        text.append(' ').append(textBlock.getText());
                    }
                }
            } else {
                text.append(JsonUtils.getJsonCodec().toJson(block));
            }
        }
        return text.toString();
    }

    /**
     * Snippet around {@code hit}: at most {@link #SNIPPET_CHARS} code points, the hit starting
     * roughly one third into the snippet, ellipses on both trimmed sides.
     */
    static String snippet(String text, int hit) {
        String stripped = text.strip();
        if (stripped.codePointCount(0, stripped.length()) <= SNIPPET_CHARS) {
            return stripped;
        }
        int total = stripped.length();
        int maxStart = Math.max(0, total - SNIPPET_CHARS);
        int start = hit - SNIPPET_CHARS / 3;
        if (start < 0) start = 0;
        if (start > maxStart) start = maxStart;
        int end = Math.min(total, start + SNIPPET_CHARS);
        return (start > 0 ? "..." : "")
                + stripped.substring(start, end)
                + (end < total ? "..." : "");
    }

    /** Lower-cased query terms used to locate the first hit for snippet centering. */
    private static List<String> searchTerms(String query) {
        return java.util.Arrays.stream(query.trim().split("\\s+"))
                .filter(term -> !term.isEmpty())
                .map(term -> term.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static int firstHit(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String term : terms) {
            int at = lower.indexOf(term);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best < 0 ? 0 : best;
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private static RuntimeContext context(RuntimeContext context) {
        return context == null ? RuntimeContext.empty() : context;
    }

    private static int positiveLimit(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
