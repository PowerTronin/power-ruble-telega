package ru.powerruble.telega;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.security.SecureRandom;
import java.time.Instant;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.powerruble.api.PowerRuble;

public final class PowerRubleTelegaMod implements ModInitializer {
    public static final String MOD_ID = "power-ruble-telega";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SimpleCommandExceptionType PLAYER_ONLY =
        new SimpleCommandExceptionType(Text.literal("This command can only be used by a player."));

    private static TelegaConfig config;
    private static TelegramBotService telegramBotService;

    @Override
    public void onInitialize() {
        config = TelegaConfig.load();
        registerCommands();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            startTelegramBot(server);
            LOGGER.info("Power Ruble Telega loaded. Telegram bot is {}.", botStateText());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopTelegramBot());
    }

    public static TelegaConfig config() {
        return config == null ? TelegaConfig.defaults() : config;
    }

    static synchronized String reload(MinecraftServer server) {
        config = TelegaConfig.load();
        restartTelegramBot(server);
        return "Power Ruble Telega config reloaded. Telegram bot is " + botStateText() + ".";
    }

    static synchronized String reloadFromTelegram(MinecraftServer server) {
        config = TelegaConfig.load();
        Thread restartThread = new Thread(() -> {
            sleepBeforeRestart();
            restartTelegramBot(server);
        }, "PowerRubleTelega-Restart");
        restartThread.setDaemon(true);
        restartThread.start();
        return "Power Ruble Telega config reloaded. Telegram bot is restarting.";
    }

    static Logger logger() {
        return LOGGER;
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            CommandManager.literal("telega")
                .executes(context -> showHelp(context.getSource()))
                .then(CommandManager.literal("help")
                    .executes(context -> showHelp(context.getSource()))
                )
                .then(CommandManager.literal("link")
                    .executes(context -> createLink(context.getSource()))
                )
                .then(CommandManager.literal("unlink")
                    .executes(context -> unlink(context.getSource()))
                )
                .then(CommandManager.literal("status")
                    .executes(context -> status(context.getSource()))
                )
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(config().adminPermissionLevel()))
                    .executes(context -> reloadConfig(context.getSource()))
                )
        ));
    }

    private static int showHelp(ServerCommandSource source) {
        send(source, "Power Ruble Telega commands:");
        send(source, "/telega link - create a Telegram link code");
        send(source, "/telega unlink - remove your Telegram link");
        send(source, "/telega status - show Telegram link status");
        if (source.hasPermissionLevel(config().adminPermissionLevel())) {
            send(source, "/telega reload - reload Telegram addon config");
        }
        return 1;
    }

    private static int createLink(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw PLAYER_ONLY.create();
        }

        TelegaState state = TelegaState.get(source.getServer());
        TelegaState.LinkRecord existingLink = state.linkByPlayer(player.getUuid());
        if (existingLink != null) {
            send(source, "Telegram is already linked to this account. Use /telega unlink first to relink.");
            return 1;
        }

        String code = nextUniqueCode(state, config().linkCodeLength());
        Instant expiresAt = Instant.now().plusSeconds(config().linkCodeTtlSeconds());
        String playerName = player.getName().getString();
        state.createPendingLink(player.getUuid(), playerName, code, expiresAt);
        PowerRuble.api().rememberAccountName(source.getServer(), player.getUuid(), playerName);

        send(source, "Telegram link code: " + code);
        send(source, "Send /link " + code + " to the bot in a private chat within " + config().linkCodeTtlSeconds() + " seconds.");
        return 1;
    }

    private static int unlink(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw PLAYER_ONLY.create();
        }

        boolean removed = TelegaState.get(source.getServer()).unlinkByPlayer(player.getUuid());
        send(source, removed ? "Telegram link removed." : "Telegram is not linked to this account.");
        return 1;
    }

    private static int status(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw PLAYER_ONLY.create();
        }

        TelegaState.LinkRecord link = TelegaState.get(source.getServer()).linkByPlayer(player.getUuid());
        if (link == null) {
            send(source, "Telegram is not linked. Use /telega link to create a code.");
            return 1;
        }

        String username = link.telegramUsername().isBlank() ? "unknown Telegram user" : "@" + link.telegramUsername();
        send(source, "Telegram is linked to " + username + ".");
        return 1;
    }

    private static int reloadConfig(ServerCommandSource source) {
        send(source, reload(source.getServer()));
        return 1;
    }

    private static synchronized void restartTelegramBot(MinecraftServer server) {
        stopTelegramBot();
        startTelegramBot(server);
    }

    private static synchronized void startTelegramBot(MinecraftServer server) {
        TelegaConfig activeConfig = config();
        if (!activeConfig.enabled()) {
            return;
        }
        if (activeConfig.botToken().isBlank()) {
            LOGGER.warn("Telegram bot is enabled, but botToken is empty in config/{}.json5", MOD_ID);
            return;
        }

        telegramBotService = new TelegramBotService(activeConfig, server, () -> reloadFromTelegram(server));
        telegramBotService.start();
    }

    private static void sleepBeforeRestart() {
        try {
            Thread.sleep(1_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void stopTelegramBot() {
        if (telegramBotService != null) {
            telegramBotService.stop();
            telegramBotService = null;
        }
    }

    private static synchronized String botStateText() {
        if (!config().enabled()) {
            return "disabled";
        }
        if (config().botToken().isBlank()) {
            return "not started: bot token is empty";
        }
        return telegramBotService == null ? "stopped" : "running";
    }

    private static String nextUniqueCode(TelegaState state, int length) {
        for (int attempt = 0; attempt < 32; attempt++) {
            String code = randomCode(length);
            if (!state.hasPendingCode(code)) {
                return code;
            }
        }

        return randomCode(length + 4);
    }

    private static String randomCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static void send(ServerCommandSource source, String message) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            player.sendMessage(Text.literal(message), false);
            return;
        }

        source.sendFeedback(() -> Text.literal(message), false);
    }
}
