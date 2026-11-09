package dev.vrcamera;

import com.mojang.math.Transformation;
import dev.vrcamera.net.CameraActionPacket;
import dev.vrcamera.net.HandPosePacket;
import dev.vrcamera.net.HeldStatePacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entity implementation deliberately uses VANILLA item_display + VANILLA item stacks.
 * Flashback records the vanilla spawn / move / metadata packets, so the replay retains
 * camera positions and its powered state without custom Flashback replay codecs.
 */
public final class CameraSystem {
    public static final String MARK = "vrcamera.camera";
    private static final String HELD = "vrcamera.held";
    private static final String FLYING = "vrcamera.flying";
    private static final String OWNER_PREFIX = "vrcamera.owner.";
    private static final double REACH_SQR = 7.0 * 7.0;

    private record Pose(Vec3 pos, float yaw, float pitch, float roll, Vec3 velocity, long gameTime) {}
    private static final Map<UUID, Pose> POSES = new HashMap<>();
    private static final Map<UUID, UUID> HELD_CAMERA = new HashMap<>();
    private static final Map<UUID, Float> ROLLS = new HashMap<>();

    private CameraSystem() {}

    static void deploy(ServerPlayer player, InteractionHand hand) {
        if (getHeld(player) != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Сначала отпусти или брось камеру (G / T)."));
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(PhysicalCameraMod.CAMERA)) return;

        ServerLevel level = player.level();
        Pose pose = currentPose(player);
        Display.ItemDisplay camera = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
        camera.setPos(pose.pos().x(), pose.pos().y(), pose.pos().z());
        camera.setYRot(pose.yaw());
        camera.setXRot(pose.pitch());
        camera.setItemStack(new ItemStack(PhysicalCameraMod.CAMERA));
        camera.setItemTransform(ItemDisplayContext.FIXED);
        camera.setTransformation(transform(pose.roll()));
        camera.setViewRange(4.0f);
        camera.setPosRotInterpolationDuration(2);
        camera.addTag(MARK);
        camera.addTag(HELD);
        camera.addTag(OWNER_PREFIX + player.getUUID());

        if (level.addFreshEntity(camera)) {
            if (!player.hasInfiniteMaterials()) stack.shrink(1);
            HELD_CAMERA.put(player.getUUID(), camera.getUUID());
            sendHeld(player, camera.getId());
        }
    }

    private static void sendHeld(ServerPlayer player, int id) {
        ServerPlayNetworking.send(player, new HeldStatePacket(id));
    }

    static void onPose(ServerPlayer player, HandPosePacket payload) {
        Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());
        if (!finite(pos) || pos.distanceToSqr(player.position()) > 4.0 * 4.0) return;
        if (!Float.isFinite(payload.yaw()) || !Float.isFinite(payload.pitch()) || !Float.isFinite(payload.roll())) return;
        Vec3 vel = new Vec3(payload.vx(), payload.vy(), payload.vz());
        if (!finite(vel)) return;
        if (vel.lengthSqr() > 2.25) vel = vel.normalize().scale(1.5);
        POSES.put(player.getUUID(), new Pose(pos, payload.yaw(),
                Mth.clamp(payload.pitch(), -89.9f, 89.9f), payload.roll(), vel, player.level().getGameTime()));
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    private static Pose currentPose(ServerPlayer player) {
        Pose pose = POSES.get(player.getUUID());
        if (pose != null && player.level().getGameTime() - pose.gameTime() <= 5
                && pose.pos().distanceToSqr(player.position()) <= 16) return pose;
        return new Pose(player.getEyePosition().add(player.getLookAngle().scale(0.65)),
                player.getYRot(), player.getXRot(), 0, Vec3.ZERO, player.level().getGameTime());
    }

    private static Transformation transform(float rollDeg) {
        return new Transformation(new Vector3f(),
                new Quaternionf().rotateZ((float) Math.toRadians(rollDeg)),
                new Vector3f(0.65f, 0.65f, 0.65f), new Quaternionf());
    }

    private static boolean isCamera(Entity e) {
        return e instanceof Display.ItemDisplay && e.getTags().contains(MARK);
    }

    private static Display.ItemDisplay getHeld(ServerPlayer player) {
        UUID uuid = HELD_CAMERA.get(player.getUUID());
        if (uuid == null) {
            // Rehydrate after a chunk load or server restart.
            for (Entity e : player.level().getAllEntities()) {
                if (isCamera(e) && e.getTags().contains(HELD)
                        && e.getTags().contains(OWNER_PREFIX + player.getUUID())) {
                    HELD_CAMERA.put(player.getUUID(), e.getUUID());
                    return (Display.ItemDisplay) e;
                }
            }
            return null;
        }
        Entity e = player.level().getEntity(uuid);
        if (!isCamera(e) || !e.getTags().contains(HELD)) {
            HELD_CAMERA.remove(player.getUUID());
            return null;
        }
        return (Display.ItemDisplay) e;
    }

