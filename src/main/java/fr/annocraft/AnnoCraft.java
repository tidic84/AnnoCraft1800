package fr.annocraft;

import com.mojang.serialization.Codec;
import fr.annocraft.world.ArchipelagoGenerator;
import fr.annocraft.building.BuildingDefinitions;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.server.ServerEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(AnnoCraft.ID)
public final class AnnoCraft {
    public static final String ID = "annocraft1800";
    public static final ResourceKey<Level> ARCHIPELAGO = ResourceKey.create(Registries.DIMENSION, id("archipelago"));
    public static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS = DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
    static { GENERATORS.register("archipelago", () -> ArchipelagoGenerator.CODEC); }
    public AnnoCraft(FMLJavaModLoadingContext context) {
        GENERATORS.register(context.getModEventBus());
        context.getModEventBus().addListener((net.minecraftforge.event.RegisterGameTestsEvent event) -> {
            if (Boolean.getBoolean("annocraft1800.tests")) event.register(Boolean.getBoolean("annocraft1800.networkSmoke")
                    ? fr.annocraft.testing.NetworkGameTest.class : fr.annocraft.testing.FoundationGameTests.class);
        });
        AnnoNetwork.register();
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        MinecraftForge.EVENT_BUS.addListener(this::reload);
    }
    private void reload(AddReloadListenerEvent event) { event.addListener(new BuildingDefinitions()); }
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
}
