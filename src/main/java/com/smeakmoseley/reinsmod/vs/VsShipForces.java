package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VsShipForces {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String SUMMARY = "unresolved";

    private VsShipForces() {}

    public static String resolutionSummary() {
        return SUMMARY;
    }

    /**
     * Applies a WORLD-space force to a VS ship using GameToPhysicsAdapter (GTPA).
     *
     * @param shipObj   usually org.valkyrienskies.core.impl.game.ships.ShipData
     * @param forceWorld force in WORLD space
     * @param worldPos   position in WORLD space where force is applied (pass null to apply at COM)
     */
    public static boolean applyWorldForce(Object shipObj, Vec3 forceWorld, Vec3 worldPos) {
        if (shipObj == null || forceWorld == null) return false;

        try {
            // 1) shipId (ShipId is basically a long in VS)
            long shipId = readLongProperty(shipObj, "id", "getId");

            // 2) dimension key used by VS to route to correct phys world
            String dimKey = readStringProperty(shipObj, "chunkClaimDimension", "getChunkClaimDimension");
            if (dimKey == null || dimKey.isBlank()) {
                SUMMARY = "no_dimension_key";
                return false;
            }

            // 3) Get ValkyrienSkiesMod.getOrCreateGTPA(dimKey)
            Class<?> vsm = Class.forName("org.valkyrienskies.mod.common.ValkyrienSkiesMod");
            Method getOrCreate = null;
            for (Method m : vsm.getMethods()) {
                if (!m.getName().equals("getOrCreateGTPA")) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] == String.class) {
                    getOrCreate = m;
                    break;
                }
            }
            if (getOrCreate == null) {
                SUMMARY = "no_gtpa_getter";
                return false;
            }

            Object gtpa = getOrCreate.invoke(null, dimKey);
            if (gtpa == null) {
                SUMMARY = "no_gtpa";
                return false;
            }

            // 4) Wake ship: gtpa.setStatic(shipId, false) if available
            tryInvokeSetStatic(gtpa, shipId, false);

            // 5) Call gtpa.applyWorldForce(shipId, Vector3dc, Vector3dc?)
            Vector3d f = new Vector3d(forceWorld.x, forceWorld.y, forceWorld.z);
            Vector3d p = (worldPos == null) ? null : new Vector3d(worldPos.x, worldPos.y, worldPos.z);

            Method applyWorldForce = findApplyWorldForce(gtpa.getClass());
            if (applyWorldForce == null) {
                SUMMARY = "no_apply_world_force";
                return false;
            }

            applyWorldForce.invoke(gtpa, shipId, f, p);

            SUMMARY = "ok_gtpa";
            return true;

        } catch (Throwable t) {
            SUMMARY = "invoke_failed:" + t.getClass().getSimpleName();
            return false;
        }
    }

    private static Method findApplyWorldForce(Class<?> gtpaClass) {
        // Kotlin signature (compiled): applyWorldForce(long, Vector3dc, Vector3dc)
        for (Method m : gtpaClass.getMethods()) {
            if (!m.getName().equals("applyWorldForce")) continue;
            if (m.getParameterCount() != 3) continue;

            Class<?>[] p = m.getParameterTypes();
            boolean firstOk = (p[0] == long.class) || (p[0] == Long.class);
            boolean vecOk =
                    p[1].getName().startsWith("org.joml.Vector3d")
                            || p[1].getName().startsWith("org.joml.Vector3dc")
                            || p[1].getName().equals("org.joml.Vector3dc");
            boolean vec2Ok =
                    p[2].getName().startsWith("org.joml.Vector3d")
                            || p[2].getName().startsWith("org.joml.Vector3dc")
                            || p[2].getName().equals("org.joml.Vector3dc");

            if (firstOk && vecOk && vec2Ok) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static void tryInvokeSetStatic(Object gtpa, long shipId, boolean isStatic) {
        try {
            for (Method m : gtpa.getClass().getMethods()) {
                if (!m.getName().equals("setStatic")) continue;
                if (m.getParameterCount() != 2) continue;

                Class<?>[] p = m.getParameterTypes();
                boolean firstOk = (p[0] == long.class) || (p[0] == Long.class);
                boolean secondOk = (p[1] == boolean.class) || (p[1] == Boolean.class);

                if (firstOk && secondOk) {
                    m.setAccessible(true);
                    m.invoke(gtpa, shipId, isStatic);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static long readLongProperty(Object obj, String fieldName, String getterName) throws Exception {
        // try getter
        try {
            Method m = obj.getClass().getMethod(getterName);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Number n) return n.longValue();
        } catch (NoSuchMethodException ignored) {}

        // try field
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        Object v = f.get(obj);
        if (v instanceof Number n) return n.longValue();

        throw new IllegalStateException("Cannot read long property " + fieldName + "/" + getterName);
    }

    private static String readStringProperty(Object obj, String fieldName, String getterName) throws Exception {
        // try getter
        try {
            Method m = obj.getClass().getMethod(getterName);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v != null) return v.toString();
        } catch (NoSuchMethodException ignored) {}

        // try field
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v != null) return v.toString();
        } catch (NoSuchFieldException ignored) {}

        return null;
    }
}
