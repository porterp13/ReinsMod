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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Mod.EventBusSubscriber(modid = ReinsMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerAnimalControlTick {

    private static final float WALK_SPEED = 0.20f;
    private static final float SPRINT_MULT = 1.80f;

    // Allow 1-block step-up while controlled
    private static final float CONTROL_STEP = 1.0f;

    // ⏳ Grace window to allow VS to register a new fence knot (ticks)
    private static final int SHIP_LEASH_GRACE_TICKS = 10; // ~0.5s

    // Per-animal grace tracking
    private static final Map<UUID, Integer> SHIP_LEASH_GRACE = new ConcurrentHashMap<>();

    // How fast the animal can rotate toward camera yaw
    private static final float MAX_TURN_DEG_PER_TICK = 18.0f;

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

                        UUID id = animal.getUUID();

                        // =========================================================
                        // SHIP LEASH DETECTION (EVENTUALLY CONSISTENT)
                        // =========================================================
                        if (animal.isLeashed()) {
                            Optional<ShipLeashInfo> infoOpt =
                                    ShipLeashDetection.detectFenceOnShip(animal);

                            if (infoOpt.isPresent()) {
                                ShipLeashInfo info = infoOpt.get();

                                cap.setLeashedToShip(true);
                                cap.setShipFencePos(info.fencePos);
                                cap.setShipAnchorPos(info.anchorPos);

                                // Success: clear grace
                                SHIP_LEASH_GRACE.remove(id);
                            } else {
                                // Knot exists but VS may not have registered it yet
                                int grace = SHIP_LEASH_GRACE.getOrDefault(id, 0);
                                if (grace < SHIP_LEASH_GRACE_TICKS) {
                                    SHIP_LEASH_GRACE.put(id, grace + 1);
                                    return; // ⏳ wait, do NOT clear yet
                                }

                                // Grace expired: now we can clear
                                cap.setLeashedToShip(false);
                                cap.setShipFencePos(null);
                                cap.setShipAnchorPos(null);
                                SHIP_LEASH_GRACE.remove(id);
                            }
                        } else {
                            // Not leashed at all
                            cap.setLeashedToShip(false);
                            cap.setShipFencePos(null);
                            cap.setShipAnchorPos(null);
                            SHIP_LEASH_GRACE.remove(id);
                        }

                        // =========================================================
                        // AI suppression only (not physics)
                        // =========================================================
                        Mob mob = (Mob) animal;
                        mob.setTarget(null);
                        mob.getNavigation().stop();

                        if (!holdingWhip || control == null) return;

                        // Step height while controlled
                        animal.setMaxUpStep(CONTROL_STEP);

                        // =========================================================
                        // ✅ Copy camera yaw onto animal (smoothly, works in seats)
                        // control.yaw is ABSOLUTE camera yaw (sent by client)
                        // =========================================================
                        float targetYaw = control.yaw;
                        float currentYaw = animal.getYRot();

                        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
                        deltaYaw = Mth.clamp(deltaYaw, -MAX_TURN_DEG_PER_TICK, MAX_TURN_DEG_PER_TICK);

                        float newYaw = currentYaw + deltaYaw;

                        animal.setYRot(newYaw);
                        animal.setYHeadRot(newYaw);
                        try { animal.yBodyRot = newYaw; } catch (Throwable ignored) {}

                        // Feed movement inputs for animation
                        try {
                            animal.zza = control.forward;
                            animal.xxa = control.strafe;
                        } catch (Throwable ignored) {}

                        // =========================================================
                        // Movement direction based on animal's current yaw
                        // =========================================================
                        float yawRad = (float) Math.toRadians(animal.getYRot());

                        Vec3 forward = new Vec3(
                                -Math.sin(yawRad),
                                0,
                                Math.cos(yawRad)
                        );

                        Vec3 right = new Vec3(
                                -forward.z,
                                0,
                                forward.x
                        );

                        float speed = WALK_SPEED * (control.sprint ? SPRINT_MULT : 1.0f);

                        // Desired horizontal displacement
                        Vec3 moveXZ = forward.scale(control.forward)
                                .add(right.scale(control.strafe))
                                .scale(speed);

                        // =========================================================
                        // RIGID ROPE CONSTRAINT (SHIP LEASH)
                        // =========================================================
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

                        // Preserve vanilla Y
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
