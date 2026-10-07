package fr.annocraft.server;

import com.google.gson.*;
import fr.annocraft.economy.Campaign;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Loads campaign missions from data/&lt;namespace&gt;/annocraft_campaign/*.json, ordered by file name then position. */
public final class CampaignDefinitions extends SimpleJsonResourceReloadListener {
    public CampaignDefinitions() { super(new Gson(), "annocraft_campaign"); }
    @Override protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
        List<Campaign.Mission> missions = new ArrayList<>();
        new TreeMap<>(json).forEach((id, value) -> {
            for (JsonElement m : value.getAsJsonObject().getAsJsonArray("missions")) missions.add(Campaign.Mission.parse(m.getAsJsonObject()));
        });
        Campaign.setMissions(missions);
    }
}
