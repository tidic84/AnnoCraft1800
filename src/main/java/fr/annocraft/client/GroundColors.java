package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.world.AnnoBlocks;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.GrassColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Worn grass takes the biome's grass colour, like the vanilla grass block it fades into. */
@Mod.EventBusSubscriber(modid = AnnoCraft.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GroundColors {
    private GroundColors() { }
    @SubscribeEvent public static void blocks(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> tint != 0 ? -1 : level == null || pos == null ? GrassColor.getDefaultColor() : BiomeColors.getAverageGrassColor(level, pos),
                AnnoBlocks.WORN_GRASS.get(), AnnoBlocks.TRODDEN_GRASS.get());
    }
    @SubscribeEvent public static void items(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? GrassColor.getDefaultColor() : -1, AnnoBlocks.WORN_GRASS.get(), AnnoBlocks.TRODDEN_GRASS.get());
    }
}
