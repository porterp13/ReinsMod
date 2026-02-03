package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.vs.ShipLeashDetection;
import com.smeakmoseley.reinsmod.vs.ShipLeashInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.phys.AABB;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

@Mod.EventBusSubscriber(
        modid = ReinsMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class ShipLeashEvents {

    @SubscribeEvent
    public static void onRightClickFence(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Must be holding a lead (this is how fences get leashed)
        if (!event.getItemStack().is(Items.LEAD)) return;

        BlockPos pos = event.getPos();

        // Only fences
        if (!(level.getBlockState(pos).getBlock() instanceof FenceBlock)) return;

        int runTick = level.getServer().getTickCount() + 1;
        level.getServer().tell(new TickTask(runTick, () -> {

            // Find ONLY the knot for the clicked fence position
            LeashFenceKnotEntity knot = level.getEntitiesOfClass(
                    LeashFenceKnotEntity.class,
                    new AABB(pos).inflate(0.75)
            ).stream().filter(k -> k.blockPosition().equals(pos)).findFirst().orElse(null);

            if (knot == null) return;

            // Scan nearby animals and pick ONLY those leashed to THIS knot
            for (Animal animal : level.getEntitiesOfClass(
                    Animal.class,
                    knot.getBoundingBox().inflate(48.0)
            )) {
                Entity holder = animal.getLeashHolder();
                if (holder != knot) continue;

                // Detect that this knot is on a VS ship (in shipyard-managed space)
                Optional<ShipLeashInfo> infoOpt = ShipLeashDetection.detectFenceOnShip(animal);
                if (infoOpt.isEmpty()) continue;

                ShipLeashInfo info = infoOpt.get();

                animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {

                    boolean alreadySame =
                            cap.isLeashedToShip()
                                    && cap.getShipFencePos() != null
                                    && cap.getShipFencePos().equals(info.fencePos);

                    cap.setLeashedToShip(true);
                    cap.setShipFencePos(info.fencePos);

                    // ✅ Store SHIPYARD-space anchor directly (no conversion)
                    cap.setShipAnchorPos(info.anchorPos);

                    if (!alreadySame) {
                        event.getEntity().sendSystemMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "Animal leashed to a Valkyrien Skies ship"
                                )
                        );
                    }
                });
            }
        }));
    }
}
