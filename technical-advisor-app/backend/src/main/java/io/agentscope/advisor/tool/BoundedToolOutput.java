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
package io.agentscope.advisor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Fixed-size UTF-8 output; crossing the limit aborts further SDK result traversal. */
final class BoundedToolOutput extends OutputStream {
    private final byte[] bytes;

    private BoundedToolOutput(int lookahead) {
        bytes = new byte[Math.addExact(ToolHttpExecutor.MAX_RESPONSE_BYTES + 1, lookahead)];
    }

    private int size;
    private boolean truncated;

    record Rendered(String text, boolean truncated) {}

    static Rendered json(ObjectMapper json, Object value, int lookahead) throws IOException {
        var output = new BoundedToolOutput(lookahead);
        try {
            json.writeValue(output, value);
        } catch (IOException error) {
            if (!output.truncated) throw error;
        }
        return new Rendered(utf8(output.bytes, output.size), output.truncated);
    }

    static Rendered redacted(String text, boolean[] hidden, String marker) {
        var output = new BoundedToolOutput(0);
        try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            for (int index = 0; index < text.length(); ) {
                int start = index;
                if (hidden != null && hidden[index]) {
                    while (index < text.length() && hidden[index]) index++;
                    writer.write(marker);
                } else {
                    while (index < text.length() && (hidden == null || !hidden[index])) index++;
                    writer.write(text, start, index - start);
                }
            }
        } catch (IOException error) {
            if (!output.truncated)
                throw new IllegalStateException("Could not encode bounded output", error);
        }
        return new Rendered(
                utf8(output.bytes, Math.min(output.size, ToolHttpExecutor.MAX_RESPONSE_BYTES)),
                output.truncated);
    }

    @Override
    public void write(int value) throws IOException {
        if (size == bytes.length) {
            truncated = true;
            throw new IOException("Tool output limit reached");
        }
        bytes[size++] = (byte) value;
        if (size > ToolHttpExecutor.MAX_RESPONSE_BYTES) truncated = true;
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        int available = Math.min(length, bytes.length - size);
        if (available > 0) {
            System.arraycopy(source, offset, bytes, size, available);
            size += available;
        }
        if (size > ToolHttpExecutor.MAX_RESPONSE_BYTES) truncated = true;
        if (available < length) throw new IOException("Tool output limit reached");
    }

    static String utf8(byte[] bytes, int limit) {
        int end = Math.min(bytes.length, limit);
        int start = end - 1;
        while (start >= 0 && (bytes[start] & 0xC0) == 0x80) start--;
        if (start >= 0) {
            int lead = bytes[start] & 0xFF;
            int width = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
            if (end - start < width) end = start;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
