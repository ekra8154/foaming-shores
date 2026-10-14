package dev.ekra.foamingshores.mixin;

import dev.ekra.foamingshores.FoamingShoresClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client-side block change passes through here; the shore fields rebuild when the shore changes. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void foamingshores$blockChanged(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
		if (old != current && FoamingShoresClient.renderer() != null) {
			FoamingShoresClient.renderer().blockChanged((ClientLevel) (Object) this, pos);
		}
	}
}
