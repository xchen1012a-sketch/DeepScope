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

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Accepted input, recorded in the same log as the work it causes.
 *
 * <p>Input is a durable fact: a submitted task, guidance for running work, or material to use later.
 * Recording it beside the execution keeps one order and one identity for everything that happened,
 * instead of two journals whose sequences have to be reconciled.
 *
 * <p>Whether input has been applied is read from its position. Input that starts work is applied once
 * its turn begins; nothing writes a separate "applied" marker, so the marker cannot drift from the
 * work it describes.
 *
 * <p>These records are log-only: they never produce a model-visible message, because the message the
 * model sees is the one the execution records when it claims the input.
 */
public final class SessionInputQueue {

    /** A submitted task: accepting it intends to start a turn. */
    public static final String SUBMIT = SessionEventTypes.INPUT_SUBMITTED;

    /** Guidance for work already running: it adjusts the current turn rather than starting one. */
    public static final String STEER = SessionEventTypes.INPUT_STEERED;

    /** Material recorded for later use: it starts nothing on its own. */
    public static final String INJECT = SessionEventTypes.INPUT_INJECTED;

    /** A request the session could not accept, with the reason it was refused. */
    public static final String REJECTED = SessionEventTypes.INPUT_REJECTED;

    private final SessionLog log;

