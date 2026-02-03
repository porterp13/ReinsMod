package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.Input;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientMovementEvents {

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!player.getMainHandItem().is(ModItems.WHIP.get())) return;

        Input in = event.getInput();

        // Cancel vanilla movement ONLY
        in.forwardImpulse = 0;
        in.leftImpulse = 0;

        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;

        in.jumping = false;
        in.shiftKeyDown = false;
    }
}