    static void onAction(ServerPlayer player, CameraActionPacket p) {
        byte op = p.action();
        if (op < 0 || op > CameraActionPacket.ROTATE) return;
        Display.ItemDisplay held = getHeld(player);
        Display.ItemDisplay target = null;
        if (p.entityId() == -1) {
            target = held;
        } else {
            Entity e = player.level().getEntity(p.entityId());
            if (isCamera(e) && e.distanceToSqr(player) < REACH_SQR) target = (Display.ItemDisplay) e;
        }
        if (target == null) return;

        if (op == CameraActionPacket.GRAB_OR_FREEZE) {
            if (target == held) {
                target.removeTag(HELD);
                target.removeTag(FLYING);
                target.setDeltaMovement(Vec3.ZERO);
                HELD_CAMERA.remove(player.getUUID());
                sendHeld(player, -1);
            } else if (held == null && !target.getTags().contains(HELD)) {
                target.removeTag(FLYING);
                target.addTag(HELD);
                for (String tag : new ArrayList<>(target.getTags())) {
                    if (tag.startsWith(OWNER_PREFIX)) target.removeTag(tag);
                }
                target.addTag(OWNER_PREFIX + player.getUUID());
                HELD_CAMERA.put(player.getUUID(), target.getUUID());
                sendHeld(player, target.getId());
            }
        } else if (op == CameraActionPacket.THROW) {
            if (target != held) return;
            Pose pose = currentPose(player);
            Vec3 direction = Vec3.directionFromRotation(pose.pitch(), pose.yaw());
            Vec3 throwVelocity = direction.scale(0.85).add(pose.velocity().scale(0.55)).add(0, 0.12, 0);
            if (throwVelocity.lengthSqr() > 3.24) throwVelocity = throwVelocity.normalize().scale(1.8);
            target.setDeltaMovement(throwVelocity);
            target.removeTag(HELD);
            target.addTag(FLYING);
            HELD_CAMERA.remove(player.getUUID());
            sendHeld(player, -1);
        } else if (op == CameraActionPacket.POWER) {
            boolean powered = target.getItemStack().is(PhysicalCameraMod.CAMERA_ON);
            target.setItemStack(new ItemStack(powered ? PhysicalCameraMod.CAMERA : PhysicalCameraMod.CAMERA_ON));
        } else if (op == CameraActionPacket.PICK_UP) {
            if (target == held) {
                HELD_CAMERA.remove(player.getUUID());
                sendHeld(player, -1);
            }
            target.discard();
            ItemStack restored = new ItemStack(PhysicalCameraMod.CAMERA);
            if (!player.getInventory().add(restored)) {
                Vec3 at = player.position();
                levelSpawnDrop(player.level(), at, restored);
            }
        } else if (op == CameraActionPacket.ROTATE) {
            if (target == held || target.getTags().contains(FLYING)) return;
            if (Math.abs(p.yawDelta()) > 5 || Math.abs(p.pitchDelta()) > 5
                    || !Float.isFinite(p.yawDelta()) || !Float.isFinite(p.pitchDelta())) return;
            target.setYRot(Mth.wrapDegrees(target.getYRot() + p.yawDelta()));
            target.setXRot(Mth.clamp(target.getXRot() + p.pitchDelta(), -89, 89));
        }
    }

    private static void levelSpawnDrop(ServerLevel level, Vec3 position, ItemStack item) {
        level.addFreshEntity(new ItemEntity(level, position.x(), position.y() + 0.3, position.z(), item));
    }

    public static void tick(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (!isCamera(entity)) continue;
            Display.ItemDisplay camera = (Display.ItemDisplay) entity;
            if (camera.getTags().contains(HELD)) {
                UUID owner = parseOwner(camera);
                ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
                if (player == null || player.level() != level) {
                    camera.removeTag(HELD);
                    camera.setDeltaMovement(Vec3.ZERO);
                    if (owner != null) HELD_CAMERA.remove(owner);
                    continue;
                }
                HELD_CAMERA.put(player.getUUID(), camera.getUUID());
                Pose pose = currentPose(player);
                camera.setPos(pose.pos().x(), pose.pos().y(), pose.pos().z());
                camera.setYRot(pose.yaw());
                camera.setXRot(pose.pitch());
                camera.setTransformation(transform(pose.roll()));
            } else if (camera.getTags().contains(FLYING)) {
                physicsTick(level, camera);
            }
        }
    }

    private static UUID parseOwner(Display.ItemDisplay camera) {
        for (String tag : camera.getTags()) {
            if (tag.startsWith(OWNER_PREFIX)) {
                try { return UUID.fromString(tag.substring(OWNER_PREFIX.length())); }
                catch (IllegalArgumentException ignored) { return null; }
            }
        }
        return null;
    }

    private static void physicsTick(ServerLevel level, Display.ItemDisplay camera) {
        Vec3 velocity = camera.getDeltaMovement().add(0, -0.045, 0).scale(0.985);
        if (velocity.lengthSqr() < 0.00004) {
            camera.removeTag(FLYING);
            camera.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 start = camera.position();
        Vec3 end = start.add(velocity);
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3 normal = switch (hit.getDirection()) {
                case DOWN -> new Vec3(0, -1, 0);
                case UP -> new Vec3(0, 1, 0);
                case NORTH -> new Vec3(0, 0, -1);
                case SOUTH -> new Vec3(0, 0, 1);
                case WEST -> new Vec3(-1, 0, 0);
                case EAST -> new Vec3(1, 0, 0);
            };
            Vec3 bounced = velocity.subtract(normal.scale(2.0 * velocity.dot(normal))).scale(0.43);
            camera.setPos(hit.getLocation().add(normal.scale(0.09)));
            camera.setDeltaMovement(bounced);
            if (normal.y() > 0.5 && bounced.lengthSqr() < 0.017) {
                camera.removeTag(FLYING);
                camera.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            camera.setPos(end);
            camera.setDeltaMovement(velocity);
        }
        camera.setYRot(Mth.wrapDegrees(camera.getYRot() + 7));
        camera.setXRot(Mth.wrapDegrees(camera.getXRot() + 5));
        float roll = ROLLS.getOrDefault(camera.getUUID(), 0f) + 9;
        if (roll >= 360) roll -= 360;
        ROLLS.put(camera.getUUID(), roll);
        camera.setTransformation(transform(roll));
    }
}
