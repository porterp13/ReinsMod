package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;

public final class VsShipAccess {

    private enum PosKind { BLOCKPOS, VEC3, JOML3D }

    private static volatile Method SHIP_LOOKUP;
    private static volatile PosKind LOOKUP_KIND;

    // Try a few known VS entrypoints. If your VS build uses a different one,
    // we still refuse to guess (because guessing is what caused shipify calls).
    private static final String[] VS_UTIL_CANDIDATES = {
            "org.valkyrienskies.mod.common.VSGameUtilsKt",
            "org.valkyrienskies.mod.common.util.VSGameUtilsKt",
            "org.valkyrienskies.mod.common.ship_handling.VSGameUtilsKt",
            "org.valkyrienskies.api.ValkyrienSkies"
    };

    private VsShipAccess() {}

    public static Optional<Object> getShipManagingPos(ServerLevel level, Vec3 worldPos) {
        try {
            Method m = SHIP_LOOKUP;
            PosKind kind = LOOKUP_KIND;

            if (m == null || kind == null) {
                Lookup found = findShipLookupMethod();
                if (found == null) return Optional.empty();
                SHIP_LOOKUP = found.method;
                LOOKUP_KIND = found.kind;
                m = found.method;
                kind = found.kind;
            }

            Object result;
            if (kind == PosKind.VEC3) {
                result = m.invoke(null, level, worldPos);
            } else if (kind == PosKind.JOML3D) {
                result = m.invoke(null, level, new Vector3d(worldPos.x, worldPos.y, worldPos.z));
            } else {
                result = m.invoke(null, level, BlockPos.containing(worldPos));
            }

            if (result == null) return Optional.empty();
            if (result instanceof Optional<?> opt) return opt.map(o -> (Object) o);
            return Optional.of(result);

        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Object> getShipManagingPos(ServerLevel level, BlockPos pos) {
        return getShipManagingPos(level, Vec3.atCenterOf(pos));
    }

    private record Lookup(Method method, PosKind kind) {}

    private static Lookup findShipLookupMethod() {
        for (String cn : VS_UTIL_CANDIDATES) {
            Class<?> util;
            try {
                util = Class.forName(cn);
            } catch (ClassNotFoundException e) {
                continue;
            }

            Method best = null;
            PosKind bestKind = null;
            int bestScore = Integer.MIN_VALUE;

            for (Method m : util.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;

                String name = m.getName().toLowerCase(Locale.ROOT);

                // 🚫 Never ever call side-effect helpers
                if (name.contains("shipify")) continue;
                if (name.contains("create")) continue;
                if (name.contains("construct")) continue;
                if (name.contains("assemble")) continue;
                if (name.contains("spawn")) continue;
                if (name.contains("build")) continue;
                if (name.contains("generate")) continue;
                if (name.contains("convert")) continue;

                // ✅ MUST be a managing-ship lookup (hard requirement)
                if (!name.contains("get")) continue;
                if (!name.contains("ship")) continue;
                if (!name.contains("managing")) continue;   // <— KEY

                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;

                // ✅ Correct direction
                if (!ServerLevel.class.isAssignableFrom(p[0])) continue;

                PosKind kind = classifyPosParam(p[1]);
                if (kind == null) continue;

                if (m.getReturnType() == void.class) continue;

                int score = 0;
                // Prefer exact-ish names
                if (name.contains("managingpos")) score += 100;
                if (name.equals("getshipmanagingpos")) score += 200;
                if (kind == PosKind.VEC3) score += 10;
                if (kind == PosKind.JOML3D) score += 8;

                // Prefer Optional return type
                if (Optional.class.isAssignableFrom(m.getReturnType())) score += 25;

                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                    bestKind = kind;
                }
            }

            if (best != null) {
                best.setAccessible(true);
                return new Lookup(best, bestKind);
            }
        }

        return null;
    }

    private static PosKind classifyPosParam(Class<?> c) {
        if (c == BlockPos.class) return PosKind.BLOCKPOS;
        if (c == Vec3.class) return PosKind.VEC3;
        String n = c.getName();
        if (n.equals("org.joml.Vector3d") || n.equals("org.joml.Vector3dc")) return PosKind.JOML3D;
        return null;
    }
}
