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

import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Session titles: a deterministic fallback, terminal-safe normalization, and the rules for when an
 * automatically chosen title may be revised.
 *
 * <p>Title text reaches a terminal, a window manager, and a file name, so it is sanitized rather than
 * trusted: escape sequences and control characters are removed, direction overrides that could make
 * a displayed title read differently than it is stored are removed, and truncation respects UTF-8
 * code point boundaries so a multi-byte character is never split.
 *
 * <p>A title a person assigned pins the session: an automatic provider may still record a newer
 * value, but nothing may silently replace what the user chose.
 */
public final class SessionTitle {

    private SessionTitle() {}

    /** The log-only event type carrying one accepted title revision. */
    public static final String TITLE_EVENT_TYPE = "session/title";

    /** Where a title came from. Later revisions win, but a user title pins the session. */
    public enum Source {
        /** The deterministic fallback derived from the first human message. */
        FALLBACK,
        /** A title provider, such as a model. */
        PROVIDER,
        /** A person assigned the title explicitly. */
        USER
    }

    /** Operating-system command escape sequences, including an unterminated tail. */
    private static final Pattern OSC =
            Pattern.compile(
                    "(?:\\u001B\\]|\\u009D)(?:(?!\\u0007|\\u001B\\\\)[\\s\\S])*(?:\\u0007|\\u001B\\\\|$)");

    /** Control-sequence-introducer escapes, such as colour codes. */
    private static final Pattern CSI = Pattern.compile("(?:\\u001B\\[|\\u009B)[0-?]*[ -/]*[@-~]");

    /** Remaining two-byte escape sequences. */
    private static final Pattern ESC = Pattern.compile("\\u001B[@-_]");

    /** Non-whitespace control characters. */
    private static final Pattern CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]");

    /** Directional and invisible controls that can make a displayed title deceptive. */
    private static final Pattern DIRECTIONAL =
            Pattern.compile(
                    "[\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\uFEFF]");

    /** Runs of whitespace, collapsed to one space. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Derive the deterministic fallback from the first eligible human message.
     *
     * @param input the message text
     * @param maxWords maximum whitespace-delimited words
     * @param maxBytes maximum UTF-8 bytes
     * @return the sanitized leading words within both limits, possibly empty
     * @throws IllegalArgumentException when a limit is not positive
     */
    public static String fallback(String input, int maxWords, int maxBytes) {
        requirePositive("maxWords", maxWords);
        requirePositive("maxBytes", maxBytes);
        String cleaned = clean(input);
        if (cleaned.isEmpty()) {
            return "";
        }
        String[] words = cleaned.split(" ");
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < words.length && index < maxWords; index++) {
            if (words[index].isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(words[index]);
        }
        return truncateUtf8(joined.toString(), maxBytes).stripTrailing();
    }

    /**
     * Sanitize one accepted title and enforce its byte budget.
     *
     * @param input untrusted title text
     * @param maxBytes maximum UTF-8 bytes
     * @return a terminal-safe single-line title, possibly empty after sanitization
     * @throws IllegalArgumentException when the budget is not positive
     */
    public static String normalize(String input, int maxBytes) {
        requirePositive("maxBytes", maxBytes);
        return truncateUtf8(clean(input), maxBytes).stripTrailing();
    }

    /**
     * Remove terminal controls and produce one trimmed line.
     *
     * @param input untrusted text
     * @return the sanitized line
     */
    public static String clean(String input) {
        if (input == null) {
            return "";
        }
        String value = OSC.matcher(input).replaceAll("");
        value = CSI.matcher(value).replaceAll("");
        value = ESC.matcher(value).replaceAll("");
        value = CONTROL.matcher(value).replaceAll("");
        value = DIRECTIONAL.matcher(value).replaceAll("");
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /**
     * Truncate to a UTF-8 byte budget without splitting a code point.
     *
     * @param input the text to truncate
     * @param maxBytes the byte budget
     * @return the longest leading code point prefix within the budget
     * @throws IllegalArgumentException when the budget is not positive
     */
    public static String truncateUtf8(String input, int maxBytes) {
        requirePositive("maxBytes", maxBytes);
        if (input.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return input;
        }
        int used = 0;
        StringBuilder output = new StringBuilder();
        for (int offset = 0; offset < input.length(); ) {
            int codePoint = input.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int bytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            output.append(character);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return output.toString();
    }

    /**
     * The latest recorded title revision.
     *
     * @param events contiguous events in sequence order
     * @return the latest title text, or null when no revision was recorded
     */
    public static String explicitTitle(List<SessionEvent> events) {
        String title = null;
        for (SessionEvent event : events) {
            if (event.data() instanceof TitleData data) {
                title = data.title();
            }
        }
        return title;
    }

    /**
     * Whether a person assigned the session's title, which pins it against silent replacement.
     *
     * @param events contiguous events in sequence order
     * @return whether any recorded revision came from a person
     */
    public static boolean isPinned(List<SessionEvent> events) {
        for (SessionEvent event : events) {
            if (event.data() instanceof TitleData data && data.source() == Source.USER) {
                return true;
            }
        }
        return false;
    }

    /**
     * The title a reader should display: the latest revision, or the fallback from the first human
     * message when none was recorded.
     *
     * @param events contiguous events in sequence order
     * @param maxWords fallback word cap
     * @param maxBytes fallback byte cap
     * @return the effective title, or null when the log offers nothing to name the session with
     */
    public static String effectiveTitle(List<SessionEvent> events, int maxWords, int maxBytes) {
        String explicit = explicitTitle(events);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String first = firstHumanMessage(events);
        if (first == null) {
            return null;
        }
        String derived = fallback(first, maxWords, maxBytes);
        return derived.isEmpty() ? null : derived;
    }

    /**
     * Append one accepted title revision.
     *
     * @param log the session log
     * @param time Unix epoch milliseconds
     * @param title the title text
     * @param source where the title came from
     * @return the committed event
     */
    public static SessionEvent append(SessionLog log, long time, String title, Source source) {
        return log.appendLogOnly(time, new TitleData(title, source));
    }

    /**
     * Append one accepted title revision through a store handle, so it is persisted.
     *
     * <p>Prefer this overload on a stored session. Appending to a handle's inner log would record the
     * revision in memory only, and the title would be gone after a reopen.
     *
     * @param handle an open write handle
     * @param time Unix epoch milliseconds
     * @param title the title text
     * @param source where the title came from
     */
    public static void appendTo(
            LocalSessionStore.Handle handle, long time, String title, Source source) {
        handle.appendLogOnly(time, new TitleData(title, source));
    }

    /** The first direct human message's text, ignoring injected context notices. */
    private static String firstHumanMessage(List<SessionEvent> events) {
        for (SessionEvent event : events) {
            if (event.data() instanceof SessionEventData.UserMessageData user
                    && user.source() == null) {
                String text = textOf(user.content());
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String textOf(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    /**
     * One recorded title revision.
     *
     * <p>Declared here so a stored revision is read back as the same payload type the writer used,
     * regardless of which component contributed it.
     *
     * @param title the accepted title text
     * @param source where it came from
     */
    public record TitleData(String title, Source source) implements SessionEventData {

        public TitleData {
            if (title == null) {
                throw new IllegalArgumentException("Title must not be null");
            }
            if (source == null) {
                throw new IllegalArgumentException("Title source must not be null");
            }
        }

        @Override
        public String type() {
            return TITLE_EVENT_TYPE;
        }
    }
}
