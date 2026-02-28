package discord.chat.mc.mixin.client;

import discord.chat.mc.chat.ChatHandler;
import discord.chat.mc.config.ModConfig;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void onSendChat(String message, CallbackInfo ci) {
        ChatHandler chatHandler = ChatHandler.getInstance();
        if (chatHandler.consumeServerChatPacketBypass()) {
            return;
        }
        
        ModConfig config = ModConfig.getInstance();
        if (!config.isRelayEnabled() || config.getRelayUrl().isBlank()) {
            return;
        }
        
        String normalized = message != null ? message.trim() : "";
        if (normalized.isEmpty()) {
            return;
        }
        
        if (chatHandler.isLocalChatToDiscordMode()) {
            chatHandler.relayLocalChatOnly(normalized);
            ci.cancel();
            return;
        }
        
        chatHandler.markLocalServerOnlyOutgoingMessage(normalized);
    }
}
