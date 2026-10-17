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

/**
 * The current logical session format version, stamped into every newly written session.
 *
 * <p>One monotonic integer, with no major and minor split. Writers emit only this version. A reader
 * accepts it, converts a supported older version through the adjacent migration chain, and refuses a
 * newer version rather than guessing at its meaning.
 *
 * <p>Retain the value when an older reader can still resume correctly, when a record is safely
 * ignorable, or when a required feature can be refused before execution. Bump it only when an
 * existing discriminator cannot prevent unsafe interpretation; a structural difference alone is not
 * a reason.
 */
public final class SessionFormatVersion {

    private SessionFormatVersion() {}

    /** The version every current writer stamps and every current reader accepts. */
    public static final int CURRENT = 1;
}
