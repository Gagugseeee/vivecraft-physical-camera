package dev.vrcamera.client;

import dev.vrcamera.net.HandPosePacket;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/**
 * Isolated optional Vivecraft API binding. This class is never loaded unless Vivecraft
 * is actually present; all VR math uses Vivecraft's supported public API.
 */
final class VivecraftPoseBridge {
    private static Vec3 previousPosition;
    private static Vec3 currentHandPosition;

    static Vec3 currentHandPosition() { return currentHandPosition; }

    private VivecraftPoseBridge() {}

    static HandPosePacket sample() {
        VRClientAPI api = VRClientAPI.instance();
        if (!api.isVRActive()) {
            previousPosition = null;
            currentHandPosition = null;
            return null;
        }
        VRPose pose = api.getPreTickWorldPose();
        if (pose == null) { currentHandPosition = null; previousPosition = null; return null; }
        VRBodyPartData hand = pose.getMainHand();
        if (hand == null) { currentHandPosition = null; previousPosition = null; return null; }

        Vec3 pos = hand.getPos();
        currentHandPosition = pos;
        Vec3 forward = hand.getDir().normalize();
        // Vanilla MC yaw: +Z = 0 degrees; -X = +90 degrees.
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, forward.y()))));
        float roll = (float) Math.toDegrees(hand.getRoll());
        Vec3 v = previousPosition == null ? Vec3.ZERO : pos.subtract(previousPosition);
        previousPosition = pos;
        if (v.lengthSqr() > 2.25) v = v.normalize().scale(1.5);
        return new HandPosePacket(pos.x(), pos.y(), pos.z(), yaw, pitch, roll,
                v.x(), v.y(), v.z());
    }
}
