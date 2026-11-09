package dev.vrcamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vrcamera.PhysicalCameraMod;
import dev.vrcamera.net.CameraActionPacket;
import dev.vrcamera.net.HandPosePacket;
import dev.vrcamera.net.HeldStatePacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Client controls; no Minecraft rendering mixins required. */
public final class PhysicalCameraClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(PhysicalCameraMod.id("camera"));
    private static KeyMapping grab, toss, power, view, monitor, pickup, left, right, up, down;
    private static int heldId = -1;
    private static CameraMonitorScreen monitorScreen;
    private static UUID spectatedCamera;
    private static int stopCountdown = -1;
    private static int ticks = 0;
    private static boolean previousWorldLoaded = false;

    private static KeyMapping key(String name, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.vrcamera." + name, InputConstants.Type.KEYBOARD, defaultKey, CATEGORY));
    }

    @Override
    public void onInitializeClient() {
        grab = key("grab", InputConstants.KEY_G);
        toss = key("throw", InputConstants.KEY_T);
        power = key("power", InputConstants.KEY_R);
        view = key("view", InputConstants.KEY_V);
        monitor = key("monitor", InputConstants.KEY_B);
        pickup = key("pickup", InputConstants.KEY_X);
        left = key("left", InputConstants.KEY_J);
        right = key("right", InputConstants.KEY_L);
        up = key("up", InputConstants.KEY_I);
        down = key("down", InputConstants.KEY_K);

        ClientPlayNetworking.registerGlobalReceiver(HeldStatePacket.TYPE, (p, ctx) ->
                ctx.client().execute(() -> heldId = p.heldEntityId()));
        ClientTickEvents.END_CLIENT_TICK.register(PhysicalCameraClient::tick);
    }

    private static void message(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[VR Camera] " + s));
    }

    private static void tick(Minecraft mc) {
        boolean worldLoaded = mc.level != null && mc.player != null;
        if (!worldLoaded) {
            if (previousWorldLoaded) {
                spectatedCamera = null;
                monitorScreen = null;
                heldId = -1;
                FlashbackBridge.reset();
                stopCountdown = -1;
            }
            previousWorldLoaded = false;
            return;
        }
        previousWorldLoaded = true;
        ticks++;
        boolean replay = FlashbackBridge.inReplay();
        if (replay) {
            // A live monitor should never hijack the camera of a Flashback replay.
            if (monitorScreen != null) monitorScreen.onClose();
            tickSpectating(mc);
            while (view.consumeClick()) toggleReplayView(mc);
            return;
        }
        if (spectatedCamera != null) {
            spectatedCamera = null;
            mc.setCameraEntity(mc.player);
        }
        // Vivecraft poses continue updating while the viewfinder GUI is open,
        // so the held camera can still follow the controller in real time.
        // Vivecraft controller poses are sent only while inside a live world.
        if ((ticks & 1) == 0 && FabricLoader.getInstance().isModLoaded("vivecraft")
                && ClientPlayNetworking.canSend(HandPosePacket.TYPE)) {
            try {
                HandPosePacket pose = VivecraftPoseBridge.sample();
                if (pose != null) ClientPlayNetworking.send(pose);
            } catch (LinkageError ex) {
                // Allows desktop mode when Vivecraft itself is absent/broken.
            }
        }

        if (mc.gui.screen() == null) {
            while (monitor.consumeClick()) {
                Display.ItemDisplay camera = heldId >= 0 ? cameraById(mc, heldId) : selected(mc, 7.0, false);
                if (camera != null) {
                    monitorScreen = new CameraMonitorScreen(camera, mc.getCameraEntity());
                    mc.setCameraEntity(camera);
                    mc.gui.setScreen(monitorScreen);
                } else message("Поднеси VR-контроллер к камере или наведи на неё взгляд.");
            }
        }
        if (stopCountdown >= 0 && --stopCountdown == 0) {
            boolean anyPowered = false;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Display.ItemDisplay d && isCamera(d) && isPowered(d)) {
                    anyPowered = true;
                    break;
                }
            }
            if (!anyPowered) FlashbackBridge.stopIfOwned();
            stopCountdown = -1;
        }

        // The GUI contains Vivecraft-pointer clickable buttons. Minecraft does not
        // dispatch normal gameplay key mappings while a GUI is open.
        if (mc.gui.screen() != null) return;

        while (grab.consumeClick()) {
            if (heldId >= 0) send(CameraActionPacket.GRAB_OR_FREEZE, -1);
            else {
                Display.ItemDisplay camera = selected(mc, 7.0, false);
                if (camera != null) send(CameraActionPacket.GRAB_OR_FREEZE, camera.getId());
                else message("Поднеси VR-контроллер к камере или наведи прицел.");
            }
        }
        while (toss.consumeClick()) {
            if (heldId >= 0) send(CameraActionPacket.THROW, -1);
            else message("Сначала возьми камеру (G).");
        }
        while (power.consumeClick()) {
            Display.ItemDisplay camera = heldId >= 0 ? cameraById(mc, heldId) : selected(mc, 7.0, false);
            if (camera == null) message("Не вижу камеру поблизости.");
            else monitorPower(camera);
        }
        while (pickup.consumeClick()) {
            Display.ItemDisplay camera = heldId >= 0 ? cameraById(mc, heldId) : selected(mc, 7.0, false);
            if (camera != null) {
                send(CameraActionPacket.PICK_UP, heldId >= 0 ? -1 : camera.getId());
                message("Камера возвращена в инвентарь.");
            }
        }
        while (view.consumeClick()) message("Для живого просмотра нажми B; в повторе Flashback — V.");

        if (heldId < 0) {
            float yaw = (right.isDown() ? 2f : 0f) - (left.isDown() ? 2f : 0f);
            float pitch = (down.isDown() ? 2f : 0f) - (up.isDown() ? 2f : 0f);
            if (yaw != 0 || pitch != 0) {
                Display.ItemDisplay camera = selected(mc, 7.0, false);
                if (camera != null && ClientPlayNetworking.canSend(CameraActionPacket.TYPE)) {
                    ClientPlayNetworking.send(new CameraActionPacket(CameraActionPacket.ROTATE, camera.getId(), yaw, pitch));
                }
            }
        }
    }

    static void monitorClosed(CameraMonitorScreen screen) {
        if (monitorScreen == screen) monitorScreen = null;
    }

    static void monitorPower(Display.ItemDisplay camera) {
        if (!isPowered(camera)) {
            if (FlashbackBridge.installed()) {
                if (!FlashbackBridge.startIfNecessary()) message("Flashback не начал запись: проверь его настройки.");
            } else message("Для сохранения повтора нужен Flashback.");
            stopCountdown = -1;
            message("Камера включена.");
        } else {
            stopCountdown = 12;
            message("Камера выключена.");
        }
        send(CameraActionPacket.POWER, camera.getId());
    }

    static void monitorGrab(Display.ItemDisplay camera) {
        send(CameraActionPacket.GRAB_OR_FREEZE,
                heldId == camera.getId() ? -1 : camera.getId());
    }

    static void monitorThrow(Display.ItemDisplay camera) {
        if (heldId == camera.getId()) send(CameraActionPacket.THROW, -1);
        else message("Сначала возьми камеру.");
    }

    private static void send(byte action, int entityId) {
        if (ClientPlayNetworking.canSend(CameraActionPacket.TYPE)) {
            ClientPlayNetworking.send(new CameraActionPacket(action, entityId, 0, 0));
        } else message("На сервере тоже должен быть установлен VR Physical Camera.");
    }

    private static boolean isCamera(Display.ItemDisplay d) {
        // Unlike entity scoreboard tags, ItemDisplay.item is synchronized to Flashback replays.
        return d.getItemStack().is(PhysicalCameraMod.CAMERA) ||
               d.getItemStack().is(PhysicalCameraMod.CAMERA_ON);
    }

    static boolean isPowered(Display.ItemDisplay d) {
        return d.getItemStack().is(PhysicalCameraMod.CAMERA_ON);
    }

    private static Display.ItemDisplay cameraById(Minecraft mc, int id) {
        if (mc.level == null) return null;
        Entity e = mc.level.getEntity(id);
        return e instanceof Display.ItemDisplay d && isCamera(d) ? d : null;
    }

    static Display.ItemDisplay cameraByUuid(Minecraft mc, UUID uuid) {
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Display.ItemDisplay d && d.getUUID().equals(uuid) && isCamera(d)) return d;
        }
        return null;
    }

    /** VR priority: controller proximity, no gaze or mouse crosshair required. */
    private static Display.ItemDisplay selected(Minecraft mc, double maxDist, boolean onlyOn) {
        if (FabricLoader.getInstance().isModLoaded("vivecraft")) {
            try {
                Vec3 hand = VivecraftPoseBridge.currentHandPosition();
                if (hand != null && mc.level != null) {
                    Display.ItemDisplay nearest = null;
                    double best = 0.72 * 0.72;
                    for (Entity e : mc.level.entitiesForRendering()) {
                        if (!(e instanceof Display.ItemDisplay d) || !isCamera(d) || (onlyOn && !isPowered(d))) continue;
                        double distance = d.position().distanceToSqr(hand);
                        if (distance < best) {
                            nearest = d;
                            best = distance;
                        }
                    }
                    if (nearest != null) return nearest;
                }
            } catch (LinkageError ignored) { }
        }
        return target(mc, maxDist, onlyOn);
    }

    /** Point-based picking works even though vanilla item_display has no physical hitbox. */
    private static Display.ItemDisplay target(Minecraft mc, double maxDist, boolean onlyOn) {
        if (mc.getCameraEntity() == null || mc.level == null) return null;
        Vec3 eye = mc.getCameraEntity().getEyePosition();
        Vec3 direction = mc.getCameraEntity().getLookAngle().normalize();
        Display.ItemDisplay best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Display.ItemDisplay d) || !isCamera(d) || (onlyOn && !isPowered(d))) continue;
            Vec3 delta = d.position().subtract(eye);
            double forward = delta.dot(direction);
            if (forward < 0 || forward > maxDist) continue;
            double offAxis = delta.subtract(direction.scale(forward)).lengthSqr();
            if (offAxis < 0.65 * 0.65 && forward < bestDist) {
                best = d;
                bestDist = forward;
            }
        }
        return best;
    }

    private static void toggleReplayView(Minecraft mc) {
        if (spectatedCamera != null) {
            spectatedCamera = null;
            mc.setCameraEntity(mc.player);
            message("Свободная камера Flashback.");
            return;
        }
        Display.ItemDisplay cam = target(mc, 64, true);
        if (cam == null) {
            // Camera may be far outside the crosshair: use nearest powered camera in 64 blocks.
            double closest = 64 * 64;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Display.ItemDisplay d && isCamera(d) && isPowered(d)) {
                    double dist = d.distanceToSqr(mc.player);
                    if (dist < closest) { closest = dist; cam = d; }
                }
            }
        }
        if (cam == null) {
            message("В этой части повтора нет включённой камеры в пределах 64 блоков.");
            return;
        }
        spectatedCamera = cam.getUUID();
        mc.setCameraEntity(cam);
        message("Вид из камеры. Нажми V, чтобы вернуться к Flashback.");
    }

    private static void tickSpectating(Minecraft mc) {
        if (spectatedCamera == null) return;
        Display.ItemDisplay cam = cameraByUuid(mc, spectatedCamera);
        if (cam != null && isPowered(cam)) {
            if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);
        } else {
            spectatedCamera = null;
            mc.setCameraEntity(mc.player);
            message("Камера выключена или вышла из записанной области.");
        }
    }
}
