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
package io.deepscope.advisor.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Validates user-controlled names and every existing component below a trusted root. */
final class SkillPaths {
    private SkillPaths() {}

    static String name(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.equals(".")
                || value.equals("..")
                || value.endsWith(".")
                || value.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || "/\\<>:\"|?*".indexOf(c) >= 0)
                || value.toUpperCase(Locale.ROOT)
                        .matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
            throw new IllegalArgumentException("invalid_skill_name");
        }
        return value;
    }

    static Path contained(Path root, Path target) {
        root = root.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("invalid_skill_path");
        }
        Path component = root;
        for (Path segment : root.relativize(target)) {
            component = component.resolve(segment);
            if (Files.isSymbolicLink(component))
                throw new IllegalArgumentException("invalid_skill_path");
        }
        if (Files.isDirectory(target)) {
            try (var files = Files.walk(target)) {
                if (files.anyMatch(Files::isSymbolicLink))
                    throw new IllegalArgumentException("invalid_skill_path");
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("invalid_skill_path", error);
            }
        }
        return target;
    }
}
