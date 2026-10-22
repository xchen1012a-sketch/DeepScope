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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/**
 * Immutable encrypted credentials.
 *
 * <p>Each secret is encrypted with its own data key; the data key is wrapped by a {@link
 * KeyProvider} and stored beside the ciphertext. A database dump alone is useless, and rotating the
 * root key never requires the user to enter a secret again.
 */
public final class ToolCredentialStore {
    private static final int DATA_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final ObjectMapper json;
    private final KeyProvider keys;
    private final DataSource dataSource;

    /** Single-key construction kept for deployments and tests that still pass one key. */
    public ToolCredentialStore(ObjectMapper json, String encodedKey, DataSource dataSource) {
        this(json, LocalKeyringProvider.from(json, "", encodedKey, null), dataSource);
    }

    public ToolCredentialStore(ObjectMapper json, KeyProvider keys, DataSource dataSource) {
        this.json = json;
        this.keys = keys;
        this.dataSource = dataSource;
    }

    public record Pending(
            String id,
            String owner,
            String ciphertext,
            String nonce,
            String wrappedDek,
            String keyId) {}

    /** Prepares the encrypted row for a caller's transaction; no secret at all means nothing to do. */
    public Pending prepare(String owner, Object secret) {
        if (secret == null) return null;
        if (keys.activeKeyId() == null)
            throw new ToolProblem(
                    422,
                    "credential_key_required",
                    "Configure the tool credential key before saving credentials");
        try {
            String id = UUID.randomUUID().toString();
            byte[] dataKey = new byte[DATA_KEY_BYTES];
            new SecureRandom().nextBytes(dataKey);
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((owner + ":" + id).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(json.writeValueAsBytes(secret));
            KeyProvider.Wrapped wrapped = keys.wrap(dataKey);
            return new Pending(
                    id,
                    owner,
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(wrapped.value()),
                    wrapped.keyId());
        } catch (ToolProblem error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Could not encrypt tool credentials", error);
        }
    }

    /**
     * Writes a replacement on its own connection. It is written before the edit lands, so a
     * rejected edit leaves the tool pointing at its previous credential rather than at nothing.
     */
    public void insert(Pending p) throws SQLException {
        if (p == null) return;
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, p);
        }
    }

    /**
     * Writes a prepared credential on a caller's connection, so a failed import leaves no
     * credential behind pointing at nothing.
     */
    public static void insert(Connection c, Pending p) throws SQLException {
        if (p == null) return;
        try (var s =
                c.prepareStatement(
                        "INSERT INTO advisor_tool_credentials (credential_id, owner_user_id,"
                                + " ciphertext, nonce, dek_wrapped, dek_key_id, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, p.id());
            s.setString(2, p.owner());
            s.setString(3, p.ciphertext());
            s.setString(4, p.nonce());
            s.setString(5, p.wrappedDek());
            s.setString(6, p.keyId());
            s.setLong(7, System.currentTimeMillis());
            s.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String owner, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return read(connection, owner, id);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Connection c, String owner, String id) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT ciphertext, nonce, dek_wrapped, dek_key_id FROM"
                                + " advisor_tool_credentials WHERE credential_id=? AND"
                                + " owner_user_id=?")) {
            s.setString(1, id);
            s.setString(2, owner);
            try (var r = s.executeQuery()) {
                if (!r.next())
                    throw new ToolProblem(
                            422, "credential_missing", "The saved credential is unavailable");
                String wrapped = r.getString(3);
                if (wrapped == null || wrapped.isBlank())
                    throw new ToolProblem(
                            422,
                            "credential_unreadable",
                            "This credential predates envelope encryption; enter it again");
                byte[] dataKey = keys.unwrap(r.getString(4), Base64.getDecoder().decode(wrapped));
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        new SecretKeySpec(dataKey, "AES"),
                        new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(r.getString(2))));
                cipher.updateAAD((owner + ":" + id).getBytes(StandardCharsets.UTF_8));
                return json.readValue(
                        cipher.doFinal(Base64.getDecoder().decode(r.getString(1))), Map.class);
            }
        } catch (SQLException | ToolProblem error) {
            throw error;
        } catch (Exception error) {
            throw new ToolProblem(
                    422,
                    "credential_unreadable",
                    "The saved credential cannot be decrypted; enter it again");
        }
    }
}
