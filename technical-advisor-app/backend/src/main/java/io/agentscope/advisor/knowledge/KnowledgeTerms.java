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
package io.agentscope.advisor.knowledge;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded Chinese bigrams plus identifier tokens; does not assume an English FTS dictionary. */
final class KnowledgeTerms {
    private static final Pattern TERMS =
            Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}][\\p{L}\\p{N}_.:/-]*");

    private KnowledgeTerms() {}

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    static List<String> of(String query) {
        var found = new LinkedHashSet<String>();
        var matcher = TERMS.matcher(normalize(query));
        while (matcher.find() && found.size() < 20) {
            String term = matcher.group();
            if (term.length() > 100) term = term.substring(0, 100);
            found.add(term);
            if (term.codePoints()
                    .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] points = term.codePoints().toArray();
                for (int i = 0; i < points.length - 1 && found.size() < 20; i++) {
                    found.add(new String(points, i, 2));
                }
            }
        }
        return List.copyOf(found);
    }
}
