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
package io.deepscope.advisor.account;

import io.deepscope.advisor.auth.AccountStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, account-owned avatar files; the client's filename is never used. */
public final class AvatarStore {
    private static final Logger log = LoggerFactory.getLogger(AvatarStore.class);
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SIDE = 8192;

    public record Avatar(byte[] bytes, String contentType) {}

    private final Path root;
    private final AccountStore accounts;

    public AvatarStore(Path dataDir, AccountStore accounts) {
        this.root = dataDir.toAbsolutePath().normalize();
        this.accounts = accounts;
    }

    public synchronized AccountStore.Profile replace(
            String userId, String contentType, InputStream input) throws IOException, SQLException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        String extension = validate(bytes, contentType);
        var previous = accounts.profile(userId).orElseThrow();
        Path directory = ownerDirectory(userId);
        requireNoSymlinks(directory);
        Files.createDirectories(directory);
        Path target = directory.resolve(UUID.randomUUID() + "." + extension);
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            accounts.setAvatar(userId, root.relativize(target).toString());
        } catch (SQLException | RuntimeException error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
        cleanup(userId, previous.avatarPath());
        return accounts.profile(userId).orElseThrow();
    }

    public synchronized Optional<Avatar> read(String userId) throws SQLException, IOException {
        var profile = accounts.profile(userId).orElseThrow();
        Optional<Path> file = managedFile(userId, profile.avatarPath());
        if (file.isEmpty() || !Files.isRegularFile(file.get(), LinkOption.NOFOLLOW_LINKS))
            return Optional.empty();
        byte[] bytes;
        try (var input = Files.newInputStream(file.get())) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        String name = file.get().getFileName().toString();
        String contentType =
                name.endsWith(".jpg")
                        ? "image/jpeg"
                        : name.endsWith(".png") ? "image/png" : "image/webp";
        try {
            validate(bytes, contentType);
            return Optional.of(new Avatar(bytes, contentType));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public synchronized void delete(String userId) throws SQLException {
        var previous = accounts.profile(userId).orElseThrow();
        accounts.setAvatar(userId, "");
        cleanup(userId, previous.avatarPath());
    }

    private void cleanup(String userId, String stored) {
        var file = managedFile(userId, stored);
        if (file.isEmpty()) return;
        try {
            Files.deleteIfExists(file.get());
        } catch (IOException error) {
            // The saved profile remains usable; report a cleanup failure without exposing paths.
            log.warn("Could not remove a retired avatar for account {}", userId);
        }
    }

    private Path ownerDirectory(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]+")) throw invalid();
        return root.resolve("avatars").resolve(userId);
    }

    private Optional<Path> managedFile(String userId, String stored) {
        if (stored == null || stored.isBlank()) return Optional.empty();
        try {
            Path relative = Path.of(stored);
            Path path = root.resolve(relative).normalize();
            if (relative.isAbsolute()
                    || !ownerDirectory(userId).equals(path.getParent())
                    || !path.getFileName().toString().matches("[0-9a-f-]{36}\\.(png|jpg|webp)"))
                return Optional.empty();
            requireNoSymlinks(path);
            return Optional.of(path);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void requireNoSymlinks(Path path) {
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) throw invalid();
        for (Path segment : root.relativize(path)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw invalid();
        }
    }

    private static String validate(byte[] bytes, String type) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES || type == null) throw invalid();
        String extension =
                switch (type.toLowerCase(Locale.ROOT)) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "image/webp" -> "webp";
                    default -> throw invalid();
                };
        if (extension.equals("webp")) {
            WebpDimensions.validate(bytes, MAX_SIDE);
            return extension;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input);
                String actual = reader.getFormatName();
                if (!(extension.equals("png")
                        ? actual.equalsIgnoreCase("png")
                        : actual.equalsIgnoreCase("jpeg"))) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE)
                    throw invalid();
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, (Math.max(width, height) + 1023) / 1024);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                if (reader.read(0, parameters) == null) throw invalid();
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw invalid();
        }
        return extension;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Use PNG, JPEG or WebP up to 10 MiB and 8192 pixels per side");
    }
}
