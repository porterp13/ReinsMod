package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.item.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = ReinsMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerPlayerWhipMovementLock {

    // Fixed UUID so we can reliably remove/replace the modifier
    private static final UUID WHIP_LOCK_UUID =
            UUID.fromString("2f2a66aa-8d1e-4d7c-a5f8-0f7f6d9b3c61");

    private static final AttributeModifier WHIP_LOCK_MOD =
            new AttributeModifier(
                    WHIP_LOCK_UUID,
                    "reinsmod_whip_movement_lock",
                    -1.0,
                    AttributeModifier.Operation.MULTIPLY_TOTAL
            );

    private ServerPlayerWhipMovementLock() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());

        AttributeInstance moveSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (moveSpeed == null) return;

        if (holdingWhip) {
            // Apply lock (MULTIPLY_TOTAL -1 => final multiplier (1 + -1) = 0)
            if (moveSpeed.getModifier(WHIP_LOCK_UUID) == null) {
                moveSpeed.addTransientModifier(WHIP_LOCK_MOD);
            }

            // Prevent jump on server too (belt + suspenders)
            player.setJumping(false);
        } else {
            // Remove lock
            if (moveSpeed.getModifier(WHIP_LOCK_UUID) != null) {
                moveSpeed.removeModifier(WHIP_LOCK_UUID);
            }
        }
    }
}
