package fr.annocraft.economy;

import com.google.gson.*;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.ToIntFunction;

/**
 * The story campaign: an ordered list of missions loaded from data packs, each with objectives measured on the
 * shared colony, rewards and dialogue (text lives in the language files under campaign.annocraft1800.&lt;id&gt;.*).
 */
public final class Campaign {
    public record Objective(String type, String target, int amount) { }
    public record Mission(String id, int chapter, String speaker, List<Objective> objectives, int rewardCoins,
                          Map<String, Integer> rewardGoods, boolean unlocksNewWorld) {
        public static Mission parse(JsonObject j) {
            List<Objective> objectives = new ArrayList<>();
            for (JsonElement e : j.getAsJsonArray("objectives")) {
                JsonObject o = e.getAsJsonObject();
                objectives.add(new Objective(o.get("type").getAsString(), o.has("target") ? o.get("target").getAsString() : null, o.get("amount").getAsInt()));
            }
            Map<String, Integer> goods = new TreeMap<>();
            JsonObject reward = j.has("reward") ? j.getAsJsonObject("reward") : new JsonObject();
            if (reward.has("goods")) reward.getAsJsonObject("goods").entrySet().forEach(e -> goods.put(e.getKey(), e.getValue().getAsInt()));
            return new Mission(j.get("id").getAsString(), j.get("chapter").getAsInt(), j.get("speaker").getAsString(), List.copyOf(objectives),
                    reward.has("coins") ? reward.get("coins").getAsInt() : 0, goods, j.has("new_world") && j.get("new_world").getAsBoolean());
        }
    }
    private static volatile List<Mission> missions = List.of();
    public static void setMissions(List<Mission> list) { missions = List.copyOf(list); }
    public static List<Mission> missions() { return missions; }

    private boolean active, finished, newWorld;
    private int index;
    public boolean active() { return active; }
    public boolean finished() { return finished; }
    public boolean newWorldUnlocked() { return newWorld; }
    public int index() { return index; }
    public Mission current() { return active && !finished && index < missions.size() ? missions.get(index) : null; }
    public void start(Colony colony) {
        if (active) return;
        active = true; index = 0; finished = missions.isEmpty();
        Mission m = current();
        if (m != null) colony.event(Colony.Event.of(true, "event.annocraft1800.mission", "#campaign.annocraft1800." + m.id() + ".title"));
    }
    public void step(Colony colony, ToIntFunction<Objective> progress) {
        Mission m = current(); if (m == null) return;
        for (Objective o : m.objectives()) if (progress.applyAsInt(o) < o.amount()) return;
        ColonyEconomy e = colony.economy();
        e.addCoins(m.rewardCoins());
        String home = e.homeIsland();
        if (home != null) m.rewardGoods().forEach((good, amount) -> e.addStock(home, good, amount));
        if (m.unlocksNewWorld()) newWorld = true;
        colony.event(Colony.Event.of(true, "event.annocraft1800.mission_done", "#campaign.annocraft1800." + m.id() + ".title"));
        index++;
        if (index >= missions.size()) { finished = true; colony.event(Colony.Event.of(true, "event.annocraft1800.campaign_done")); }
        else colony.event(Colony.Event.of(true, "event.annocraft1800.mission", "#campaign.annocraft1800." + missions.get(index).id() + ".title"));
    }
    public CompoundTag save() {
        CompoundTag t = new CompoundTag(); t.putBoolean("active", active); t.putBoolean("finished", finished);
        t.putBoolean("new_world", newWorld); t.putInt("index", index); return t;
    }
    public static Campaign load(CompoundTag t) {
        Campaign c = new Campaign(); c.active = t.getBoolean("active"); c.finished = t.getBoolean("finished");
        c.newWorld = t.getBoolean("new_world"); c.index = t.getInt("index"); return c;
    }
    public CompoundTag snapshot(ToIntFunction<Objective> progress) {
        CompoundTag t = save(); t.putInt("count", missions.size());
        Mission m = current();
        if (m != null) {
            t.putString("mission", m.id()); t.putInt("chapter", m.chapter()); t.putString("speaker", m.speaker());
            ListTag objectives = new ListTag();
            for (Objective o : m.objectives()) {
                CompoundTag c = new CompoundTag(); c.putString("type", o.type()); if (o.target() != null) c.putString("target", o.target());
                c.putInt("amount", o.amount()); c.putInt("progress", Math.min(o.amount(), progress.applyAsInt(o))); objectives.add(c);
            }
            t.put("objectives", objectives);
            t.putInt("reward", m.rewardCoins());
        }
        return t;
    }
}
