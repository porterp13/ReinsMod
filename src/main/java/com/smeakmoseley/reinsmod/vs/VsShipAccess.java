package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Collection;

/**
 * Dedicated-server safe ship lookup for Valkyrien Skies.
 *
 * Key idea for YOUR VS build (ShipObjectWorld = org.valkyrienskies.core.impl.shadow.Er):
 *   use ShipObjectWorld.getShipObjectsIntersecting(AABBdc) as the reliable "ship at position" query,
 *   because getShipManagingPos(...) is not present.
 *
 * This solves multi-ship selection deterministically.
 */
public final class VsShipAccess {
    private static final Logger LOGGER = LogUtils.getLogger();

    private enum PosKind { BLOCKPOS, VEC3, JOML3D }

    private static volatile boolean RESOLVED = false;

    // Level/ServerLevel -> ShipObjectWorld
    private static volatile Method GET_SHIP_OBJECT_WORLD = null;

    // ShipObjectWorld -> direct lookup (if present in some builds)
    private static volatile Method SOW_LOOKUP = null;
    private static volatile PosKind SOW_LOOKUP_KIND = null;

    // ShipObjectWorld -> intersection lookup (present in your build)
    private static volatile Method SOW_INTERSECT = null;

    // ShipObjectWorld -> loadedShips getter (last-resort fallback)
    private static volatile Method SOW_GET_LOADED_SHIPS = null;

    // Small radius box around a point for intersection queries
    private static final double INTERSECT_RADIUS = 0.25;

    private static Method LOADED_SHIPS_GETTER;      // ShipObjectWorld.getLoadedShips()
    private static Method SHIP_GET_ID;              // ship.getId()
    private static Method SHIP_TO_WORLD;            // transform position ship->world (runtime-specific)
    private static Method WORLD_TO_SHIP;            // transform position world->ship (runtime-specific)

    private static Method SHIP_GET_TRANSFORM = null;
    private static Method TRANSFORM_GET_SHIP_TO_WORLD = null;
    private static Method TRANSFORM_GET_WORLD_TO_SHIP = null;
    private static Method SHIP_GET_SHIP_TO_WORLD = null;
    private static Method SHIP_GET_WORLD_TO_SHIP = null;
    private static Method MATRIX_TRANSFORM_POSITION_1 = null; // transformPosition(Vector3d) -> Vector3d?
    private static Method MATRIX_TRANSFORM_POSITION_2 = null; // transformPosition(Vector3dc, Vector3d) -> Vector3d?

    private VsShipAccess() {}

    public static Optional<Object> getShipManagingPos(ServerLevel level, Vec3 pos) {
        if (level == null || pos == null) return Optional.empty();
        ensureResolved(level);

        Object sow = getShipObjectWorld(level);
        if (sow == null) return Optional.empty();

        // Try direct lookup if it exists (rare in your build)
        Object ship = tryLookupOnSow(sow, level, pos);
        if (ship != null) return Optional.of(ship);

        // Try intersect at the provided pos
        ship = tryIntersectOnSow(sow, pos);
        if (ship != null) return Optional.of(ship);

        return Optional.empty();
    }

    public static Optional<Object> getShipManagingPos(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return Optional.empty();
        return getShipManagingPos(level, Vec3.atCenterOf(pos));
    }

    // ------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------

