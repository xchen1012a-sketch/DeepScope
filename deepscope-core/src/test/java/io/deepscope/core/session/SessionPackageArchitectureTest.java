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
package io.deepscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins what the {@code io.deepscope.core.session} package is allowed to contain after the old
 * recording protocol was deleted.
 *
 * <p>The assertion matches <em>types</em>, never event-name strings: {@code run/end},
 * {@code state/checkpoint}, and {@code state/snapshot} are deliberately retained payload names, and
 * {@code io.deepscope.core.session.log.*} is the kernel's own package.
 */
class SessionPackageArchitectureTest {

    /** The only types the session package may still hold. */
    private static final List<String> ALLOWED =
            List.of("AtomicSessionStorage", "InMemorySessionLogStore", "SessionKey");

    /**
     * Any reference to a type directly inside {@code io.deepscope.core.session}.
     *
     * <p>The captured name must start uppercase, which is what keeps the kernel's own {@code
     * io.deepscope.core.session.log} package — and the {@code package ...session.log;} declaration
     * inside it — from being mistaken for a deleted type.
     */
    private static final Pattern SESSION_TYPE =
            Pattern.compile("io\\.deepscope\\.core\\.session\\.([A-Z][\\w$]*)(?![.\\w$])");

    @Test
    void sessionPackageHoldsOnlyTheRetainedTypes() throws IOException {
        Path directory =
                repositoryRoot().resolve("deepscope-core/src/main/java/io/deepscope/core/session");
        assertTrue(
                Files.isDirectory(directory),
                "the session package directory must exist: " + directory);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .forEach(names::add);
        }
        names.sort(String::compareTo);
        assertEquals(
                ALLOWED,
                names,
                "the session package may only keep the retained types; the old protocol must not"
                        + " drift back in");
    }

    @Test
    void noSourceReferencesADeletedSessionType() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = javaSources(repositoryRoot())) {
            for (Path source : sources.toList()) {
                String text = Files.readString(source);
                Matcher matcher = SESSION_TYPE.matcher(text);
                while (matcher.find()) {
                    String type = matcher.group(1);
                    if (!ALLOWED.contains(type)) {
                        violations.add(source + " -> io.deepscope.core.session." + type);
                    }
                }
            }
        }
        assertEquals(
                List.of(),
                violations,
                "no source may reference a session type other than " + ALLOWED);
    }

    private static Stream<Path> javaSources(Path root) throws IOException {
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/target/"))
                .filter(path -> !path.toString().contains("/node_modules/"))
                .filter(path -> !path.toString().contains("/.git/"));
    }

    /** Walk up from the running module until the directory that holds every module is found. */
    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("deepscope-core"))
                    && Files.isDirectory(current.resolve("deepscope-harness"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Repository root not found from " + Path.of("").toAbsolutePath());
    }
}
