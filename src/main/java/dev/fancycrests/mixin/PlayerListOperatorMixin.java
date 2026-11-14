package dev.fancycrests.mixin;

import dev.fancycrests.FancyCrestsMod;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(PlayerList.class)
public abstract class PlayerListOperatorMixin {
    @Inject(method = "op(Lnet/minecraft/server/players/NameAndId;Ljava/util/Optional;Ljava/util/Optional;)V", at = @At("RETURN"))
    private void fancycrests$operatorAdded(NameAndId player, Optional<LevelBasedPermissionSet> level,
                                          Optional<Boolean> bypassPlayerLimit, CallbackInfo ci) {
        FancyCrestsMod.onOperatorChanged(player.id());
    }

    @Inject(method = "deop(Lnet/minecraft/server/players/NameAndId;)V", at = @At("RETURN"))
    private void fancycrests$operatorRemoved(NameAndId player, CallbackInfo ci) {
        FancyCrestsMod.onOperatorChanged(player.id());
    }
}
