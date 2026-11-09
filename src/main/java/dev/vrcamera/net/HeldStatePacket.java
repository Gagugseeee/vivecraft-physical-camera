package dev.vrcamera.net;

import dev.vrcamera.PhysicalCameraMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client, the entity ID of the player's currently physically held camera. */
public record HeldStatePacket(int heldEntityId) implements CustomPacketPayload {
    public static final Type<HeldStatePacket> TYPE = new Type<>(PhysicalCameraMod.id("held_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HeldStatePacket> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeInt(p.heldEntityId()),
            buf -> new HeldStatePacket(buf.readInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
