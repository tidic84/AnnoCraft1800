package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Management-screen commands: fleet, trade, diplomacy, campaign and travel. Validated on the server thread. */
public final class ColonyActions {
    private ColonyActions() { }
    public static BuildingService.Result handle(ServerPlayer player, String action, CompoundTag a) {
        if (!BuildingService.allowed(player)) return BuildingService.Result.fail("wrong_region");
        ColonyData data = ColonyData.get(player.server);
        CompanyColony colony = data.colony(player);
        Maritime fleet = colony.maritime();
        String error;
        try {
            error = switch (action) {
                case "ship_build" -> fleet.build(colony, a.getString("island"), a.getString("type"));
                case "ship_route" -> fleet.assignRoute(colony, a.getUUID("ship"), a.getString("a"), a.getString("b"), good(a, "out"), good(a, "back"));
                case "ship_move" -> fleet.move(colony, a.getUUID("ship"), a.getString("island"));
                case "ship_attack" -> fleet.attack(colony, a.getUUID("ship"), a.getString("island"));
                case "ship_escort" -> fleet.escort(a.getUUID("ship"), a.getUUID("target"));
                case "ship_scrap" -> fleet.scrap(a.getUUID("ship"));
                case "ship_goto" -> each(a, id -> fleet.sailTo(colony, id, a.getDouble("x"), a.getDouble("z")));
                case "ship_hunt" -> each(a, id -> fleet.hunt(colony, id, UUID.fromString(a.getString("target"))));
                case "ship_dock" -> each(a, id -> fleet.move(colony, id, a.getString("island")));
                case "ship_siege" -> each(a, id -> fleet.attack(colony, id, a.getString("island")));
                case "trade_buy", "trade_sell" -> trade(colony, action.equals("trade_buy"), a.getString("faction"), a.getString("island"), a.getString("good"), Math.max(1, Math.min(100, a.getInt("amount"))));
                case "diplomacy" -> data.diplomacy().act(colony, a.getString("faction"), a.getString("action"));
                case "campaign_start" -> { data.startCampaign(colony); yield null; }
                case "travel" -> travel(player, colony, a.getString("world"));
                case "brand" -> data.brand(player.getUUID(), a.getString("name"), a.getInt("color"), flag(a.getString("flag")), a.getString("avatar"));
                default -> "invalid_action";
            };
        } catch (RuntimeException malformed) { error = "invalid_action"; }
        if (error != null) return BuildingService.Result.fail(error);
        data.markDirty();
        return BuildingService.Result.ok();
    }
    /** Applies an order to every selected ship ('ships': comma-separated ids). @return the first refusal, or null when one ship at least obeyed. */
    private static String each(CompoundTag a, java.util.function.Function<UUID, String> order) {
        String error = "invalid_ship"; boolean any = false;
        for (String id : a.getString("ships").split(",")) {
            if (id.isBlank()) continue;
            String e = order.apply(UUID.fromString(id.trim()));
            if (e == null) any = true; else if (!any) error = e;
        }
        return any ? null : error;
    }
    /** A flag sent as one hexadecimal digit (palette index) per cell. */
    private static byte[] flag(String hex) {
        if (hex.length() != Company.FLAG_W * Company.FLAG_H) return null;
        byte[] f = new byte[hex.length()];
        for (int i = 0; i < f.length; i++) { int v = Character.digit(hex.charAt(i), 16); if (v < 0) return null; f[i] = (byte) v; }
        return f;
    }
    private static String good(CompoundTag a, String key) { String g = a.getString(key); return g.isEmpty() ? null : g; }
    private static String trade(CompanyColony data, boolean buy, String faction, String island, String good, int amount) {
        Diplomacy d = data.diplomacy(); ColonyEconomy e = data.economy();
        if (!d.trades(faction)) return "no_trade";
        if (!data.ports().contains(island) || !Diplomacy.PRICES.containsKey(good)) return "invalid_action";
        if (buy) {
            double price = d.buyPrice(faction, good) * amount;
            if (!e.sandbox() && e.coins() < price) return "no_coins";
            if (e.capacity(island) - e.stock(island, good) < amount) return "storage_full";
            e.addStock(island, good, amount); if (!e.sandbox()) e.addCoins(-price);
        } else {
            if (e.stock(island, good) + 1e-9 < amount) return "no_goods";
            e.addStock(island, good, -amount); e.addCoins(d.sellPrice(faction, good) * amount);
            d.faction(faction).relation = Math.min(100, d.faction(faction).relation + amount / 50.0);
        }
        e.recordFlow(island, good, buy ? amount : -amount);
        return null;
    }
    private static String travel(ServerPlayer player, CompanyColony data, String world) {
        if (IslandLayout.NEW_WORLD.equals(world) && !data.newWorldOpen()) return "new_world_locked";
        var key = AnnoCraft.dimension(world);
        if (player.level().dimension().equals(key)) return "already";
        CameraSessions.close(player);
        ServerEvents.teleportToColony(player, key);
        return null;
    }
    public static CompoundTag args(Object... pairs) {
        CompoundTag t = new CompoundTag();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String k = (String) pairs[i]; Object v = pairs[i + 1];
            if (v instanceof UUID u) t.putUUID(k, u); else if (v instanceof Integer n) t.putInt(k, n); else if (v instanceof Double d) t.putDouble(k, d); else if (v != null) t.putString(k, v.toString());
        }
        return t;
    }
}