    private static void ensureResolved(ServerLevel level) {
        if (RESOLVED) return;
        synchronized (VsShipAccess.class) {
            if (RESOLVED) return;

            // Bind level.getShipObjectWorld() by walking class chain
            GET_SHIP_OBJECT_WORLD = findNoArgMethod(level.getClass(), "getShipObjectWorld");

            Object sow = (GET_SHIP_OBJECT_WORLD != null) ? getShipObjectWorld(level) : null;

            if (GET_SHIP_OBJECT_WORLD != null) {
                LOGGER.info("[ReinsMod VS] Bound Level.getShipObjectWorld() on {}", level.getClass().getName());
            } else {
                LOGGER.warn("[ReinsMod VS] Could not find getShipObjectWorld() on ServerLevel/Level class chain.");
            }

            if (sow != null) {
                resolveSowMethods(sow.getClass());
                LOGGER.info("[ReinsMod VS] ShipObjectWorld runtime class = {}", sow.getClass().getName());
            } else {
                SOW_LOOKUP = null;
                SOW_LOOKUP_KIND = null;
                SOW_INTERSECT = null;
                SOW_GET_LOADED_SHIPS = null;
            }

            if (SOW_LOOKUP != null) {
                LOGGER.info("[ReinsMod VS] ShipObjectWorld direct lookup bound: {}.{}({})",
                        SOW_LOOKUP.getDeclaringClass().getName(),
                        SOW_LOOKUP.getName(),
                        SOW_LOOKUP.getParameterCount() == 0 ? "?" :
                                SOW_LOOKUP.getParameterTypes()[SOW_LOOKUP.getParameterCount() - 1].getSimpleName());
            } else {
                LOGGER.warn("[ReinsMod VS] ShipObjectWorld direct lookup NOT resolved (ok on your build).");
            }

            if (SOW_INTERSECT != null) {
                LOGGER.info("[ReinsMod VS] ShipObjectWorld intersect bound: {}.{}({})",
                        SOW_INTERSECT.getDeclaringClass().getName(),
                        SOW_INTERSECT.getName(),
                        SOW_INTERSECT.getParameterTypes()[0].getSimpleName());
            } else {
                LOGGER.warn("[ReinsMod VS] ShipObjectWorld intersect NOT resolved (this will break multi-ship).");
            }

            if (SOW_GET_LOADED_SHIPS != null) {
                LOGGER.info("[ReinsMod VS] ShipObjectWorld loadedShips getter bound: {}.{}()",
                        SOW_GET_LOADED_SHIPS.getDeclaringClass().getName(),
                        SOW_GET_LOADED_SHIPS.getName());
            } else {
                LOGGER.warn("[ReinsMod VS] ShipObjectWorld loadedShips getter NOT resolved (cannot fallback).");
            }

            RESOLVED = true;
        }
    }

