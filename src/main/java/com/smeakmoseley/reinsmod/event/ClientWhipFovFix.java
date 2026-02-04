package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientWhipFovFix {

    // Hold neutral FOV for a couple ticks after releasing whip to prevent 1-frame "pop"
    private static int graceTicks = 0;

    // Tune this: 2 is usually enough, 3 if you're still seeing it.
    private static final int GRACE_TICKS_ON_RELEASE = 5;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            graceTicks = 0;
            return;
        }

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());

        if (holdingWhip) {
            graceTicks = GRACE_TICKS_ON_RELEASE;
        } else if (graceTicks > 0) {
            graceTicks--;
        }
    }

    @SubscribeEvent
    public static void onFovModifier(ComputeFovModifierEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());

        if (holdingWhip || graceTicks > 0) {
            // Neutralize speed-based FOV changes (prevents zoom-in/out pop)
            event.setNewFovModifier(1.0f);
        }
    }
}
