package ru.powerruble.telega;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TelegaConfigTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadCreatesJson5DefaultsWhenNoConfigExists() throws IOException {
        TelegaConfig config = TelegaConfig.load(tempDir);

        Path path = tempDir.resolve("power-ruble-telega.json5");
        assertTrue(Files.exists(path));
        assertFalse(config.enabled());
        assertEquals("", config.botToken());
        assertEquals(8, config.linkCodeLength());
        assertTrue(Files.readString(path).contains("botToken: \"\""));
    }

    @Test
    void loadReadsJson5WithComments() throws IOException {
        Files.writeString(tempDir.resolve("power-ruble-telega.json5"), """
            {
              telegram: {
                enabled: false,
                // local-only secret
                botToken: "12345:token",
                pollTimeoutSeconds: 10,
                requestTimeoutSeconds: 20,
                reconnectDelayMillis: 2500,
              },
              links: {
                codeLength: 10,
                codeTtlSeconds: 120,
              },
              commands: {
                payEnabled: false,
                maxReasonLength: 42,
                notifyOnlinePlayers: false,
              },
              admin: {
                permissionLevel: 3,
              },
            }
            """);

        TelegaConfig config = TelegaConfig.load(tempDir);

        assertFalse(config.enabled());
        assertEquals("12345:token", config.botToken());
        assertEquals(10, config.pollTimeoutSeconds());
        assertEquals(20, config.requestTimeoutSeconds());
        assertEquals(2500L, config.reconnectDelayMillis());
        assertEquals(10, config.linkCodeLength());
        assertEquals(120, config.linkCodeTtlSeconds());
        assertFalse(config.payEnabled());
        assertEquals(42, config.maxReasonLength());
        assertFalse(config.notifyOnlinePlayers());
        assertEquals(3, config.adminPermissionLevel());
    }

    @Test
    void invalidExistingJson5DoesNotGetOverwritten() throws IOException {
        Path path = tempDir.resolve("power-ruble-telega.json5");
        Files.writeString(path, "{ invalid");

        TelegaConfig config = TelegaConfig.load(tempDir);

        assertFalse(config.enabled());
        assertEquals("{ invalid", Files.readString(path));
    }
}
