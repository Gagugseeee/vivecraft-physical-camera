package dev.vrcamera.net;

import dev.vrcamera.PhysicalCameraMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** VR world-space pose + approximate controller velocity, sent every second tick. */
public record HandPosePacket(double x, double y, double z,
                             float yaw, float pitch, float roll,
                             double vx, double vy, double vz) implements CustomPacketPayload {
    public static final Type<HandPosePacket> TYPE = new Type<>(PhysicalCameraMod.id("hand_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HandPosePacket> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeDouble(p.x()); buf.writeDouble(p.y()); buf.writeDouble(p.z());
                buf.writeFloat(p.yaw()); buf.writeFloat(p.pitch()); buf.writeFloat(p.roll());
                buf.writeDouble(p.vx()); buf.writeDouble(p.vy()); buf.writeDouble(p.vz());
            },
            buf -> new HandPosePacket(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
