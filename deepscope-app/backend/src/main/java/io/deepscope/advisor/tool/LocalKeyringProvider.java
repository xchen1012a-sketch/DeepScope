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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A versioned keyring kept in deployment configuration.
 *
 * <p>The ring may hold several key versions at once. New data keys are wrapped under the active
 * version; older versions stay available for unwrapping until every data key has been re-wrapped.
 * This keeps rotation a deployment operation instead of a "re-enter every credential" event.
 */
public final class LocalKeyringProvider implements KeyProvider {

    /** Key id used when the deployment only supplies the single legacy key. */
    public static final String LEGACY_KEY_ID = "local-v1";

    private static final int DATA_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;

    public LocalKeyringProvider(Map<String, String> encodedKeys, String activeKeyId) {
        Map<String, SecretKeySpec> decoded = new LinkedHashMap<>();
        if (encodedKeys != null) {
            encodedKeys.forEach(
                    (id, value) -> {
                        if (id != null && !id.isBlank() && value != null && !value.isBlank())
                            decoded.put(id.strip(), decode(id, value.strip()));
                    });
        }
        if (activeKeyId != null
                && !activeKeyId.isBlank()
                && !decoded.isEmpty()
                && !decoded.containsKey(activeKeyId.strip()))
            throw new IllegalArgumentException(
                    "The active credential key id is not present in the keyring");
        this.keys = decoded;
        this.activeKeyId =
                activeKeyId != null && !activeKeyId.isBlank()
                        ? activeKeyId.strip()
                        : decoded.size() == 1 ? decoded.keySet().iterator().next() : null;
    }

    /**
     * Builds the ring from configuration: a JSON map of id to base64 key wins, otherwise the single
     * legacy key becomes {@link #LEGACY_KEY_ID}.
     */
    public static LocalKeyringProvider from(
            ObjectMapper json, String encodedKeys, String encodedKey, String activeKeyId) {
        if (encodedKeys != null && !encodedKeys.isBlank()) {
            try {
                Map<String, String> parsed =
                        json.readValue(encodedKeys, new TypeReference<Map<String, String>>() {});
                return new LocalKeyringProvider(parsed, activeKeyId);
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid credential keyring", error);
            }
        }
        if (encodedKey != null && !encodedKey.isBlank())
            return new LocalKeyringProvider(Map.of(LEGACY_KEY_ID, encodedKey), activeKeyId);
        return new LocalKeyringProvider(Map.of(), null);
    }

    @Override
    public String activeKeyId() {
        return keys.isEmpty() ? null : activeKeyId;
    }

    @Override
    public Wrapped wrap(byte[] dataKey) {
        if (activeKeyId == null)
            throw new ToolProblem(
                    422,
                    "credential_key_required",
                    "Configure the tool credential key before saving credentials");
        SecretKeySpec key = keys.get(activeKeyId);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(("dek:" + activeKeyId).getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(dataKey);
            byte[] value = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, value, 0, nonce.length);
            System.arraycopy(sealed, 0, value, nonce.length, sealed.length);
            return new Wrapped(activeKeyId, value);
        } catch (Exception error) {
            throw new IllegalStateException("Could not wrap the credential data key", error);
        }
    }

    @Override
    public byte[] unwrap(String keyId, byte[] wrapped) {
        SecretKeySpec key = keyId == null ? null : keys.get(keyId);
        if (key == null)
            throw new ToolProblem(
                    422,
                    "credential_unreadable",
                    "The credential was wrapped with an unknown key version");
        try {
            if (wrapped == null || wrapped.length <= NONCE_BYTES)
                throw new IllegalArgumentException("Wrapped data key is empty");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
            cipher.updateAAD(("dek:" + keyId).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(wrapped, NONCE_BYTES, wrapped.length - NONCE_BYTES);
        } catch (Exception error) {
            throw new ToolProblem(
                    422,
                    "credential_unreadable",
                    "The credential was wrapped with a key this deployment cannot use");
        }
    }

    private static SecretKeySpec decode(String id, String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != DATA_KEY_BYTES)
                throw new IllegalArgumentException(
                        "Credential key '" + id + "' must contain 32 bytes");
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Credential key '" + id + "' is not valid base64 for 32 bytes", error);
        }
    }

    @Override
    public String toString() {
        return "LocalKeyringProvider[keys=" + keys.size() + ", activeKeyId=" + activeKeyId + "]";
    }
}
