package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.control.ServerControlState;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.vs.ShipLeashDetection;
import com.smeakmoseley.reinsmod.vs.ShipLeashInfo;
import com.smeakmoseley.reinsmod.vs.ShipRopeConstraint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

@Mod.EventBusSubscriber(modid = ReinsMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerAnimalControlTick {

    private static final float WALK_SPEED = 0.35f;
    private static final float SPRINT_MULT = 1.65f;

    private static final double JUMP_VEL = 0.52; // tuned for horses-ish feel

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {

                boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());
                ServerControlState.Control control = ServerControlState.get(player.getUUID());

                if (!holdingWhip) {
                    ServerControlState.clear(player.getUUID());
                }

                level.getEntitiesOfClass(
                        Animal.class,
                        player.getBoundingBox().inflate(48.0)
                ).forEach(animal -> {

                    animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
                        if (!cap.hasReins()) return;
                        if (!player.getUUID().equals(cap.getOwner())) return;

                        // Refresh leash anchor from the knot each tick (moving ship fix)
                        if (cap.isLeashedToShip()) {
                            Optional<ShipLeashInfo> infoOpt = ShipLeashDetection.detectFenceOnShip(animal);
                            if (infoOpt.isPresent()) {
                                ShipLeashInfo info = infoOpt.get();
                                cap.setShipFencePos(info.fencePos);
                                cap.setShipAnchorPos(info.anchorPos); // shipyard
                            } else {
                                cap.setLeashedToShip(false);
                                cap.setShipFencePos(null);
                                cap.setShipAnchorPos(null);
                            }
                        }

                        // While whip is held, we "puppet" the animal
                        animal.setNoAi(holdingWhip);
                        if (!holdingWhip || control == null) return;

                        // (2) Body orientation follows CAMERA yaw
                        animal.setYRot(control.yaw);
                        animal.setYHeadRot(control.yaw);
                        try {
                            // Mob has yBodyRot; Animal inherits it
                            animal.yBodyRot = control.yaw;
                        } catch (Throwable ignored) {}

                        // (3) Feed movement inputs so limb animation plays
                        try {
                            animal.zza = control.forward; // forward/back
                            animal.xxa = control.strafe;  // strafe
                        } catch (Throwable ignored) {}

                        float yawRad = (float) Math.toRadians(control.yaw);

                        Vec3 forward = new Vec3(
                                -Math.sin(yawRad),
                                0,
                                Math.cos(yawRad)
                        );

                        // (1) Correct right vector (fix A/D)
                        Vec3 right = new Vec3(
                                -forward.z,
                                0,
                                forward.x
                        );

                        float speed = WALK_SPEED * (control.sprint ? SPRINT_MULT : 1.0f);

                        Vec3 move = forward.scale(control.forward)
                                .add(right.scale(control.strafe))
                                .scale(speed);

                        // Rope clamp if leashed to ship
                        if (cap.isLeashedToShip()) {
                            Vec3 anchorShipyard = cap.getShipAnchorPos();
                            BlockPos fencePos = cap.getShipFencePos();
                            if (anchorShipyard != null && fencePos != null) {
                                move = ShipRopeConstraint.applyRigid(
                                        level,
                                        animal,
                                        fencePos,
                                        anchorShipyard,
                                        move
                                );
                            }
                        }

                        // Apply motion
                        animal.setDeltaMovement(move);
                        animal.move(MoverType.SELF, move);
                        animal.hurtMarked = true;

                        // (5) Jump on space (only if on ground)
                        if (control.jump && animal.onGround()) {
                            Vec3 dm = animal.getDeltaMovement();
                            animal.setDeltaMovement(dm.x, JUMP_VEL, dm.z);
                            animal.hurtMarked = true;
                        }
                    });
                });
            }
        }
    }
}
