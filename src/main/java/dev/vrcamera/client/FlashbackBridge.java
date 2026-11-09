package dev.vrcamera.client;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Flashback integration is limited to public entry points and intentionally
 * uses reflection: Flashback is client-only and not a mandatory build dependency.
 */
final class FlashbackBridge {
    private static boolean recordingOwnedByCamera = false;
    private static boolean failed = false;
    private static Class<?> clazz;
    private static Field recorder;
    private static Method start;
    private static Method finish;
    private static Method isInReplay;

    private FlashbackBridge() {}

    private static boolean load() {
        if (failed || !FabricLoader.getInstance().isModLoaded("flashback")) return false;
        if (clazz != null) return true;
        try {
            clazz = Class.forName("com.moulberry.flashback.Flashback");
            recorder = clazz.getField("RECORDER");
            start = clazz.getMethod("startRecordingReplay");
            finish = clazz.getMethod("finishRecordingReplay");
            isInReplay = clazz.getMethod("isInReplay");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            failed = true;
            return false;
        }
    }

    static boolean installed() { return load(); }

    static boolean inReplay() {
        if (!load()) return false;
        try { return Boolean.TRUE.equals(isInReplay.invoke(null)); }
        catch (ReflectiveOperationException ex) { return false; }
    }

    static boolean startIfNecessary() {
        if (!load() || inReplay()) return false;
        try {
            if (recorder.get(null) != null) return true; // Existing Flashback recording: do NOT own it.
            start.invoke(null);
            if (recorder.get(null) != null) {
                recordingOwnedByCamera = true;
                return true;
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    static void stopIfOwned() {
        if (!recordingOwnedByCamera) return;
        recordingOwnedByCamera = false;
        if (!load()) return;
        try { if (recorder.get(null) != null) finish.invoke(null); }
        catch (ReflectiveOperationException ignored) { }
    }

    static void reset() { recordingOwnedByCamera = false; }
}
