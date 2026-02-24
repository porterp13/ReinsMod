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
import com.smeakmoseley.reinsmod.vs.KnotShipAnchorRegistry;
import net.minecraft.world.phys.Vec3;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.server.level.ServerPlayer;

@Mod.EventBusSubscriber(
        modid = ReinsMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class ShipLeashEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

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

            Vec3 clickedWorld;
            try {
                clickedWorld = event.getHitVec().getLocation();
            } catch (Throwable t) {
                clickedWorld = knot.position();
            }

            LOGGER.info("[ReinsMod VS DBG] attach clickedWorld={} knotCenter={}",
                    clickedWorld, Vec3.atCenterOf(knot.blockPosition()));

            // Scan nearby animals and pick ONLY those leashed to THIS knot
            for (Animal animal : level.getEntitiesOfClass(
                    Animal.class,
                    knot.getBoundingBox().inflate(48.0)
            )) {
                Entity holder = animal.getLeashHolder();
                if (holder != knot) continue;

                boolean bound = KnotShipAnchorRegistry.rememberOrUpdate(level, knot, clickedWorld, event.getEntity().position());

                if (!bound) {
                    int retryTick = level.getServer().getTickCount() + 4; // 4 ticks later
                    Vec3 playerHint = event.getEntity().position();

                    level.getServer().tell(new TickTask(retryTick, () -> {
                        LeashFenceKnotEntity retryKnot = level.getEntitiesOfClass(
                                LeashFenceKnotEntity.class,
                                new AABB(pos).inflate(0.75)
                        ).stream().filter(k -> k.blockPosition().equals(pos)).findFirst().orElse(null);

                        if (retryKnot == null) return;

                        boolean rebound = KnotShipAnchorRegistry.rememberOrUpdate(level, retryKnot, null, playerHint);

                        if (!rebound && event.getEntity() instanceof ServerPlayer sp) {
                            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Couldn't attach leash to ship. Try again while standing on the ship near the fence."
                            ));
                        }
                    }));
                }

                // Authoritative: registry (shipId + anchor bound at attach time)
                var resolvedOpt = KnotShipAnchorRegistry.resolve(level, knot, false);
                if (resolvedOpt.isEmpty()) continue;

                var resolved = resolvedOpt.get();

                animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
                    boolean alreadySame =
                            cap.isLeashedToShip()
                                    && cap.getShipFencePos() != null
                                    && cap.getShipFencePos().equals(knot.blockPosition());

                    cap.setLeashedToShip(true);

                    // Fence block on the ship (knot block pos is what your UI/logic usually keys on)
                    cap.setShipFencePos(knot.blockPosition());

                    // Use resolved world anchor from registry
                    cap.setShipAnchorPos(resolved.anchorWorld());

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
