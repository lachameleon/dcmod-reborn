<p align="center">
<img src="https://github.com/user-attachments/assets/35b66ae1-cfe5-4ecc-ae4a-82d137e3c808" alt="Discord-Chat-Integration-Banner" width="25%"/>
</p>
<h1 align="center">Discord Chat Integration</h1>

<p align="center">Brings your Minecraft chat into Discord with additional automation features. Send messages/commands and listens to Minecraft from Discord without bots. 
</p>

## What It Does

This mod bridges Discord and Minecraft through a local WebSocket connection, allowing you to send messages and execute commands in Minecraft directly from Discord without any bots. It also includes a built-in HTTP relay (enabled by default) that posts Minecraft chat to Discord through the hosted bot relay.

- **Botless Discord Integration** - No Discord bot setup required; uses Vencord or BetterDiscord plugins
- **Bidirectional Chat** - Send messages/commands from Discord to Minecraft and receive chat feedback in Discord
- **[Multi-Client Support](#multi-client-setup)** - Control multiple Minecraft clients from a single Discord interface
- **[Sync Groups](#sync-groups)** - Achieve tick-perfect synchronization across multiple Minecraft clients
- **[Chat Delay](#chat-delay)** - Queue and batch-send messages
- **[Automations](#automations)** - Create trigger-based rules that execute actions when Minecraft chat events occur
- **Relay Forwarding (Default)** - Sends Minecraft chat to `https://discordrelay.lacha.dev/relay` out of the box
- **Relay Feed (Default)** - Shows relay-fed Discord messages and other clients' chat in-game

## Requirements

- **Minecraft**: 1.21.11
- **Fabric Loader**: 0.18.4 or higher
- **Fabric API**: Latest version for 1.21.x
- **Java**: 21 or higher
- **Discord**: Latest MinecraftChat Vencord/BetterDiscord plugin

---

> [!IMPORTANT]
> Relay forwarding (Minecraft chat -> Discord bot relay channel) works with just the mod.
> Install the Discord plugin too if you want bidirectional Discord -> Minecraft control.

---

### Installing the Minecraft Mod

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version
2. Download the latest [Fabric API](https://modrinth.com/mod/fabric-api) for your Minecraft version
3. Download the latest `discord-chat-integration-[version]-[mod_version].jar` from the [Releases](https://github.com/aurickk/Discord-Chat-Integration/releases/) page
4. Place both mods in your `.minecraft/mods` folder
5. Launch Minecraft

---

### Installing the Discord Plugin

- [BetterDiscord Plugin Installation](https://github.com/aurickk/Discord-Chat-Integration/blob/main/betterdiscord-plugin/README.md)
- [Vencord Plugin Installation](https://github.com/aurickk/Discord-Chat-Integration/blob/main/vencord-plugin/README.md)

## Usage

### Quick Start Guide

1. Start Minecraft with the mod installed
2. Join a world or server
3. Open Discord with the Discord plugin enabled 
4. The plugin will automatically connect (if "Auto Connect" is enabled and the port number matches)
5. Send messages/commands in the configured Discord channel, they'll appear/execute in Minecraft chat
6. Recieve messages or command feedback in Discord (If "Forward to Discord" is enabled)

### Relay-First Quick Start (Default)

1. Install and launch the mod on Minecraft `1.21.11`
2. Join a world/server
3. Run `/discordchat relay status`
4. Confirm relay is enabled and URL is `https://discordrelay.lacha.dev/relay`
5. Send Minecraft chat messages; they will be relayed to Discord through the Vercel relay
6. Messages relayed by other clients are shown in-game as `[DCI Relay]` entries

> [!NOTE]
> Discord messages are now read directly by the relay bot from the configured channel, so plugin-side relay publishing is optional.

## Setup and Configurations

### Quick Settings Access

<img width="647" height="73" alt="quick access setttings" src="https://github.com/user-attachments/assets/f96de6ac-4394-458a-a4e5-a7c6663da956" />

A **gear icon** appears next to the chat input box when the plugin is enabled. Click it to quickly access the Minecraft Chat settings without navigating through Discord's settings menu. The icon turns **green** when at least one client is connected.

---

### Adding Minecraft Clients

1. Click **"Add Client"** in quick access menu
2. Configure each client:
   - **Name**: A friendly name for this client 
   - **Port**: WebSocket port
   - **Channel ID**: The Discord channel ID to bridge with this client
   - **Enabled**: Toggle to enable/disable this client connection
   - **Forward to Discord**: Toggle to enable/disable forwarding Minecraft chat to Discord
   - **Sync Group**: Assign client to a sync group for tick-perfect synchronization

3. **Getting a Channel ID**:
   - Enable Developer Mode in Discord (User Settings → Advanced → Developer Mode)
   - Right-click on the channel you want to use
   - Click "Copy Channel ID"
   - Paste into the plugin settings

---

### Multi-Client Setup

> [!IMPORTANT]
> Different Minecraft clients cannot share the same port, you can change the port with a [command](#minecraft-mod-configuration).

You can run multiple Minecraft clients, each connected to different or shared Discord channels:

**Different Channels:**
1. **Client 1**: Port `25580` → Discord Channel `123456789`
2. **Client 2**: Port `25581` → Discord Channel `987654321`
3. **Client 3**: Port `25582` → Discord Channel `555555555`

**Shared Channel:**
Multiple clients can be assigned to the same Discord channel. When this happens:
- Messages from Discord are sent to **all** Minecraft clients assigned to that channel
- Messages forwarded to Discord include a `[Client Name]` tag to identify the source
- If multiple clients are on the same sync group, the message/command will be sent on the same server tick

---

Addional configuration can be found in **User Settings → Vencord/BetterDiscord → Plugins → MinecraftChat**

| Setting | Description | Default |
|---------|-------------|---------|
| **Auto Connect** | Automatically connect to all enabled clients when Discord starts | `true` |
| **Advanced Features** | Enable advanced features (Chat Delay, Sync Groups, Automations) | `false` |
| **Connection Logging Channel** | Discord channel ID where connection/disconnection events are posted (Leave blank to disable) | Empty |
| **Enable Console Logging** | Log plugin debug messages to browser console (DevTools F12) | `true` |

---

### Minecraft Mod Configuration

The mod uses a default port of `25580`. You can change this using the in-game command:

```
/discordchat port <port_number>
```

The port must be between 1024 and 65535 and match the configured client settings in Discord.

## Advanced Features

Enable advanced features in the plugin settings to unlock powerful automation and synchronization capabilities. Access these features via the chat bar gear icon.

### Chat Delay

Queue messages from Discord and send them at once to Minecraft at the same game tick.

**How to use:**
1. Enable "Advanced Features" in plugin settings
2. Click the chat bar gear icon to open settings
3. Toggle "Chat Delay" on to start queuing messages
4. Click "Send Queue" to release all queued messages at once

---

### Sync Groups

Assign multiple Minecraft clients to sync groups (A-F) for tick-perfect message execution. When clients in the same sync group receive messages, they execute them on the exact same server game tick when assigned to the same Discord channel or with [Chat Delay](#chat-delay).

**How to configure:**
1. Enable "Advanced Features" in plugin settings
2. In client configuration, set each client's "Sync Group" (A-F or None)
3. "None" disables synchronization for that client

---

### Automations

Create trigger-based automation rules that execute actions when specific messages appear in Minecraft chat.

**How to configure:**
1. Enable "Advanced Features" in plugin settings
2. Click the chat bar gear icon → "Automations" section
3. Click "+ Add Automation" to create a new rule
4. Configure:
   - **Name**: Identify your automation
   - **Enabled**: Toggle to activate/deactivate
   - **Listen to**: Select which Minecraft clients trigger this automation
   - **Trigger**: Text to match in Minecraft chat
   - **Absolute Match**: If checked, message must exactly match trigger (must contain player prefixes as well)
   - **Cooldown**: Minimum milliseconds between triggers
   - **Actions**: What to do when triggered

**Available Actions:**
- **Send to Minecraft**: Send message/command to selected clients
- **Send to Discord**: Post message to configured logging channel
- **Enable Chat Delay**: Turn on message queuing
- **Disable Chat Delay**: Turn off queuing and send all queued messages
- **Wait**: Delay in milliseconds before next action

---

### Commands

The mod provides a `/discordchat` command with the following subcommands:

#### `/discordchat` or `/discordchat status`
Shows the current connection status:
- Server status (Running/Stopped)
- Current port
- Number of connected clients

#### `/discordchat port <number>`
Changes the WebSocket server port. Must be between 1024 and 65535.

#### `/discordchat reconnect`
Restarts the WebSocket server.

#### `/discordchat disconnect`
Stops the WebSocket server and disconnects all Discord clients. Use `/discordchat reconnect` to reconnect.

#### `/discordchat ticktest`
Displays tick synchronization diagnostic information. Useful for debugging sync groups and verifying tick-perfect execution.

Shows:
- Player name
- Current server tick
- Client time (milliseconds)
- Network ping
- Current sync group assignment
- Last sync execution details (target tick, execution tick, timing)

This command is particularly useful when using [Sync Groups](#sync-groups) to verify that messages from multiple clients are executing at the correct game ticks.

#### `/discordchat relay ...`
Configures HTTP relay forwarding from the mod to an external server (for example, `vercel/relay`):
- `/discordchat relay status` - Shows relay settings
- `/discordchat relay enable|disable` - Toggles relay
- `/discordchat relay url <http(s)://...>` - Sets relay endpoint
- `/discordchat relay token <token>` - Sets bearer token for relay auth
- `/discordchat relay token clear` - Clears relay token
- `/discordchat relay timeout <1000-30000>` - Sets HTTP timeout in milliseconds

#### `/discordchat ratelimit [messagesPerMinute]`
Shows or sets Discord-originated send rate limits (default `45` per minute, range `1-600`).

#### `/discordchat send <message>`
Sends a normal chat message to the Minecraft server only and bypasses relay forwarding to Vercel/Discord.

#### `/toggle`
Client-side command that switches plain chat destination between:
- **Discord relay only**
- **Minecraft server only**

#### Discord message behavior
- Messages starting with `/` are treated as Minecraft commands.
- Use `/send <message>` from Discord to force a normal chat message instead of command execution.
- Plain in-game chat input (without command prefix) follows the `/toggle` mode when relay mode is enabled.
- `/discordchat send <message>` always sends to Minecraft server only and does not relay to Discord.

## Building from Source

### Prerequisites

- **Java 21** or higher
- **Gradle** (included via wrapper)

### Building the Minecraft Mod

1. **Clone the repository**
   ```bash
   git clone https://github.com/aurickk/Discord-Chat-Integration.git
   cd Discord-Chat-Integration/
   ```

2. **Build the mod**
   ```bash
   # Windows
   .\gradlew.bat build
   
   # Linux/Mac
   ./gradlew build
   ```
   
Output JARs are in `build/libs/` (1.21.11)
