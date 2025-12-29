/**
 * @name MinecraftChat
 * @author Aurick
 * @authorId 1348025017233047634
 * @version 1.0.2
 * @description Bridge Discord channel chat with multiple Minecraft clients via WebSocket
 * @website https://github.com/aurickk/Discord-Chat-Integration/
 * @source https://github.com/aurickk/Discord-Chat-Integration/blob/main/betterdiscord-plugin
 */

module.exports = class MinecraftChat {
    constructor() {
        this.defaultSettings = {
            autoConnect: true,
            connectionLoggingChannel: "",
            enableConsoleLogging: true,
            advancedFeatures: false,
            clientProperties: "[]",
            automationProperties: "[]"
        };
        
        this.wsConnections = new Map();
        this.reconnectIntervals = new Map();
        this.isConnecting = new Map();
        this.playerNames = new Map();
        this.disconnectMessageSent = new Map();
        this.sentMessageNonces = new Set();
        this.forwardedToDiscordMessages = new Set();
        this.processedDiscordMessageIds = new Set();
        this.messageQueues = new Map();
        this.isSendingMessage = new Map();
        this.clientTicks = new Map();
        this.automationLastTriggered = new Map();
        
        this.isSubscribed = false;
        this.observer = null;
        this.statusRefreshInterval = null;
        this.boundHandleDiscordMessage = this.handleDiscordMessage.bind(this);
        this.chatDelayEnabled = false;
        this.delayedMessages = [];
        this.TICK_SYNC_BUFFER = 5;
        
        // Cached webpack modules
        this._modules = null;
    }

    getName() { return "MinecraftChat"; }
    getAuthor() { return "Aurick"; }
    getDescription() { return "Bridge Discord channel chat with multiple Minecraft clients via WebSocket"; }
    getVersion() { return "1.0.2"; }

    get modules() {
        if (!this._modules) {
            this._modules = {
                Dispatcher: BdApi.Webpack.getModule(m => m.dispatch && m.subscribe),
                ChannelStore: BdApi.Webpack.getModule(m => m.getChannel && m.getDMFromUserId),
                UserStore: BdApi.Webpack.getModule(m => m.getCurrentUser && m.getUser),
                RestAPI: BdApi.Webpack.getModule(m => m.post && m.get && m.patch && m.del),
                Constants: BdApi.Webpack.getModule(m => m.Endpoints),
                MessageActions: BdApi.Webpack.getModule(m => m.sendMessage && m._sendMessage)
            };
        }
        return this._modules;
    }

    load() {
        this.settings = BdApi.Data.load(this.getName(), "settings") || { ...this.defaultSettings };
    }

    start() {
        this.log("Plugin starting...");
        this.addChatBarButton();

        if (!this.isSubscribed && this.modules.Dispatcher) {
            this.modules.Dispatcher.subscribe("MESSAGE_CREATE", this.boundHandleDiscordMessage);
            this.isSubscribed = true;
        }

        if (this.settings.autoConnect) {
            setTimeout(() => {
                for (const client of this.getClients()) {
                    if (client.enabled) this.connectWebSocket(client);
                }
            }, 2000);
        }
        this.log("Plugin started!");
    }

    stop() {
        this.log("Plugin stopping...");
        this.removeChatBarButton();

        if (this.isSubscribed && this.modules.Dispatcher) {
            this.modules.Dispatcher.unsubscribe("MESSAGE_CREATE", this.boundHandleDiscordMessage);
            this.isSubscribed = false;
        }

        this.sentMessageNonces.clear();
        this.processedDiscordMessageIds.clear();
        this.forwardedToDiscordMessages.clear();
        this.disconnectMessageSent.clear();
        this.disconnectAllWebSockets();
        this._modules = null;
        this.log("Plugin stopped!");
    }

    log(message, ...args) {
        if (this.settings.enableConsoleLogging) {
            console.log(`[MinecraftChat] ${message}`, ...args);
        }
    }

    saveSettings() { BdApi.Data.save(this.getName(), "settings", this.settings); }
    
    getClients() {
        try { return JSON.parse(this.settings.clientProperties || "[]"); }
        catch { return []; }
    }
    
    saveClients(clients) {
        this.settings.clientProperties = JSON.stringify(clients);
        this.saveSettings();
    }
    
    getAutomations() {
        try { return JSON.parse(this.settings.automationProperties || "[]"); }
        catch { return []; }
    }
    
    saveAutomations(automations) {
        this.settings.automationProperties = JSON.stringify(automations);
        this.saveSettings();
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    getChannelName(channelId) {
        if (!channelId?.trim()) return null;
        return this.modules.ChannelStore?.getChannel(channelId)?.name || null;
    }

    pruneSet(set, maxSize, keepCount) {
        if (set.size > maxSize) {
            const entries = Array.from(set);
            set.clear();
            entries.slice(-keepCount).forEach(e => set.add(e));
        }
    }

    // ============== CHAT DELAY ==============

    isChatDelayEnabled() { return this.chatDelayEnabled; }
    getDelayedMessageCount() { return this.delayedMessages.length; }

    toggleChatDelay() {
        this.chatDelayEnabled = !this.chatDelayEnabled;
        if (!this.chatDelayEnabled && this.delayedMessages.length > 0) this.flushDelayedMessages();
        this.log(`Chat delay ${this.chatDelayEnabled ? "enabled" : "disabled"}`);
        return this.chatDelayEnabled;
    }

    queueDelayedMessage(author, content, channelId, messageId, targetClientIds) {
        this.delayedMessages.push({ author, content, channelId, messageId, targetClientIds });
        this.log(`Message queued (${this.delayedMessages.length} in queue)`);
    }

    clearDelayedMessages() {
        this.log(`Cleared ${this.delayedMessages.length} delayed message(s)`);
        this.delayedMessages.length = 0;
    }

    sendToTargetClients(author, content, targetClientIds, syncGroupTargetTicks) {
        const clients = this.getClients();
        const targets = clients.filter(c => targetClientIds.includes(c.id) && c.enabled);
        if (targets.length === 0) return;
        
        const clientsBySyncGroup = new Map();
        for (const client of targets) {
            const group = client.syncGroup || "A";
            if (!clientsBySyncGroup.has(group)) clientsBySyncGroup.set(group, []);
            clientsBySyncGroup.get(group).push(client);
        }
        
        for (const [syncGroup, groupClients] of clientsBySyncGroup) {
            let targetTick = syncGroupTargetTicks?.get(syncGroup) ?? this.calculateTargetTickForSyncGroup(syncGroup);
            syncGroupTargetTicks?.set(syncGroup, targetTick);
            
            for (const client of groupClients) {
                const ws = this.wsConnections.get(client.id);
                if (ws?.readyState === WebSocket.OPEN) {
                    try {
                        const message = { type: "discord_message", author, content, tickSync: targetTick >= 0, syncGroup };
                        if (targetTick >= 0) message.targetTick = targetTick;
                        ws.send(JSON.stringify(message));
                    } catch (e) {
                        this.log(`Error sending to ${client.name}:`, e);
                    }
                }
            }
        }
    }

    flushDelayedMessages() {
        if (this.delayedMessages.length === 0) return;
        this.log(`Flushing ${this.delayedMessages.length} delayed message(s)`);
        const syncGroupTargetTicks = new Map();
        while (this.delayedMessages.length > 0) {
            const msg = this.delayedMessages.shift();
            if (msg.targetClientIds?.length) {
                this.sendToTargetClients(msg.author, msg.content, msg.targetClientIds, syncGroupTargetTicks);
            } else {
                this.sendToMinecraft(msg.author, msg.content, msg.channelId, msg.messageId, syncGroupTargetTicks);
            }
        }
    }

    // ============== SYNC GROUPS ==============

    calculateTargetTickForSyncGroup(syncGroup) {
        if (syncGroup === "none") return -1;
        const clients = this.getClients().filter(c => c.enabled && (c.syncGroup || "A") === syncGroup);
        if (clients.length === 0) return -1;
        
        let maxTick = -1;
        for (const client of clients) {
            const tick = this.clientTicks.get(client.id);
            if (tick !== undefined && tick >= 0) maxTick = Math.max(maxTick, tick);
        }
        return maxTick < 0 ? -1 : maxTick + this.TICK_SYNC_BUFFER;
    }

    // ============== AUTOMATIONS ==============

    async executeAutomationActions(automation, triggeringClientId) {
        for (const action of automation.actions) {
            switch (action.type) {
                case "message":
                    if (action.content?.trim()) {
                        const targetIds = action.targetClientIds?.length ? action.targetClientIds : automation.clientIds;
                        if (this.chatDelayEnabled) {
                            this.queueDelayedMessage("Automation", action.content, "", undefined, targetIds);
                        } else {
                            this.sendToTargetClients("Automation", action.content, targetIds);
                        }
                    }
                    break;
                case "enable_delay":
                    this.log(`Executing enable_delay action (currently: ${this.chatDelayEnabled})`);
                    this.chatDelayEnabled = true;
                    this.log(`Chat delay is now: ${this.chatDelayEnabled}`);
                    break;
                case "disable_delay":
                    this.log(`Executing disable_delay action (currently: ${this.chatDelayEnabled}, queued: ${this.delayedMessages.length})`);
                    if (this.chatDelayEnabled) {
                        this.chatDelayEnabled = false;
                        if (this.delayedMessages.length > 0) this.flushDelayedMessages();
                    }
                    this.log(`Chat delay is now: ${this.chatDelayEnabled}`);
                    break;
                case "wait":
                    if (action.waitTime > 0) await new Promise(r => setTimeout(r, action.waitTime));
                    break;
                case "discord_message":
                    if (action.content?.trim()) await this.sendLogToDiscord(action.content);
                    break;
            }
        }
    }

    checkAutomations(messageContent, clientId) {
        const automations = this.getAutomations();
        const trimmedContent = messageContent.trim();
        const now = Date.now();
        
        for (const automation of automations) {
            if (!automation.enabled || !automation.clientIds.includes(clientId)) continue;
            
            const lastTriggered = this.automationLastTriggered.get(automation.id) || 0;
            if (now - lastTriggered < automation.cooldown) continue;
            
            const trigger = automation.trigger?.trim();
            if (!trigger) continue;
            
            const matches = automation.isAbsolute ? trimmedContent === trigger : trimmedContent.includes(trigger);
            if (matches) {
                this.log(`Automation "${automation.name}" triggered with ${automation.actions.length} action(s): ${automation.actions.map(a => a.type).join(', ')}`);
                this.automationLastTriggered.set(automation.id, now);
                this.executeAutomationActions(automation, clientId);
            }
        }
    }

    // ============== DISCORD MESSAGING ==============

    async sendDiscordMessage(channelId, content, nonce) {
        const { RestAPI, Constants, MessageActions } = this.modules;
        
        if (RestAPI && Constants?.Endpoints?.MESSAGES) {
            try {
                await RestAPI.post({
                    url: Constants.Endpoints.MESSAGES(channelId),
                    body: { content, flags: 0, mobile_network_type: "unknown", nonce, tts: false }
                });
                return;
            } catch {}
        }

        if (MessageActions) {
            try { MessageActions.sendMessage(channelId, { content, tts: false, nonce }, undefined, {}); }
            catch {}
        }
    }

    async sendLogToDiscord(content) {
        const logChannelId = this.settings.connectionLoggingChannel;
        if (!logChannelId) return;

        const nonce = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
        this.sentMessageNonces.add(nonce);
        await this.sendDiscordMessage(logChannelId, content, nonce);
    }

    getConnectedClientsList() {
        const clients = this.getClients();
        const connected = clients.filter(c => this.wsConnections.get(c.id)?.readyState === WebSocket.OPEN);
        if (connected.length === 0) return "None";
        return connected.map(c => {
            const name = this.playerNames.get(c.id);
            return name ? `• ${c.name} (${name})` : `• ${c.name}`;
        }).join("\n");
    }

    // ============== WEBSOCKET ==============

    connectWebSocket(client, attemptedPort) {
        if (this.wsConnections.get(client.id)?.readyState === WebSocket.OPEN || this.isConnecting.get(client.id)) return;

        this.isConnecting.set(client.id, true);
        const portToUse = attemptedPort ?? client.port;

        try {
            const ws = new WebSocket(`ws://127.0.0.1:${portToUse}`);
            this.wsConnections.set(client.id, ws);

            ws.onopen = () => {
                this.isConnecting.set(client.id, false);
                if (portToUse !== client.port) {
                    this.saveClients(this.getClients().map(c => c.id === client.id ? { ...c, port: portToUse } : c));
                    this.log(`Port ${client.port} taken, using ${portToUse} for "${client.name}"`);
                }
                this.log(`Client "${client.name}" connected on port ${portToUse}`);
                try { ws.send(JSON.stringify({ type: "set_sync_group", syncGroup: client.syncGroup || "A" })); } catch {}
                
                const interval = this.reconnectIntervals.get(client.id);
                if (interval) { clearInterval(interval); this.reconnectIntervals.delete(client.id); }
                this.updateChatButtonColor();
            };

            ws.onmessage = (event) => {
                try { this.handleMinecraftMessage(JSON.parse(event.data), client.id); }
                catch (e) { this.log(`Error parsing message from ${client.name}:`, e); }
            };

            ws.onclose = (event) => {
                this.isConnecting.set(client.id, false);
                
                if (event.code === 1006 && portToUse === client.port) {
                    const clients = this.getClients();
                    const usedPorts = new Set(clients.filter(c => c.id !== client.id).map(c => c.port));
                    let alt = client.port + 1, attempts = 0;
                    while (attempts < 10 && (usedPorts.has(alt) || alt > 65535)) { alt++; attempts++; }
                    if (attempts < 10 && alt <= 65535) {
                        setTimeout(() => this.connectWebSocket(client, alt), 1000);
                        return;
                    }
                }
                
                this.log(`Client "${client.name}" disconnected`);
                if (!this.disconnectMessageSent.get(client.id)) {
                    this.disconnectMessageSent.set(client.id, true);
                    this.sendLogToDiscord(`❌ **Client Disconnected**\n**Client:** ${client.name}\n\n**Connected Clients:**\n${this.getConnectedClientsList()}`);
                }
                
                if (this.settings.autoConnect && client.enabled && !this.reconnectIntervals.has(client.id)) {
                    const interval = setInterval(() => {
                        const currentWs = this.wsConnections.get(client.id);
                        if (!currentWs || currentWs.readyState === WebSocket.CLOSED) {
                            const updatedClient = this.getClients().find(c => c.id === client.id);
                            if (updatedClient?.enabled) this.connectWebSocket(updatedClient);
                            else { clearInterval(interval); this.reconnectIntervals.delete(client.id); }
                        }
                    }, 5000);
                    this.reconnectIntervals.set(client.id, interval);
                }
                this.updateChatButtonColor();
            };

            ws.onerror = () => {};
        } catch {
            this.isConnecting.set(client.id, false);
        }
    }

    disconnectWebSocket(clientId) {
        const interval = this.reconnectIntervals.get(clientId);
        if (interval) { clearInterval(interval); this.reconnectIntervals.delete(clientId); }
        const ws = this.wsConnections.get(clientId);
        if (ws) { ws.close(1000, "Client disabled"); this.wsConnections.delete(clientId); }
        this.disconnectMessageSent.delete(clientId);
        this.updateChatButtonColor();
    }

    disconnectAllWebSockets() {
        for (const clientId of this.wsConnections.keys()) this.disconnectWebSocket(clientId);
    }

    // ============== MESSAGE HANDLING ==============

    handleMinecraftMessage(data, clientId) {
        const client = this.getClients().find(c => c.id === clientId);
        if (!client) return;

        switch (data.type) {
            case "connection_status": {
                const newPlayerName = data.playerName;
                if (newPlayerName && newPlayerName !== "Unknown" && newPlayerName.trim()) {
                    const prev = this.playerNames.get(clientId);
                    const isNewOrReconnect = this.disconnectMessageSent.get(clientId) === true || !this.playerNames.has(clientId);
                    this.playerNames.set(clientId, newPlayerName);
                    if (isNewOrReconnect || prev !== newPlayerName) {
                        this.disconnectMessageSent.set(clientId, false);
                        this.sendLogToDiscord(`✅ **Client Connected**\n**Client:** ${client.name}\n**Player:** ${newPlayerName}\n\n**Connected Clients:**\n${this.getConnectedClientsList()}`);
                    }
                }
                break;
            }
            case "player_info":
                if (data.name && data.name !== "Unknown") this.playerNames.set(clientId, data.name);
                break;
            case "tick_update":
                if (typeof data.tick === "number" && data.tick >= 0) this.clientTicks.set(clientId, data.tick);
                break;
            case "minecraft_message": {
                const author = data.author || "Minecraft";
                const content = data.content || "";
                if (!content) return;
                
                this.checkAutomations(content, clientId);

                const freshClient = this.getClients().find(c => c.id === clientId);
                if (!freshClient?.forwardToDiscord || !freshClient.channelId) return;

                const playerName = this.playerNames.get(clientId);
                if (playerName) {
                    const isOwn = content.startsWith(`<${playerName}>`) || (author !== "System" && author !== "Minecraft" && author === playerName);
                    if (isOwn) return;
                }

                let plainText = (author !== "System" && author !== "Minecraft") ? `${author}: ${content}` : content;
                const hasMultiple = this.getClients().filter(c => c.channelId === freshClient.channelId && c.enabled && c.forwardToDiscord).length > 1;
                if (hasMultiple) plainText = `[${freshClient.name}] ${plainText}`;

                const messageText = plainText.includes("\n") ? `\`\`\`\n${plainText}\n\`\`\`` : `\`${plainText}\``;
                if (!this.messageQueues.has(clientId)) this.messageQueues.set(clientId, []);
                this.messageQueues.get(clientId).push({ plainText, messageText, channelId: freshClient.channelId });
                this.processMessageQueue(clientId);
                break;
            }
            case "run_automation": {
                const automationName = data.name;
                if (automationName) {
                    const success = this.runAutomationByName(automationName, clientId);
                    const ws = this.wsConnections.get(clientId);
                    if (ws?.readyState === WebSocket.OPEN) {
                        try {
                            ws.send(JSON.stringify({
                                type: "automation_result",
                                name: automationName,
                                success,
                                message: success ? `Automation "${automationName}" executed` : `Automation "${automationName}" not found`
                            }));
                        } catch {}
                    }
                }
                break;
            }
            case "get_automations": {
                const ws = this.wsConnections.get(clientId);
                if (ws?.readyState === WebSocket.OPEN) {
                    try {
                        ws.send(JSON.stringify({
                            type: "automations_list",
                            automations: this.getAutomationNames()
                        }));
                    } catch {}
                }
                break;
            }
        }
    }

    async processMessageQueue(clientId) {
        if (this.isSendingMessage.get(clientId)) return;
        const queue = this.messageQueues.get(clientId);
        if (!queue?.length) return;

        this.isSendingMessage.set(clientId, true);

        while (queue.length > 0) {
            const message = queue.shift();
            const messageKey = `${message.channelId}:${message.plainText}`;
            this.forwardedToDiscordMessages.add(messageKey);
            this.pruneSet(this.forwardedToDiscordMessages, 100, 50);
            setTimeout(() => this.forwardedToDiscordMessages.delete(messageKey), 5000);

            const nonce = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
            this.sentMessageNonces.add(nonce);
            
            await this.sendDiscordMessage(message.channelId, message.messageText, nonce);
            await new Promise(r => setTimeout(r, 100));
        }
        this.isSendingMessage.set(clientId, false);
    }

    sendToMinecraft(author, content, channelId, messageId, sharedSyncTicks = null) {
        const clients = this.getClients();
        const matchingClients = clients.filter(c => c.channelId === channelId && c.enabled);
        if (matchingClients.length === 0) return;

        const clientsBySyncGroup = new Map();
        for (const client of matchingClients) {
            const group = client.syncGroup || "A";
            if (!clientsBySyncGroup.has(group)) clientsBySyncGroup.set(group, []);
            clientsBySyncGroup.get(group).push(client);
        }
        
        const syncGroupTargetTicks = sharedSyncTicks || new Map();
        for (const [syncGroup, groupClients] of clientsBySyncGroup) {
            if (syncGroup !== "none" && groupClients.length >= 2 && !syncGroupTargetTicks.has(syncGroup)) {
                const targetTick = this.calculateTargetTickForSyncGroup(syncGroup);
                if (targetTick >= 0) syncGroupTargetTicks.set(syncGroup, targetTick);
            }
        }

        for (const client of matchingClients) {
            const ws = this.wsConnections.get(client.id);
            if (ws?.readyState === WebSocket.OPEN) {
                const syncGroup = client.syncGroup || "A";
                const targetTick = syncGroupTargetTicks.get(syncGroup);
                const useTickSync = targetTick !== undefined && targetTick >= 0;
                const message = { type: "discord_message", author, content, tickSync: useTickSync, syncGroup };
                if (useTickSync) message.targetTick = targetTick;
                if (messageId) message.messageId = messageId;
                try { ws.send(JSON.stringify(message)); } catch {}
            }
        }
    }

    handleDiscordMessage(event) {
        if (!event.message) return;
        const { message } = event;
        const { channel_id: channelId, id: messageId, content: messageContent, author, nonce } = message;

        if (messageId && this.processedDiscordMessageIds.has(messageId)) return;
        if (!this.getClients().some(c => c.channelId === channelId && c.enabled)) return;

        if (messageId) {
            this.processedDiscordMessageIds.add(messageId);
            this.pruneSet(this.processedDiscordMessageIds, 1000, 500);
        }

        if (nonce && this.sentMessageNonces.has(nonce)) {
            this.sentMessageNonces.delete(nonce);
            return;
        }

        const currentUserId = this.modules.UserStore?.getCurrentUser()?.id;
        if (currentUserId === author?.id && (message.pending || message.state === "SENDING" || message.failed)) return;

        if (messageContent) {
            let plainContent = messageContent;
            if (plainContent.startsWith('```') && plainContent.endsWith('```')) {
                plainContent = plainContent.slice(3, -3).trim();
            } else {
                plainContent = plainContent.replace(/^`+|`+$/g, '');
            }
            if (this.forwardedToDiscordMessages.has(`${channelId}:${plainContent}`)) {
                this.forwardedToDiscordMessages.delete(`${channelId}:${plainContent}`);
                return;
            }
        }

        if (!messageContent) return;

        const authorName = author?.username || "Unknown";
        if (this.chatDelayEnabled) {
            this.queueDelayedMessage(authorName, messageContent, channelId, messageId);
            return;
        }
        this.sendToMinecraft(authorName, messageContent, channelId, messageId);
    }

    // ============== CHAT BAR BUTTON ==============

    addChatBarButton() {
        const selector = '[class*="channelTextArea"] [class*="buttons"]';
        this.observer = new MutationObserver(() => {
            const chatBar = document.querySelector(selector);
            if (chatBar && !chatBar.querySelector('.minecraft-chat-button')) this.injectChatButton(chatBar);
        });
        this.observer.observe(document.body, { childList: true, subtree: true });
        setTimeout(() => {
            const chatBar = document.querySelector(selector);
            if (chatBar) this.injectChatButton(chatBar);
        }, 1000);
    }

    removeChatBarButton() {
        if (this.observer) { this.observer.disconnect(); this.observer = null; }
        document.querySelector('.minecraft-chat-button')?.remove();
    }

    injectChatButton(chatBar) {
        if (chatBar.querySelector('.minecraft-chat-button')) return;

        const button = document.createElement('div');
        button.className = 'minecraft-chat-button';
        button.title = 'Minecraft Chat Settings';
        button.style.cssText = 'display:flex;align-items:center;justify-content:center;width:32px;height:32px;cursor:pointer;border-radius:4px;margin:0 4px;';
        button.innerHTML = `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" style="color:${this.hasAnyConnection() ? '#3ba55c' : '#b5bac1'}"><path fill="currentColor" d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>`;

        button.addEventListener('click', () => this.openSettingsModal());
        button.addEventListener('mouseenter', () => button.style.backgroundColor = 'var(--background-modifier-hover)');
        button.addEventListener('mouseleave', () => button.style.backgroundColor = 'transparent');
        chatBar.insertBefore(button, chatBar.firstChild);
    }

    hasAnyConnection() {
        return this.getClients().some(c => c.enabled && this.wsConnections.get(c.id)?.readyState === WebSocket.OPEN);
    }

    updateChatButtonColor() {
        const svg = document.querySelector('.minecraft-chat-button svg');
        if (svg) svg.style.color = this.hasAnyConnection() ? '#3ba55c' : '#b5bac1';
    }

    // ============== SETTINGS MODAL ==============

    openSettingsModal() {
        const modalOverlay = document.createElement('div');
        modalOverlay.className = 'minecraft-chat-modal-overlay';
        modalOverlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);display:flex;align-items:center;justify-content:center;z-index:10000;';
        modalOverlay.innerHTML = this.createModalHTML();

        modalOverlay.addEventListener('click', (e) => {
            if (e.target === modalOverlay) {
                if (this.statusRefreshInterval) clearInterval(this.statusRefreshInterval);
                modalOverlay.remove();
            }
        });

        document.body.appendChild(modalOverlay);
        this.setupModalEventListeners(modalOverlay);
        this.startStatusRefresh(modalOverlay);
    }

    getClientStatus(clientId) {
        const ws = this.wsConnections.get(clientId);
        if (!ws) return { text: 'Disconnected', color: '#ed4245' };
        if (ws.readyState === WebSocket.CONNECTING) return { text: 'Connecting...', color: '#faa61a' };
        if (ws.readyState === WebSocket.OPEN) {
            const playerName = this.playerNames.get(clientId);
            return { text: playerName ? `Connected (${playerName})` : 'Connected', color: '#3ba55c' };
        }
        if (ws.readyState === WebSocket.CLOSING) return { text: 'Closing...', color: '#faa61a' };
        return { text: 'Disconnected', color: '#ed4245' };
    }

    createModalHTML() {
        const clients = this.getClients();
        const advancedFeatures = this.settings.advancedFeatures;

        const clientsHTML = clients.length === 0
            ? '<div style="text-align:center;padding:20px;color:#b5bac1;border:1px dashed #4f545c;border-radius:8px;">No clients configured. Click "Add Client" to get started.</div>'
            : clients.map(c => this.createClientCardHTML(c, advancedFeatures)).join('');

        const chatDelaySection = advancedFeatures ? `
            <div class="chat-delay-section" style="margin-bottom:16px;padding:12px;background:${this.chatDelayEnabled ? '#3ba55c20' : '#2b2d31'};border-radius:8px;border:1px solid ${this.chatDelayEnabled ? '#3ba55c' : '#4f545c'};">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
                    <div style="font-weight:600;color:#fff;">Chat Delay</div>
                    <span class="queue-count-label" style="font-size:12px;color:#faa61a;font-weight:500;${this.chatDelayEnabled ? '' : 'display:none;'}">${this.delayedMessages.length} message${this.delayedMessages.length !== 1 ? 's' : ''} queued</span>
                </div>
                <div class="delay-buttons-container" style="display:flex;align-items:center;gap:8px;margin-bottom:8px;">
                    <button class="toggle-delay-btn" style="padding:8px 16px;color:#fff;background:${this.chatDelayEnabled ? '#ed4245' : '#3ba55c'};border:none;border-radius:4px;cursor:pointer;font-weight:500;min-width:140px;">${this.chatDelayEnabled ? 'Disable & Send' : 'Enable Delay'}</button>
                    <button class="clear-queue-btn" style="padding:8px 16px;color:#fff;background:#4f545c;border:none;border-radius:4px;cursor:pointer;font-weight:500;${this.chatDelayEnabled && this.delayedMessages.length > 0 ? '' : 'display:none;'}">Clear Queue</button>
                </div>
                <div class="delay-description" style="font-size:12px;color:#b5bac1;">${this.chatDelayEnabled ? 'Messages are being queued. Disable to send all at the same game tick.' : 'Enable to queue messages for tick-perfect execution across multiple clients.'}</div>
            </div>` : '';

        const automationsSection = advancedFeatures ? this.createAutomationsSectionHTML() : '';

        return `
            <div class="minecraft-chat-modal" style="background:#313338;border-radius:8px;width:500px;max-height:80vh;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.5);">
                <div style="display:flex;justify-content:space-between;align-items:center;padding:16px;border-bottom:1px solid #4f545c;">
                    <h2 style="margin:0;color:#fff;font-size:20px;font-weight:600;">Minecraft Chat Settings</h2>
                    <button class="modal-close-btn" style="background:none;border:none;color:#b5bac1;cursor:pointer;font-size:24px;padding:0;line-height:1;">&times;</button>
                </div>
                <div class="modal-scroll-container" style="padding:16px;max-height:calc(80vh - 60px);overflow-y:auto;">
                    <div style="margin-bottom:16px;padding:12px;background:#2b2d31;border-radius:8px;border:1px solid #4f545c;">
                        <div style="margin-bottom:10px;font-weight:600;color:#fff;">Global Settings</div>
                        <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;">
                            <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;cursor:pointer;">
                                <input type="checkbox" class="auto-connect-checkbox" ${this.settings.autoConnect ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">
                                Auto Connect on Discord startup
                            </label>
                        </div>
                        <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;">
                            <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;cursor:pointer;">
                                <input type="checkbox" class="advanced-features-checkbox" ${advancedFeatures ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">
                                Advanced Features (Chat Delay, Sync Groups, Automations)
                            </label>
                        </div>
                        <div>
                            <label style="display:block;margin-bottom:4px;color:#b5bac1;font-size:12px;">Connection Logging Channel ID</label>
                            <input type="text" class="log-channel-input" value="${this.settings.connectionLoggingChannel || ''}" placeholder="Enter channel ID for connection logs" style="width:100%;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                        </div>
                    </div>
                    ${chatDelaySection}
                    <div style="margin-bottom:12px;">
                        <button class="add-client-btn" style="padding:8px 16px;color:#fff;background:#3ba55c;border:none;border-radius:4px;cursor:pointer;font-weight:500;">+ Add Client</button>
                    </div>
                    <div class="clients-container">${clientsHTML}</div>
                    ${automationsSection}
                </div>
            </div>`;
    }

    createClientCardHTML(client, advancedFeatures = false) {
        const status = this.getClientStatus(client.id);
        const channelName = this.getChannelName(client.channelId);

        const syncGroupSelect = advancedFeatures ? `
            <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;">
                Sync Group:
                <select class="client-syncgroup-select" data-client-id="${client.id}" style="padding:4px 8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;cursor:pointer;font-size:13px;">
                    ${['none', 'A', 'B', 'C', 'D', 'E', 'F'].map(g => `<option value="${g}" ${(client.syncGroup || 'A') === g ? 'selected' : ''}>${g === 'none' ? 'None' : g}</option>`).join('')}
                </select>
            </label>` : '';

        return `
            <div class="client-card" data-client-id="${client.id}" style="border:1px solid #4f545c;padding:12px;margin-bottom:12px;border-radius:8px;background:#2b2d31;">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
                    <strong style="color:#fff;font-size:14px;">${this.escapeHtml(client.name)}</strong>
                    <button class="remove-client-btn" data-client-id="${client.id}" style="padding:4px 12px;color:#fff;background:#ed4245;border:none;border-radius:4px;cursor:pointer;font-size:12px;">Remove</button>
                </div>
                <div style="display:grid;gap:8px;">
                    <div>
                        <label style="display:block;margin-bottom:4px;color:#b5bac1;font-size:12px;">Name</label>
                        <input type="text" class="client-name-input" data-client-id="${client.id}" value="${this.escapeHtml(client.name)}" style="width:100%;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                    </div>
                    <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;">
                        <div>
                            <label style="display:block;margin-bottom:4px;color:#b5bac1;font-size:12px;">Port</label>
                            <input type="text" class="client-port-input" data-client-id="${client.id}" value="${client.port}" style="width:100%;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                        </div>
                        <div>
                            <label style="display:block;margin-bottom:4px;color:#b5bac1;font-size:12px;">Channel ID${channelName ? ` (${this.escapeHtml(channelName)})` : ''}</label>
                            <input type="text" class="client-channel-input" data-client-id="${client.id}" value="${client.channelId}" placeholder="Enter channel ID" style="width:100%;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                        </div>
                    </div>
                    <div style="display:flex;gap:16px;margin-top:4px;flex-wrap:wrap;align-items:center;">
                        <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;cursor:pointer;">
                            <input type="checkbox" class="client-enabled-checkbox" data-client-id="${client.id}" ${client.enabled ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">Enabled
                        </label>
                        <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;cursor:pointer;">
                            <input type="checkbox" class="client-forward-checkbox" data-client-id="${client.id}" ${client.forwardToDiscord ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">Forward to Discord
                        </label>
                        ${syncGroupSelect}
                    </div>
                    <div class="client-status" data-client-id="${client.id}" style="font-size:12px;color:#b5bac1;margin-top:4px;display:flex;align-items:center;gap:8px;">
                        <span>Status: <span style="color:${status.color};font-weight:500;">${status.text}</span></span>
                        <button class="refresh-client-btn" data-client-id="${client.id}" style="padding:2px 8px;font-size:11px;color:#fff;background:#4f545c;border:none;border-radius:3px;cursor:pointer;" title="Refresh connection status">↻ Refresh</button>
                    </div>
                </div>
            </div>`;
    }

    createAutomationsSectionHTML() {
        const automations = this.getAutomations();
        const clients = this.getClients();

        const automationsHTML = automations.length === 0
            ? '<div style="text-align:center;padding:20px;color:#b5bac1;border:1px dashed #4f545c;border-radius:8px;">No automations configured. Click "Add Automation" to create one.</div>'
            : automations.map(a => this.createAutomationCardHTML(a, clients)).join('');

        return `
            <div style="margin-top:24px;border-top:1px solid #4f545c;padding-top:16px;">
                <div style="margin-bottom:12px;font-weight:600;color:#fff;font-size:16px;">Automations</div>
                <div style="margin-bottom:12px;">
                    <button class="add-automation-btn" style="padding:8px 16px;color:#fff;background:#5865f2;border:none;border-radius:4px;cursor:pointer;font-weight:500;">+ Add Automation</button>
                </div>
                <div class="automations-container">${automationsHTML}</div>
            </div>`;
    }

    createAutomationCardHTML(automation, clients) {
        const clientCheckboxes = clients.map(c => `
            <label style="display:flex;align-items:center;gap:6px;padding:4px 8px;background:${automation.clientIds.includes(c.id) ? '#5865f220' : '#1e1f22'};border:1px solid ${automation.clientIds.includes(c.id) ? '#5865f2' : '#4f545c'};border-radius:4px;cursor:pointer;font-size:12px;color:#b5bac1;">
                <input type="checkbox" class="automation-client-checkbox" data-automation-id="${automation.id}" data-client-id="${c.id}" ${automation.clientIds.includes(c.id) ? 'checked' : ''} style="cursor:pointer;">
                ${this.escapeHtml(c.name)}
            </label>`).join('');

        const actionsHTML = automation.actions.map((action, i) => `
            <div class="action-item" data-automation-id="${automation.id}" data-action-index="${i}" style="display:flex;gap:8px;align-items:center;margin-bottom:8px;padding:8px;background:#1e1f22;border-radius:4px;">
                <span style="color:#72767d;font-size:12px;min-width:20px;">${i + 1}.</span>
                ${this.createActionContentHTML(action, automation.id, i, automation)}
                <div style="display:flex;gap:2px;margin-left:auto;">
                    <button class="move-action-up-btn" data-automation-id="${automation.id}" data-action-index="${i}" ${i === 0 ? 'disabled' : ''} style="padding:4px 6px;color:${i === 0 ? '#4f545c' : '#b5bac1'};background:transparent;border:1px solid #4f545c;border-radius:4px;cursor:${i === 0 ? 'not-allowed' : 'pointer'};font-size:10px;" title="Move up">▲</button>
                    <button class="move-action-down-btn" data-automation-id="${automation.id}" data-action-index="${i}" ${i === automation.actions.length - 1 ? 'disabled' : ''} style="padding:4px 6px;color:${i === automation.actions.length - 1 ? '#4f545c' : '#b5bac1'};background:transparent;border:1px solid #4f545c;border-radius:4px;cursor:${i === automation.actions.length - 1 ? 'not-allowed' : 'pointer'};font-size:10px;" title="Move down">▼</button>
                </div>
                <button class="remove-action-btn" data-automation-id="${automation.id}" data-action-index="${i}" style="padding:4px 8px;color:#ed4245;background:transparent;border:1px solid #ed4245;border-radius:4px;cursor:pointer;font-size:11px;">✕</button>
            </div>`).join('');

        return `
            <div class="automation-card" data-automation-id="${automation.id}" style="border:1px solid #4f545c;padding:12px;margin-bottom:12px;border-radius:8px;background:#2b2d31;">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
                    <div style="display:flex;align-items:center;gap:10px;">
                        <label style="display:flex;align-items:center;gap:6px;cursor:pointer;">
                            <input type="checkbox" class="automation-enabled-checkbox" data-automation-id="${automation.id}" ${automation.enabled ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">
                        </label>
                        <input type="text" class="automation-name-input" data-automation-id="${automation.id}" value="${this.escapeHtml(automation.name)}" style="padding:6px 10px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;font-weight:600;font-size:14px;width:200px;">
                    </div>
                    <div style="display:flex;gap:8px;">
                        <button class="run-automation-btn" data-automation-id="${automation.id}" style="padding:4px 12px;color:#fff;background:#5865f2;border:none;border-radius:4px;cursor:pointer;font-size:12px;" title="Run this automation manually">▶ Run</button>
                        <button class="remove-automation-btn" data-automation-id="${automation.id}" style="padding:4px 12px;color:#fff;background:#ed4245;border:none;border-radius:4px;cursor:pointer;font-size:12px;">Remove</button>
                    </div>
                </div>
                <div style="margin-bottom:10px;">
                    <label style="display:block;margin-bottom:6px;color:#b5bac1;font-size:12px;">Listen to Clients:</label>
                    <div style="display:flex;flex-wrap:wrap;gap:8px;">${clients.length === 0 ? '<span style="color:#72767d;font-size:12px;font-style:italic;">No clients configured</span>' : clientCheckboxes}</div>
                </div>
                <div style="margin-bottom:10px;">
                    <label style="display:block;margin-bottom:6px;color:#b5bac1;font-size:12px;">Trigger:</label>
                    <div style="display:flex;gap:8px;align-items:center;">
                        <input type="text" class="automation-trigger-input" data-automation-id="${automation.id}" value="${this.escapeHtml(automation.trigger || '')}" placeholder="Enter trigger text..." style="flex:1;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                        <label style="display:flex;align-items:center;gap:6px;color:#b5bac1;font-size:13px;cursor:pointer;white-space:nowrap;">
                            <input type="checkbox" class="automation-absolute-checkbox" data-automation-id="${automation.id}" ${automation.isAbsolute ? 'checked' : ''} style="cursor:pointer;width:16px;height:16px;">Absolute Match
                        </label>
                    </div>
                    <div style="font-size:11px;color:#72767d;margin-top:4px;">${automation.isAbsolute ? 'Message must exactly match the trigger text' : 'Message must contain the trigger text'}</div>
                </div>
                <div style="margin-bottom:10px;">
                    <label style="display:block;margin-bottom:6px;color:#b5bac1;font-size:12px;">Actions:</label>
                    ${automation.actions.length === 0 ? '<div style="padding:10px;background:#1e1f22;border-radius:4px;color:#72767d;font-size:12px;margin-bottom:8px;">No actions configured. Add an action below.</div>' : ''}
                    <div class="actions-container" data-automation-id="${automation.id}">${actionsHTML}</div>
                    <div style="display:flex;gap:8px;">
                        <select class="action-type-select" data-automation-id="${automation.id}" style="padding:6px 10px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;cursor:pointer;font-size:12px;">
                            <option value="message">Send to Minecraft</option>
                            <option value="discord_message">Send to Discord</option>
                            <option value="wait">Wait</option>
                            <option value="enable_delay">Enable Chat Delay</option>
                            <option value="disable_delay">Disable Chat Delay</option>
                        </select>
                        <button class="add-action-btn" data-automation-id="${automation.id}" style="padding:6px 12px;color:#fff;background:#4f545c;border:none;border-radius:4px;cursor:pointer;font-size:12px;">+ Add Action</button>
                    </div>
                </div>
                <div>
                    <label style="display:block;margin-bottom:4px;color:#b5bac1;font-size:12px;">Cooldown (ms)</label>
                    <input type="number" class="automation-cooldown-input" data-automation-id="${automation.id}" value="${automation.cooldown || 1000}" min="0" style="width:200px;padding:8px;color:#fff;background:#1e1f22;border:1px solid #4f545c;border-radius:4px;box-sizing:border-box;">
                    <div style="font-size:11px;color:#72767d;margin-top:2px;">Time before automation can trigger again</div>
                </div>
            </div>`;
    }

    createActionContentHTML(action, automationId, actionIndex, automation) {
        const clients = this.getClients();
        
        switch (action.type) {
            case 'message': {
                const clientCheckboxes = clients.map(c => {
                    const isSelected = action.targetClientIds?.length ? action.targetClientIds.includes(c.id) : automation.clientIds.includes(c.id);
                    const isDefault = !action.targetClientIds?.length;
                    return `<label style="display:flex;align-items:center;gap:4px;padding:2px 6px;background:${isSelected ? '#3ba55c20' : '#2b2d31'};border:1px solid ${isSelected ? '#3ba55c' : '#4f545c'};border-radius:4px;cursor:pointer;font-size:11px;color:#b5bac1;opacity:${isDefault ? '0.7' : '1'};">
                        <input type="checkbox" class="action-target-client-checkbox" data-automation-id="${automationId}" data-action-index="${actionIndex}" data-client-id="${c.id}" ${isSelected ? 'checked' : ''} style="cursor:pointer;width:12px;height:12px;">${this.escapeHtml(c.name)}
                    </label>`;
                }).join('');
                const defaultNote = !action.targetClientIds?.length ? '<span style="font-size:10px;color:#72767d;font-style:italic;">(using trigger clients)</span>' : '';
                return `<div style="flex:1;display:flex;flex-direction:column;gap:6px;">
                    <div style="display:flex;align-items:center;gap:8px;">
                        <span style="color:#3ba55c;font-size:12px;min-width:70px;">Minecraft:</span>
                        <input type="text" class="action-content-input" data-automation-id="${automationId}" data-action-index="${actionIndex}" value="${this.escapeHtml(action.content || '')}" placeholder="Enter message or command..." style="flex:1;padding:6px 8px;color:#fff;background:#2b2d31;border:1px solid #4f545c;border-radius:4px;font-size:13px;">
                    </div>
                    <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
                        <span style="color:#72767d;font-size:11px;">Send to:</span>${clientCheckboxes}${defaultNote}
                    </div>
                </div>`;
            }
            case 'discord_message':
                return `<span style="color:#5865f2;font-size:12px;min-width:70px;">Discord:</span>
                    <input type="text" class="action-content-input" data-automation-id="${automationId}" data-action-index="${actionIndex}" value="${this.escapeHtml(action.content || '')}" placeholder="Message to send to logging channel..." style="flex:1;padding:6px 8px;color:#fff;background:#2b2d31;border:1px solid #4f545c;border-radius:4px;font-size:13px;">`;
            case 'wait':
                return `<span style="color:#72767d;font-size:12px;min-width:50px;">Wait:</span>
                    <input type="number" class="action-wait-input" data-automation-id="${automationId}" data-action-index="${actionIndex}" value="${action.waitTime || 0}" min="0" placeholder="ms" style="width:100px;padding:6px 8px;color:#fff;background:#2b2d31;border:1px solid #4f545c;border-radius:4px;font-size:13px;">
                    <span style="color:#72767d;font-size:12px;">ms</span>`;
            case 'enable_delay':
                return '<span style="color:#3ba55c;font-size:13px;">Enable Chat Delay</span>';
            case 'disable_delay':
                return '<span style="color:#faa61a;font-size:13px;">Disable Chat Delay (& Send Queue)</span>';
            default:
                return '<span style="color:#72767d;font-size:13px;">Unknown action</span>';
        }
    }

    setupModalEventListeners(modalOverlay) {
        const $ = (sel) => modalOverlay.querySelector(sel);
        const $$ = (sel) => modalOverlay.querySelectorAll(sel);

        $('.modal-close-btn').addEventListener('click', () => {
            if (this.statusRefreshInterval) clearInterval(this.statusRefreshInterval);
            modalOverlay.remove();
        });

        $('.auto-connect-checkbox').addEventListener('change', (e) => {
            this.settings.autoConnect = e.target.checked;
            this.saveSettings();
        });

        $('.advanced-features-checkbox')?.addEventListener('change', (e) => {
            this.settings.advancedFeatures = e.target.checked;
            this.saveSettings();
            this.refreshModal(modalOverlay);
        });

        $('.log-channel-input').addEventListener('blur', (e) => {
            this.settings.connectionLoggingChannel = e.target.value;
            this.saveSettings();
        });

        $('.toggle-delay-btn')?.addEventListener('click', () => {
            this.toggleChatDelay();
        });

        $('.clear-queue-btn')?.addEventListener('click', () => {
            this.clearDelayedMessages();
        });

        $('.add-client-btn').addEventListener('click', () => this.addClient(modalOverlay));
        $('.add-automation-btn')?.addEventListener('click', () => this.addAutomation(modalOverlay));

        this.setupClientEventListeners(modalOverlay);
        this.setupAutomationEventListeners(modalOverlay);
    }

    refreshModal(modalOverlay) {
        const modalContent = modalOverlay.querySelector('.minecraft-chat-modal');
        if (modalContent) {
            const scrollContainer = modalContent.querySelector('.modal-scroll-container');
            const scrollTop = scrollContainer ? scrollContainer.scrollTop : 0;
            modalContent.outerHTML = this.createModalHTML();
            this.setupModalEventListeners(modalOverlay);
            this.startStatusRefresh(modalOverlay);
            requestAnimationFrame(() => {
                const newScrollContainer = modalOverlay.querySelector('.modal-scroll-container');
                if (newScrollContainer) newScrollContainer.scrollTop = scrollTop;
            });
        }
    }

    setupClientEventListeners(modalOverlay) {
        const $$ = (sel) => modalOverlay.querySelectorAll(sel);

        $$('.remove-client-btn').forEach(btn => btn.addEventListener('click', (e) => this.removeClient(e.target.dataset.clientId, modalOverlay)));
        $$('.client-name-input').forEach(input => input.addEventListener('blur', (e) => this.updateClient(e.target.dataset.clientId, { name: e.target.value }, modalOverlay)));
        $$('.client-port-input').forEach(input => input.addEventListener('blur', (e) => this.updateClient(e.target.dataset.clientId, { port: parseInt(e.target.value) || 0 }, modalOverlay)));
        $$('.client-channel-input').forEach(input => input.addEventListener('blur', (e) => this.updateClient(e.target.dataset.clientId, { channelId: e.target.value }, modalOverlay)));
        $$('.client-enabled-checkbox').forEach(cb => cb.addEventListener('change', (e) => this.updateClient(e.target.dataset.clientId, { enabled: e.target.checked }, modalOverlay)));
        $$('.client-forward-checkbox').forEach(cb => cb.addEventListener('change', (e) => this.updateClient(e.target.dataset.clientId, { forwardToDiscord: e.target.checked }, modalOverlay)));
        
        $$('.client-syncgroup-select').forEach(select => select.addEventListener('change', (e) => {
            const clientId = e.target.dataset.clientId;
            this.updateClient(clientId, { syncGroup: e.target.value }, modalOverlay);
            const ws = this.wsConnections.get(clientId);
            if (ws?.readyState === WebSocket.OPEN) {
                try { ws.send(JSON.stringify({ type: "set_sync_group", syncGroup: e.target.value })); } catch {}
            }
        }));

        $$('.refresh-client-btn').forEach(btn => btn.addEventListener('click', (e) => this.refreshClientStatus(e.target.dataset.clientId)));
    }

    refreshClientStatus(clientId) {
        const client = this.getClients().find(c => c.id === clientId);
        if (!client) return;
        
        const ws = this.wsConnections.get(clientId);
        if (ws?.readyState === WebSocket.OPEN) {
            try { ws.send(JSON.stringify({ type: "request_player_info" })); } catch {}
        } else {
            this.disconnectWebSocket(clientId);
            if (client.enabled) this.connectWebSocket(client);
        }
    }

    setupAutomationEventListeners(modalOverlay) {
        const $$ = (sel) => modalOverlay.querySelectorAll(sel);

        $$('.run-automation-btn').forEach(btn => btn.addEventListener('click', (e) => this.runAutomationManually(e.target.dataset.automationId)));
        $$('.remove-automation-btn').forEach(btn => btn.addEventListener('click', (e) => this.removeAutomation(e.target.dataset.automationId, modalOverlay)));
        $$('.automation-enabled-checkbox').forEach(cb => cb.addEventListener('change', (e) => this.updateAutomation(e.target.dataset.automationId, { enabled: e.target.checked })));
        $$('.automation-name-input').forEach(input => input.addEventListener('blur', (e) => this.updateAutomation(e.target.dataset.automationId, { name: e.target.value })));
        $$('.automation-trigger-input').forEach(input => input.addEventListener('blur', (e) => this.updateAutomation(e.target.dataset.automationId, { trigger: e.target.value })));
        $$('.automation-cooldown-input').forEach(input => input.addEventListener('blur', (e) => this.updateAutomation(e.target.dataset.automationId, { cooldown: Math.max(0, parseInt(e.target.value) || 0) })));

        $$('.automation-absolute-checkbox').forEach(cb => cb.addEventListener('change', (e) => {
            this.updateAutomation(e.target.dataset.automationId, { isAbsolute: e.target.checked });
            this.refreshModal(modalOverlay);
        }));

        $$('.automation-client-checkbox').forEach(cb => cb.addEventListener('change', (e) => {
            const { automationId, clientId } = e.target.dataset;
            const automation = this.getAutomations().find(a => a.id === automationId);
            if (!automation) return;
            const clientIds = e.target.checked ? [...automation.clientIds, clientId] : automation.clientIds.filter(id => id !== clientId);
            this.updateAutomation(automationId, { clientIds });
            this.refreshModal(modalOverlay);
        }));

        $$('.add-action-btn').forEach(btn => btn.addEventListener('click', (e) => {
            const automationId = e.target.dataset.automationId;
            const select = modalOverlay.querySelector(`.action-type-select[data-automation-id="${automationId}"]`);
            this.addAction(automationId, select?.value || 'message', modalOverlay);
        }));

        $$('.remove-action-btn').forEach(btn => btn.addEventListener('click', (e) => this.removeAction(e.target.dataset.automationId, parseInt(e.target.dataset.actionIndex), modalOverlay)));
        $$('.move-action-up-btn').forEach(btn => btn.addEventListener('click', (e) => this.moveAction(e.target.dataset.automationId, parseInt(e.target.dataset.actionIndex), 'up', modalOverlay)));
        $$('.move-action-down-btn').forEach(btn => btn.addEventListener('click', (e) => this.moveAction(e.target.dataset.automationId, parseInt(e.target.dataset.actionIndex), 'down', modalOverlay)));
        $$('.action-content-input').forEach(input => input.addEventListener('blur', (e) => this.updateAction(e.target.dataset.automationId, parseInt(e.target.dataset.actionIndex), { content: e.target.value })));
        $$('.action-wait-input').forEach(input => input.addEventListener('blur', (e) => this.updateAction(e.target.dataset.automationId, parseInt(e.target.dataset.actionIndex), { waitTime: Math.max(0, parseInt(e.target.value) || 0) })));

        $$('.action-target-client-checkbox').forEach(cb => cb.addEventListener('change', (e) => {
            const { automationId, actionIndex, clientId } = e.target.dataset;
            const automation = this.getAutomations().find(a => a.id === automationId);
            if (!automation?.actions[actionIndex]) return;
            const action = automation.actions[actionIndex];
            const currentTargets = action.targetClientIds?.length ? [...action.targetClientIds] : [...automation.clientIds];
            const newTargets = e.target.checked ? [...currentTargets, clientId] : currentTargets.filter(id => id !== clientId);
            this.updateAction(automationId, parseInt(actionIndex), { targetClientIds: newTargets });
            this.refreshModal(modalOverlay);
        }));
    }

    startStatusRefresh(modalOverlay) {
        this.statusRefreshInterval = setInterval(() => {
            // Update client statuses
            for (const client of this.getClients()) {
                const statusEl = modalOverlay.querySelector(`.client-status[data-client-id="${client.id}"] span`);
                if (statusEl) {
                    const status = this.getClientStatus(client.id);
                    statusEl.textContent = status.text;
                    statusEl.style.color = status.color;
                }
            }
            
            // Update chat delay section
            const delaySection = modalOverlay.querySelector('.chat-delay-section');
            if (delaySection) {
                const count = this.delayedMessages.length;
                const enabled = this.chatDelayEnabled;
                
                // Update section styling
                delaySection.style.background = enabled ? '#3ba55c20' : '#2b2d31';
                delaySection.style.borderColor = enabled ? '#3ba55c' : '#4f545c';
                
                // Update queue count label
                const queueLabel = delaySection.querySelector('.queue-count-label');
                if (queueLabel) {
                    queueLabel.textContent = `${count} message${count !== 1 ? 's' : ''} queued`;
                    queueLabel.style.display = enabled ? '' : 'none';
                }
                
                // Update toggle button
                const toggleBtn = delaySection.querySelector('.toggle-delay-btn');
                if (toggleBtn) {
                    toggleBtn.textContent = enabled ? 'Disable & Send' : 'Enable Delay';
                    toggleBtn.style.background = enabled ? '#ed4245' : '#3ba55c';
                }
                
                // Update clear queue button visibility
                const clearBtn = delaySection.querySelector('.clear-queue-btn');
                if (clearBtn) {
                    clearBtn.style.display = (enabled && count > 0) ? '' : 'none';
                }
                
                // Update description text
                const desc = delaySection.querySelector('.delay-description');
                if (desc) {
                    desc.textContent = enabled 
                        ? 'Messages are being queued. Disable to send all at the same game tick.'
                        : 'Enable to queue messages for tick-perfect execution across multiple clients.';
                }
            }
        }, 500);
    }

    // ============== CLIENT/AUTOMATION MANAGEMENT ==============

    addClient(modalOverlay) {
        const clients = this.getClients();
        clients.push({
            id: `client_${Date.now()}`,
            name: `Client ${clients.length + 1}`,
            port: 25580 + clients.length,
            channelId: "",
            enabled: true,
            forwardToDiscord: false,
            syncGroup: "A"
        });
        this.saveClients(clients);
        this.refreshModal(modalOverlay);
    }

    removeClient(clientId, modalOverlay) {
        this.disconnectWebSocket(clientId);
        this.playerNames.delete(clientId);
        this.saveClients(this.getClients().filter(c => c.id !== clientId));
        this.refreshModal(modalOverlay);
    }

    updateClient(clientId, updates, modalOverlay) {
        const clients = this.getClients();
        const oldClient = clients.find(c => c.id === clientId);
        const updated = clients.map(c => c.id === clientId ? { ...c, ...updates } : c);
        this.saveClients(updated);

        const newClient = updated.find(c => c.id === clientId);
        if (!newClient) return;

        const portChanged = oldClient && oldClient.port !== newClient.port;
        const enabledChanged = oldClient && oldClient.enabled !== newClient.enabled;

        if (portChanged || (enabledChanged && !newClient.enabled)) this.disconnectWebSocket(clientId);
        if (newClient.enabled && (!this.wsConnections.has(clientId) || portChanged || enabledChanged)) {
            setTimeout(() => this.connectWebSocket(newClient), 100);
        }

        if (updates.name) {
            const title = modalOverlay.querySelector(`.client-card[data-client-id="${clientId}"] strong`);
            if (title) title.textContent = updates.name;
        }
    }

    addAutomation(modalOverlay) {
        const automations = this.getAutomations();
        automations.push({
            id: `automation_${Date.now()}`,
            name: `Automation ${automations.length + 1}`,
            enabled: true,
            clientIds: [],
            trigger: "",
            isAbsolute: false,
            actions: [],
            cooldown: 1000
        });
        this.saveAutomations(automations);
        this.refreshModal(modalOverlay);
    }

    removeAutomation(automationId, modalOverlay) {
        this.automationLastTriggered.delete(automationId);
        this.saveAutomations(this.getAutomations().filter(a => a.id !== automationId));
        this.refreshModal(modalOverlay);
    }

    updateAutomation(automationId, updates) {
        this.saveAutomations(this.getAutomations().map(a => a.id === automationId ? { ...a, ...updates } : a));
    }

    runAutomationManually(automationId) {
        const automation = this.getAutomations().find(a => a.id === automationId);
        if (!automation) {
            this.log(`Automation not found: ${automationId}`);
            return;
        }
        this.log(`Manually running automation: "${automation.name}"`);
        this.executeAutomationActions(automation, null);
    }

    runAutomationByName(name, clientId = null) {
        const automations = this.getAutomations();
        const automation = automations.find(a => a.name.toLowerCase() === name.toLowerCase());
        if (!automation) {
            this.log(`Automation not found by name: "${name}"`);
            // Send error back to Minecraft
            if (clientId) {
                const ws = this.wsConnections.get(clientId);
                if (ws?.readyState === WebSocket.OPEN) {
                    try { ws.send(JSON.stringify({ type: "automation_error", message: `Automation "${name}" not found` })); } catch {}
                }
            }
            return false;
        }
        this.log(`Running automation by name: "${automation.name}"`);
        this.executeAutomationActions(automation, clientId);
        return true;
    }

    getAutomationNames() {
        return this.getAutomations().map(a => a.name);
    }

    addAction(automationId, actionType, modalOverlay) {
        const automations = this.getAutomations();
        const automation = automations.find(a => a.id === automationId);
        if (!automation) return;

        automation.actions.push({
            type: actionType,
            content: ['message', 'discord_message'].includes(actionType) ? '' : undefined,
            waitTime: actionType === 'wait' ? 1000 : undefined
        });
        this.saveAutomations(automations);
        this.refreshModal(modalOverlay);
    }

    removeAction(automationId, actionIndex, modalOverlay) {
        const automations = this.getAutomations();
        const automation = automations.find(a => a.id === automationId);
        if (!automation) return;
        automation.actions.splice(actionIndex, 1);
        this.saveAutomations(automations);
        this.refreshModal(modalOverlay);
    }

    moveAction(automationId, actionIndex, direction, modalOverlay) {
        const automations = this.getAutomations();
        const automation = automations.find(a => a.id === automationId);
        if (!automation) return;

        const newIndex = direction === 'up' ? actionIndex - 1 : actionIndex + 1;
        if (newIndex < 0 || newIndex >= automation.actions.length) return;

        const [movedAction] = automation.actions.splice(actionIndex, 1);
        automation.actions.splice(newIndex, 0, movedAction);
        this.saveAutomations(automations);
        this.refreshModal(modalOverlay);
    }

    updateAction(automationId, actionIndex, updates) {
        const automations = this.getAutomations();
        const automation = automations.find(a => a.id === automationId);
        if (!automation?.actions[actionIndex]) return;
        automation.actions[actionIndex] = { ...automation.actions[actionIndex], ...updates };
        this.saveAutomations(automations);
    }

    // ============== BETTERDISCORD SETTINGS PANEL ==============

    getSettingsPanel() {
        const panel = document.createElement('div');
        panel.style.cssText = 'padding:16px;color:#ffffff;';

        const hasConnection = this.hasAnyConnection();
        const clients = this.getClients();

        panel.innerHTML = `
            <div style="display:flex;align-items:center;gap:12px;padding:12px 16px;background:${hasConnection ? '#3ba55c20' : '#ed424520'};border-radius:8px;border:1px solid ${hasConnection ? '#3ba55c' : '#ed4245'};margin-bottom:20px;">
                <div style="width:10px;height:10px;border-radius:50%;background:${hasConnection ? '#3ba55c' : '#ed4245'};"></div>
                <div>
                    <div style="font-weight:600;color:#fff;">${hasConnection ? 'Connected' : 'Disconnected'}</div>
                    <div style="font-size:13px;color:#b5bac1;">${clients.length} client${clients.length !== 1 ? 's' : ''} configured</div>
                </div>
            </div>
            ${this.createSettingToggle('mc-auto-connect', 'Auto Connect', 'Automatically connect to all enabled clients on Discord startup', this.settings.autoConnect)}
            ${this.createSettingToggle('mc-advanced-features', 'Advanced Features', 'Enable advanced features: Chat Delay, Sync Groups, and Automations', this.settings.advancedFeatures)}
            <div class="mc-setting-item" style="margin-bottom:16px;">
                <div style="font-weight:500;color:#fff;margin-bottom:4px;">Connection Logging Channel</div>
                <div style="font-size:12px;color:#b5bac1;margin-bottom:8px;">Discord channel ID where connection/disconnection events are posted</div>
                <input type="text" id="mc-log-channel" value="${this.settings.connectionLoggingChannel || ''}" placeholder="Enter channel ID" style="width:100%;padding:10px;background:#1e1f22;border:1px solid #3f4147;border-radius:4px;color:#fff;box-sizing:border-box;font-size:14px;">
            </div>
            ${this.createSettingToggle('mc-console-logging', 'Enable Console Logging', 'Log plugin debug messages to browser console (DevTools F12)', this.settings.enableConsoleLogging)}
        `;

        const updateToggle = (cb, checked) => {
            const label = cb.parentElement;
            label.querySelector('span:first-of-type').style.backgroundColor = checked ? '#3ba55c' : '#72767d';
            label.querySelector('span:last-of-type').style.left = checked ? '19px' : '3px';
        };

        panel.querySelector('#mc-auto-connect').addEventListener('change', (e) => { this.settings.autoConnect = e.target.checked; this.saveSettings(); updateToggle(e.target, e.target.checked); });
        panel.querySelector('#mc-advanced-features').addEventListener('change', (e) => { this.settings.advancedFeatures = e.target.checked; this.saveSettings(); updateToggle(e.target, e.target.checked); });
        panel.querySelector('#mc-log-channel').addEventListener('blur', (e) => { this.settings.connectionLoggingChannel = e.target.value; this.saveSettings(); });
        panel.querySelector('#mc-console-logging').addEventListener('change', (e) => { this.settings.enableConsoleLogging = e.target.checked; this.saveSettings(); updateToggle(e.target, e.target.checked); });

        return panel;
    }

    createSettingToggle(id, title, description, checked) {
        return `
            <div class="mc-setting-item" style="margin-bottom:16px;">
                <div style="display:flex;justify-content:space-between;align-items:center;">
                    <div>
                        <div style="font-weight:500;color:#fff;margin-bottom:4px;">${title}</div>
                        <div style="font-size:12px;color:#b5bac1;">${description}</div>
                    </div>
                    <label style="position:relative;display:inline-block;width:40px;height:24px;">
                        <input type="checkbox" id="${id}" ${checked ? 'checked' : ''} style="opacity:0;width:0;height:0;">
                        <span style="position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;background-color:${checked ? '#3ba55c' : '#72767d'};transition:.2s;border-radius:24px;"></span>
                        <span style="position:absolute;content:'';height:18px;width:18px;left:${checked ? '19px' : '3px'};bottom:3px;background-color:white;transition:.2s;border-radius:50%;"></span>
                    </label>
                </div>
            </div>`;
    }
};
