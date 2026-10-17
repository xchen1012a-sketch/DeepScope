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
package io.deepscope.core.session.log;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Payloads that make every request a pure function of the log.
 *
 * <p>History comes from the surface; everything else a request needs is captured here at dispatch
 * time. Without a recorded envelope a reader can see what the model said but not what it was asked,
 * which makes replay and partial-window reconstruction impossible.
 */
public final class SessionRequestData {

    private SessionRequestData() {}

    /** Why a request header snapshot was appended. */
    public enum RequestHeaderReason {
        /** The log's first header: a new conversation. */
        INITIAL,
        /** A loop instance's first request over a log that already holds headers. */
        RESUME,
        /** A later request used a different envelope. */
        CHANGE,
        /** An unchanged envelope began a distinct message series or followed a replacement. */
        SERIES
    }

    /**
     * A full snapshot of the request envelope outside derived history.
     *
     * <p>The rendered system prompt is deliberately absent: it is surface node 0, so one text has
     * exactly one home.
     *
     * @param reason why this snapshot was appended
     * @param startsSeries whether this request begins a distinct message series independently of
     *     {@code reason}
     * @param toolNames assembled tool names in request order; empty for a tool-less request
     * @param config call configuration the request used
     * @param adapterDefaults fields the adapter resolved rather than the caller proposed
     */
    public record RequestHeader(
            RequestHeaderReason reason,
            boolean startsSeries,
            List<String> toolNames,
            Map<String, Object> config,
            Map<String, Object> adapterDefaults)
            implements SessionEventData {
        public RequestHeader {
            Objects.requireNonNull(reason, "reason");
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            config = config == null ? Map.of() : Map.copyOf(config);
            adapterDefaults = adapterDefaults == null ? Map.of() : Map.copyOf(adapterDefaults);
        }

        /** A first request with no adapter-resolved fields. */
        public static RequestHeader initial(List<String> toolNames, Map<String, Object> config) {
            return new RequestHeader(
                    RequestHeaderReason.INITIAL, false, toolNames, config, Map.of());
        }

        @Override
        public String type() {
            return SessionEventTypes.REQUEST_HEADER;
        }
    }

    /**
     * Route metadata for a request.
     *
     * <p>Recorded only when the route, its advertised capacity, or the system-prompt update mode
     * changes. It advertises the context window the conversation page needs for its occupancy ring,
     * and it never participates in request reconstruction.
     *
     * @param provider the registered provider route
     * @param model the provider-owned model id
     * @param contextWindow maximum combined request and response context in tokens, when advertised
     */
    public record RequestContext(String provider, String model, Long contextWindow)
            implements SessionEventData {
        public RequestContext {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            if (contextWindow != null && contextWindow <= 0) {
                throw new IllegalArgumentException("contextWindow must be positive when present");
            }
        }

        @Override
        public String type() {
            return SessionEventTypes.REQUEST_CONTEXT;
        }
    }

    /**
     * Marks the boundary between inherited history and later lifecycle-owned work.
     *
     * <p>Only the session constructor and fork-seed construction write one. The last tagged marker is
     * the session's inherited cut; an untagged marker keeps an ordinary lifecycle boundary, because a
     * restored seed that already ends in a marker does not need a second one.
     *
     * @param inherited whether this marker records the exact cut of a fork-inherited prefix
     */
    public record EndSeed(boolean inherited) implements SessionEventData {
        @Override
        public String type() {
            return SessionEventTypes.END_SEED;
        }
    }

    /**
     * Fold the log to the request envelope the next request inherits.
     *
     * @param events contiguous events in sequence order
     * @return the latest full snapshot, or null when the log holds no header yet
     */
    public static RequestHeader foldRequestHeader(List<SessionEvent> events) {
        RequestHeader latest = null;
        for (SessionEvent event : events) {
            if (event.data() instanceof RequestHeader header) {
                latest = header;
            }
        }
        return latest;
    }

    /**
     * Fold the log to the latest recorded route metadata.
     *
     * @param events contiguous events in sequence order
     * @return the latest route snapshot, or null when the route has never been recorded
     */
    public static RequestContext foldRequestContext(List<SessionEvent> events) {
        RequestContext latest = null;
        for (SessionEvent event : events) {
            if (event.data() instanceof RequestContext context) {
                latest = context;
            }
        }
        return latest;
    }

    /**
     * Derive the reason for a header snapshot about to be appended.
     *
     * @param log the log receiving the snapshot
     * @param envelope the envelope to record
     * @return {@code INITIAL} for the first header, {@code SERIES} when the envelope is unchanged or
     *     explicitly starts a series, otherwise {@code CHANGE}
     */
    public static RequestHeaderReason reasonFor(SessionLog log, RequestHeader envelope) {
        RequestHeader latest = foldRequestHeader(log.events());
        if (latest == null) {
            return RequestHeaderReason.INITIAL;
        }
        if (envelope.startsSeries() || sameEnvelope(latest, envelope)) {
            return RequestHeaderReason.SERIES;
        }
        return RequestHeaderReason.CHANGE;
    }

    /**
     * Whether two envelopes describe the same request outside history.
     *
     * <p>The reason and the series marker describe why a snapshot was written, not what was sent, so
     * they do not participate in equality.
     *
     * @param left one envelope
     * @param right another envelope
     * @return whether both would produce the same request
     */
    public static boolean sameEnvelope(RequestHeader left, RequestHeader right) {
        return left.toolNames().equals(right.toolNames())
                && left.config().equals(right.config())
                && left.adapterDefaults().equals(right.adapterDefaults());
    }
}
