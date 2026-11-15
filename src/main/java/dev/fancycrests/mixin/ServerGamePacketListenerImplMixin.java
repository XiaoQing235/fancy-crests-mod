package dev.fancycrests.mixin;

import dev.fancycrests.gui.TitleMenu;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    /** Reject clicks sent for a previous page before vanilla applies them to the current one. */
    @Inject(
        method = "handleContainerClick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;suppressRemoteUpdates()V"),
        cancellable = true
    )
    private void fancycrests$rejectStaleMenuClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player.containerMenu instanceof TitleMenu menu && packet.stateId() != menu.getStateId()) {
            menu.broadcastFullState();
            ci.cancel();
        }
    }
}
