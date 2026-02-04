package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientWhipMovementLock {

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!player.getMainHandItem().is(ModItems.WHIP.get())) return;

        // Zero movement inputs
        event.getInput().leftImpulse = 0.0f;    // A/D
        event.getInput().forwardImpulse = 0.0f; // W/S
        event.getInput().jumping = false;       // spacebar

        // IMPORTANT: do NOT touch shiftKeyDown (crouch) so shifting still works.
        // event.getInput().shiftKeyDown remains whatever the player is doing.
    }
}
