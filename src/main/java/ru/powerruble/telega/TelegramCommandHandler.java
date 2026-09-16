package ru.powerruble.telega;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import ru.powerruble.RubleConfig;
import ru.powerruble.RubleState;
import ru.powerruble.RubleTransaction;
import ru.powerruble.api.PowerRuble;
import ru.powerruble.api.PowerRubleApi;

final class TelegramCommandHandler {
    private static final int MAX_LISTED_PLAYERS = 50;

    private final TelegaConfig config;
    private final MinecraftServer server;
    private final Supplier<String> reloadAction;
    private final BiConsumer<Long, String> telegramSender;

    TelegramCommandHandler(TelegaConfig config, MinecraftServer server, Supplier<String> reloadAction, BiConsumer<Long, String> telegramSender) {
        this.config = config;
        this.server = server;
        this.reloadAction = reloadAction;
        this.telegramSender = telegramSender;
    }

    CompletableFuture<String> handlePrivateMessage(long chatId, String telegramUsername, String text) {
        CompletableFuture<String> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(handleOnServerThread(chatId, telegramUsername, text));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private String handleOnServerThread(long chatId, String telegramUsername, String text) {
        ParsedTelegramCommand command = ParsedTelegramCommand.parse(text);
        return switch (command.name()) {
            case "start" -> command.rest().isBlank() ? help() : link(chatId, telegramUsername, command.argument(0));
            case "help" -> help();
            case "link" -> link(chatId, telegramUsername, command.argument(0));
            case "unlink" -> unlink(chatId);
            case "whoami" -> whoami(chatId);
            case "balance" -> balance(chatId);
            case "pay" -> pay(chatId, command);
            case "players" -> players(chatId);
            case "online" -> online(chatId);
            case "admin" -> admin(chatId, command);
            default -> help();
        };
    }

    private String help() {
        return String.join("\n",
            "Power Ruble Telegram commands:",
            "/link <code> - link your Minecraft account",
            "/unlink - remove the link",
            "/whoami - show the linked account",
            "/balance - show your balance",
            "/pay <player> <amount> [comment] - send money",
            "/players - show known players",
            "/online - show online players",
            "/admin help - admin commands for linked operators"
        );
    }

    private String adminHelp() {
        return String.join("\n",
            "Power Ruble Telegram admin commands:",
            "/admin balance <player>",
            "/admin give <player> <amount> [reason]",
            "/admin take <player> <amount> [reason]",
            "/admin set <player> <amount>",
            "/admin bank balance|give|take|set|pay ...",
            "/admin reload"
        );
    }

    private String link(long chatId, String telegramUsername, String code) {
        if (code.isBlank()) {
            return "Usage: /link <code>. Create a code in Minecraft with /telega link.";
        }

        TelegaState.CompleteLinkResult result = TelegaState.get(server).completeLink(code, chatId, telegramUsername, Instant.now());
        if (!result.success()) {
            return "Link code is invalid or expired. Create a new code in Minecraft with /telega link.";
        }

        PowerRuble.api().rememberAccountName(server, result.link().playerId(), result.link().playerName());
        return "Linked to Minecraft account " + result.link().playerName() + ". Use /balance or /pay.";
    }

    private String unlink(long chatId) {
        boolean removed = TelegaState.get(server).unlinkByChat(chatId);
        return removed ? "Telegram link removed." : "This Telegram chat is not linked.";
    }

    private String whoami(long chatId) {
        TelegaState.LinkRecord link = linkedAccount(chatId);
        if (link == null) {
            return notLinked();
        }

        return "Linked Minecraft account: " + currentPlayerName(link) + ".";
    }

    private String balance(long chatId) {
        TelegaState.LinkRecord link = linkedAccount(chatId);
        if (link == null) {
            return notLinked();
        }

        long balance = PowerRuble.api().getBalance(server, link.playerId());
        return currentPlayerName(link) + " balance: " + format(balance) + ".";
    }

    private String pay(long chatId, ParsedTelegramCommand command) {
        if (!config.payEnabled()) {
            return "Telegram payments are disabled by server config.";
        }
        if (command.argumentCount() < 2) {
            return "Usage: /pay <player> <amount> [comment]";
        }

        TelegaState.LinkRecord link = linkedAccount(chatId);
        if (link == null) {
            return notLinked();
        }

        ResolvedAccount target = resolvePlayer(command.argument(0));
        if (target == null) {
            return unknownPlayer(command.argument(0));
        }
        if (link.playerId().equals(target.id())) {
            return "You cannot pay yourself.";
        }

        Long amount = positiveAmount(command.argument(1));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        RubleConfig rubleConfig = api.config();
        if (amount < rubleConfig.minTransferAmount()) {
            return "Minimum transfer amount is " + format(rubleConfig.minTransferAmount()) + ".";
        }
        if (amount > rubleConfig.maxTransferAmount()) {
            return "Maximum transfer amount is " + format(rubleConfig.maxTransferAmount()) + ".";
        }

        String senderName = currentPlayerName(link);
        String reason = normalizeReason(command.remainingFrom(2));
        api.rememberAccountName(server, link.playerId(), senderName);
        api.rememberAccountName(server, target.id(), target.name());

        long fee = calculateTransferFee(amount, rubleConfig);
        FeeRecipient feeRecipient = feeRecipient(fee, rubleConfig);
        if (!feeRecipient.ok()) {
            return feeRecipient.error();
        }

        RubleState.TransferResult transferResult = api.transfer(
            server,
            link.playerId(),
            target.id(),
            feeRecipient.id(),
            amount,
            fee,
            rubleConfig.transferDebtLimit()
        );
        if (transferResult != RubleState.TransferResult.OK) {
            return payError(transferResult, link.playerId(), rubleConfig.transferDebtLimit());
        }

        api.addTransaction(server, RubleTransaction.transfer(
            Instant.now(),
            link.playerId(),
            senderName,
            target.id(),
            target.name(),
            amount,
            fee,
            Optional.ofNullable(feeRecipient.id()),
            feeRecipient.name(),
            reason
        ));

        notifyPlayer(link.playerId(), "Telegram transfer to " + target.name() + ": " + format(amount) + ".");
        notifyPlayer(target.id(), "Telegram transfer from " + senderName + ": " + format(amount) + ".");
        String feeText = fee > 0L ? " Fee: " + format(fee) + "." : "";
        return "Sent " + format(amount) + " to " + target.name() + "." + feeText;
    }

    private String players(long chatId) {
        if (linkedAccount(chatId) == null) {
            return notLinked();
        }

        List<AccountSummary> players = knownPlayers();
        if (players.isEmpty()) {
            return "No known players yet.";
        }

        return formatPlayerList("Known players", players, true);
    }

    private String online(long chatId) {
        if (linkedAccount(chatId) == null) {
            return notLinked();
        }

        List<AccountSummary> players = onlinePlayers();
        if (players.isEmpty()) {
            return "No players are online.";
        }

        return formatPlayerList("Online players", players, false);
    }

    private String admin(long chatId, ParsedTelegramCommand command) {
        TelegaState.LinkRecord link = linkedAccount(chatId);
        if (link == null) {
            return notLinked();
        }
        if (!hasAdminPermission(link)) {
            return "You do not have permission for Telegram admin commands.";
        }

        String subcommand = command.argument(0).toLowerCase(java.util.Locale.ROOT);
        return switch (subcommand) {
            case "", "help" -> adminHelp();
            case "balance" -> adminBalance(command);
            case "give" -> adminGive(link, command);
            case "take" -> adminTake(link, command);
            case "set" -> adminSet(link, command);
            case "bank" -> adminBank(link, command);
            case "reload" -> reloadAction.get();
            default -> adminHelp();
        };
    }

    private String adminBalance(ParsedTelegramCommand command) {
        if (command.argumentCount() < 2) {
            return "Usage: /admin balance <player>";
        }

        ResolvedAccount target = resolvePlayer(command.argument(1));
        if (target == null) {
            return unknownPlayer(command.argument(1));
        }

        PowerRuble.api().rememberAccountName(server, target.id(), target.name());
        return target.name() + " balance: " + format(PowerRuble.api().getBalance(server, target.id())) + ".";
    }

    private String adminGive(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin give <player> <amount> [reason]";
        }

        ResolvedAccount target = resolvePlayer(command.argument(1));
        if (target == null) {
            return unknownPlayer(command.argument(1));
        }
        Long amount = positiveAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        api.rememberAccountName(server, target.id(), target.name());
        if (!api.add(server, target.id(), amount)) {
            return "Could not add money: target balance would overflow.";
        }

        api.addTransaction(server, RubleTransaction.admin(
            RubleTransaction.Type.ADMIN_GIVE,
            Instant.now(),
            target.id(),
            target.name(),
            amount,
            adminReason(admin, command.remainingFrom(3))
        ));
        notifyPlayer(target.id(), "Admin added " + format(amount) + " to your balance via Telegram.");
        return "Added " + format(amount) + " to " + target.name() + ". New balance: " + format(api.getBalance(server, target.id())) + ".";
    }

