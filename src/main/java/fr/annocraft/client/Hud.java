package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/**
 * Always-visible colony HUD, in the management view and while visiting: quest tracker, notification feed
 * and character dialogues when a mission starts.
 */
public final class Hud {
    private record Notice(Component text, boolean good, long time) { }
    private static final Deque<Notice> NOTICES = new ArrayDeque<>();
    private static String lastMission = "";
    private static long dialogueStart;
    private static boolean dialogueOpen, trackerCollapsed;
    private Hud() { }

    public static void notice(Component text, boolean good) {
        NOTICES.addFirst(new Notice(text, good, System.currentTimeMillis()));
        while (NOTICES.size() > 6) NOTICES.removeLast();
    }
    static CompoundTag campaign() { return ClientState.economy.getCompound("campaign"); }
    /** Opens the character dialogue when the server reports a new mission. */
    static void watchMission() {
        CompoundTag c = campaign();
        String mission = c.getBoolean("active") ? c.getString("mission") : "";
        if (!mission.equals(lastMission)) {
            if (!mission.isEmpty() && !lastMission.isEmpty() || !mission.isEmpty() && c.getInt("index") == 0) { dialogueOpen = true; dialogueStart = System.currentTimeMillis(); }
            lastMission = mission;
        }
    }
    public static void toggleTracker() { trackerCollapsed = !trackerCollapsed; }
    public static void closeDialogue() { dialogueOpen = false; }
    public static boolean dialogueOpen() { return dialogueOpen; }

