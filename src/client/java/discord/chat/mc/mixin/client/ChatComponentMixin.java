package discord.chat.mc.mixin.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import discord.chat.mc.chat.ChatHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    private static final Pattern BRACKET_CHAT_PATTERN = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)*<([^>]{1,64})>\\s*(.+)$");
    private static final Pattern COLON_CHAT_PATTERN = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*:\\s+(.+)$");
    
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("TAIL"))
    private void onAddMessage(Component message, net.minecraft.network.chat.MessageSignature signature, net.minecraft.client.GuiMessageTag tag, CallbackInfo ci) {
        try {
            String rawText = message.getString().replaceAll("\\u00A7.", "").trim();
            if (!rawText.isEmpty()) {
                if (rawText.startsWith("[DCI Relay]")) return;
                
                ParsedChat parsedChat = parseChat(rawText);
                if ("System".equals(parsedChat.playerName())) return;
                
                String playerUuid = resolveLocalPlayerUuid(parsedChat.playerName());
                String skinUrl = resolveLocalSkinUrl(parsedChat.playerName());
                ChatHandler.getInstance().handleIncomingMinecraftMessage(parsedChat.playerName(), parsedChat.content(), playerUuid, skinUrl);
            }
        } catch (Exception ignored) {}
    }
    
    private ParsedChat parseChat(String rawText) {
        Matcher bracketMatcher = BRACKET_CHAT_PATTERN.matcher(rawText);
        if (bracketMatcher.matches()) {
            return new ParsedChat(bracketMatcher.group(1).trim(), bracketMatcher.group(2).trim());
        }
        
        Matcher colonMatcher = COLON_CHAT_PATTERN.matcher(rawText);
        if (colonMatcher.matches()) {
            return new ParsedChat(colonMatcher.group(1).trim(), colonMatcher.group(2).trim());
        }
        
        return new ParsedChat("System", rawText);
    }
    
    private String resolveLocalPlayerUuid(String playerName) {
        if (playerName == null || playerName.equals("System")) return null;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return null;
        
        try {
            String localName = client.player.getGameProfile().name();
            if (localName != null && localName.equalsIgnoreCase(playerName) && client.player.getGameProfile().id() != null) {
                return client.player.getGameProfile().id().toString();
            }
        } catch (Exception ignored) {}
        
        return null;
    }
    
    private String resolveLocalSkinUrl(String playerName) {
        if (playerName == null || playerName.equals("System")) return null;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return null;
        
        try {
            String localName = client.player.getGameProfile().name();
            if (localName == null || !localName.equalsIgnoreCase(playerName)) return null;
            
            var textures = client.player.getGameProfile().properties().get("textures");
            if (textures == null || textures.isEmpty()) return null;
            
            for (var property : textures) {
                if (property == null || property.value() == null || property.value().isBlank()) continue;
                try {
                    String decoded = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
                    JsonObject root = JsonParser.parseString(decoded).getAsJsonObject();
                    if (!root.has("textures")) continue;
                    JsonObject texturesObj = root.getAsJsonObject("textures");
                    if (!texturesObj.has("SKIN")) continue;
                    JsonObject skinObj = texturesObj.getAsJsonObject("SKIN");
                    if (skinObj.has("url")) {
                        String url = skinObj.get("url").getAsString();
                        if (url != null && !url.isBlank()) return url;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        
        return null;
    }
    
    private record ParsedChat(String playerName, String content) {}
}
