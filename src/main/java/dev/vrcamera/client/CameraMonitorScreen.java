package dev.vrcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Live viewfinder without a second render pass: Minecraft renders the real world
 * using the selected item_display as its camera. The transparent Screen provides
 * the camera's LCD/viewfinder UI, including VR-clickable buttons.
 *
 * IMPORTANT: This is a full-screen viewfinder, not picture-in-picture and not
 * a texture on the 3D model. A second simultaneous world render is a separate task.
 */
public final class CameraMonitorScreen extends Screen {
    private static final int WHITE = 0xffe5f4eb;
    private static final int DARK = 0xc5101919;
    private final UUID cameraUuid;
    private final Entity previousView;
    private Display.ItemDisplay camera;
    private boolean closed;
    private Button powerButton;

    CameraMonitorScreen(Display.ItemDisplay target, Entity previousView) {
        super(Component.literal("VR Camera — live monitor"));
        this.cameraUuid = target.getUUID();
        this.camera = target;
        this.previousView = previousView;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Intentionally transparent: the actual world view is the monitor's image.
    }

    @Override
    protected void init() {
        int y = this.height - 32;
        this.powerButton = this.addRenderableWidget(Button.builder(
                Component.literal("REC: переключить"), b -> togglePower())
                .bounds(12, y, 116, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Отпустить / взять"), b -> {
            if (camera != null) PhysicalCameraClient.monitorGrab(camera);
        }).bounds(134, y, 125, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Бросить"), b -> {
            if (camera != null) PhysicalCameraClient.monitorThrow(camera);
        }).bounds(265, y, 75, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Закрыть экран"), b -> onClose())
                .bounds(Math.max(345, width - 128), y, 116, 20).build());
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            onClose();
            return;
        }
        Display.ItemDisplay resolved = PhysicalCameraClient.cameraByUuid(minecraft, cameraUuid);
        if (resolved == null) {
            onClose();
            return;
        }
        this.camera = resolved;
        // Keep the camera view even after another mod changes camera entity.
        if (minecraft.getCameraEntity() != camera) minecraft.setCameraEntity(camera);
        if (powerButton != null) {
            powerButton.setMessage(Component.literal(PhysicalCameraClient.isPowered(camera)
                    ? "REC: выключить" : "REC: включить"));
        }
    }

    private void togglePower() {
        if (camera != null) PhysicalCameraClient.monitorPower(camera);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // A camera frame with an unobstructed transparent center (live image).
        g.fill(0, 0, width, 24, DARK);
        g.fill(0, height - 39, width, height, DARK);
        g.fill(0, 24, 3, height - 39, 0x995af3c3);
        g.fill(width - 3, 24, width, height - 39, 0x995af3c3);
        g.text(font, "VR CAMERA   /   LIVE VIEW", 10, 8, WHITE);
        String state = camera != null && PhysicalCameraClient.isPowered(camera) ? "● REC" : "○ STANDBY";
        g.text(font, state, Math.max(4, width - 98), 8,
                camera != null && PhysicalCameraClient.isPowered(camera) ? 0xffff5555 : 0xffe5f4eb);
        int cx = width / 2, cy = (height - 39) / 2 + 8;
        int col = 0x99ddffee;
        g.horizontalLine(cx - 11, cx - 4, cy, col);
        g.horizontalLine(cx + 4, cx + 11, cy, col);
        g.verticalLine(cx, cy - 11, cy - 4, col);
        g.verticalLine(cx, cy + 4, cy + 11, col);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;
        Minecraft mc = Minecraft.getInstance();
        PhysicalCameraClient.monitorClosed(this);
        if (mc.player != null && mc.level != null) {
            // Never leave the player's view bound to a camera on closing.
            mc.setCameraEntity(previousView != null && !previousView.isRemoved() ? previousView : mc.player);
        }
        if (mc.gui.screen() == this) mc.gui.setScreen(null);
    }
}
