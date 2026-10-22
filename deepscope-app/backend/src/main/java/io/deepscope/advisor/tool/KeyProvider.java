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

/**
 * Wraps the per-credential data key that actually encrypts a stored secret.
 *
 * <p>Stored ciphertext is bound to a key id, so rotating the root key only re-wraps data keys and
 * never touches the secrets themselves. A deployment can replace the local keyring with a managed
 * KMS implementation without changing credential storage or call sites.
 */
public interface KeyProvider {

    /** Key id new data keys are wrapped with, or null when this deployment has no key. */
    String activeKeyId();

    /** Wraps one data key under the active root key. */
    Wrapped wrap(byte[] dataKey);

    /** Unwraps a data key that was wrapped under {@code keyId}. */
    byte[] unwrap(String keyId, byte[] wrapped);

    /** One wrapped data key plus the id of the root key that wrapped it. */
    record Wrapped(String keyId, byte[] value) {}
}
