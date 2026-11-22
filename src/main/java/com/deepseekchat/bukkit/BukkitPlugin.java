package com.deepseekchat.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeekChat 子服端主类 (Spigot)
 *
 * 通过 BungeeCord 插件消息通道与代理服通信:
 * - 接收: DATA(采集授权数据) / CMD(执行指令) / AUTH_QUERY(查询授权) / AGENT_TOGGLE(功能开关) / OPEN_BOOK(弹出书本GUI)
 * - 返回: DATA_R / CMD_R / AUTH_SUMMARY / PLAYER_REPLY
 * 所有请求都必须由玩家在代理服点击二次确认按钮后才会发出。
 */
public final class BukkitPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    public static final String CHANNEL = "deepseek:main";

    private AuthManager authManager;
    private DataCollector dataCollector;
    private boolean agentEnabled = true;
    /** 等待玩家回答AI问题 (玩家名 -> AI问题), 聊天/书本内容会被采集为回答 */
    private final Map<String, String> awaitingReply = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        agentEnabled = getConfig().getBoolean("agent.enabled", true);
        authManager = new AuthManager(this);
        dataCollector = new DataCollector(this);

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("dsauth") != null) {
            getCommand("dsauth").setExecutor(new AuthCommand(this, authManager));
        }

        getLogger().info("DeepSeekChat 子服端已启用 (数据授权 / Agent)");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        JsonObject o;
        try {
            o = new JsonParser().parse(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        String t = o.has("t") ? o.get("t").getAsString() : "";
        getLogger().info("[通道] 收到代理服消息: " + t + " player="
                + (o.has("player") ? o.get("player").getAsString() : "-"));
        switch (t) {
            case "AGENT_TOGGLE":
                agentEnabled = o.has("enabled") && o.get("enabled").getAsBoolean();
                getLogger().info("Agent 功能已" + (agentEnabled ? "开启" : "关闭"));
                break;
            case "DATA":
                handleDataRequest(player, o);
                break;
            case "CMD":
                handleCmdRequest(player, o);
                break;
            case "AUTH_QUERY":
                handleAuthQuery(player, o);
                break;
            case "OPEN_BOOK":
                handleOpenBook(o);
                break;
            default:
                break;
        }
    }

    // ==================== AI 提问 -> 采集玩家回答 ====================

    /**
     * 代理服通知: AI 向玩家提问, 弹出书本 GUI 并开始采集玩家回答
     */
    private void handleOpenBook(JsonObject o) {
        String playerName = o.has("player") ? o.get("player").getAsString() : "";
        String question = o.has("question") ? o.get("question").getAsString() : "";
        Player target = getServer().getPlayerExact(playerName);
        if (target == null) {
            return;
        }
        awaitingReply.put(target.getName(), question);
        openBookGui(target, question);
        target.sendMessage("§7[DeepSeek] §eAI 提问: §f" + question);
        target.sendMessage("§7[DeepSeek] §7请在书本里填写你的回答后关闭书本 (会发送给AI), 或在聊天框直接输入回答");
    }

    /**
     * 弹出书本编辑 GUI (1.16+ 用 Bukkit API, 1.9-1.15 用 OpenBook 数据包, 1.8 退回手持说明)
     */
    private void openBookGui(Player target, String question) {
        ItemStack book = new ItemStack(Material.BOOK_AND_QUILL, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setAuthor("DeepSeek");
            meta.setTitle("AI的问题");
            meta.setPages("AI 的问题: " + question, "");
            book.setItemMeta(meta);
        }
        target.getInventory().setItemInHand(book);
        target.updateInventory();
        // 1.16+: Bukkit API Player.openBook(ItemStack)
        try {
            java.lang.reflect.Method openBook = Player.class.getMethod("openBook", ItemStack.class);
            openBook.invoke(target, book);
            return;
        } catch (Throwable ignored) {
        }
        // 1.9-1.15: 反射发送 PacketPlayOutOpenBook
        try {
            Object handle = target.getClass().getMethod("getHandle").invoke(target);
            Object connection = handle.getClass().getField("playerConnection").get(handle);
            String pkg = handle.getClass().getPackage().getName();
            Class<?> enumHand = Class.forName(pkg + ".EnumHand");
            Object mainHand = enumHand.getEnumConstants()[0];
            Class<?> packetClass;
            try {
                packetClass = Class.forName(pkg + ".PacketPlayOutOpenBook");
            } catch (ClassNotFoundException e) {
                packetClass = Class.forName("net.minecraft.server.network.PacketPlayOutOpenBook");
            }
            Object packet = packetClass.getConstructor(enumHand).newInstance(mainHand);
            connection.getClass().getMethod("sendPacket", Class.forName(pkg + ".Packet"))
                    .invoke(connection, packet);
        } catch (Throwable ignored) {
            // 1.8 或反射失败: 退化为手持书本手动打开
            target.sendMessage("§7[DeepSeek] §e如书本未自动打开, 请在主手手持书本右键打开, 编写完成后关闭即发送");
        }
    }

    /**
     * 采集到回答后回传代理服, 并结束等待状态
     */
    private void captureReply(Player player, String answer) {
        awaitingReply.remove(player.getName());
        player.sendMessage("§7[DeepSeek] §a你的回答已发送给AI: §f" + answer);
        JsonObject resp = new JsonObject();
        resp.addProperty("t", "PLAYER_REPLY");
        resp.addProperty("player", player.getName());
        resp.addProperty("answer", answer);
        sendBack(player, resp);
    }

    /**
     * 聊天框输入回答 (异步事件, 1.8-1.18 及兼容服务端)
     */
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!awaitingReply.containsKey(event.getPlayer().getName())) {
            return;
        }
        event.setCancelled(true);
        captureReply(event.getPlayer(), event.getMessage());
    }

    /**
     * 聊天框输入回答 (同步事件兜底, 覆盖异步事件不触发的服务端)
     */
    @EventHandler
    public void onChatSync(PlayerChatEvent event) {
        if (!awaitingReply.containsKey(event.getPlayer().getName())) {
            return;
        }
        event.setCancelled(true);
        captureReply(event.getPlayer(), event.getMessage());
    }

    /**
     * 书本编辑完成后关闭 -> 采集书页内容作为回答
     */
    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        String question = awaitingReply.get(event.getPlayer().getName());
        if (question == null) {
            return;
        }
        event.setCancelled(true);
        List<String> pages = event.getNewBookMeta().getPages();
        // 去掉第一页的预填问题后拼接; 若玩家没有填写任何新内容则不发送
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < pages.size(); i++) {
            String page = pages.get(i);
            if (page != null && !page.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(page.trim());
            }
        }
        String answer = sb.toString().trim();
        if (answer.isEmpty()) {
            event.getPlayer().sendMessage("§7[DeepSeek] §c书本里没有填写内容, 请在第一页之后的页面填写你的回答");
            return;
        }
        captureReply(event.getPlayer(), answer);
    }

    /**
     * 玩家离开子服时清理等待回答状态
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingReply.remove(event.getPlayer().getName());
    }

    private void handleDataRequest(Player carrier, JsonObject o) {
        String reqPlayer = o.has("player") ? o.get("player").getAsString() : "";
        String token = o.has("token") ? o.get("token").getAsString() : "";
        String category = o.has("category") ? o.get("category").getAsString() : "";
        JsonObject resp = new JsonObject();
        resp.addProperty("t", "DATA_R");
        resp.addProperty("token", token);
        getLogger().info("[DATA] 收到数据请求 category=" + category + " player=" + reqPlayer);
        if (!agentEnabled) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "Agent功能未开启");
            sendBack(resolveReplyPlayer(carrier, reqPlayer), resp);
            return;
        }
        // 授权检查以"请求方玩家"的授权为准 (玩家在代理服点击确认后才发到本服)
        if (!authManager.isEnabled(reqPlayer)) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "你未开启数据授权总开关 (在子服执行 /dsauth on)");
            sendBack(resolveReplyPlayer(carrier, reqPlayer), resp);
            return;
        }
        if (!authManager.isAuthorized(reqPlayer, category)) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "该数据类别未授权 (在子服执行 /dsauth " + category + " 开启)");
            sendBack(resolveReplyPlayer(carrier, reqPlayer), resp);
            return;
        }
        boolean playerLevel = isPlayerLevel(category);
        Player target = getServer().getPlayerExact(reqPlayer);
        if (playerLevel && target == null) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "玩家不在本服");
            sendBack(resolveReplyPlayer(carrier, reqPlayer), resp);
            return;
        }
        try {
            JsonObject data = playerLevel
                    ? dataCollector.collect(category, target)
                    : dataCollector.collectServer(category);
            resp.addProperty("ok", true);
            resp.add("data", data);
        } catch (Exception e) {
            resp.addProperty("ok", false);
            resp.addProperty("error", e.getMessage());
        }
        sendBack(resolveReplyPlayer(carrier, reqPlayer), resp);
    }

    /**
     * 确定回传响应用哪条玩家连接: 优先请求携带的连接, 其次本服的目标玩家, 最后任意在线玩家
     * (回传 payload 内含目标玩家名, 由代理服按 token 定位, 用哪个玩家连接携带均可)
     */
    private Player resolveReplyPlayer(Player carrier, String reqPlayer) {
        if (carrier != null) {
            return carrier;
        }
        Player exact = getServer().getPlayerExact(reqPlayer);
        if (exact != null) {
            return exact;
        }
        java.util.Collection<? extends Player> online = getServer().getOnlinePlayers();
        return online.isEmpty() ? null : online.iterator().next();
    }

    /**
     * 玩家级数据需要该玩家在本服; 服务器级数据只要有任意连接即可回传
     */
    private boolean isPlayerLevel(String category) {
        switch (category == null ? "" : category) {
            case "position":
            case "chunk":
            case "inventory":
            case "player":
            case "world":
                return true;
            default:
                return false;
        }
    }

    private void handleCmdRequest(Player player, JsonObject o) {
        String reqPlayer = o.has("player") ? o.get("player").getAsString() : "";
        String token = o.has("token") ? o.get("token").getAsString() : "";
        String command = o.has("command") ? o.get("command").getAsString() : "";
        JsonObject resp = new JsonObject();
        resp.addProperty("t", "CMD_R");
        resp.addProperty("token", token);
        if (!agentEnabled) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "Agent功能未开启");
            sendBack(player, resp);
            return;
        }
        if (!player.getName().equalsIgnoreCase(reqPlayer)) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "玩家不在本服");
            sendBack(player, resp);
            return;
        }
        if (command == null || command.trim().isEmpty()) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "指令为空");
            sendBack(player, resp);
            return;
        }
        try {
            // 以控制台身份在玩家所在子服执行 (命令由玩家二次确认后才到这一步)
            boolean ok = getServer().dispatchCommand(getServer().getConsoleSender(), command);
            resp.addProperty("ok", ok);
            if (!ok) {
                resp.addProperty("error", "指令执行失败 (可能命令不存在或无权限)");
            }
        } catch (Exception e) {
            resp.addProperty("ok", false);
            resp.addProperty("error", e.getMessage());
        }
        sendBack(player, resp);
    }

    private void handleAuthQuery(Player player, JsonObject o) {
        String token = o.has("token") ? o.get("token").getAsString() : "";
        JsonObject resp = new JsonObject();
        resp.addProperty("t", "AUTH_SUMMARY");
        resp.addProperty("token", token);
        resp.addProperty("enabled", authManager.isEnabled(player.getName()));
        JsonObject cats = new JsonObject();
        for (String c : AuthManager.CATEGORIES) {
            cats.addProperty(c, authManager.isAuthorized(player.getName(), c));
        }
        resp.add("categories", cats);
        sendBack(player, resp);
    }

    private void sendBack(Player player, JsonObject resp) {
        if (player == null) {
            // 没有可用的玩家连接 (目标子服无人), 回传会丢失, 至少记录日志
            getLogger().warning("无法回传插件消息 (子服无玩家连接): " + resp);
            return;
        }
        getLogger().info("[通道] 回传消息 " + resp.get("t").getAsString()
                + " -> " + player.getName() + " (ok=" + resp.has("ok") + ")");
        player.sendPluginMessage(this, CHANNEL, resp.toString().getBytes(StandardCharsets.UTF_8));
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public boolean isAgentEnabled() {
        return agentEnabled;
    }
}
