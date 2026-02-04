package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.vs.ShipLeashDetection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

        // Server-side only
        if (player.level().isClientSide) return;

        boolean holdingReins = player.getMainHandItem().is(ModItems.REINS.get());
        boolean removing = player.isShiftKeyDown(); // SHIFT + right click removes (no item required)

        animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {

            // =========================
            // REMOVE REINS (shift-click)
            // =========================
            if (removing) {
                if (!cap.hasReins()) return; // nothing to do

                cap.setHasReins(false);
                cap.setOwner(null);

                // Clear ship leash state too
                cap.setLeashedToShip(false);
                cap.setShipFencePos(null);
                cap.setShipAnchorPos(null);

                // Drop the reins item for survival compatibility
                animal.spawnAtLocation(new ItemStack(ModItems.REINS.get(), 1));

                player.sendSystemMessage(Component.literal("Reins removed from animal"));

                // Prevent mounting / other interaction when we handled it
                event.setCanceled(true);
                return;
            }

            // =========================
            // ATTACH REINS (must hold)
            // =========================
            if (!holdingReins) return;

            // If you want to avoid “free dupes”, only consume a reins item if not creative
            if (!player.getAbilities().instabuild) {
                player.getMainHandItem().shrink(1);
            }

            cap.setHasReins(true);
            cap.setOwner(player.getUUID());

            player.sendSystemMessage(Component.literal("Reins attached to animal"));

            // Detect ship leash immediately (optional message)
            ShipLeashDetection.detectFenceOnShip(animal).ifPresent(info -> {
                player.sendSystemMessage(
                        Component.literal("Animal leashed to a Valkyrien Skies ship")
                );
            });

            // Prevent mounting
            event.setCanceled(true);
        });
    }
}
