package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
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
    private static final double BASE_INTENT_FORCE_PER_TON = 60_000.0;
    private static final double INTENT_FORCE_MASS_EXPONENT = 0.75;
    private static final double MAX_INTENT_FORCE = 8_000_000.0;
    private static final double MIN_INTENT_FORCE = 5_000.0;

    // Stability / anti-spike
    private static final double FORCE_SMOOTHING = 0.75;
    private static final double MIN_FORCE_MASS_MULT = 2.0;
    private static final double FORCE_RATE_LIMIT_MULT_LIGHT = 400.0;
    private static final double FORCE_RATE_LIMIT_MULT_HEAVY = 220.0;

    // Braking / stability constants
    private static final double STOP_EPS = 0.03;
    private static final double SPEED_EPS = 0.02;
    private static final double VELOCITY_EPS = 0.015;
    private static final double MAX_FORCE = 1_000_000.0;
    private static final double MAX_REASONABLE_DIST = 128.0;

    private static final ConcurrentHashMap<UUID, Double> LAST_FORCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> LAST_BAD_ANCHOR_TICK = new ConcurrentHashMap<>();

    private ShipLeashPhysicsTick() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

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
                    Object ship0 = VsShipAccess.getShipManagingPos(level, knotPos).orElse(null);
                    if (ship0 == null) return;

                    AnchorSolve solved = resolveAnchorWorld(level, ship0, knotPos, animal.position());
                    if (!solved.ok || solved.anchorWorld == null) {
                        maybeWarnBadAnchor(player, animal.getUUID(), knotPos, cap.getShipAnchorPos());
                        return;
                    }

                    Vec3 anchorWorld = solved.anchorWorld;
                    Object ship = solved.shipObj;

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

                    boolean playerControlled = animal.isNoAi();
                    double slack = playerControlled ? PLAYER_SLACK : SLACK;
                    double stretch = dist - slack;

                    Vec3 dir = delta.scale(1.0 / dist);

                    Vec3 animalVel = animal.getDeltaMovement();
                    double animalSpeedAlong = animalVel.dot(dir);

                    Vec3 shipVel = getShipVelocity(ship);
                    double shipSpeedAlong = shipVel.dot(dir);

                    double shipMass = VsShipMass.getShipMass(ship);
                    if (shipMass <= 0) shipMass = 20_000.0;

                    // ------------------------------------------------------------------
                    // HARD STOP
                    // ------------------------------------------------------------------
                    if (playerControlled && animalSpeedAlong < STOP_EPS) {
                        if (shipSpeedAlong > 0.0) {
                            Vec3 hardStopForce = dir.scale(-shipSpeedAlong * shipMass * 2);
                            VsShipForces.applyWorldForce(ship, hardStopForce, anchorWorld);
                        }

                        double animalSpeed = animalVel.length();
                        double shipSpeed = shipVel.length();
                        if (shipSpeed > animalSpeed + SPEED_EPS && shipSpeed > 1.0e-6) {
                            Vec3 excess = shipVel.normalize().scale(shipSpeed - animalSpeed);
                            VsShipForces.applyWorldForce(ship, excess.scale(-shipMass), anchorWorld);
                        }

                        if (animal.tickCount % 20 == 0) {
                            debug(player,
                                    "STOP clamp dist=" + fmt(dist)
                                            + " shipAlong=" + fmt(shipSpeedAlong)
                                            + " animalAlong=" + fmt(animalSpeedAlong)
                                            + " mode=" + solved.mode
                            );
                        }
                        return;
                    }

                    // ------------------------------------------------------------------
                    // Pull force calculation
                    // ------------------------------------------------------------------
                    double minForce = Math.max(1.0, shipMass * MIN_FORCE_MASS_MULT);
                    double targetForce = 0.0;
                    double intentForce = 0.0;

                    if (playerControlled) {
                        double intent = Math.max(0.0, animalSpeedAlong);

                        // Small breakaway bias for heavy / stuck ships
                        if (intent < 0.08 && shipSpeedAlong < 0.15) {
                            intent = Math.max(intent, 0.12);
                        }

                        if (intent > 0.01) {
                            double shipTons = shipMass / 1000.0;
                            double intentBase = intent * BASE_INTENT_FORCE_PER_TON;
                            double massFactor = Math.pow(shipTons, INTENT_FORCE_MASS_EXPONENT);

                            // No light-ship bonus — pure proportional scaling

                            intentForce = intentBase * massFactor;
                            intentForce = Math.min(MAX_INTENT_FORCE, Math.max(MIN_INTENT_FORCE, intentForce));

                            double stretchForce = Math.max(0.0, stretch) * SPRING * 6.0;
                            targetForce = Math.max(intentForce + stretchForce, minForce);
                        }
                    } else {
                        if (stretch > 0.0) {
                            double relVel = animalVel.dot(dir);
                            targetForce = Math.max((stretch * SPRING) + (relVel * DAMPING), 0.0);
                        }
                    }

                    // ------------------------------------------------------------------
                    // Smooth + rate limit
                    // ------------------------------------------------------------------
                    UUID key = animal.getUUID();
                    double prev = LAST_FORCE.getOrDefault(key, targetForce);

                    double smoothed = prev + (targetForce - prev) * FORCE_SMOOTHING;

                    double rateLimit = shipMass < 80_000 ? FORCE_RATE_LIMIT_MULT_LIGHT : FORCE_RATE_LIMIT_MULT_HEAVY;
                    double maxDelta = shipMass * rateLimit;

                    double df = smoothed - prev;
                    if (df > maxDelta) df = maxDelta;
                    if (df < -maxDelta) df = -maxDelta;

                    double forceMag = prev + df;
                    forceMag = Math.min(MAX_FORCE, Math.max(0.0, forceMag));

                    LAST_FORCE.put(key, forceMag);

                    if (forceMag > 0.0) {
                        VsShipForces.applyWorldForce(ship, dir.scale(forceMag), anchorWorld);
                    }

                    // ------------------------------------------------------------------
                    // HARD SPEED CAP: ship may not move faster than animal overall
                    // ------------------------------------------------------------------
                    if (playerControlled) {
                        double animalSpeed = animalVel.length();
                        double shipSpeed = shipVel.length();
                        if (shipSpeed > animalSpeed + SPEED_EPS && shipSpeed > 1.0e-6) {
                            Vec3 excess = shipVel.normalize().scale(shipSpeed - animalSpeed);
                            VsShipForces.applyWorldForce(ship, excess.scale(-shipMass), anchorWorld);
                        }
                    }

                    // ------------------------------------------------------------------
                    // Along-rein clamp
                    // ------------------------------------------------------------------
                    if (playerControlled && shipSpeedAlong > animalSpeedAlong + VELOCITY_EPS) {
                        double allowed = animalSpeedAlong + VELOCITY_EPS;
                        double remove = shipSpeedAlong - allowed;
                        Vec3 clamp = dir.scale(-remove * shipMass);
                        VsShipForces.applyWorldForce(ship, clamp, anchorWorld);
                    }

                    // ------------------------------------------------------------------
                    // MATCH HORSE SPEED TO SHIP if ship is ahead / faster
                    // Prevents ship overtaking / running over horses
                    // ------------------------------------------------------------------
                    if (playerControlled) {
                        double animalSpeed = animalVel.length();
                        double shipSpeed = shipVel.length();

                        boolean shipTooFastOverall = shipSpeed > animalSpeed + SPEED_EPS;
                        boolean shipTooFastAlong   = shipSpeedAlong > animalSpeedAlong + VELOCITY_EPS;

                        if (shipTooFastOverall || shipTooFastAlong) {
                            // Match horse's horizontal velocity to ship's (preserve vertical for jumps/falls)
                            Vec3 shipHorizontalVel = new Vec3(shipVel.x, 0, shipVel.z);
                            animal.setDeltaMovement(shipHorizontalVel);
                            animal.hurtMarked = true;

                            // Tiny upward nudge if on ground and ship has upward momentum
                            if (animal.onGround() && shipVel.y > 0.01) {
                                animal.setDeltaMovement(animal.getDeltaMovement().add(0, 0.02, 0));
                            }

                            if (animal.tickCount % 5 == 0) {
                                debug(player,
                                        "§eSPEED MATCH: horse synced to ship | " +
                                        "shipSpd=" + fmt(shipSpeed) +
                                        " horseSpd=" + fmt(animalSpeed) +
                                        " alongDiff=" + fmt(shipSpeedAlong - animalSpeedAlong)
                                );
                            }
                        }
                    }

                    // ------------------------------------------------------------------
                    // Debug
                    // ------------------------------------------------------------------
                    if (animal.tickCount % 20 == 0) {
                        double shipTons = shipMass / 1000.0;
                        debug(player,
                                "dist=" + fmt(dist)
                                        + " stretch=" + fmt(stretch)
                                        + " F=" + fmt(forceMag)
                                        + " shipAlong=" + fmt(shipSpeedAlong)
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

    // Utils
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
        LAST_FORCE.remove(id);
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
        return String.format("%.2f", d);
    }

    private static String fmt(Vec3 v) {
        if (v == null) return "null";
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}