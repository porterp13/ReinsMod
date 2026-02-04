package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import com.smeakmoseley.reinsmod.control.ServerControlState;
import com.smeakmoseley.reinsmod.vs.VsShipAccess;
import com.smeakmoseley.reinsmod.vs.VsShipForces;
import com.smeakmoseley.reinsmod.vs.VsShipMass;
import com.smeakmoseley.reinsmod.vs.VsShipTransforms;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(
        modid = ReinsMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ShipLeashPhysicsTick {

    private static final double SCAN_RADIUS = 64.0;

    // Rope geometry
    private static final double SLACK = 2.5;
    private static final double PLAYER_SLACK = 0.25;

    // Spring leash (passive / stretch component)
    private static final double SPRING = 2800.0;
    private static final double DAMPING = 280.0;

    // Player intent pulling – proportional to mass, no light-ship favoritism
    private static final double BASE_INTENT_FORCE_PER_TON = 30_000.0;
    private static final double INTENT_FORCE_MASS_EXPONENT = 0.75;
    private static final double MAX_INTENT_FORCE = 8_000_000.0;
    private static final double MIN_INTENT_FORCE = 5_000.0;

    // Smooth + rate limit (pull force only)
    private static final double FORCE_SMOOTHING = 0.75;
    private static final double MIN_FORCE_MASS_MULT = 2.0;
    private static final double FORCE_RATE_LIMIT_MULT_LIGHT = 400.0;
    private static final double FORCE_RATE_LIMIT_MULT_HEAVY = 220.0;

    // Safety rule: shipSpeedAlong must NOT exceed allowedAlong + eps
    private static final double VELOCITY_EPS = 0.015;

    // Hysteresis band to prevent brake/pull pumping
    private static final double SPEED_HYST = 0.05; // tune 0.03–0.08

    // Braking controller (COM braking only)
    private static final double BRAKE_GAIN = 1.25; // tune 0.8–2.0
    private static final double MAX_FORCE = 1_000_000.0;

    // STOP-INTENT braking (player released movement input)
    // When player is controlling but not giving movement input, brake any forward ship motion along reins.
    private static final double STOP_BRAKE_GAIN = 2.25;      // stronger than BRAKE_GAIN
    private static final double STOP_BRAKE_DEADZONE = 0.005; // ignore jitter (blocks/tick)
    private static final double STOP_MAX_FORCE = 1_500_000.0;

    private static final double MAX_REASONABLE_DIST = 128.0;

    // --- commanded speed constants (match ServerAnimalControlTick) ---
    private static final double CMD_WALK_SPEED = 0.16;
    private static final double CMD_SPRINT_MULT = 1.80;

    // "no input" threshold (stick noise guard)
    private static final double INPUT_MAG_EPS = 0.05;

    // VS velocity is *likely* blocks/sec, Minecraft movement is blocks/tick
    private static final double SEC_PER_TICK = 1.0 / 20.0;

    private static final ConcurrentHashMap<UUID, Double> LAST_PULL_FORCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> LAST_BAD_ANCHOR_TICK = new ConcurrentHashMap<>();

    private ShipLeashPhysicsTick() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        int nowTick = level.getServer().getTickCount();

        Set<Integer> seen = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            AABB scan = player.getBoundingBox().inflate(SCAN_RADIUS);
            for (Animal animal : level.getEntitiesOfClass(Animal.class, scan)) {
                if (!seen.add(animal.getId())) continue;

                animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
                    if (!cap.hasReins()) return;
                    if (!cap.isLeashedToShip()) return;
                    if (!animal.isLeashed()) return;

                    if (!(animal.getLeashHolder() instanceof LeashFenceKnotEntity knot)) {
                        cleanup(animal);
                        return;
                    }

                    Vec3 knotPos = knot.position();

                    // Anchor math should be horizontal-only for distance checks
                    Vec3 animalPosStable = new Vec3(
                            animal.position().x,
                            knotPos.y,
                            animal.position().z
                    );

                    Object ship0 = VsShipAccess.getShipManagingPos(level, knotPos).orElse(null);
                    if (ship0 == null) return;

                    AnchorSolve solved = resolveAnchorWorld(level, ship0, knotPos, animalPosStable);
                    if (!solved.ok || solved.anchorWorld == null) {
                        maybeWarnBadAnchor(player, animal.getUUID(), knotPos, cap.getShipAnchorPos());
                        return;
                    }

                    Vec3 anchorWorld = solved.anchorWorld;
                    Object ship = solved.shipObj;

                    // Horizontal delta from anchor to animal
                    Vec3 delta = animal.position().subtract(anchorWorld);
                    delta = new Vec3(delta.x, 0.0, delta.z);
                    double dist = delta.length();

                    if (dist < 1.0e-6) return;
                    if (dist > MAX_REASONABLE_DIST) {
                        debug(player,
                                "§4DIST TOO LARGE dist=" + fmt(dist)
                                        + " animal=" + fmt(animal.position())
                                        + " anchorWorld=" + fmt(anchorWorld)
                                        + " knot=" + fmt(knotPos)
                                        + " mode=" + solved.mode
                        );
                        return;
                    }

                    Vec3 dir = delta.scale(1.0 / dist);

                    // Stable "player controlled" detection
                    UUID owner = cap.getOwner();
                    ServerControlState.Control ctl = (owner == null) ? null
                            : ServerControlState.getRecent(owner, nowTick, 5);
                    boolean playerControlled = (ctl != null);

                    // Commanded speed (blocks/tick)
                    double commandedSpeed = 0.0;
                    double inputMag = 0.0;
                    if (playerControlled) {
                        inputMag = Math.sqrt((double) ctl.forward * ctl.forward + (double) ctl.strafe * ctl.strafe);
                        if (inputMag > 1.0) inputMag = 1.0;

                        double mult = ctl.sprint ? CMD_SPRINT_MULT : 1.0;
                        commandedSpeed = CMD_WALK_SPEED * mult * inputMag;
                    }
                    boolean hasInput = playerControlled && inputMag > INPUT_MAG_EPS;

                    // Rope slack and stretch
                    double slack = playerControlled ? PLAYER_SLACK : SLACK;
                    double stretch = dist - slack;

                    // True velocities (blocks/tick)
                    Vec3 animalVel = animal.getDeltaMovement();
                    double animalSpeedAlong = animalVel.dot(dir);

                    // VS velocity likely blocks/sec -> convert to blocks/tick
                    Vec3 shipVelWorldPerSec = getShipVelocity(ship);
                    double shipSpeedAlong = shipVelWorldPerSec.dot(dir) * SEC_PER_TICK;

                    double shipMass = VsShipMass.getShipMass(ship);
                    if (shipMass <= 0) shipMass = 20_000.0;

                    // ============================================================
                    // STOP INTENT: player is controlling but released movement input
                    // Hard-reins behavior: drive ship horizontal velocity toward ZERO.
                    // ============================================================
                    boolean stopIntent = playerControlled && !hasInput;
                    if (stopIntent) {
                        UUID key = animal.getUUID();

                        // ship velocity in blocks/sec (VS)
                        Vec3 vSec = getShipVelocity(ship);

                        // only horizontal braking (don't fight buoyancy / vertical physics)
                        Vec3 vXZ = new Vec3(vSec.x, 0.0, vSec.z);
                        double speed = vXZ.length();

                        // ignore tiny jitter
                        if (speed > 0.02) {
                            // "velocity damper" gain: accel ≈ STOP_VEL_GAIN * speed  (blocks/sec^2)
                            // Force = mass * accel
                            final double STOP_VEL_GAIN = 6.0;          // tune 3..12
                            final double STOP_MAX_FORCE = 2_500_000.0; // tune up if needed

                            Vec3 dirBrake = vXZ.scale(1.0 / speed); // unit direction of motion
                            double brakeMag = shipMass * speed * STOP_VEL_GAIN;
                            brakeMag = Math.min(brakeMag, STOP_MAX_FORCE);

                            // Apply opposite to velocity at COM
                            VsShipForces.applyWorldForce(ship, dirBrake.scale(-brakeMag), null);
                        }

                        // don't let pull smoothing fight this
                        LAST_PULL_FORCE.put(key, 0.0);

                        if (animal.tickCount % 20 == 0) {
                            debug(player, "STOP-HALT speed=" + fmt(speed) + " (blocks/sec) mode=" + solved.mode);
                        }

                        return; // skip normal controller
                    }

                    // Allowed forward speed along reins (blocks/tick)
                    double allowedAlong;
                    if (playerControlled) {
                        // Use commanded speed as primary intent; animalSpeedAlong is noisy but useful as floor
                        allowedAlong = Math.max(commandedSpeed, Math.max(0.0, animalSpeedAlong));
                    } else {
                        allowedAlong = Math.max(0.0, animalSpeedAlong);
                    }
                    allowedAlong += VELOCITY_EPS;

                    // 3-state controller with hysteresis
                    double hi = allowedAlong + SPEED_HYST;
                    double lo = Math.max(0.0, allowedAlong - SPEED_HYST);

                    UUID key = animal.getUUID();

                    // ============================================================
                    // BRAKE: ship outruns allowed+H (exclusive; no pull this tick)
                    // ============================================================
                    if (shipSpeedAlong > hi) {
                        double excess = shipSpeedAlong - allowedAlong;

                        double brakeMag = excess * shipMass * BRAKE_GAIN;
                        brakeMag = Math.min(brakeMag, MAX_FORCE);

                        // Brake opposite along-rein motion, AT COM (null)
                        VsShipForces.applyWorldForce(ship, dir.scale(-brakeMag), null);

                        // Do not let pull smoothing fight this tick
                        LAST_PULL_FORCE.put(key, 0.0);

                        if (animal.tickCount % 20 == 0) {
                            debug(player,
                                    "BRAKE shipAlong=" + fmt(shipSpeedAlong)
                                            + " allowed=" + fmt(allowedAlong)
                                            + " band=[" + fmt(lo) + "," + fmt(hi) + "]"
                                            + " brake=" + fmt(brakeMag)
                                            + " cmd=" + fmt(commandedSpeed)
                                            + " animalAlong=" + fmt(animalSpeedAlong)
                                            + " mode=" + solved.mode
                            );
                        }
                        return;
                    }

                    // ============================================================
                    // HOLD: within band (exclusive; no pull, no brake)
                    // ============================================================
                    if (shipSpeedAlong >= lo) {
                        LAST_PULL_FORCE.put(key, 0.0);

                        if (animal.tickCount % 20 == 0) {
                            debug(player,
                                    "HOLD shipAlong=" + fmt(shipSpeedAlong)
                                            + " allowed=" + fmt(allowedAlong)
                                            + " band=[" + fmt(lo) + "," + fmt(hi) + "]"
                                            + " cmd=" + fmt(commandedSpeed)
                                            + " animalAlong=" + fmt(animalSpeedAlong)
                                            + " mode=" + solved.mode
                            );
                        }
                        return;
                    }

                    // ============================================================
                    // PULL: clearly under-speed (exclusive; no brake)
                    // ============================================================
                    double minForce = Math.max(1.0, shipMass * MIN_FORCE_MASS_MULT);
                    double targetForce = 0.0;
                    double intentForce = 0.0;

                    if (playerControlled) {
                        if (hasInput) {
                            double intent = Math.max(0.0, commandedSpeed);

                            // Breakaway bias (helps heavy ships start moving)
                            if (intent < 0.08 && shipSpeedAlong < 0.15) {
                                intent = Math.max(intent, 0.12);
                            }

                            if (intent > 0.01) {
                                double shipTons = shipMass / 1000.0;
                                double intentBase = intent * BASE_INTENT_FORCE_PER_TON;
                                double massFactor = Math.pow(shipTons, INTENT_FORCE_MASS_EXPONENT);

                                intentForce = intentBase * massFactor;
                                intentForce = Math.min(MAX_INTENT_FORCE, Math.max(MIN_INTENT_FORCE, intentForce));

                                double stretchForce = Math.max(0.0, stretch) * SPRING * 6.0;
                                targetForce = Math.max(intentForce + stretchForce, minForce);
                            }
                        } else {
                            // No input: only spring stretch (keeps it from "self driving")
                            if (stretch > 0.0) {
                                double relVel = animalVel.dot(dir);
                                targetForce = Math.max((stretch * SPRING) + (relVel * DAMPING), 0.0);
                            }
                        }
                    } else {
                        // Passive mode: spring only
                        if (stretch > 0.0) {
                            double relVel = animalVel.dot(dir);
                            targetForce = Math.max((stretch * SPRING) + (relVel * DAMPING), 0.0);
                        }
                    }

                    // Smooth + rate limit pull force
                    double prev = LAST_PULL_FORCE.getOrDefault(key, targetForce);
                    double smoothed = prev + (targetForce - prev) * FORCE_SMOOTHING;

                    double rateLimit = shipMass < 80_000 ? FORCE_RATE_LIMIT_MULT_LIGHT : FORCE_RATE_LIMIT_MULT_HEAVY;
                    double maxDelta = shipMass * rateLimit;

                    double df = smoothed - prev;
                    if (df > maxDelta) df = maxDelta;
                    if (df < -maxDelta) df = -maxDelta;

                    double forceMag = prev + df;
                    forceMag = Math.min(MAX_FORCE, Math.max(0.0, forceMag));

                    LAST_PULL_FORCE.put(key, forceMag);

                    if (forceMag > 0.0) {
                        // Pull ONLY along rein dir, applied at anchor point
                        VsShipForces.applyWorldForce(ship, dir.scale(forceMag), anchorWorld);
                    }

                    if (animal.tickCount % 20 == 0) {
                        double shipTons = shipMass / 1000.0;
                        debug(player,
                                "PULL dist=" + fmt(dist)
                                        + " stretch=" + fmt(stretch)
                                        + " F=" + fmt(forceMag)
                                        + " shipAlong=" + fmt(shipSpeedAlong)
                                        + " allowed=" + fmt(allowedAlong)
                                        + " cmd=" + fmt(commandedSpeed)
                                        + " animalAlong=" + fmt(animalSpeedAlong)
                                        + " mass=" + String.format("%.0ft", shipTons)
                                        + " intentF≈" + String.format("%.0f kN", intentForce / 1000)
                                        + " mode=" + solved.mode
                        );
                    }
                });
            }
        }
    }

    // Anchor resolution helpers (unchanged)
    private static final class AnchorSolve {
        final boolean ok;
        final Vec3 anchorWorld;
        final Object shipObj;
        final String mode;

        AnchorSolve(boolean ok, Vec3 anchorWorld, Object shipObj, String mode) {
            this.ok = ok;
            this.anchorWorld = anchorWorld;
            this.shipObj = shipObj;
            this.mode = mode;
        }
    }

    private static final class BestAnchor {
        Vec3 anchorWorld = null;
        Object shipObj = null;
        String mode = "none";
        double dist = Double.POSITIVE_INFINITY;
    }

    private static AnchorSolve resolveAnchorWorld(ServerLevel level, Object ship0, Vec3 knotPos, Vec3 animalWorldPos) {
        BestAnchor best = new BestAnchor();

        considerAnchor(level, ship0, animalWorldPos,
                VsShipTransforms.shipyardToWorld(ship0, knotPos),
                "knot_as_shipyard", best);

        considerAnchor(level, ship0, animalWorldPos,
                knotPos,
                "knot_as_world", best);

        if (best.anchorWorld == null) return new AnchorSolve(false, null, ship0, "no_candidate");
        if (!Double.isFinite(best.dist)) return new AnchorSolve(false, null, ship0, "nan_dist");
        if (best.dist > MAX_REASONABLE_DIST) {
            VsShipTransforms.clearCacheFor(ship0.getClass());
            return new AnchorSolve(false, best.anchorWorld, best.shipObj, "all_bad:" + best.mode);
        }

        return new AnchorSolve(true, best.anchorWorld, best.shipObj, best.mode);
    }

    private static void considerAnchor(ServerLevel level, Object fallbackShip, Vec3 animalWorldPos,
                                       Vec3 anchorWorld, String mode, BestAnchor best) {
        if (anchorWorld == null) return;
        Object shipHere = VsShipAccess.getShipManagingPos(level, anchorWorld).orElse(fallbackShip);

        Vec3 d = animalWorldPos.subtract(anchorWorld);
        d = new Vec3(d.x, 0.0, d.z);
        double dist = d.length();

        if (dist < best.dist) {
            best.dist = dist;
            best.anchorWorld = anchorWorld;
            best.shipObj = shipHere;
            best.mode = mode;
        }
    }

    private static void maybeWarnBadAnchor(ServerPlayer p, UUID animalId, Vec3 knotPos, Vec3 capAnchor) {
        int now = p.tickCount;
        int last = LAST_BAD_ANCHOR_TICK.getOrDefault(animalId, -999999);
        if (now - last < 40) return;

        LAST_BAD_ANCHOR_TICK.put(animalId, now);
        p.sendSystemMessage(Component.literal(
                "§7[ReinsDbg] §cAnchor solve failed. knot=" + fmt(knotPos) + " cap=" + fmt(capAnchor)
        ));
    }

    // Use true VS ship velocity (assumed world space, units likely blocks/sec). If unavailable, returns ZERO (safe).
    private static Vec3 getShipVelocity(Object ship) {
        try {
            var m = ship.getClass().getMethod("getVelocity");
            Object v = m.invoke(ship);
            if (v instanceof org.joml.Vector3dc j) {
                return new Vec3(j.x(), j.y(), j.z());
            }
        } catch (Throwable ignored) {}
        return Vec3.ZERO;
    }

    private static void cleanup(Animal animal) {
        UUID id = animal.getUUID();
        LAST_PULL_FORCE.remove(id);
        LAST_BAD_ANCHOR_TICK.remove(id);
        animal.getCapability(ReinedAnimalProvider.CAPABILITY).ifPresent(cap -> {
            cap.setLeashedToShip(false);
            cap.setShipFencePos(null);
            cap.setShipAnchorPos(null);
        });
    }

    private static void debug(ServerPlayer p, String msg) {
        if (p.tickCount % 10 == 0) {
            p.sendSystemMessage(Component.literal("§7[ReinsDbg] " + msg));
        }
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    private static String fmt(Vec3 v) {
        if (v == null) return "null";
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
