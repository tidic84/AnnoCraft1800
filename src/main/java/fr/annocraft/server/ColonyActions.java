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
        Maritime fleet = data.maritime();
        String error;
        try {
            error = switch (action) {
                case "ship_build" -> fleet.build(data, a.getString("island"), a.getString("type"));
                case "ship_route" -> fleet.assignRoute(data, a.getUUID("ship"), a.getString("a"), a.getString("b"), good(a, "out"), good(a, "back"));
                case "ship_move" -> fleet.move(data, a.getUUID("ship"), a.getString("island"));
                case "ship_attack" -> fleet.attack(data, a.getUUID("ship"), a.getString("island"));
                case "ship_escort" -> fleet.escort(a.getUUID("ship"), a.getUUID("target"));
                case "ship_scrap" -> fleet.scrap(a.getUUID("ship"));
                case "trade_buy", "trade_sell" -> trade(data, action.equals("trade_buy"), a.getString("faction"), a.getString("island"), a.getString("good"), Math.max(1, Math.min(100, a.getInt("amount"))));
                case "diplomacy" -> data.diplomacy().act(data, a.getString("faction"), a.getString("action"));
                case "campaign_start" -> { data.startCampaign(); yield null; }
                case "travel" -> travel(player, data, a.getString("world"));
                default -> "invalid_action";
            };
        } catch (RuntimeException malformed) { error = "invalid_action"; }
        if (error != null) return BuildingService.Result.fail(error);
        data.markDirty();
        return BuildingService.Result.ok();
    }
    private static String good(CompoundTag a, String key) { String g = a.getString(key); return g.isEmpty() ? null : g; }
    private static String trade(ColonyData data, boolean buy, String faction, String island, String good, int amount) {
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
    private static String travel(ServerPlayer player, ColonyData data, String world) {
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
            if (v instanceof UUID u) t.putUUID(k, u); else if (v instanceof Integer n) t.putInt(k, n); else if (v != null) t.putString(k, v.toString());
        }
        return t;
    }
}