    /** Quest tracker panel. @return its height */
    public static int tracker(GuiGraphics g, int x, int y, int w, boolean interactive) {
        Font font = Minecraft.getInstance().font; CompoundTag c = campaign();
        boolean active = c.getBoolean("active"), finished = c.getBoolean("finished") || active && !c.contains("mission");
        List<Runnable> lines = new ArrayList<>(); int[] h = {14};
        Component title;
        if (!active) title = Component.translatable("hud.annocraft1800.campaign");
        else if (finished) title = Component.translatable("hud.annocraft1800.campaign_done");
        else title = Component.translatable("colony.annocraft1800.chapter", c.getInt("chapter"), Component.translatable("campaign.annocraft1800." + c.getString("mission") + ".title"));
        if (!trackerCollapsed) {
            if (!active) h[0] += font.wordWrapHeight(Component.translatable(interactive ? "hud.annocraft1800.start" : "hud.annocraft1800.start_visit"), w - 8) + 4;
            else if (!finished) h[0] += 13 * c.getList("objectives", Tag.TAG_COMPOUND).size() + 2;
        }
        UiKit.panel(g, x, y, w, h[0]);
        UiKit.icon(g, UiKit.item("quest"), x + 3, y + 2, 10);
        UiKit.text(g, title, x + 16, y + 3, UiKit.GOLD_TEXT, w - 28);
        g.drawString(font, trackerCollapsed ? "+" : "–", x + w - 9, y + 3, UiKit.MUTED, false);
        if (trackerCollapsed) return h[0];
        int ty = y + 15;
        if (!active) {
            g.drawWordWrap(font, Component.translatable(interactive ? "hud.annocraft1800.start" : "hud.annocraft1800.start_visit"), x + 4, ty, w - 8, interactive ? UiKit.GOOD : UiKit.TEXT);
            return h[0];
        }
        if (finished) return h[0];
        for (Tag t : c.getList("objectives", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) t;
            int progress = o.getInt("progress"), amount = Math.max(1, o.getInt("amount"));
            boolean done = progress >= amount;
            UiKit.text(g, Component.literal(done ? "✔ " : "").append(ColonyScreen.objective(o)), x + 4, ty, done ? UiKit.GOOD : UiKit.TEXT, w - 8);
            UiKit.bar(g, x + 4, ty + 9, w - 8, 2, (double) progress / amount, done ? UiKit.GOOD : UiKit.GOLD);
            ty += 13;
        }
        return h[0];
    }
    /** Fading notification feed. */
    public static void notices(GuiGraphics g, int x, int y, int w) {
        long now = System.currentTimeMillis();
        for (Notice n : NOTICES) {
            long age = now - n.time; if (age > 12000) continue;
            int alpha = (int) (255 * Math.min(1, (12000 - age) / 2000.0));
            if (alpha < 8) continue;
            Font font = Minecraft.getInstance().font;
            List<FormattedCharSequence> lines = font.split(n.text, w - 10);
            int height = lines.size() * 9 + 4;
            g.fill(x, y, x + w, y + height, (alpha * 3 / 4) << 24 | 0x121c26);
            g.fill(x, y, x + 2, y + height, alpha << 24 | (n.good ? 0x8fd49a : 0xffb060));
            int ly = y + 2;
            for (FormattedCharSequence line : lines) { g.drawString(font, line, x + 6, ly, alpha << 24 | 0xe8e2d4, false); ly += 9; }
            y += height + 2;
        }
    }
    /** Character dialogue with portrait and typed text. @return the panel bounds, or null when closed */
    public static int[] dialogue(GuiGraphics g, int x, int bottom, int w) {
        if (!dialogueOpen) return null;
        CompoundTag c = campaign(); if (!c.contains("mission")) { dialogueOpen = false; return null; }
        Font font = Minecraft.getInstance().font;
        Component full = Component.translatable("campaign.annocraft1800." + c.getString("mission") + ".text");
        String text = full.getString(); long elapsed = System.currentTimeMillis() - dialogueStart;
        int shown = (int) Math.min(text.length(), elapsed / 25);
        if (shown >= text.length() && elapsed > text.length() * 25L + 25000) { dialogueOpen = false; return null; }
        List<FormattedCharSequence> lines = font.split(Component.literal(text.substring(0, shown)), w - 34);
        int height = Math.max(40, 24 + font.split(full, w - 34).size() * 9);
        int y = bottom - height;
        UiKit.panel(g, x, y, w, height);
        g.fill(x + 3, y + 3, x + 27, y + 27, 0xff2a3a48);
        UiKit.icon(g, UiKit.item("speaker:" + c.getString("speaker")), x + 7, y + 7, 16);
        UiKit.text(g, Component.translatable("speaker.annocraft1800." + c.getString("speaker")), x + 31, y + 4, UiKit.GOLD_TEXT, w - 36);
        int ly = y + 15;
        for (FormattedCharSequence line : lines) { g.drawString(font, line, x + 31, ly, UiKit.TEXT, false); ly += 9; }
        return new int[]{x, y, w, height};
    }

    /** The notification feed replaces the chat while managing the colony. */
    @Mod.EventBusSubscriber(modid = AnnoCraft.ID, value = Dist.CLIENT)
    public static final class HideChat {
        @SubscribeEvent public static void chat(net.minecraftforge.client.event.RenderGuiOverlayEvent.Pre event) {
            if (RtsController.active && event.getOverlay().id().equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CHAT_PANEL.id())) event.setCanceled(true);
        }
    }
    /** First-person HUD: tracker, notices and dialogues while visiting the colony. */
    @Mod.EventBusSubscriber(modid = AnnoCraft.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Overlay {
        @SubscribeEvent public static void register(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("colony_hud", (gui, g, partialTick, width, height) -> {
                Minecraft mc = Minecraft.getInstance();
                if (!RtsController.inRegion() || RtsController.active || mc.options.hideGui || ClientState.DEFINITIONS.isEmpty() || mc.screen != null) return;
                watchMission();
                int w = Math.min(170, width / 3);
                tracker(g, width - w - 4, 4, w, false);
                notices(g, 4, 4, Math.min(200, width / 3));
                dialogue(g, 4, height - 40, Math.min(260, width / 2));
            });
        }
    }
}
