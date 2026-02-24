package com.smeakmoseley.reinsmod.vs;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class VsShipMass {
    private static final Logger LOGGER = LogUtils.getLogger();

    private record Resolved(
            Method getInertiaData, Field inertiaDataField,
            Method getMass, Field massField
    ) {}

    private static final ConcurrentHashMap<Class<?>, Resolved> CACHE = new ConcurrentHashMap<>();

    private VsShipMass() {}

    /** Returns ship mass if resolvable, else -1. */
    public static double getShipMass(Object shipObj) {
        if (shipObj == null) return -1.0;

        try {
            Resolved r = CACHE.computeIfAbsent(shipObj.getClass(), VsShipMass::resolve);
            if (r == null) return -1.0;

            Object inertia = null;

            if (r.getInertiaData != null) {
                inertia = r.getInertiaData.invoke(shipObj);
            } else if (r.inertiaDataField != null) {
                inertia = r.inertiaDataField.get(shipObj);
            }

            if (inertia == null) return -1.0;

            Object massObj = null;

            if (r.getMass != null) {
                massObj = r.getMass.invoke(inertia);
            } else if (r.massField != null) {
                massObj = r.massField.get(inertia);
            }

            if (massObj instanceof Number n) return n.doubleValue();
            return -1.0;

        } catch (Throwable t) {
            return -1.0;
        }
    }

    private static Resolved resolve(Class<?> shipClass) {
        try {
            Method getInertiaData = null;
            Field inertiaField = null;

            // Kotlin "val inertiaData" => Java getter "getInertiaData()"
            try {
                getInertiaData = shipClass.getMethod("getInertiaData");
                getInertiaData.setAccessible(true);
            } catch (Throwable ignored) {}

            if (getInertiaData == null) {
                try {
                    inertiaField = shipClass.getDeclaredField("inertiaData");
                    inertiaField.setAccessible(true);
                } catch (Throwable ignored) {}
            }

            // We need inertia type to resolve mass access
            Class<?> inertiaType = null;
            if (getInertiaData != null) inertiaType = getInertiaData.getReturnType();
            if (inertiaType == null && inertiaField != null) inertiaType = inertiaField.getType();
            if (inertiaType == null) return null;

            Method getMass = null;
            Field massField = null;

            try {
                getMass = inertiaType.getMethod("getMass");
                getMass.setAccessible(true);
            } catch (Throwable ignored) {}

            if (getMass == null) {
                try {
                    massField = inertiaType.getDeclaredField("mass");
                    massField.setAccessible(true);
                } catch (Throwable ignored) {}
            }

            if (getMass == null && massField == null) {
                LOGGER.info("[ReinsMod VS] Could not resolve mass on inertia type {}", inertiaType.getName());
                // still cache resolution so we don't spam
                return new Resolved(getInertiaData, inertiaField, null, null);
            }

            LOGGER.info("[ReinsMod VS] Resolved ship mass via {}.{} -> {}.{}",
                    shipClass.getName(),
                    (getInertiaData != null ? getInertiaData.getName() : "inertiaData"),
                    inertiaType.getName(),
                    (getMass != null ? getMass.getName() : "mass")
            );

            return new Resolved(getInertiaData, inertiaField, getMass, massField);

        } catch (Throwable t) {
            return null;
        }
    }
}
