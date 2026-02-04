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
import net.minecraft.world.entity.Mob;
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

    // ✅ allow 1-block step-up while controlled
    private static final float CONTROL_STEP = 1.0f;

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

                        // ✅ ONLY suppress AI decisions, NOT physics:
                        // Do NOT call setNoAi(true). Instead stop pathing/targets.
                        Mob mob = (Mob) animal;
                        mob.setTarget(null);
                        mob.getNavigation().stop();

                        if (!holdingWhip || control == null) return;

                        // ✅ Step height while controlled
                        animal.setMaxUpStep(CONTROL_STEP);

                        // (2) Body orientation follows CAMERA yaw
                        animal.setYRot(control.yaw);
                        animal.setYHeadRot(control.yaw);
                        try { animal.yBodyRot = control.yaw; } catch (Throwable ignored) {}

                        // (3) Feed movement inputs so limb animation plays
                        try {
                            animal.zza = control.forward;
                            animal.xxa = control.strafe;
                        } catch (Throwable ignored) {}

                        float yawRad = (float) Math.toRadians(control.yaw);

                        Vec3 forward = new Vec3(
                                -Math.sin(yawRad),
                                0,
                                Math.cos(yawRad)
                        );

                        // (1) Correct right vector (fix A/D) — keep your proven math
                        Vec3 right = new Vec3(
                                -forward.z,
                                0,
                                forward.x
                        );

                        float speed = WALK_SPEED * (control.sprint ? SPRINT_MULT : 1.0f);

                        // Desired horizontal displacement (your “feel”)
                        Vec3 moveXZ = forward.scale(control.forward)
                                .add(right.scale(control.strafe))
                                .scale(speed);

                        // Rope clamp if leashed to ship
                        if (cap.isLeashedToShip()) {
                            Vec3 anchorShipyard = cap.getShipAnchorPos();
                            BlockPos fencePos = cap.getShipFencePos();
                            if (anchorShipyard != null && fencePos != null) {
                                moveXZ = ShipRopeConstraint.applyRigid(
                                        level,
                                        animal,
                                        fencePos,
                                        anchorShipyard,
                                        moveXZ
                                );
                            }
                        }

                        // ✅ Preserve vanilla Y (gravity/falling already happened earlier this tick)
                        Vec3 dm = animal.getDeltaMovement();
                        Vec3 move = new Vec3(moveXZ.x, dm.y, moveXZ.z);

                        // Apply motion
                        animal.setDeltaMovement(move);
                        animal.move(MoverType.SELF, move);
                        animal.hurtMarked = true;

                        // (Jump ignored for now, per your request)
                    });
                });
            }
        }
    }
}