    private static Method findNoArgMethod(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null) {
            try {
                Method m = c.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object getShipObjectWorld(ServerLevel level) {
        if (GET_SHIP_OBJECT_WORLD == null) return null;
        try {
            return GET_SHIP_OBJECT_WORLD.invoke(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void resolveSowMethods(Class<?> sowClass) {
        // A) Try to find any "ship at position" method by scanning public methods once (safe).
        //    Your build likely won't have one, but this keeps the class portable.
        SOW_LOOKUP = null;
        SOW_LOOKUP_KIND = null;

        String[] preferredNames = {"getShipManagingPos", "getShipAtPos", "getShipAtPosition", "getShipAt"};

        Method best = null;
        PosKind bestKind = null;
        int bestScore = Integer.MIN_VALUE;

        Class<?> v3dc = tryLoad("org.joml.Vector3dc");
        Class<?> v3d  = tryLoad("org.joml.Vector3d");

        for (Method m : sowClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            int pc = m.getParameterCount();
            if (!(pc == 1 || pc == 2)) continue;

            // Accept (pos) OR (level,pos)
            Class<?> p0 = m.getParameterTypes()[0];
            Class<?> p1 = (pc == 2) ? m.getParameterTypes()[1] : null;

            boolean okLevelPrefix = (pc == 1) || (Level.class.isAssignableFrom(p0));
            Class<?> posType = (pc == 1) ? p0 : p1;
            if (!okLevelPrefix || posType == null) continue;

            PosKind kind = null;
            if (Vec3.class.isAssignableFrom(posType)) kind = PosKind.VEC3;
            else if (BlockPos.class.isAssignableFrom(posType)) kind = PosKind.BLOCKPOS;
            else if (v3dc != null && v3dc.isAssignableFrom(posType)) kind = PosKind.JOML3D;
            else if (v3d != null && v3d.isAssignableFrom(posType)) kind = PosKind.JOML3D;

            if (kind == null) continue;
            if (m.getReturnType() == void.class) continue;

            int score = 0;
            String name = m.getName();
            String lower = name.toLowerCase(Locale.ROOT);

            for (String pref : preferredNames) {
                if (name.equals(pref)) score += 500;
                if (lower.contains(pref.toLowerCase(Locale.ROOT))) score += 200;
            }
            if (pc == 1) score += 50;
            if (kind == PosKind.VEC3) score += 30;
            if (kind == PosKind.JOML3D) score += 10;

            if (score > bestScore) {
                bestScore = score;
                best = m;
                bestKind = kind;
            }
        }

        if (best != null) {
            best.setAccessible(true);
            SOW_LOOKUP = best;
            SOW_LOOKUP_KIND = bestKind;
        }

        // B) Bind getShipObjectsIntersecting(AABBdc) (THIS is the reliable one in your runtime)
        try {
            Class<?> aabbdc = Class.forName("org.joml.primitives.AABBdc");
            Method m = sowClass.getMethod("getShipObjectsIntersecting", aabbdc);
            m.setAccessible(true);
            SOW_INTERSECT = m;
        } catch (Throwable t) {
            SOW_INTERSECT = null;
        }

        // C) loadedShips getter (fallback)
        Method ls = tryInstanceNoArgs(sowClass, "getLoadedShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "loadedShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "getShips");
        if (ls == null) ls = tryInstanceNoArgs(sowClass, "ships");
        SOW_GET_LOADED_SHIPS = ls;
    }

    private static Method tryInstance(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            if (Modifier.isStatic(m.getModifiers())) return null;
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method tryInstanceNoArgs(Class<?> owner, String name) {
        return tryInstance(owner, name);
    }

    private static Class<?> tryLoad(String cn) {
        try { return Class.forName(cn); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------
    // Using ShipObjectWorld
    // ------------------------------------------------------------

    private static Object tryLookupOnSow(Object sow, ServerLevel level, Vec3 worldPos) {
        if (sow == null || SOW_LOOKUP == null) return null;

        try {
            Object result;
            Class<?>[] p = SOW_LOOKUP.getParameterTypes();

            if (p.length == 1) {
                result = switch (SOW_LOOKUP_KIND) {
                    case VEC3 -> SOW_LOOKUP.invoke(sow, worldPos);
                    case JOML3D -> SOW_LOOKUP.invoke(sow, new Vector3d(worldPos.x, worldPos.y, worldPos.z));
                    case BLOCKPOS -> SOW_LOOKUP.invoke(sow, BlockPos.containing(worldPos));
                };
            } else if (p.length == 2) {
                Object posArg = switch (SOW_LOOKUP_KIND) {
                    case VEC3 -> worldPos;
                    case JOML3D -> new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                    case BLOCKPOS -> BlockPos.containing(worldPos);
                };
                result = SOW_LOOKUP.invoke(sow, level, posArg);
            } else {
                return null;
            }

            if (result == null) return null;
            if (result instanceof Optional<?> opt) return opt.orElse(null);
            return result;

        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The multi-ship-safe query: getShipObjectsIntersecting(AABBdc) around the world position.
     */
    private static Object tryIntersectOnSow(Object sow, Vec3 worldPos) {
        if (sow == null || SOW_INTERSECT == null || worldPos == null) return null;

        try {
            Object box = makeAABBdc(
                    worldPos.x - INTERSECT_RADIUS, worldPos.y - INTERSECT_RADIUS, worldPos.z - INTERSECT_RADIUS,
                    worldPos.x + INTERSECT_RADIUS, worldPos.y + INTERSECT_RADIUS, worldPos.z + INTERSECT_RADIUS
            );
            if (box == null) return null;

            Object result = SOW_INTERSECT.invoke(sow, box);
            if (!(result instanceof java.util.List<?> list) || list.isEmpty()) {
                if (DEBUG_INTERSECT) {
                    LOGGER.info("[ReinsMod VS] intersect hits=0 pos={}", worldPos);
                }
                return null;
            }

            if (DEBUG_INTERSECT) {
                StringBuilder sb = new StringBuilder();
                sb.append("[ReinsMod VS] intersect hits=").append(list.size()).append(" pos=").append(worldPos).append(" ships=");
                for (Object s : list) {
                    long id = tryGetShipId(s).orElse(-1L);
                    sb.append(id).append(",");
                }
                LOGGER.info(sb.toString());
            }

            // Pick closest ship by its reported world position if possible
            Object best = null;
            double bestD2 = Double.POSITIVE_INFINITY;

            for (Object ship : list) {
                if (ship == null) continue;

                Vec3 sp = tryShipWorldPos(ship);
                if (sp == null) {
                    // Can't score -> but at least return a ship from the intersection list
                    return ship;
                }

                double dx = sp.x - worldPos.x;
                double dy = sp.y - worldPos.y;
                double dz = sp.z - worldPos.z;
                double d2 = dx * dx + dy * dy + dz * dz;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = ship;
                }
            }

            return best != null ? best : list.get(0);

        } catch (Throwable t) {
            return null;
        }
    }

    private static final boolean DEBUG_INTERSECT = true;

    public static Optional<Long> tryGetShipId(Object ship) {
        if (ship == null) return Optional.empty();
        try {
            if (SHIP_GET_ID == null) {
                // Try common method names first
                for (String name : new String[]{"getId", "getShipId", "getIdImpl"}) {
                    try {
                        Method m = ship.getClass().getMethod(name);
                        m.setAccessible(true);
                        SHIP_GET_ID = m;
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            if (SHIP_GET_ID == null) return Optional.empty();

            Object out = SHIP_GET_ID.invoke(ship);
            if (out instanceof Number n) return Optional.of(n.longValue());
        } catch (Throwable ignored) {}
        return Optional.empty();
    }

    /**
     * Build an AABBdc instance via reflection.
     * Uses org.joml.primitives.AABBd (implements AABBdc) constructor:
     *   AABBd(minX, minY, minZ, maxX, maxY, maxZ)
     */
    private static Object makeAABBdc(double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        try {
            Class<?> aabbd = Class.forName("org.joml.primitives.AABBd");
            return aabbd.getConstructor(
                    double.class, double.class, double.class,
                    double.class, double.class, double.class
            ).newInstance(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tryNearestFromLoadedShips(Object sow, Vec3 worldPos) {
        if (sow == null || SOW_GET_LOADED_SHIPS == null) return null;

        try {
            Object loaded = SOW_GET_LOADED_SHIPS.invoke(sow);
            if (loaded == null) return null;

            Iterable<?> it = asIterable(loaded);
            if (it == null) return null;

            Object bestShip = null;
            double bestD2 = Double.POSITIVE_INFINITY;

            for (Object ship : it) {
                if (ship == null) continue;

                Vec3 shipPos = tryShipWorldPos(ship);
                if (shipPos == null) continue;

                double dx = shipPos.x - worldPos.x;
                double dz = shipPos.z - worldPos.z;
                double d2 = dx * dx + dz * dz;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestShip = ship;
                }
            }

            return bestShip;

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Iterable<?> asIterable(Object o) {
        if (o instanceof Iterable<?> i) return i;
        if (o != null && o.getClass().isArray()) {
            return () -> new Iterator<>() {
                final int len = java.lang.reflect.Array.getLength(o);
                int idx = 0;
                @Override public boolean hasNext() { return idx < len; }
                @Override public Object next() { return java.lang.reflect.Array.get(o, idx++); }
            };
        }
        return null;
    }

    /**
     * Best-effort ship WORLD position used for scoring candidates.
     * Tries common patterns with exact method calls (no heavy enumeration).
     */
    private static Vec3 tryShipWorldPos(Object ship) {
        // getPosition(): Vector3dc or Vector3d
        try {
            Method m = ship.getClass().getMethod("getPosition");
            Object v = m.invoke(ship);
            Vec3 vv = toVec3(v);
            if (vv != null) return vv;
        } catch (Throwable ignored) {}

        // getTransform().getPosition()
        try {
            Method gt = ship.getClass().getMethod("getTransform");
            Object tr = gt.invoke(ship);
            if (tr != null) {
                try {
                    Method gp = tr.getClass().getMethod("getPosition");
                    Object v = gp.invoke(tr);
                    Vec3 vv = toVec3(v);
                    if (vv != null) return vv;
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Vec3 toVec3(Object v) {
        if (v == null) return null;

        if (v instanceof Vector3d d) return new Vec3(d.x, d.y, d.z);

        // Vector3dc: has x(), y(), z()
        try {
            if (v.getClass().getName().equals("org.joml.Vector3dc")) {
                Method x = v.getClass().getMethod("x");
                Method y = v.getClass().getMethod("y");
                Method z = v.getClass().getMethod("z");
                return new Vec3(
                        ((Number) x.invoke(v)).doubleValue(),
                        ((Number) y.invoke(v)).doubleValue(),
                        ((Number) z.invoke(v)).doubleValue()
                );
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static String debugShipId(Object ship) {
        if (ship == null) return "null";
        return ship.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(ship));
    }

    /**
     * Get loaded ship by numeric ship id. This uses your already-bound getLoadedShips().
     */
    public static Optional<Object> getLoadedShipById(ServerLevel level, long shipId) {
        try {
            Object shipWorld = getShipObjectWorld(level); // you likely already have this internally
            if (shipWorld == null) return Optional.empty();

            if (LOADED_SHIPS_GETTER == null) {
                // you already log "ShipObjectWorld loadedShips getter bound..."
                LOADED_SHIPS_GETTER = shipWorld.getClass().getMethod("getLoadedShips");
                LOADED_SHIPS_GETTER.setAccessible(true);
            }

            Object loaded = LOADED_SHIPS_GETTER.invoke(shipWorld);
            if (!(loaded instanceof Collection<?> ships)) return Optional.empty();

            for (Object ship : ships) {
                long id = tryGetShipId(ship).orElse(Long.MIN_VALUE);
                if (id == shipId) return Optional.of(ship);
            }
        } catch (Throwable t) {
            // keep this quiet or debug-level if spammy
        }
        return Optional.empty();
    }

    /**
     * Convert ship-local -> world. Wire this to your VS runtime transform access.
     * For now this includes a safe fallback (returns input) so code compiles while you hook it up.
     */
    public static Vec3 shipToWorld(Object ship, Vec3 shipLocal) {
        if (ship == null || shipLocal == null) return null;
        try {
            Object mat = getShipToWorldMatrix(ship);
            if (mat == null) return null;
            return applyMatrixTransformPosition(mat, shipLocal);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Convert world -> ship-local. Wire this to your VS runtime transform access.
     * For now this includes a safe fallback (returns input) so code compiles while you hook it up.
     */
    public static Vec3 worldToShip(Object ship, Vec3 worldPos) {
        if (ship == null || worldPos == null) return null;
        try {
            Object mat = getWorldToShipMatrix(ship);
            if (mat == null) return null;
            return applyMatrixTransformPosition(mat, worldPos);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object findShipManagingPos(ServerLevel level, Vec3 pos) {
        return getShipManagingPos(level, pos).orElse(null);
    }

    public static long getShipId(Object ship) {
        return tryGetShipId(ship).orElse(-1L);
    }

    public static Object findLoadedShipById(ServerLevel level, long shipId) {
        return getLoadedShipById(level, shipId).orElse(null);
    }

    // ---- matrix access ----

    private static Object getShipToWorldMatrix(Object ship) {
        // Prefer ship.getTransform().getShipToWorld()
        try {
            Object tr = getShipTransform(ship);
            if (tr != null) {
                if (TRANSFORM_GET_SHIP_TO_WORLD == null) {
                    TRANSFORM_GET_SHIP_TO_WORLD = tryInstanceNoArgs(tr.getClass(), "getShipToWorld");
                }
                if (TRANSFORM_GET_SHIP_TO_WORLD != null) {
                    return TRANSFORM_GET_SHIP_TO_WORLD.invoke(tr);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: ship.getShipToWorld()
        try {
            if (SHIP_GET_SHIP_TO_WORLD == null) {
                SHIP_GET_SHIP_TO_WORLD = tryInstanceNoArgs(ship.getClass(), "getShipToWorld");
            }
            if (SHIP_GET_SHIP_TO_WORLD != null) {
                return SHIP_GET_SHIP_TO_WORLD.invoke(ship);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Object getWorldToShipMatrix(Object ship) {
        // Prefer ship.getTransform().getWorldToShip()
        try {
            Object tr = getShipTransform(ship);
            if (tr != null) {
                if (TRANSFORM_GET_WORLD_TO_SHIP == null) {
                    TRANSFORM_GET_WORLD_TO_SHIP = tryInstanceNoArgs(tr.getClass(), "getWorldToShip");
                }
                if (TRANSFORM_GET_WORLD_TO_SHIP != null) {
                    return TRANSFORM_GET_WORLD_TO_SHIP.invoke(tr);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: ship.getWorldToShip()
        try {
            if (SHIP_GET_WORLD_TO_SHIP == null) {
                SHIP_GET_WORLD_TO_SHIP = tryInstanceNoArgs(ship.getClass(), "getWorldToShip");
            }
            if (SHIP_GET_WORLD_TO_SHIP != null) {
                return SHIP_GET_WORLD_TO_SHIP.invoke(ship);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Object getShipTransform(Object ship) {
        try {
            if (SHIP_GET_TRANSFORM == null) {
                SHIP_GET_TRANSFORM = tryInstanceNoArgs(ship.getClass(), "getTransform");
            }
            if (SHIP_GET_TRANSFORM != null) {
                return SHIP_GET_TRANSFORM.invoke(ship);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---- matrix * position ----

    private static Vec3 applyMatrixTransformPosition(Object matrix, Vec3 in) {
        if (matrix == null || in == null) return null;

        try {
            Vector3d v = new Vector3d(in.x, in.y, in.z);

            // Try transformPosition(Vector3d)
            if (MATRIX_TRANSFORM_POSITION_1 == null) {
                MATRIX_TRANSFORM_POSITION_1 = tryMethod(matrix.getClass(), "transformPosition", Vector3d.class);
            }
            if (MATRIX_TRANSFORM_POSITION_1 != null) {
                Object out = MATRIX_TRANSFORM_POSITION_1.invoke(matrix, v);
                Vec3 vec = toVec3(out);
                if (vec != null) return vec;

                // Some JOML impls mutate and return same object
                return new Vec3(v.x, v.y, v.z);
            }

            // Try transformPosition(Vector3dc, Vector3d)
            Class<?> v3dc = tryLoad("org.joml.Vector3dc");
            if (v3dc != null && MATRIX_TRANSFORM_POSITION_2 == null) {
                MATRIX_TRANSFORM_POSITION_2 = tryMethod(matrix.getClass(), "transformPosition", v3dc, Vector3d.class);
            }
            if (MATRIX_TRANSFORM_POSITION_2 != null) {
                Vector3d dest = new Vector3d();
                Object out = MATRIX_TRANSFORM_POSITION_2.invoke(matrix, v, dest);
                Vec3 vec = toVec3(out);
                if (vec != null) return vec;
                return new Vec3(dest.x, dest.y, dest.z);
            }

            // Last resort: some APIs use transformPosition(Vector3d, Vector3d)
            Method m = tryMethod(matrix.getClass(), "transformPosition", Vector3d.class, Vector3d.class);
            if (m != null) {
                Vector3d dest = new Vector3d();
                Object out = m.invoke(matrix, v, dest);
                Vec3 vec = toVec3(out);
                if (vec != null) return vec;
                return new Vec3(dest.x, dest.y, dest.z);
            }

        } catch (Throwable ignored) {}

        return null;
    }

    private static Method tryMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            try {
                Method m = owner.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    public static Iterable<Object> getLoadedShips(ServerLevel level) {
        ensureResolved(level);
        Object sow = getShipObjectWorld(level);
        if (sow == null || SOW_GET_LOADED_SHIPS == null) return java.util.List.of();

        try {
            Object loaded = SOW_GET_LOADED_SHIPS.invoke(sow);
            Iterable<?> it = asIterable(loaded);
            return (it != null) ? (Iterable<Object>) it : java.util.List.of();
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    public static Optional<Object> findNearestLoadedShip(ServerLevel level, Vec3 worldPos, double maxDist) {
        if (level == null || worldPos == null) return Optional.empty();

        Object best = null;
        double bestD2 = maxDist * maxDist;

        for (Object ship : getLoadedShips(level)) {
            if (ship == null) continue;

            Vec3 shipPos = tryShipWorldPos(ship);
            if (shipPos == null) continue;

            double dx = shipPos.x - worldPos.x;
            double dy = shipPos.y - worldPos.y;
            double dz = shipPos.z - worldPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;

            if (d2 < bestD2) {
                bestD2 = d2;
                best = ship;
            }
        }

        return Optional.ofNullable(best);
    }
}