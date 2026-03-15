package com.deinname.chatfilter.mixin;

import com.deinname.chatfilter.ChatFilterMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the user from pressing F1 to hide the HUD and bypass
 * the DVD screensaver troll overlay. Resets hudHidden every frame
 * before the HUD visibility check in GameRenderer.render().
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void chatfilter$forceHudVisible(CallbackInfo ci) {
        if (ChatFilterMod.isDvdActive()) {
            MinecraftClient.getInstance().options.hudHidden = false;
        }
    }
}
