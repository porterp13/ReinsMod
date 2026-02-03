package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.vs.ShipLeashDetection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
    modid = ReinsMod.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class ReinsInteractionEvents {

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();

        // Only animals
        if (!(event.getTarget() instanceof Animal animal)) return;

        // Must be holding reins
        if (!player.getMainHandItem().is(ModItems.REINS.get())) return;

        // Server-side only
        if (player.level().isClientSide) return;

        animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
            cap.setHasReins(true);
            cap.setOwner(player.getUUID());

            player.sendSystemMessage(
                    Component.literal("Reins attached to animal")
            );

            // 🔹 Detect ship leash immediately
            ShipLeashDetection.detectFenceOnShip(animal).ifPresent(info -> {
                player.sendSystemMessage(
                        Component.literal("Animal leashed to a Valkyrien Skies ship")
                );
            });
        });

        // Prevent mounting
        event.setCanceled(true);
    }
}
