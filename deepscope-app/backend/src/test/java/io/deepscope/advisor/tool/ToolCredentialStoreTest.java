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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** A stored secret comes back only to the owner who saved it, and only with a usable key version. */
class ToolCredentialStoreTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_KEY = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=";
    private static final Map<String, Object> SECRET = Map.of("type", "bearer", "value", "s3cret");

    private final ObjectMapper json = new ObjectMapper();
    private DataSource source;
    private ToolCredentialStore store;

    @BeforeEach
    void setUp() throws Exception {
        source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:credential-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        store = new ToolCredentialStore(json, KEY, source);
    }

    @Test
    void aStoredSecretComesBackUnchangedAndIsNotReadableOnDisk() throws Exception {
        var pending = store.prepare("owner", SECRET);
        store.insert(pending);

        assertEquals(SECRET, store.read("owner", pending.id()));
        assertFalse(storedCiphertext(pending.id()).contains("s3cret"));
        assertEquals(LocalKeyringProvider.LEGACY_KEY_ID, storedKeyId(pending.id()));
    }

    @Test
    void savingWithoutADeploymentKeyIsRefusedRatherThanStoredInTheClear() {
        var keyless = new ToolCredentialStore(json, "", source);

        assertEquals(
                "credential_key_required",
                assertThrows(ToolProblem.class, () -> keyless.prepare("owner", SECRET)).code());
    }

    @Test
    void noCredentialsMeansNothingIsWritten() throws Exception {
        assertEquals(null, store.prepare("owner", null));
        store.insert((ToolCredentialStore.Pending) null);
        assertEquals("{}", ToolAuth.reference(null, null));
    }

    @Test
    void aDifferentKeyCannotReadTheSecret() throws Exception {
        var pending = store.prepare("owner", SECRET);
        store.insert(pending);
        var other = new ToolCredentialStore(json, OTHER_KEY, source);

        assertEquals(
                "credential_unreadable",
                assertThrows(ToolProblem.class, () -> other.read("owner", pending.id())).code());
    }

    @Test
    void anotherOwnerCannotReadTheSecret() throws Exception {
        var pending = store.prepare("owner", SECRET);
        store.insert(pending);

        assertEquals(
                "credential_missing",
                assertThrows(ToolProblem.class, () -> store.read("intruder", pending.id())).code());
    }

    @Test
    void aSecretSurvivesARotationBecauseItsDataKeyCarriesTheKeyVersion() throws Exception {
        var v1 = new LocalKeyringProvider(Map.of("v1", KEY), "v1");
        var before = new ToolCredentialStore(json, v1, source);
        var pending = before.prepare("owner", SECRET);
        before.insert(pending);

        var rotated = new LocalKeyringProvider(Map.of("v1", KEY, "v2", OTHER_KEY), "v2");
        var after = new ToolCredentialStore(json, rotated, source);

        assertEquals(SECRET, after.read("owner", pending.id()));
    }

    @Test
    void aCredentialFromBeforeEnvelopeEncryptionIsReportedAsUnreadable() throws Exception {
        String id = UUID.randomUUID().toString();
        try (var connection = source.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO advisor_tool_credentials (credential_id,"
                                        + " owner_user_id, ciphertext, nonce, created_at)"
                                        + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, "owner");
            insert.setString(3, "AAAA");
            insert.setString(4, "AAAA");
            insert.setLong(5, 1L);
            insert.executeUpdate();
        }

        assertEquals(
                "credential_unreadable",
                assertThrows(ToolProblem.class, () -> store.read("owner", id)).code());
    }

    @Test
    void aKeyringThatNamesAMissingActiveVersionIsRejectedAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalKeyringProvider(Map.of("v1", KEY), "v2"));
    }

    private String storedCiphertext(String id) throws Exception {
        return storedColumn(id, "ciphertext");
    }

    private String storedKeyId(String id) throws Exception {
        return storedColumn(id, "dek_key_id");
    }

    private String storedColumn(String id, String column) throws Exception {
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + column
                                        + " FROM advisor_tool_credentials WHERE"
                                        + " credential_id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
