package dev.vrcamera;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Right-click creates one physical camera; consumes one inventory item. */
public final class DeployCameraItem extends Item {
    public DeployCameraItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            CameraSystem.deploy(serverPlayer, hand);
        }
        return InteractionResult.SUCCESS;
    }
}
