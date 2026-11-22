package com.deepseekchat.command;

import com.deepseekchat.AgentManager;
import com.deepseekchat.ConfigManager;
import com.deepseekchat.DeepSeekChat;
import com.deepseekchat.PlayerData;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.util.Arrays;
import java.util.List;

/**
 * /ds 主命令 (BungeeCord)
 */
public class DSCommand extends Command {

    private final DeepSeekChat plugin;
    private final AgentManager agentManager;

    private static final List<String> KNOWN_SUBCOMMANDS = Arrays.asList(
            "help", "send", "api", "quota", "times", "token", "clear", "reset",
            "reload", "confirm", "deny", "agent", "auth", "stop");

    public DSCommand(DeepSeekChat plugin, AgentManager agentManager) {
        super("ds", null, "deepseek");
        this.plugin = plugin;
        this.agentManager = agentManager;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }
        // AI 提问等待回答时, 玩家输入的内容视为对问题的回答
        if (sender instanceof ProxiedPlayer) {
            ProxiedPlayer player = (ProxiedPlayer) sender;
            if (agentManager.isAwaitingReply(player.getName())) {
                String first = args[0].toLowerCase();
                if (!KNOWN_SUBCOMMANDS.contains(first)) {
                    agentManager.submitReply(player, String.join(" ", args).trim());
                    return;
                }
                if (first.equals("send") && args.length >= 2) {
                    agentManager.submitReply(player,
                            String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim());
                    return;
                }
            }
        }
        switch (args[0].toLowerCase()) {
            case "help":
                sendHelp(sender);
                break;
            case "send":
                handleSend(sender, args);
                break;
            case "api":
                handleApi(sender, args);
                break;
            case "quota":
            case "times":
            case "token":
                handleTimes(sender, args);
                break;
            case "clear":
            case "reset":
                handleClear(sender);
                break;
            case "stop":
                handleStop(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "confirm":
                handleConfirm(sender, args);
                break;
            case "deny":
                handleDeny(sender, args);
                break;
            case "agent":
                handleAgent(sender, args);
                break;
            case "auth":
                handleAuth(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }
    }

    // ---------------- /ds send ----------------

    private void handleSend(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        if (args.length < 2) {
            send(sender, "&c用法: &e/ds send <内容>");
            return;
        }
        String content = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (content.isEmpty()) {
            send(sender, "&c请输入要发送的内容");
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        // 回显玩家提问内容
        send(player, "&7[DeepSeek] &f你: &e" + escapeAmp(content));
        agentManager.startChat(player, content);
    }

    // ---------------- /ds confirm / deny ----------------

    private void handleConfirm(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        if (args.length < 2) {
            send(sender, "&c用法: &e/ds confirm <token> (请点击按钮确认)");
            return;
        }
        agentManager.confirm((ProxiedPlayer) sender, args[1]);
    }

    private void handleDeny(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        if (args.length < 2) {
            send(sender, "&c用法: &e/ds deny <token> (请点击按钮拒绝)");
            return;
        }
        agentManager.deny((ProxiedPlayer) sender, args[1]);
    }

    // ---------------- /ds agent ----------------

    private void handleAgent(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            send(sender, "&c你没有管理权限");
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        if (args.length < 2) {
            send(sender, "&eAgent 功能当前状态: " + (cfg.isAgentEnabled() ? "&a已开启" : "&c已关闭"));
            send(sender, "&e用法: &a/ds agent on&7 开启 | &c/ds agent off&7 关闭 | &e/ds agent status");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on":
                cfg.setAgentEnabled(true);
                agentManager.broadcastAgentToggle(true);
                send(sender, "&aAgent 功能已开启 (已同步到所有子服)");
                break;
            case "off":
                cfg.setAgentEnabled(false);
                agentManager.broadcastAgentToggle(false);
                send(sender, "&eAgent 功能已关闭 (已同步到所有子服)");
                break;
            case "status":
                send(sender, "&eAgent 功能当前状态: " + (cfg.isAgentEnabled() ? "&a已开启" : "&c已关闭"));
                break;
            default:
                send(sender, "&c用法: /ds agent on|off|status");
                break;
        }
    }

    // ---------------- /ds auth ----------------

    private void handleAuth(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        send(player, "&7[DeepSeek] &e正在获取子服数据授权状态…");
        agentManager.requestAuthSummary(player);
    }

    // ---------------- /ds api ----------------

    private void handleApi(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            if (args.length < 3) {
                send(player, "&c用法: &e/ds api set <APIKey>");
                return;
            }
            String apiKey = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            if (apiKey.isEmpty()) {
                send(player, "&cAPI Key 不能为空");
                return;
            }
            final String key = apiKey;
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                boolean ok = plugin.getTokenManager().setApiKey(player.getName(), key);
                send(player, ok
                        ? "&a个人 API Key 设置成功! 之后对话将使用你的API, 不再消耗额度。"
                        : "&c保存 API Key 失败, 请检查数据库连接");
            });
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                boolean ok = plugin.getTokenManager().setApiKey(player.getName(), null);
                send(player, ok
                        ? "&a已清除个人 API Key, 将回退到公共 API (消耗额度)。"
                        : "&c清除 API Key 失败, 请检查数据库连接");
            });
            return;
        }
        // 查看当前模式
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            PlayerData data = plugin.getTokenManager().loadPlayer(player.getName(), player.getUniqueId().toString());
            if (data == null) {
                send(player, "&c数据库连接异常");
                return;
            }
            String key = data.getApiKey();
            if (key != null && !key.trim().isEmpty()) {
                send(player, "&a当前模式: &e个人API&a (不消耗额度), Key: &7" + maskKey(key));
            } else if (!plugin.getConfigManager().getPublicApiKey().isEmpty()) {
                send(player, "&e当前模式: 公共API&a (每次消耗 &e1&a 点额度, 当前剩余 &e" + data.getTokens() + "&a 点)");
            } else {
                send(player, "&c未配置任何 API Key! 请使用 &e/ds api set <key>&c 设置个人API, 或联系管理员配置公共API");
            }
        });
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 6) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    // ---------------- /ds quota ----------------

    private void handleTimes(CommandSender sender, String[] args) {
        // /ds quota give|set <玩家> <数量>  (管理员, 支持控制台)
        if (args.length >= 2 && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("set"))) {
            if (!isAdmin(sender)) {
                send(sender, "&c你没有管理权限");
                return;
            }
            handleTimesAdmin(sender, args);
            return;
        }
        // 玩家查看剩余额度
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c用法: &e/ds quota give <玩家> <数量> | set <玩家> <数量>");
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            PlayerData data = plugin.getTokenManager().loadPlayer(player.getName(), player.getUniqueId().toString());
            if (data == null) {
                send(player, "&c数据库连接异常");
                return;
            }
            String key = data.getApiKey();
            String mode = (key != null && !key.trim().isEmpty()) ? "&a个人API (不消耗额度)" : "&e公共API";
            send(player, "&6===== &b对话额度 &6=====\n"
                    + "&f剩余额度: &e" + data.getTokens() + " &f点\n"
                    + "&f当前模式: " + mode + "\n"
                    + "&f公共API单次消耗: &e1 &f点额度");
        });
    }

    private void handleTimesAdmin(CommandSender admin, String[] args) {
        if (args.length < 4) {
            send(admin, "&c用法: &e/ds quota " + args[1].toLowerCase() + " <玩家> <数量>");
            return;
        }
        String targetName = args[2];
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            send(admin, "&c数量必须是数字");
            return;
        }
        if (amount < 0) {
            send(admin, "&c数量不能为负数");
            return;
        }

        final boolean isGive = args[1].equalsIgnoreCase("give");
        final long finalAmount = amount;
        final String targetKey = targetName.toLowerCase();

        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            // 确保目标玩家行存在
            plugin.getTokenManager().loadPlayer(targetKey, null);
            boolean ok;
            if (isGive) {
                ok = plugin.getTokenManager().addTokens(targetKey, finalAmount);
            } else {
                ok = plugin.getTokenManager().setTokens(targetKey, finalAmount);
            }
            if (!ok) {
                send(admin, "&c数据库操作失败");
                return;
            }
            long balance = plugin.getTokenManager().getTokens(targetKey);
            send(admin, "&a已" + (isGive ? "发放" : "设置") + " &e" + finalAmount + "&a 点额度给 &e" + targetName
                    + "&a, 剩余 &e" + balance + "&a 点");
            ProxiedPlayer online = plugin.getProxy().getPlayer(targetName);
            if (online != null && !(admin instanceof ProxiedPlayer
                    && online.getName().equalsIgnoreCase(((ProxiedPlayer) admin).getName()))) {
                send(online, "&e[DeepSeek] &f管理员已" + (isGive ? "发放" : "设置")
                        + " &e" + finalAmount + "&f 点额度, 剩余 &e" + balance + "&f 点");
            }
        });
    }

    // ---------------- /ds clear ----------------

    private void handleClear(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        plugin.getSessionManager().getSession(player.getName()).reset();
        send(player, "&a已清空对话上下文, 开启全新对话。");
    }

    // ---------------- /ds stop ----------------

    private void handleStop(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer)) {
            send(sender, "&c该命令只能由玩家执行");
            return;
        }
        agentManager.stopChat((ProxiedPlayer) sender);
    }

    // ---------------- /ds reload ----------------

    private void handleReload(CommandSender sender) {
        if (!isAdmin(sender)) {
            send(sender, "&c你没有管理权限");
            return;
        }
        plugin.reloadPlugin();
        send(sender, "&a已重载配置与数据库连接。");
    }

    // ---------------- 权限 ----------------

    private boolean isAdmin(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer)) {
            return true; // 控制台
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        return player.hasPermission("deepseek.admin")
                || plugin.getConfigManager().getAdminList().contains(player.getName().toLowerCase());
    }

    // ---------------- 帮助 ----------------

    private void sendHelp(CommandSender sender) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("&6========== &bDeepSeek Chat &6==========");
        lines.add("&e/ds send <内容> &7- 向AI发送消息");
        lines.add("&e/ds api set <APIKey> &7- 设置个人API Key (之后不消耗额度)");
        lines.add("&e/ds api clear &7- 清除个人API Key");
        lines.add("&e/ds api &7- 查看当前API模式");
        lines.add("&e/ds quota &7- 查看剩余对话额度");
        lines.add("&e/ds auth &7- 查看游戏数据授权状态");
        lines.add("&e/ds clear &7- 清空对话上下文");
        lines.add("&e/ds stop &7- 强制打断正在进行的对话");
        if (isAdmin(sender)) {
            lines.add("&e/ds quota give <玩家> <数量> &7- 给玩家发放对话额度 (管理员)");
            lines.add("&e/ds quota set <玩家> <数量> &7- 设置玩家对话额度 (管理员)");
            lines.add("&e/ds agent on|off|status &7- 开关Agent功能 (管理员)");
            lines.add("&e/ds reload &7- 重载配置与数据库 (管理员)");
        }
        for (String line : lines) {
            send(sender, line);
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 发送消息 (可在异步线程调用), 支持换行/长文本自动分行
     */
    private void send(CommandSender sender, String message) {
        String colored = ChatColor.translateAlternateColorCodes('&', message);
        for (String line : colored.split("\n")) {
            sendLines(sender, line);
        }
    }

    private void sendLines(CommandSender sender, String line) {
        // 聊天每行最多 256 字符, 超长自动拆分
        while (line.length() > 256) {
            int idx = line.lastIndexOf(' ', 256);
            if (idx <= 0) {
                idx = 256;
            }
            sender.sendMessage(line.substring(0, idx));
            line = line.substring(idx).trim();
        }
        if (!line.isEmpty()) {
            sender.sendMessage(line);
        }
    }

    /**
     * 转义文本中的 & (&& 会被翻译为字面 &), 防止内容被当作颜色码
     */
    private String escapeAmp(String text) {
        return text == null ? "" : text.replace("&", "&&");
    }
}
