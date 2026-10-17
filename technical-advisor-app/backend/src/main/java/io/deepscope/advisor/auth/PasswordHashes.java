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
package io.deepscope.advisor.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordHashes {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashes() {}

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt)
                + ":"
                + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    static boolean matches(String password, String encoded) {
        String[] parts = encoded.split(":", -1);
        if (parts.length != 2) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected, derive(password, salt));
        } catch (IllegalArgumentException invalidHash) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }
}