    private String adminTake(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin take <player> <amount> [reason]";
        }

        ResolvedAccount target = resolvePlayer(command.argument(1));
        if (target == null) {
            return unknownPlayer(command.argument(1));
        }
        Long amount = positiveAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        String bankName = api.config().bankAccountName();
        UUID bankId = api.bankAccountId();
        api.rememberAccountName(server, target.id(), target.name());
        api.rememberAccountName(server, bankId, bankName);

        RubleState.TransferResult transferResult = api.transfer(server, target.id(), bankId, null, amount, 0L, Long.MIN_VALUE);
        if (transferResult == RubleState.TransferResult.OVERFLOW) {
            return "Could not take money: bank balance would overflow.";
        }
        if (transferResult != RubleState.TransferResult.OK) {
            return "Could not take money: target balance would underflow.";
        }

        api.addTransaction(server, RubleTransaction.adminTakeToBank(
            Instant.now(),
            target.id(),
            target.name(),
            bankId,
            bankName,
            amount,
            adminReason(admin, command.remainingFrom(3))
        ));
        notifyPlayer(target.id(), "Admin took " + format(amount) + " from your balance via Telegram.");
        return "Took " + format(amount) + " from " + target.name() + " to bank. New balance: " + format(api.getBalance(server, target.id())) + ".";
    }

    private String adminSet(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin set <player> <amount>";
        }

        ResolvedAccount target = resolvePlayer(command.argument(1));
        if (target == null) {
            return unknownPlayer(command.argument(1));
        }
        Long amount = nonNegativeAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be zero or a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        api.rememberAccountName(server, target.id(), target.name());
        api.setBalance(server, target.id(), amount);
        api.addTransaction(server, RubleTransaction.admin(
            RubleTransaction.Type.ADMIN_SET,
            Instant.now(),
            target.id(),
            target.name(),
            amount,
            adminReason(admin, "")
        ));
        notifyPlayer(target.id(), "Admin set your balance to " + format(amount) + " via Telegram.");
        return "Set " + target.name() + " balance to " + format(amount) + ".";
    }

    private String adminBank(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        String action = command.argument(1).toLowerCase(java.util.Locale.ROOT);
        return switch (action) {
            case "", "help" -> String.join("\n",
                "Bank admin commands:",
                "/admin bank balance",
                "/admin bank give <amount>",
                "/admin bank take <amount>",
                "/admin bank set <amount>",
                "/admin bank pay <player> <amount> [comment]"
            );
            case "balance" -> adminBankBalance();
            case "give" -> adminBankGive(admin, command);
            case "take" -> adminBankTake(admin, command);
            case "set" -> adminBankSet(admin, command);
            case "pay" -> adminBankPay(admin, command);
            default -> "Unknown bank command. Use /admin bank help.";
        };
    }

    private String adminBankBalance() {
        PowerRubleApi api = PowerRuble.api();
        rememberBank(api);
        return "Bank balance: " + format(api.getBalance(server, api.bankAccountId())) + ".";
    }

    private String adminBankGive(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin bank give <amount>";
        }
        Long amount = positiveAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        rememberBank(api);
        UUID bankId = api.bankAccountId();
        if (!api.add(server, bankId, amount)) {
            return "Could not add money: bank balance would overflow.";
        }

        api.addTransaction(server, RubleTransaction.bank(
            RubleTransaction.Type.BANK_GIVE,
            Instant.now(),
            bankId,
            api.config().bankAccountName(),
            amount,
            adminReason(admin, "bank give")
        ));
        return "Added " + format(amount) + " to bank. Bank balance: " + format(api.getBalance(server, bankId)) + ".";
    }

    private String adminBankTake(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin bank take <amount>";
        }
        Long amount = positiveAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        rememberBank(api);
        UUID bankId = api.bankAccountId();
        if (!api.subtract(server, bankId, amount)) {
            return "Bank does not have enough money.";
        }

        api.addTransaction(server, RubleTransaction.bank(
            RubleTransaction.Type.BANK_TAKE,
            Instant.now(),
            bankId,
            api.config().bankAccountName(),
            amount,
            adminReason(admin, "bank take")
        ));
        return "Took " + format(amount) + " from bank. Bank balance: " + format(api.getBalance(server, bankId)) + ".";
    }

    private String adminBankSet(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 3) {
            return "Usage: /admin bank set <amount>";
        }
        Long amount = nonNegativeAmount(command.argument(2));
        if (amount == null) {
            return "Amount must be zero or a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        rememberBank(api);
        UUID bankId = api.bankAccountId();
        api.setBalance(server, bankId, amount);
        api.addTransaction(server, RubleTransaction.bank(
            RubleTransaction.Type.BANK_SET,
            Instant.now(),
            bankId,
            api.config().bankAccountName(),
            amount,
            adminReason(admin, "bank set")
        ));
        return "Set bank balance to " + format(amount) + ".";
    }

    private String adminBankPay(TelegaState.LinkRecord admin, ParsedTelegramCommand command) {
        if (command.argumentCount() < 4) {
            return "Usage: /admin bank pay <player> <amount> [comment]";
        }

        ResolvedAccount target = resolvePlayer(command.argument(2));
        if (target == null) {
            return unknownPlayer(command.argument(2));
        }
        Long amount = positiveAmount(command.argument(3));
        if (amount == null) {
            return "Amount must be a positive whole number.";
        }

        PowerRubleApi api = PowerRuble.api();
        rememberBank(api);
        api.rememberAccountName(server, target.id(), target.name());
        UUID bankId = api.bankAccountId();
        String bankName = api.config().bankAccountName();
        String reason = normalizeReason(command.remainingFrom(4));

        RubleState.TransferResult transferResult = api.transfer(server, bankId, target.id(), null, amount, 0L, 0L);
        if (transferResult == RubleState.TransferResult.NOT_ENOUGH_MONEY) {
            return "Bank does not have enough money.";
        }
        if (transferResult != RubleState.TransferResult.OK) {
            return "Could not pay from bank: target balance would overflow.";
        }

        api.addTransaction(server, RubleTransaction.transfer(
            Instant.now(),
            bankId,
            bankName,
            target.id(),
            target.name(),
            amount,
            0L,
            Optional.empty(),
            "",
            reason.isBlank() ? adminReason(admin, "bank pay") : adminReason(admin, reason)
        ));
        notifyPlayer(target.id(), "Bank sent you " + format(amount) + " via Telegram admin command.");
        return "Sent " + format(amount) + " from bank to " + target.name() + ". Bank balance: " + format(api.getBalance(server, bankId)) + ".";
    }

    void handleTransaction(MinecraftServer eventServer, RubleTransaction transaction) {
        if (eventServer != server) {
            return;
        }

        server.execute(() -> notifyTelegramTransaction(transaction));
    }

    private void notifyTelegramTransaction(RubleTransaction transaction) {
        switch (transaction.type()) {
            case TRANSFER -> notifyTransferTransaction(transaction);
            case ADMIN_GIVE -> transaction.toId().ifPresent(targetId -> notifyTelegram(
                targetId,
                "Admin added " + format(transaction.amount()) + " to your balance."
            ));
            case ADMIN_TAKE -> transaction.fromId().ifPresent(targetId -> notifyTelegram(
                targetId,
                "Admin took " + format(transaction.amount()) + " from your balance."
            ));
            case ADMIN_SET -> transaction.toId().ifPresent(targetId -> notifyTelegram(
                targetId,
                "Admin set your balance to " + format(transaction.amount()) + "."
            ));
            case BANK_GIVE, BANK_TAKE, BANK_SET, TAX, LEGACY -> {
            }
        }
    }

    private void notifyTransferTransaction(RubleTransaction transaction) {
        transaction.toId().ifPresent(targetId -> {
            if (PowerRuble.api().bankAccountId().equals(targetId)) {
                return;
            }

            String comment = transaction.reason().isBlank() ? "" : " Comment: " + transaction.reason() + ".";
            notifyTelegram(targetId, "Received " + format(transaction.amount()) + " from " + transaction.fromName() + "." + comment);
        });
    }

    private List<AccountSummary> knownPlayers() {
        Map<UUID, AccountSummary> accounts = new HashMap<>();
        for (AccountSummary onlinePlayer : onlinePlayers()) {
            accounts.put(onlinePlayer.id(), onlinePlayer);
        }

        PowerRubleApi api = PowerRuble.api();
        for (RubleState.BalanceEntry entry : api.state(server).knownAccounts()) {
            if (api.bankAccountId().equals(entry.playerId())) {
                continue;
            }
            accounts.putIfAbsent(entry.playerId(), new AccountSummary(entry.playerId(), entry.name(), isOnline(entry.playerId())));
        }

        return accounts.values().stream()
            .sorted(Comparator.comparing(AccountSummary::online).reversed().thenComparing(AccountSummary::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private List<AccountSummary> onlinePlayers() {
        return server.getPlayerManager().getPlayerList().stream()
            .map(player -> new AccountSummary(player.getUuid(), player.getName().getString(), true))
            .sorted(Comparator.comparing(AccountSummary::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private String formatPlayerList(String header, List<AccountSummary> players, boolean includeStatus) {
        StringBuilder builder = new StringBuilder(header).append(" (").append(players.size()).append("):");
        int listed = Math.min(players.size(), MAX_LISTED_PLAYERS);
        for (int index = 0; index < listed; index++) {
            AccountSummary player = players.get(index);
            builder.append("\n- ").append(player.name());
            if (includeStatus) {
                builder.append(player.online() ? " (online)" : " (offline)");
            }
        }
        if (players.size() > listed) {
            builder.append("\n... and ").append(players.size() - listed).append(" more");
        }
        return builder.toString();
    }

    private TelegaState.LinkRecord linkedAccount(long chatId) {
        return TelegaState.get(server).linkByChat(chatId);
    }

    private boolean hasAdminPermission(TelegaState.LinkRecord link) {
        int permissionLevel = config.adminPermissionLevel();
        if (permissionLevel <= 0) {
            return true;
        }

        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(link.playerId());
        if (onlinePlayer != null && onlinePlayer.hasPermissionLevel(permissionLevel)) {
            return true;
        }

        return server.getPlayerManager().isOperator(new GameProfile(link.playerId(), link.playerName()));
    }

    private ResolvedAccount resolvePlayer(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank()) {
            return null;
        }

        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(name);
        if (onlinePlayer != null) {
            return new ResolvedAccount(onlinePlayer.getUuid(), onlinePlayer.getName().getString());
        }

        Optional<RubleState.BalanceEntry> knownAccount = PowerRuble.api().state(server).findKnownAccountByName(name);
        if (knownAccount.isPresent()) {
            RubleState.BalanceEntry entry = knownAccount.get();
            return new ResolvedAccount(entry.playerId(), entry.name());
        }

        Optional<GameProfile> cachedProfile = cachedOfflineProfile(name);
        if (cachedProfile.isPresent() && cachedProfile.get().getId() != null) {
            return new ResolvedAccount(cachedProfile.get().getId(), cleanName(cachedProfile.get().getName(), name));
        }

        return null;
    }

    private Optional<GameProfile> cachedOfflineProfile(String name) {
        if (server.isOnlineMode()) {
            return Optional.empty();
        }

        return server.getUserCache().getByUuid(offlineUuid(name));
    }

    private static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private FeeRecipient feeRecipient(long feeAmount, RubleConfig rubleConfig) {
        if (feeAmount <= 0L || rubleConfig.transferFeeGoesToExchange()) {
            return FeeRecipient.none();
        }

        if ("bank".equalsIgnoreCase(rubleConfig.transferFeeRecipient())) {
            if (!rubleConfig.bankEnabled()) {
                return FeeRecipient.none();
            }
            PowerRubleApi api = PowerRuble.api();
            rememberBank(api);
            return new FeeRecipient(api.bankAccountId(), rubleConfig.bankAccountName(), "");
        }

        ResolvedAccount recipient = resolvePlayer(rubleConfig.transferFeeRecipient());
        if (recipient == null) {
            return new FeeRecipient(null, "", "Configured fee recipient is unknown: " + rubleConfig.transferFeeRecipient() + ".");
        }

        PowerRuble.api().rememberAccountName(server, recipient.id(), recipient.name());
        return new FeeRecipient(recipient.id(), recipient.name(), "");
    }

    private String currentPlayerName(TelegaState.LinkRecord link) {
        ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(link.playerId());
        return onlinePlayer == null ? link.playerName() : onlinePlayer.getName().getString();
    }

    private void notifyPlayer(UUID playerId, String message) {
        if (!config.notifyOnlinePlayers()) {
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Text.literal(message), false);
        }
    }

    private void notifyTelegram(UUID playerId, String message) {
        TelegaState.LinkRecord link = TelegaState.get(server).linkByPlayer(playerId);
        if (link != null) {
            telegramSender.accept(link.chatId(), message);
        }
    }

    private boolean isOnline(UUID playerId) {
        return server.getPlayerManager().getPlayer(playerId) != null;
    }

    private void rememberBank(PowerRubleApi api) {
        api.rememberAccountName(server, api.bankAccountId(), api.config().bankAccountName());
    }

    private String adminReason(TelegaState.LinkRecord admin, String rawReason) {
        String reason = normalizeReason(rawReason);
        String actor = "telegram admin by " + currentPlayerName(admin);
        return reason.isBlank() ? actor : actor + ": " + reason;
    }

    private String normalizeReason(String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim().replaceAll("\\s+", " ");
        if (reason.length() <= config.maxReasonLength()) {
            return reason;
        }
        return reason.substring(0, config.maxReasonLength());
    }

    private String payError(RubleState.TransferResult result, UUID senderId, long debtLimit) {
        return switch (result) {
            case NOT_ENOUGH_MONEY -> "Not enough money. Balance: " + format(PowerRuble.api().getBalance(server, senderId))
                + ", debt limit: " + format(debtLimit) + ".";
            case OVERFLOW -> "Could not transfer money: target balance would overflow.";
            case FEE_OVERFLOW -> "Could not transfer money: fee recipient balance would overflow.";
            case OK -> "Transfer completed.";
        };
    }

    private String format(long amount) {
        return amount + " " + PowerRuble.api().config().currencyName();
    }

    private static Long positiveAmount(String rawAmount) {
        try {
            long amount = Long.parseLong(rawAmount);
            return amount > 0L ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long nonNegativeAmount(String rawAmount) {
        try {
            long amount = Long.parseLong(rawAmount);
            return amount >= 0L ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long calculateTransferFee(long amount, RubleConfig config) {
        long fixedFee = config.transferFeeAmount();
        long percentFee = calculatePercentFee(amount, config.transferFeePercent());
        if (Long.MAX_VALUE - fixedFee < percentFee) {
            return Long.MAX_VALUE;
        }

        long fee = fixedFee + percentFee;
        if (fee > 0L && config.transferFeeMin() > fee) {
            fee = config.transferFeeMin();
        }
        if (config.transferFeeMax() > 0L && fee > config.transferFeeMax()) {
            fee = config.transferFeeMax();
        }
        return fee;
    }

    private static long calculatePercentFee(long amount, double percent) {
        if (amount <= 0L || percent <= 0.0D) {
            return 0L;
        }

        double fee = Math.floor(amount * percent / 100.0D);
        if (fee >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) fee;
    }

    private static String unknownPlayer(String name) {
        return "Unknown player: " + name + ". The player must join the server at least once.";
    }

    private static String notLinked() {
        return "This Telegram chat is not linked. Use /telega link in Minecraft, then /link <code> here.";
    }

    private static String cleanName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private record ResolvedAccount(UUID id, String name) {
    }

    private record AccountSummary(UUID id, String name, boolean online) {
    }

    private record FeeRecipient(UUID id, String name, String error) {
        static FeeRecipient none() {
            return new FeeRecipient(null, "", "");
        }

        boolean ok() {
            return error.isBlank();
        }
    }
}
