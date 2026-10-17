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

import java.util.ArrayList;
import java.util.List;

/**
 * One entry per turn: where it starts, what was asked, and what was answered.
 *
 * <p>This is what the conversation page's turn rail reads to list and jump between turns. The entry
 * names the turn's start position so a jump targets a stable log position rather than an offset that
 * history loading could shift.
 *
 * <p>A turn is published when it closes. While it is still open the entry carries no response, and
 * draft-only updates keep the published list identical, so a reader that renders the rail is not
 * rebuilt on every streaming event.
 */
public final class TurnOutlineProjection {

    /** The state key this projection publishes. */
    public static final String KEY = "turnOutline";

    /** The state version, bumped when the fields or fold semantics change. */
    public static final long STATE_VERSION = 1L;

    /** Longest prompt summary, in characters. */
    public static final int PROMPT_MAX_CHARS = 50;

    /** Longest response summary, in characters. */
    public static final int RESPONSE_MAX_CHARS = 120;

    /**
     * One turn's outline entry.
     *
     * @param turn the turn number
     * @param seq the turn's start position, used as the jump target
     * @param prompt the first human message's text, truncated, or null when the turn had none
     * @param response the latest assistant text at turn end, truncated, or null while the turn runs
     */
    public record TurnOutline(long turn, long seq, String prompt, String response) {}

    /**
     * The closed turns plus the open turn's draft entry.
     *
     * <p>The open turn is kept as one entry that is replaced as its draft facts arrive, so a
     * draft-only update changes that entry without producing a new outline list. That identity is
     * what keeps a renderer of the turn rail from being rebuilt on every streaming event.
     *
     * @param closed entries for turns that have closed, in turn order
     * @param openTurn the open turn's number, or null between turns
     * @param openSeq the open turn's start position
     * @param openDraft the open turn's entry, replaced as its prompt and response arrive
     */
    public record State(
            List<TurnOutline> closed, Long openTurn, long openSeq, TurnOutline openDraft) {

        public State {
            closed = List.copyOf(closed);
        }

        /** The state before any committed event. */
        public static State empty() {
            return new State(List.of(), null, 0L, null);
        }

        /**
         * The published outline: closed turns, then the open turn while one exists.
         *
         * <p>The common shapes return a stable reference, so repeated reads of one state compare
         * equal and a draft update that produced no new state republishes nothing.
         *
         * @return the outline entries
         */
        public List<TurnOutline> view() {
            if (openDraft == null) {
                return closed;
            }
            // The open turn is the last entry, so expose it as an immutable suffix of one element.
            return appendOne(openDraft);
        }

        private List<TurnOutline> appendOne(TurnOutline entry) {
            List<TurnOutline> tail = new ArrayList<>(closed);
            tail.add(entry);
            return List.copyOf(tail);
        }
    }

    /** Contribute this projection to a registry. */
    public ProjectionRegistry.Registration register(ProjectionRegistry registry) {
        return registry.register(unit());
    }

    /**
     * The fold unit.
     *
     * @return the unit to register
     */
    public ProjectionRegistry.Unit<State> unit() {
        return new ProjectionRegistry.Unit<>() {
            @Override
            public String key() {
                return KEY;
            }

            @Override
            public long stateVersion() {
                return STATE_VERSION;
            }

            @Override
            public State init(SessionLog log) {
                return State.empty();
            }

            @Override
            public State apply(State state, SessionEvent event) {
                return fold(state, event);
            }
        };
    }

    private static State fold(State state, SessionEvent event) {
        SessionEventData data = event.data();
        if (data instanceof SessionExecutionData.TurnStart start) {
            TurnOutline draft = new TurnOutline(start.turn(), event.seq().value(), null, null);
            return new State(state.closed(), start.turn(), event.seq().value(), draft);
        }
        if (data instanceof SessionEventData.UserMessageData user) {
            // A direct prompt is a human message; an injected notice carries a source and is not
            // what the user asked, so it never becomes the prompt summary.
            if (user.source() != null
                    || state.openDraft() == null
                    || state.openDraft().prompt() != null) {
                return state;
            }
            TurnOutline draft = state.openDraft();
            return new State(
                    state.closed(),
                    state.openTurn(),
                    state.openSeq(),
                    new TurnOutline(
                            draft.turn(),
                            draft.seq(),
                            truncate(textOf(user.content()), PROMPT_MAX_CHARS),
                            draft.response()));
        }
        if (data instanceof SessionEventData.AssistantMessageData assistant) {
            if (state.openDraft() == null) {
                return state;
            }
            String text = textOf(assistant.content());
            if (text == null) {
                return state;
            }
            TurnOutline draft = state.openDraft();
            return new State(
                    state.closed(),
                    state.openTurn(),
                    state.openSeq(),
                    new TurnOutline(
                            draft.turn(),
                            draft.seq(),
                            draft.prompt(),
                            truncate(text, RESPONSE_MAX_CHARS)));
        }
        if (data instanceof SessionExecutionData.TurnEnd end
                && state.openTurn() != null
                && state.openTurn() == end.turn()) {
            List<TurnOutline> closed = new ArrayList<>(state.closed());
            closed.add(state.openDraft());
            return new State(List.copyOf(closed), null, state.openSeq(), null);
        }
        return state;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Concatenated text of a message's text blocks, or null when it carries none. */
    private static String textOf(List<io.deepscope.core.message.ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (io.deepscope.core.message.ContentBlock block : content) {
            if (block instanceof io.deepscope.core.message.TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}
