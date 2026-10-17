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
package io.deepscope.advisor.tool;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Removes known active credentials before remote text is returned, persisted or given to a model. */
final class ToolCredentialRedactor {
    private ToolCredentialRedactor() {}

    /** Enough bounded lookahead to finish the longest secret representation, including JSON unicode escapes. */
    static int lookahead(Map<String, Object> credential) {
        int longest = representations(credential).stream().mapToInt(String::length).max().orElse(0);
        try {
            return Math.multiplyExact(longest, 6);
        } catch (ArithmeticException error) {
            throw new ToolProblem(
                    422, "invalid_auth", "The credential is too large to execute safely");
        }
    }

    static BoundedToolOutput.Rendered render(
            String text, Map<String, Object> credential, boolean sourceTruncated) {
        List<String> secrets = representations(credential);
        if (secrets.isEmpty()) return BoundedToolOutput.redacted(text, null, "");
        boolean[] hidden = new boolean[text.length()];
        Decoded decoded = decodeJsonText(text);
        for (String secret : secrets) {
            for (int start = text.indexOf(secret);
                    start >= 0;
                    start = text.indexOf(secret, start + secret.length()))
                java.util.Arrays.fill(hidden, start, start + secret.length(), true);
            for (int start = decoded.text().indexOf(secret);
                    start >= 0;
                    start = decoded.text().indexOf(secret, start + secret.length()))
                java.util.Arrays.fill(
                        hidden,
                        decoded.starts()[start],
                        decoded.ends()[start + secret.length() - 1],
                        true);
            if (sourceTruncated) {
                int prefix = trailingPrefix(decoded.text(), secret);
                if (prefix > 0)
                    java.util.Arrays.fill(
                            hidden,
                            decoded.starts()[decoded.text().length() - prefix],
                            text.length(),
                            true);
            }
        }
        String marker = secrets.stream().anyMatch("[REDACTED]"::contains) ? "" : "[REDACTED]";
        return BoundedToolOutput.redacted(text, hidden, marker);
    }

    private record Decoded(String text, int[] starts, int[] ends) {}

    /** Maps decoded JSON string characters back to raw spans so other response formatting is preserved. */
    private static Decoded decodeJsonText(String raw) {
        var decoded = new StringBuilder(raw.length());
        int[] starts = new int[raw.length()];
        int[] ends = new int[raw.length()];
        for (int index = 0; index < raw.length(); ) {
            int start = index;
            char character = raw.charAt(index++);
            if (character == '\\' && index < raw.length()) {
                char escape = raw.charAt(index);
                if (escape == 'u') {
                    if (index + 5 > raw.length()) break;
                    try {
                        character =
                                (char) Integer.parseInt(raw.substring(index + 1, index + 5), 16);
                        index += 5;
                    } catch (NumberFormatException invalid) {
                        /* Keep invalid escapes as raw text. */
                    }
                } else {
                    Character replacement =
                            switch (escape) {
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                case 'b' -> '\b';
                                case 'f' -> '\f';
                                case '\\', '/', '"' -> escape;
                                default -> null;
                            };
                    if (replacement != null) {
                        character = replacement;
                        index++;
                    }
                }
            } else if (character == '\\' && index == raw.length()) break;
            int position = decoded.length();
            starts[position] = start;
            ends[position] = index;
            decoded.append(character);
        }
        return new Decoded(decoded.toString(), starts, ends);
    }

    /** Linear prefix matching avoids quadratic work for long repeated secret characters. */
    private static int trailingPrefix(String text, String secret) {
        int[] failure = new int[secret.length()];
        for (int index = 1, matched = 0; index < secret.length(); index++) {
            while (matched > 0 && secret.charAt(index) != secret.charAt(matched))
                matched = failure[matched - 1];
            if (secret.charAt(index) == secret.charAt(matched)) matched++;
            failure[index] = matched;
        }
        int matched = 0;
        for (int index = Math.max(0, text.length() - secret.length());
                index < text.length();
                index++) {
            while (matched > 0 && text.charAt(index) != secret.charAt(matched))
                matched = failure[matched - 1];
            if (text.charAt(index) == secret.charAt(matched)) matched++;
            if (matched == secret.length()) matched = failure[matched - 1];
        }
        return matched;
    }

    static URI redirect(URI target, Map<String, Object> credential) {
        if (credential == null || target.getRawQuery() == null) return target;
        String apiName =
                "apiKey".equals(credential.get("type")) && "query".equals(credential.get("in"))
                        ? String.valueOf(credential.get("name"))
                        : null;
        List<String> secrets = representations(credential);
        var kept = new ArrayList<String>();
        for (String pair : target.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            if (name.equals(apiName) || secrets.stream().anyMatch(value::contains)) continue;
            kept.add(pair);
        }
        String root = target.getScheme() + "://" + target.getRawAuthority() + target.getRawPath();
        return URI.create(root + (kept.isEmpty() ? "" : "?" + String.join("&", kept)));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new ToolProblem(
                    422, "invalid_redirect", "The redirect query cannot be checked safely");
        }
    }

    private static List<String> representations(Map<String, Object> credential) {
        if (credential == null) return List.of();
        var values = new LinkedHashSet<String>();
        for (String field : List.of("value", "password"))
            if (credential.get(field) instanceof String value && !value.isEmpty())
                values.add(value);
        if ("basic".equals(credential.get("type"))) {
            String pair = credential.get("username") + ":" + credential.get("password");
            values.add(pair);
            values.add(
                    java.util.Base64.getEncoder()
                            .encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        values.addAll(ToolAuth.headers(credential).values());
        for (String secret : List.copyOf(values)) {
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            values.add(encoded);
            values.add(encoded.replace("+", "%20"));
            values.add(
                    secret.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r"));
        }
        return values.stream()
                .filter(value -> !value.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}
