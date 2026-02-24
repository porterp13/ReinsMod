package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores leash-knot <-> ship anchor bindings for VS ships.
 *
 * Dedicated-server note:
 * - knot.position() is often shipyard-space (huge coords ~ -28M, +12M).
 * - For player-sampled binds, we STORE that shipyard-space knot position, then
 *   resolve it every tick with shipyardToWorld(ship, stored).
 */
public final class KnotShipAnchorRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnotShipAnchorRegistry.class);

    private static final Map<UUID, AnchorRecord> BY_KNOT = new ConcurrentHashMap<>();

    private static final double MAX_REASONABLE_COORD = 30_000_000.0;
    private static final double MAX_PLAYER_SAMPLE_RADIUS = 24.0;

    private KnotShipAnchorRegistry() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Best-effort bind with no player/hit hint. Works in some cases, often fails on dedicated (expected). */
    public static boolean rememberOrUpdate(final Level level, final LeashFenceKnotEntity knot) {
        return rememberOrUpdate(level, knot, null, null);
    }

    /**
     * Bind using optional clicked world position and/or player proximity sampling.
     *
     * @param clickedWorld optional hit world pos from attach event (can be null on dedicated)
     * @param player optional player who attached the knot (used for ship sampling)
     */
    public static boolean rememberOrUpdate(final Level level,
                                           final LeashFenceKnotEntity knot,
                                           final Vec3 clickedWorld,
                                           final Vec3 playerWorldSample) {
        if (!(level instanceof ServerLevel serverLevel) || knot == null) return false;

        final UUID knotId = knot.getUUID();
        final BlockPos knotBlockPos = knot.getPos();
        final Vec3 knotCenterRaw = Vec3.atCenterOf(knotBlockPos); // often bogus/shipyard on dedicated
        final Vec3 knotEntityPosRaw = knot.position();            // often shipyard on dedicated

        Object ship = null;
        Vec3 chosenWorldAnchor = null;
        String mode = "none";

        if (clickedWorld != null && !isBogusWorld(clickedWorld)) {
            ship = VsShipAccess.getShipManagingPos(serverLevel, clickedWorld).orElse(null);
            if (ship != null) {
                chosenWorldAnchor = clickedWorld;
                mode = "clickedWorld";
            }
        }

        if (ship == null && !isBogusWorld(knotCenterRaw)) {
            ship = VsShipAccess.getShipManagingPos(serverLevel, knotCenterRaw).orElse(null);
            if (ship != null) {
                chosenWorldAnchor = knotCenterRaw;
                mode = "knotCenterWorld";
            }
        }

        if (ship == null && clickedWorld != null && isBogusWorld(clickedWorld) && playerWorldSample != null) {
            ShipPick shipyardPick = pickShipFromShipyardPoint(serverLevel, clickedWorld, playerWorldSample);
            if (shipyardPick != null && shipyardPick.ship != null) {
                ship = shipyardPick.ship;
                chosenWorldAnchor = shipyardPick.sampleWorld; // resolved world-space anchor for debug/fallback
                mode = "shipyardClick+closestToPlayer";
            }
        }

        // 2) Dedicated fallback: sample around player to find which ship is actually nearby.
        //    Once we know the ship, we store knot.position() (shipyard-space) and resolve later via shipyardToWorld.
        if (ship == null && playerWorldSample != null) {
            ShipPick pick = pickShipNearPlayer(serverLevel, playerWorldSample);
            if (pick != null && pick.ship != null) {
                ship = pick.ship;
                chosenWorldAnchor = pick.sampleWorld; // debug/fallback only
                mode = "playerSample";
            }
        }

        if (ship == null) {
            LOGGER.warn("[ReinsMod VS] rememberOrUpdate(no-hint) refused for knot={} (needs world hint)", shortId(knotId));
            return false;
        }

        final long shipId = VsShipAccess.getShipId(ship);

        // What we store depends on mode:
        // - playerSample: store shipyard-space knot entity pos (huge coords), resolve via shipyardToWorld later
        // - direct world modes: try true world->ship local; if that fails, still fall back to shipyard storage
        final AnchorKind anchorKind;
        final Vec3 anchorStored;

        if ("playerSample".equals(mode) || "shipyardClick+closestToPlayer".equals(mode)) {
            // IMPORTANT:
            // In these modes, chosenWorldAnchor is only a SHIP SELECTION hint (near player),
            // not the actual leash anchor. The real anchor is the knot's entity position,
            // which is often in shipyard space on dedicated servers.
            anchorKind = AnchorKind.SHIPYARD_POS;
            anchorStored = knotEntityPosRaw;
        } else {
            // Direct world-hit modes can try true world->ship local
            Vec3 local = null;
            try {
                local = VsShipTransforms.worldToShipyard(ship, chosenWorldAnchor);
            } catch (Throwable ignored) {}

            if (local != null) {
                anchorKind = AnchorKind.SHIP_LOCAL;
                anchorStored = local;
            } else {
                // Fallback keeps things working even if worldToShip is unreliable on this VS build
                anchorKind = AnchorKind.SHIPYARD_POS;
                anchorStored = knotEntityPosRaw;
                mode = mode + "+shipyardFallback";
            }
        }

        if (anchorStored == null) {
            BY_KNOT.remove(knotId);
            LOGGER.warn("[ReinsMod VS] rememberOrUpdate failed to compute storable anchor for knot={}", shortId(knotId));
            return false;
        }

        final Vec3 debugWorld = (chosenWorldAnchor != null ? chosenWorldAnchor : knotCenterRaw);

        final AnchorRecord rec = new AnchorRecord(
            knotId,
            shipId,
            anchorStored, // name kept anchorLocal for compatibility with existing callers
            anchorKind,
            debugWorld,
            serverLevel.dimension().location().toString(),
            serverLevel.getGameTime()
        );

        BY_KNOT.put(knotId, rec);

        LOGGER.info(
            "[ReinsMod VS] remember knot={} shipId={} mode={} local=({}, {}, {}) world=({}, {}, {})",
            shortId(knotId),
            shipId,
            mode,
            fmt(anchorStored.x), fmt(anchorStored.y), fmt(anchorStored.z),
            fmt(debugWorld.x), fmt(debugWorld.y), fmt(debugWorld.z)
        );

        return true;
    }

    public static Optional<ResolvedAnchor> resolve(final Level level, final LeashFenceKnotEntity knot, final boolean createIfMissing) {
        if (!(level instanceof ServerLevel serverLevel) || knot == null) return Optional.empty();

        final UUID knotId = knot.getUUID();
        AnchorRecord rec = BY_KNOT.get(knotId);

        if (rec == null) {
            if (!createIfMissing) return Optional.empty();

            // Best-effort create from current knot only (often fails on dedicated, but harmless)
            rememberOrUpdate(serverLevel, knot, null, null);
            rec = BY_KNOT.get(knotId);
            if (rec == null) return Optional.empty();
        }

        final String currentDim = serverLevel.dimension().location().toString();
        if (!currentDim.equals(rec.dimensionId())) {
            BY_KNOT.remove(knotId);
            return Optional.empty();
        }

        Object ship = VsShipAccess.findLoadedShipById(serverLevel, rec.shipId());

        // Reconnect / reload recovery:
        // If the stored ship isn't found, try re-resolving from the knot using last known world anchor.
        if (ship == null) {
            Vec3 hint = rec.lastKnownFenceCenterWorld();
            boolean rebound = rememberOrUpdate(serverLevel, knot, hint, hint);
            if (rebound) {
                rec = BY_KNOT.get(knotId);
                if (rec != null) {
                    ship = VsShipAccess.findLoadedShipById(serverLevel, rec.shipId());
                }
            }
        }

        if (ship == null) return Optional.empty();

        Vec3 anchorWorld = null;

        try {
            anchorWorld = VsShipTransforms.shipyardToWorld(ship, rec.anchorLocal());
        } catch (Throwable ignored) {}

        // Fallback to last known sampled world anchor if transform failed
        if (anchorWorld == null || isBogusWorld(anchorWorld)) {
            anchorWorld = rec.lastKnownFenceCenterWorld();
        }

        // If still bad, try one more rebind using last known world anchor
        if (anchorWorld == null || isBogusWorld(anchorWorld)) {
            Vec3 hint = rec.lastKnownFenceCenterWorld();
            boolean rebound = rememberOrUpdate(serverLevel, knot, hint, hint);
            if (rebound) {
                rec = BY_KNOT.get(knotId);
                if (rec != null) {
                    ship = VsShipAccess.findLoadedShipById(serverLevel, rec.shipId());
                    if (ship != null) {
                        try {
                            anchorWorld = (rec.anchorKind() == AnchorKind.SHIP_LOCAL)
                                    ? VsShipAccess.shipToWorld(ship, rec.anchorLocal())
                                    : VsShipTransforms.shipyardToWorld(ship, rec.anchorLocal());
                        } catch (Throwable ignored) {}

                        if (anchorWorld == null || isBogusWorld(anchorWorld)) {
                            anchorWorld = rec.lastKnownFenceCenterWorld();
                        }
                    }
                }
            }
        }

        if (anchorWorld == null || isBogusWorld(anchorWorld)) {
            LOGGER.warn(
                "[ReinsMod VS] resolve bad anchor world knot={} shipId={} stored=({}, {}, {}) kind={}",
                shortId(knotId),
                rec.shipId(),
                fmt(rec.anchorLocal().x), fmt(rec.anchorLocal().y), fmt(rec.anchorLocal().z),
                rec.anchorKind()
            );
            return Optional.empty();
        }

        return Optional.of(new ResolvedAnchor(rec, ship, anchorWorld, ResolveMode.RECORDED_SHIP_LOCAL));
    }

    public static Optional<ResolvedAnchor> resolve(final Level level, final LeashFenceKnotEntity knot) {
        return resolve(level, knot, false);
    }

    public static void forget(final LeashFenceKnotEntity knot) {
        if (knot == null) return;
        BY_KNOT.remove(knot.getUUID());
    }

    public static void clear() {
        BY_KNOT.clear();
    }

    public static int size() {
        return BY_KNOT.size();
    }

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    public enum ResolveMode {
        RECORDED_SHIP_LOCAL
    }

    /** How to interpret anchorLocal() in the record. */
    public enum AnchorKind {
        SHIP_LOCAL,    // true ship-local coords; resolve with shipToWorld
        SHIPYARD_POS   // shipyard/world-huge coords; resolve with shipyardToWorld
    }

    public record AnchorRecord(
        UUID knotUuid,
        long shipId,
        Vec3 anchorLocal, // name kept for compatibility with existing code
        AnchorKind anchorKind,
        Vec3 lastKnownFenceCenterWorld,
        String dimensionId,
        long updatedGameTime
    ) {}

    public record ResolvedAnchor(
        AnchorRecord record,
        Object shipOrNull,
        Vec3 anchorWorld,
        ResolveMode mode
    ) {}

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final class ShipPick {
        final Object ship;
        final Vec3 sampleWorld;
        final double dist2;

        ShipPick(Object ship, Vec3 sampleWorld, double dist2) {
            this.ship = ship;
            this.sampleWorld = sampleWorld;
            this.dist2 = dist2;
        }
    }

    /**
     * Sample a small grid around the player and choose the closest hit ship.
     * This is the dedicated-safe fallback that identified shipId=3 vs 187 in your logs.
     */
    private static ShipPick pickShipNearPlayer(final ServerLevel level, final Vec3 fallbackWorldHint) {
        final Vec3 p = fallbackWorldHint;
        final double y = p.y;

        ShipPick best = null;

        // sample center + cardinals at multiple radii
        final double[] radii = {0.0, 4.0, 8.0, 12.0};
        final int[][] dirs = {
            {0, 0},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };

        for (double r : radii) {
            for (int[] d : dirs) {
                final Vec3 s = new Vec3(p.x + d[0] * r, y, p.z + d[1] * r);

                Object ship = null;
                try {
                    ship = VsShipAccess.getShipManagingPos(level, s).orElse(null);
                } catch (Throwable ignored) {}

                if (ship == null) continue;

                final double dx = s.x - p.x;
                final double dz = s.z - p.z;
                final double dist2 = dx * dx + dz * dz;

                if (dist2 > (MAX_PLAYER_SAMPLE_RADIUS * MAX_PLAYER_SAMPLE_RADIUS)) continue;

                if (best == null || dist2 < best.dist2) {
                    best = new ShipPick(ship, s, dist2);
                }
            }
        }

        return best;
    }

    private static boolean isBogusWorld(final Vec3 v) {
        if (v == null) return true;
        return !Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)
            || Math.abs(v.x) > MAX_REASONABLE_COORD
            || Math.abs(v.y) > MAX_REASONABLE_COORD
            || Math.abs(v.z) > MAX_REASONABLE_COORD;
    }

    private static String fmt(final double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    private static String shortId(final UUID id) {
        final String s = id.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    private static ShipPick pickShipFromShipyardPoint(final ServerLevel level, final Vec3 shipyardPos, final Vec3 playerWorld) {
        Iterable<Object> ships;
        try {
            ships = VsShipAccess.getLoadedShips(level);
        } catch (Throwable t) {
            return null;
        }

        ShipPick best = null;

        for (Object ship : ships) {
            if (ship == null) continue;

            Vec3 world;
            try {
                world = VsShipTransforms.shipyardToWorld(ship, shipyardPos);
            } catch (Throwable ignored) {
                continue;
            }

            if (world == null || isBogusWorld(world)) continue;

            double dx = world.x - playerWorld.x;
            double dy = world.y - playerWorld.y;
            double dz = world.z - playerWorld.z;
            double dist2 = dx * dx + dy * dy + dz * dz;

            if (best == null || dist2 < best.dist2) {
                best = new ShipPick(ship, world, dist2);
            }
        }

        return best;
    }
}