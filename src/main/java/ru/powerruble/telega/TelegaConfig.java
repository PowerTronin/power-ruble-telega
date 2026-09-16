package ru.powerruble.telega;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class TelegaConfig {
    private static final String CONFIG_FILE_NAME = PowerRubleTelegaMod.MOD_ID + ".json5";

    private final boolean enabled;
    private final String botToken;
    private final int pollTimeoutSeconds;
    private final int requestTimeoutSeconds;
    private final long reconnectDelayMillis;
    private final int linkCodeLength;
    private final int linkCodeTtlSeconds;
    private final boolean payEnabled;
    private final int maxReasonLength;
    private final boolean notifyOnlinePlayers;
    private final int adminPermissionLevel;

    private TelegaConfig(
        boolean enabled,
        String botToken,
        int pollTimeoutSeconds,
        int requestTimeoutSeconds,
        long reconnectDelayMillis,
        int linkCodeLength,
        int linkCodeTtlSeconds,
        boolean payEnabled,
        int maxReasonLength,
        boolean notifyOnlinePlayers,
        int adminPermissionLevel
    ) {
        this.enabled = enabled;
        this.botToken = botToken;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.reconnectDelayMillis = reconnectDelayMillis;
        this.linkCodeLength = linkCodeLength;
        this.linkCodeTtlSeconds = linkCodeTtlSeconds;
        this.payEnabled = payEnabled;
        this.maxReasonLength = maxReasonLength;
        this.notifyOnlinePlayers = notifyOnlinePlayers;
        this.adminPermissionLevel = adminPermissionLevel;
    }

    public static TelegaConfig load() {
        return load(FabricLoader.getInstance().getConfigDir());
    }

    static TelegaConfig load(Path configDir) {
        Path path = configDir.resolve(CONFIG_FILE_NAME);
        if (Files.exists(path)) {
            TelegaConfig config = readJson5(path);
            if (config != null) {
                return config;
            }

            return defaults();
        }

        TelegaConfig defaults = defaults();
        writeJson5(path, defaults);
        return defaults;
    }

    public static TelegaConfig defaults() {
        return new TelegaConfig(
            false,
            "",
            25,
            35,
            5_000L,
            8,
            600,
            true,
            120,
            true,
            2
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public String botToken() {
        return botToken;
    }

    public int pollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public long reconnectDelayMillis() {
        return reconnectDelayMillis;
    }

    public int linkCodeLength() {
        return linkCodeLength;
    }

    public int linkCodeTtlSeconds() {
        return linkCodeTtlSeconds;
    }

    public boolean payEnabled() {
        return payEnabled;
    }

    public int maxReasonLength() {
        return maxReasonLength;
    }

    public boolean notifyOnlinePlayers() {
        return notifyOnlinePlayers;
    }

    public int adminPermissionLevel() {
        return adminPermissionLevel;
    }

    private static TelegaConfig readJson5(Path path) {
        try {
            return fromJson(Jankson.builder().build().load(path.toFile()));
        } catch (IOException | SyntaxError exception) {
            PowerRubleTelegaMod.logger().warn("Could not read {}, using defaults", path, exception);
            return null;
        }
    }

    private static TelegaConfig fromJson(JsonObject root) {
        JsonObject telegram = section(root, "telegram");
        JsonObject links = section(root, "links");
        JsonObject commands = section(root, "commands");
        JsonObject admin = section(root, "admin");

        int pollTimeout = positiveInt(telegram, "pollTimeoutSeconds", 25);
        int requestTimeout = Math.max(pollTimeout + 5, positiveInt(telegram, "requestTimeoutSeconds", 35));

        return new TelegaConfig(
            booleanValue(telegram, "enabled", false),
            stringValue(telegram, "botToken", "").trim(),
            pollTimeout,
            requestTimeout,
            positiveLong(telegram, "reconnectDelayMillis", 5_000L),
            clampInt(positiveInt(links, "codeLength", 8), 4, 32),
            clampInt(positiveInt(links, "codeTtlSeconds", 600), 30, 86_400),
            booleanValue(commands, "payEnabled", true),
            clampInt(nonNegativeInt(commands, "maxReasonLength", 120), 0, 512),
            booleanValue(commands, "notifyOnlinePlayers", true),
            clampInt(nonNegativeInt(admin, "permissionLevel", 2), 0, 4)
        );
    }

    private static void writeJson5(Path path, TelegaConfig config) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJson5(config));
        } catch (IOException exception) {
            PowerRubleTelegaMod.logger().warn("Could not write {}", path, exception);
        }
    }

    private static String toJson5(TelegaConfig config) {
        return """
            {
              telegram: {
                enabled: %s,
                // Keep empty in version control. Put the BotFather token only on the server.
                botToken: "%s",
                pollTimeoutSeconds: %d,
                requestTimeoutSeconds: %d,
                reconnectDelayMillis: %d,
              },
              links: {
                codeLength: %d,
                codeTtlSeconds: %d,
              },
              commands: {
                payEnabled: %s,
                maxReasonLength: %d,
                notifyOnlinePlayers: %s,
              },
              admin: {
                permissionLevel: %d,
              },
            }
            """.formatted(
            Boolean.toString(config.enabled),
            escape(config.botToken),
            config.pollTimeoutSeconds,
            config.requestTimeoutSeconds,
            config.reconnectDelayMillis,
            config.linkCodeLength,
            config.linkCodeTtlSeconds,
            Boolean.toString(config.payEnabled),
            config.maxReasonLength,
            Boolean.toString(config.notifyOnlinePlayers),
            config.adminPermissionLevel
        );
    }

    private static JsonObject section(JsonObject object, String key) {
        JsonObject section = object.getObject(key);
        return section == null ? new JsonObject() : section;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element instanceof JsonPrimitive primitive ? primitive.asString() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        return element instanceof JsonPrimitive primitive ? primitive.asBoolean(fallback) : fallback;
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        long value = positiveLong(object, key, fallback);
        return value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static int nonNegativeInt(JsonObject object, String key, int fallback) {
        long value = nonNegativeLong(object, key, fallback);
        return value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static long positiveLong(JsonObject object, String key, long fallback) {
        long value = longValue(object, key, fallback);
        return value < 1L ? fallback : value;
    }

    private static long nonNegativeLong(JsonObject object, String key, long fallback) {
        long value = longValue(object, key, fallback);
        return value < 0L ? fallback : value;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        return element instanceof JsonPrimitive primitive ? primitive.asLong(fallback) : fallback;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
