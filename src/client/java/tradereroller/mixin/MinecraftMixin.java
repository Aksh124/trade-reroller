package tradereroller.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tradereroller.Reroller;

/**
 * Vanilla calls continueAttack every tick and, when the attack key isn't held, cancels any block
 * being broken. While the reroller is breaking the lectern, it skips that.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void tradereroller$keepBreaking(boolean down, CallbackInfo ci) {
		if (Reroller.isBreaking()) {
			ci.cancel();
		}
	}
}
