package dev.vrcamera.net;

import dev.vrcamera.PhysicalCameraMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server. No privileged action is accepted without server-side validation. */
public record CameraActionPacket(byte action, int entityId, float yawDelta, float pitchDelta) implements CustomPacketPayload {
    public static final byte GRAB_OR_FREEZE = 0;
    public static final byte THROW = 1;
    public static final byte POWER = 2;
    public static final byte PICK_UP = 3;
    public static final byte ROTATE = 4;

    public static final Type<CameraActionPacket> TYPE = new Type<>(PhysicalCameraMod.id("camera_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CameraActionPacket> CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeByte(packet.action());
                buf.writeInt(packet.entityId());
                buf.writeFloat(packet.yawDelta());
                buf.writeFloat(packet.pitchDelta());
            },
            buf -> new CameraActionPacket(buf.readByte(), buf.readInt(), buf.readFloat(), buf.readFloat())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
