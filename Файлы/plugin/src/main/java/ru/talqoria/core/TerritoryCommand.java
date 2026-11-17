package ru.talqoria.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Command handler for test towns and chunk claims. No async Bukkit access. */
final class TerritoryCommand {
    private record Invite(UUID townId, UUID actorId, long expiresAt) { }
    private final TalqoriaCorePlugin plugin;
    private final TerritoryStore db;
    private final Map<UUID, Invite> invites = new HashMap<>();

    TerritoryCommand(TalqoriaCorePlugin plugin, TerritoryStore db) { this.plugin = plugin; this.db = db; }
    void say(Player p, String message) { p.sendMessage("§2[TALQORIA] §7" + message); }
    private void outcome(Player p, TerritoryStore.Result result) {
        String text = switch (result) {
            case OK -> "Готово. Изменение сохранено.";
            case INVALID_NAME -> "Название: 3–24 символа, буквы/цифры/пробелы/дефис/_.";
            case NAME_TAKEN -> "Такое поселение уже существует.";
            case ALREADY_IN_TOWN -> "Ты уже состоишь в поселении.";
            case NOT_IN_TOWN -> "Сначала создай или вступи в поселение: /tq town create <название>.";
            case NOT_OWNER -> "Недостаточно прав в поселении.";
            case ALREADY_CLAIMED -> "Чанк уже занят.";
            case CLAIM_LIMIT -> "Достигнут лимит чанков поселения.";
            case NOT_CLAIMED -> "Этот чанк не запривачен.";
            case FOREIGN_CLAIM -> "Этот чанк принадлежит другому поселению.";
            case MEMBER_LIMIT -> "Достигнут лимит участников или гостей.";
            case ALREADY_MEMBER -> "Игрок уже состоит в поселении, либо у него есть другое.";
            case NOT_MEMBER -> "Игрок не является участником поселения.";
            case ALREADY_OFFICER -> "Игрок уже назначен офицером.";
            case NOT_OFFICER -> "Игрок не является офицером.";
            case OWNER_CANNOT_LEAVE -> "Основатель должен распустить поселение командой /tq town disband confirm.";
            case ALREADY_GUEST -> "Игрок уже находится в списке доверенных.";
            case NOT_GUEST -> "Не найден в списке доверенных игроков.";
            case SELF -> "Нельзя выполнить действие по отношению к себе.";
            case ALREADY_CONTRIBUTED -> "Влияние за эту награду уже начислено.";
            case XP_LIMIT -> "Превышен допустимый лимит влияния.";
        };
        say(p, text);
    }
    private void changed(Player p, TerritoryStore.Result result) {
        if (result == TerritoryStore.Result.OK && !plugin.persistTerritories()) {
            say(p, "ОШИБКА СОХРАНЕНИЯ. Действия заблокированы, срочно сообщи администратору.");
            return;
        }
        outcome(p, result);
    }
    private TerritoryStore.ChunkKey locationChunk(Player p) {
        Chunk c = p.getLocation().getChunk();
        return new TerritoryStore.ChunkKey(c.getWorld().getUID(), c.getX(), c.getZ());
    }
    void execute(Player p, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "claim" -> {
                List<String> worlds = plugin.getConfig().getStringList("claims.allowed-worlds");
                if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) {
                    say(p, "В этом мире приваты отключены."); return;
                }
                Location spawn = plugin.readSpawn();
                int radius = Math.max(0, plugin.getConfig().getInt("claims.spawn-protection-radius-blocks", 128));
                Chunk c = p.getLocation().getChunk();
                if (radius > 0 && spawn != null && spawn.getWorld().equals(p.getWorld()) &&
                    Math.abs((c.getX() << 4) + 8.0 - spawn.getX()) < radius + 8 &&
                    Math.abs((c.getZ() << 4) + 8.0 - spawn.getZ()) < radius + 8) {
                    say(p, "Нельзя приватить территорию возле спавна."); return;
                }
                changed(p, db.claim(p.getUniqueId(), locationChunk(p)));
            }
            case "unclaim" -> changed(p, db.unclaim(p.getUniqueId(), locationChunk(p)));
            case "claims" -> {
                TerritoryStore.Town town = db.townFor(p.getUniqueId());
                if (town == null) { outcome(p, TerritoryStore.Result.NOT_IN_TOWN); return; }
                say(p, "Поселение «" + town.name() + "» · участков: " + db.claimCount(town) + ".");
                for (var chunk : db.chunksFor(town)) say(p, "Мир " + chunk.world() + ", чанк " + chunk.x() + ", " + chunk.z());
            }
            case "trust" -> {
                if (args.length != 2) { say(p, "Использование: /tq trust <ник_онлайн>"); return; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { say(p, "Игрок должен быть онлайн."); return; }
                changed(p, db.trust(p.getUniqueId(), target.getUniqueId(), target.getName()));
            }
            case "untrust" -> {
                if (args.length != 2) { say(p, "Использование: /tq untrust <ник>"); return; }
                changed(p, db.untrust(p.getUniqueId(), args[1]));
            }
            case "town" -> town(p, args);
            default -> say(p, "Неизвестная команда территорий.");
        }
    }
    private void town(Player p, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        UUID playerId = p.getUniqueId();
        TerritoryStore.Town town = db.townFor(playerId);
        switch (sub) {
            case "help" -> {
                say(p, "Поселения: /tq town create, invite, accept, info, leave, kick, promote, demote, disband confirm.");
                say(p, "Участки: /tq claim, unclaim, claims, trust <ник>, untrust <ник>.");
            }
            case "create" -> {
                if (args.length < 3) { say(p, "Использование: /tq town create <название>"); return; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                changed(p, db.create(playerId, name));
            }
            case "info" -> {
                if (town == null) { outcome(p, TerritoryStore.Result.NOT_IN_TOWN); return; }
                say(p, "Поселение «" + town.name() + "» · участники: " + town.members().size()
                    + " · гости: " + town.guests().size() + " · участки: " + db.claimCount(town)
                    + " · уровень: " + town.level() + " · влияние: " + town.influence()
                    + " · лимит приватов: " + db.claimLimit(town)
                    + " · основатель: " + town.owner() + " · твоя роль: " + town.role(playerId)
                    + " · офицеры: " + town.officers().size());
            }
            case "invite" -> {
                if (args.length != 3) { say(p, "Использование: /tq town invite <ник_онлайн>"); return; }
                if (town == null || !db.mayInvite(playerId)) { outcome(p, town == null ? TerritoryStore.Result.NOT_IN_TOWN : TerritoryStore.Result.NOT_OWNER); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null || target.equals(p)) { say(p, "Нужен другой игрок онлайн."); return; }
                if (db.townFor(target.getUniqueId()) != null) { say(p, "Игрок уже состоит в поселении."); return; }
                invites.put(target.getUniqueId(), new Invite(town.id(), playerId, System.currentTimeMillis() + 300_000L));
                say(p, "Приглашение отправлено на 5 минут: " + target.getName());
                say(target, "Приглашение в «" + town.name() + "» от " + p.getName() + ". Принять: /tq town accept");
            }
            case "accept" -> {
                Invite invitation = invites.remove(playerId);
                if (invitation == null || invitation.expiresAt() < System.currentTimeMillis()) {
                    say(p, "Нет действующего приглашения."); return;
                }
                TerritoryStore.Town inviterTown = db.towns().stream()
                    .filter(t -> t.id().equals(invitation.townId())).findFirst().orElse(null);
                if (inviterTown == null || !db.mayInvite(invitation.actorId()) ||
                    !inviterTown.id().equals(db.townFor(invitation.actorId()).id())) {
                    say(p, "Приглашение больше не действует."); return;
                }
                changed(p, db.addMember(invitation.actorId(), playerId));
            }
            case "leave" -> changed(p, db.leave(playerId));
            case "kick" -> {
                if (args.length != 3) { say(p, "Использование: /tq town kick <ник_онлайн или UUID>"); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                UUID id;
                try { id = target != null ? target.getUniqueId() : UUID.fromString(args[2]); }
                catch (IllegalArgumentException invalid) { say(p, "Игрок должен быть онлайн либо укажи UUID участника."); return; }
                changed(p, db.kick(playerId, id));
            }
            case "promote", "demote" -> {
                if (args.length != 3) { say(p, "Использование: /tq town " + sub + " <ник_онлайн или UUID>"); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                UUID id;
                try { id = target != null ? target.getUniqueId() : UUID.fromString(args[2]); }
                catch (IllegalArgumentException invalid) { say(p, "Укажи ник онлайн-игрока или UUID участника."); return; }
                changed(p, db.setOfficer(playerId, id, sub.equals("promote")));
            }
            case "disband" -> {
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                    say(p, "ВНИМАНИЕ: это удалит поселение и ВСЕ его приваты! Подтверди: /tq town disband confirm"); return;
                }
                changed(p, db.disband(playerId));
            }
            default -> say(p, "Использование: /tq town help");
        }
    }
}
