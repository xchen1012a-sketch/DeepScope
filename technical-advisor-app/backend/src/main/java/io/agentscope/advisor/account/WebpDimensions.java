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
package io.agentscope.advisor.account;

import java.nio.charset.StandardCharsets;

/** RIFF/frame metadata bounds for WebP; the JDK has no built-in WebP pixel decoder. */
final class WebpDimensions {
    private WebpDimensions() {}

    static void validate(byte[] bytes, int maxSide) {
        if (bytes.length < 20
                || !tag(bytes, 0).equals("RIFF")
                || !tag(bytes, 8).equals("WEBP")
                || number(bytes, 4, 4) + 8 != bytes.length) throw invalid();
        if (!chunks(bytes, 12, bytes.length, maxSide, true)) throw invalid();
    }

    private static boolean chunks(
            byte[] bytes, int offset, int limit, int maxSide, boolean container) {
        boolean frame = false;
        boolean animated = false;
        boolean animationHeader = false;
        while (offset + 8 <= limit) {
            String type = tag(bytes, offset);
            long size = number(bytes, offset + 4, 4);
            int start = offset + 8;
            long end = start + size;
            if (end > limit) throw invalid();
            if (type.equals("VP8X")) {
                if (!container || size != 10) throw invalid();
                animated = (bytes[start] & 2) != 0;
                bounds(number(bytes, start + 4, 3) + 1, number(bytes, start + 7, 3) + 1, maxSide);
            } else if (type.equals("ANIM")) {
                if (!animated || size != 6) throw invalid();
                animationHeader = true;
            } else if (type.equals("ANMF")) {
                if (!container || !animationHeader || size <= 16) throw invalid();
                long width = number(bytes, start + 6, 3) + 1;
                long height = number(bytes, start + 9, 3) + 1;
                bounds(
                        width + 2 * number(bytes, start, 3),
                        height + 2 * number(bytes, start + 3, 3),
                        maxSide);
                if (!chunks(bytes, start + 16, (int) end, maxSide, false)) throw invalid();
                frame = true;
            } else if (type.equals("VP8 ")) {
                if (frame
                        || animated
                        || size <= 10
                        || (bytes[start] & 1) != 0
                        || (bytes[start + 3] & 255) != 0x9d
                        || (bytes[start + 4] & 255) != 1
                        || (bytes[start + 5] & 255) != 0x2a) throw invalid();
                bounds(
                        number(bytes, start + 6, 2) & 0x3fff,
                        number(bytes, start + 8, 2) & 0x3fff,
                        maxSide);
                frame = true;
            } else if (type.equals("VP8L")) {
                if (frame || animated || size <= 5 || (bytes[start] & 255) != 0x2f) throw invalid();
                long bits = number(bytes, start + 1, 4);
                if ((bits >>> 29) != 0) throw invalid();
                bounds((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1, maxSide);
                frame = true;
            }
            offset = (int) end + ((int) size & 1);
        }
        if (offset != limit) throw invalid();
        return frame;
    }

    private static void bounds(long width, long height, int max) {
        if (width < 1 || height < 1 || width > max || height > max) throw invalid();
    }

    private static String tag(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static long number(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) value |= (long) (bytes[offset + i] & 255) << (8 * i);
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or oversized WebP avatar");
    }
}
