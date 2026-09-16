package ru.powerruble.telega;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import ru.powerruble.api.PowerRubleEvents;

public final class TelegramBotService implements Runnable {
    private final TelegaConfig config;
    private final TelegramCommandHandler commandHandler;
    private final HttpClient httpClient;
    private final ExecutorService outboundExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread thread;
    private long offset;
    private AutoCloseable transactionListener;

    public TelegramBotService(TelegaConfig config, MinecraftServer server, Supplier<String> reloadAction) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
            .build();
        this.outboundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread sender = new Thread(runnable, "PowerRubleTelega-Sender");
            sender.setDaemon(true);
            return sender;
        });
        this.commandHandler = new TelegramCommandHandler(config, server, reloadAction, this::sendAsync);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        transactionListener = PowerRubleEvents.registerTransactionListener(commandHandler::handleTransaction);
        thread = new Thread(this, "PowerRubleTelega-Polling");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
        closeTransactionListener();
        outboundExecutor.shutdown();
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                poll();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                PowerRubleTelegaMod.logger().warn("Telegram polling failed", exception);
                sleep(config.reconnectDelayMillis());
            }
        }
    }

    private void poll() throws IOException, InterruptedException {
        JsonObject response = call("getUpdates?timeout=" + config.pollTimeoutSeconds() + "&offset=" + offset);
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
            sleep(config.reconnectDelayMillis());
            return;
        }

        JsonArray updates = response.getAsJsonArray("result");
        for (int index = 0; index < updates.size(); index++) {
            JsonObject update = updates.get(index).getAsJsonObject();
            offset = Math.max(offset, update.get("update_id").getAsLong() + 1L);
            handleUpdate(update);
        }
    }

    private void handleUpdate(JsonObject update) {
        if (!update.has("message")) {
            return;
        }

        JsonObject message = update.getAsJsonObject("message");
        if (!message.has("text") || !message.has("chat")) {
            return;
        }

        JsonObject chat = message.getAsJsonObject("chat");
        if (chat.has("type") && !"private".equals(chat.get("type").getAsString())) {
            return;
        }

        long chatId = chat.get("id").getAsLong();
        String text = message.get("text").getAsString();
        String username = telegramUsername(message);
        CompletableFuture<String> response = commandHandler.handlePrivateMessage(chatId, username, text);
        response.whenCompleteAsync((reply, throwable) -> {
            if (throwable != null) {
                PowerRubleTelegaMod.logger().warn("Telegram command failed", throwable);
                sendPlain(chatId, "Internal server error while handling the command.");
                return;
            }
            if (reply != null && !reply.isBlank()) {
                sendPlain(chatId, reply);
            }
        }, outboundExecutor);
    }

    private JsonObject call(String methodAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl(methodAndQuery)))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject post(String method, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl(method)))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void sendPlain(long chatId, String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("text", text);
            post("sendMessage", body);
        } catch (Exception exception) {
            PowerRubleTelegaMod.logger().warn("Failed to send Telegram message to {}", chatId, exception);
        }
    }

    private void sendAsync(long chatId, String text) {
        if (!running.get()) {
            return;
        }

        outboundExecutor.execute(() -> sendPlain(chatId, text));
    }

    private void closeTransactionListener() {
        if (transactionListener == null) {
            return;
        }

        try {
            transactionListener.close();
        } catch (Exception exception) {
            PowerRubleTelegaMod.logger().warn("Failed to unregister Telegram transaction listener", exception);
        } finally {
            transactionListener = null;
        }
    }

    private String apiUrl(String methodAndQuery) {
        return "https://api.telegram.org/bot" + config.botToken() + "/" + methodAndQuery;
    }

    private static String telegramUsername(JsonObject message) {
        if (!message.has("from")) {
            return "";
        }

        JsonObject from = message.getAsJsonObject("from");
        if (from.has("username")) {
            return from.get("username").getAsString();
        }
        if (from.has("first_name")) {
            return from.get("first_name").getAsString();
        }
        return "";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