    /**
     * Serve the queue of one log.
     *
     * @param log the session log that holds the input records
     */
    public SessionInputQueue(SessionLog log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * One accepted input.
     *
     * @param requestId the caller's idempotency identity
     * @param kind which input record carries it
     * @param turnId the turn this input belongs to
     * @param messages the input content
     * @param requestId the caller's idempotency identity
     * @param kind which input record carries it, and the event type it is stored under
     * @param turnId the turn this input belongs to
     * @param messages the input content
     * @param startsTurn whether accepting it intends to start a turn
     */
    public record Input(
            String requestId, String kind, String turnId, List<Msg> messages, boolean startsTurn)
            implements SessionEventData {

        public Input {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        @Override
        public String type() {
            return kind;
        }
    }

    /**
     * Accept a submitted task.
     *
     * @param requestId the caller's idempotency identity; a retry with the same id and the same
     *     content returns the recorded input rather than recording it twice
     * @param messages the task content
     * @param turnId the turn the task will run as
     * @return the accepted input
     * @throws IllegalArgumentException when the id was already used for different content
     */
    public Input submit(String requestId, List<Msg> messages, String turnId) {
        return accept(SUBMIT, requestId, messages, turnId, true);
    }

    /**
     * Accept guidance for running work.
     *
     * @param requestId the caller's idempotency identity
     * @param messages the guidance
     * @param turnId the turn being adjusted
     * @return the accepted input
     */
    public Input steer(String requestId, List<Msg> messages, String turnId) {
        return accept(STEER, requestId, messages, turnId, false);
    }

    /**
     * Record material to use later.
     *
     * @param requestId the caller's idempotency identity
     * @param messages the material
     * @return the accepted input
     */
    public Input inject(String requestId, List<Msg> messages) {
        return accept(INJECT, requestId, messages, null, false);
    }

    /**
     * Refuse a request that was already accepted, recording why.
     *
     * @param requestId the identity of the input being refused
     * @param reason a reader-facing explanation
     */
    public void reject(String requestId, String reason) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reason, "reason");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId);
        fields.put("reason", reason);
        log.appendLogOnly(
                System.currentTimeMillis(), new SessionEventData.PayloadData(REJECTED, fields));
    }

    /**
     * The reason a request was refused, when it was.
     *
     * @param requestId the identity to look up
     * @return the recorded reason, or empty when the request was not refused
     */
    public Optional<String> rejectionOf(String requestId) {
        String found = null;
        for (SessionEvent event : log.events()) {
            if (event.data() instanceof SessionEventData.PayloadData payload
                    && REJECTED.equals(payload.type())
                    && requestId.equals(payload.fields().get("requestId"))) {
                found = String.valueOf(payload.fields().get("reason"));
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * The accepted input that has not yet been applied, in the order it was accepted.
     *
     * <p>Input that starts work is applied once its turn begins. Guidance and material never start a
     * turn, so they remain until an execution records having used them.
     *
     * @return the pending input, oldest first
     */
    public List<Input> pending() {
        // A position belongs to the record, not to the payload, so the fold carries both.
        List<Recorded> accepted = new ArrayList<>();
        // A turn is identified by its recorded identity and by its number; an input names the
        // identity, while the work that applies it records the number. Both are tracked so either
        // naming applies the input.
        Map<String, SessionSeq.Seq> openedTurns = new LinkedHashMap<>();
        java.util.Set<String> applied = new java.util.HashSet<>();
        for (SessionEvent event : log.events()) {
            if (event.data() instanceof SessionExecutionData.TurnStart start) {
                openedTurns.put(String.valueOf(start.turn()), event.seq());
                if (event.turnId() != null) {
                    openedTurns.put(event.turnId(), event.seq());
                }
            }
            if (event.data() instanceof SessionEventData.PayloadData payload
                    && APPLIED.equals(payload.type())) {
                Object requestId = payload.fields().get("requestId");
                if (requestId != null) {
                    applied.add(String.valueOf(requestId));
                }
            }
            if (event.data() instanceof Input input && !REJECTED.equals(input.kind())) {
                accepted.add(new Recorded(input, event.seq()));
            }
        }
        List<Input> pending = new ArrayList<>();
        for (Recorded recorded : accepted) {
            Input input = recorded.input();
            if (applied.contains(input.requestId())) {
                continue;
            }
            if (!input.startsTurn()) {
                pending.add(input);
                continue;
            }
            SessionSeq.Seq opened = openedTurns.get(input.turnId());
            if (opened == null || opened.compareTo(recorded.seq()) < 0) {
                pending.add(input);
            }
        }
        return List.copyOf(pending);
    }

    /** Log-only record that an execution has consumed one accepted input. */
    public static final String APPLIED = "input/applied";

    /**
     * Record that an execution used this input, so {@link #pending()} no longer returns it.
     *
     * @param requestId the accepted input to consume
     */
    public void applied(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId);
        log.appendLogOnly(
                System.currentTimeMillis(), new SessionEventData.PayloadData(APPLIED, fields));
    }

    /** One accepted input together with the position that recorded it. */
    private record Recorded(Input input, SessionSeq.Seq seq) {}

    /**
     * Every accepted input, applied or not.
     *
     * @return the accepted input, oldest first
     */
    public List<Input> accepted() {
        List<Input> accepted = new ArrayList<>();
        for (SessionEvent event : log.events()) {
            if (event.data() instanceof Input input && !REJECTED.equals(input.kind())) {
                accepted.add(input);
            }
        }
        return List.copyOf(accepted);
    }

    private Input accept(
            String kind, String requestId, List<Msg> messages, String turnId, boolean startsTurn) {
        Objects.requireNonNull(requestId, "requestId");
        List<Msg> content = messages == null ? List.of() : List.copyOf(messages);
        for (Input existing : accepted()) {
            if (!existing.requestId().equals(requestId)) {
                continue;
            }
            if (!sameContent(existing.messages(), content)) {
                throw new IllegalArgumentException(
                        "Request id " + requestId + " was already used for different input");
            }
            return existing;
        }
        Input recorded = new Input(requestId, kind, turnId, content, startsTurn);
        log.appendLogOnly(System.currentTimeMillis(), recorded);
        return recorded;
    }

    private static boolean sameContent(List<Msg> left, List<Msg> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Objects.equals(
                    left.get(index).getTextContent(), right.get(index).getTextContent())) {
                return false;
            }
        }
        return true;
    }
}
