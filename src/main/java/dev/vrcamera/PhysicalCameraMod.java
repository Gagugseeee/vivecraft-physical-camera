package dev.vrcamera;

import dev.vrcamera.net.CameraActionPacket;
import dev.vrcamera.net.HandPosePacket;
import dev.vrcamera.net.HeldStatePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class PhysicalCameraMod implements ModInitializer {
    public static final String MOD_ID = "vrcamera";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static Item registerCamera(String name, boolean deployable) {
        Identifier id = id(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item.Properties props = new Item.Properties().setId(key).stacksTo(1);
        Item item = deployable ? new DeployCameraItem(props) : new Item(props);
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    public static final Item CAMERA = registerCamera("camera", true);
    public static final Item CAMERA_ON = registerCamera("camera_on", false);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(HandPosePacket.TYPE, HandPosePacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CameraActionPacket.TYPE, CameraActionPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HeldStatePacket.TYPE, HeldStatePacket.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HandPosePacket.TYPE,
                (packet, context) -> context.server().execute(() -> CameraSystem.onPose(context.player(), packet)));
        ServerPlayNetworking.registerGlobalReceiver(CameraActionPacket.TYPE,
                (packet, context) -> context.server().execute(() -> CameraSystem.onAction(context.player(), packet)));

        ServerTickEvents.END_WORLD_TICK.register(CameraSystem::tick);
    }
}
