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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a stored session as a normalized record stream.
 *
 * <p>The export is backend-neutral: another tool can read it without knowing where or how the session
 * was stored. The header names the format version, and every committed event follows on its own line
 * in the same record form the store uses, so an exported session and a stored one describe the same
 * facts.
 *
 * <p>Shadowed events are exported. The log keeps them deliberately, and dropping them would lose the
 * conversation a person already saw.
 */
public final class SessionExport {

    private SessionExport() {}

    /**
     * Export a whole stored session.
     *
     * @param query the query surface to read through
     * @param sessionId the session to export
     * @return the exported records, one per line, ending with exactly one newline
     * @throws LocalSessionStore.SessionNotFoundException when the session is not stored
     */
    public static String of(SessionQuery query, String sessionId) {
        return render(query.readEvents(sessionId), sessionId, -1L);
    }

    /**
     * Export a stored session up to and including one position.
     *
     * @param query the query surface to read through
     * @param sessionId the session to export
     * @param throughSeq the last included position, inclusive
     * @return the bounded export and the position it stopped at
     * @throws IllegalArgumentException when no event occupies that position
     */
    public static Bounded upTo(SessionQuery query, String sessionId, SessionSeq.Seq throughSeq) {
        List<SessionEvent> all = query.readEvents(sessionId);
        if (throughSeq.value() >= all.size()) {
            throw new IllegalArgumentException(
                    "Export bound " + throughSeq + " is outside the log of " + all.size());
        }
        List<SessionEvent> included = all.subList(0, (int) throughSeq.value() + 1);
        return new Bounded(render(included, sessionId, throughSeq.value()), throughSeq);
    }

    /**
     * A bounded export.
     *
     * @param jsonl the exported records
     * @param throughSeq the last included position
     */
    public record Bounded(String jsonl, SessionSeq.Seq throughSeq) {}

    private static String render(List<SessionEvent> events, String sessionId, long throughSeq) {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder output = new StringBuilder();
        output.append(header(sessionId, throughSeq, mapper)).append('\n');
        for (SessionEvent event : events) {
            try {
                output.append(mapper.writeValueAsString(SessionEventCodec.encode(event, mapper)))
                        .append('\n');
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not encode an exported event", failure);
            }
        }
        return output.toString();
    }

    private static String header(String sessionId, long throughSeq, ObjectMapper mapper) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("version", SessionFormatVersion.CURRENT);
        header.put("session", sessionId);
        if (throughSeq >= 0) {
            header.put("throughSeq", throughSeq);
        }
        try {
            return mapper.writeValueAsString(header);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not encode the export header", failure);
        }
    }
}
