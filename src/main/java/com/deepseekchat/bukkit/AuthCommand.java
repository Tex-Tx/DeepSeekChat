package com.deepseekchat.bukkit;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /dsauth 命令 - 玩家管理游戏数据授权 (子服端)
 */
public class AuthCommand implements CommandExecutor {

    private final BukkitPlugin plugin;
    private final AuthManager auth;

    public AuthCommand(BukkitPlugin plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color("&c该命令只能由玩家执行"));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("deepseek.use")) {
            p.sendMessage(color("&c你没有权限使用"));
            return true;
        }
        if (args.length == 0) {
            showStatus(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "on":
                auth.setEnabled(p.getName(), true);
                p.sendMessage(color("&a已开启游戏数据授权, 使用 &e/dsauth <类别>&a 选择要授权的数据"));
                break;
            case "off":
                auth.setEnabled(p.getName(), false);
                p.sendMessage(color("&e已关闭游戏数据授权, AI 将无法获取你的游戏数据"));
                break;
            case "help":
                showHelp(p);
                break;
            default: {
                String cat = normalize(args[0]);
                if (cat == null) {
                    p.sendMessage(color("&c未知类别! 可选: &e位置 | 插件 | 区块 | 背包"));
                    return true;
                }
                if (!auth.isEnabled(p.getName())) {
                    p.sendMessage(color("&c请先执行 &e/dsauth on &c开启数据授权"));
                    return true;
                }
                boolean now = !auth.isAuthorized(p.getName(), cat);
                auth.setAuthorized(p.getName(), cat, now);
                p.sendMessage(color((now ? "&a已授权" : "&c已取消授权") + " &e" + catName(cat)));
                break;
            }
        }
        return true;
    }

    private void showStatus(Player p) {
        String name = p.getName();
        p.sendMessage(color("&6===== &b游戏数据授权 &6====="));
        p.sendMessage(color("&f授权总开关: " + (auth.isEnabled(name) ? "&a已开启" : "&c已关闭")));
        for (String c : AuthManager.CATEGORIES) {
            p.sendMessage(color("&f- &e" + catName(c) + "&f: " + (auth.isAuthorized(name, c) ? "&a已授权" : "&c未授权")));
        }
        p.sendMessage(color("&7使用: &e/dsauth on|off &7开关总授权, &e/dsauth <位置|插件|区块|背包|玩家|世界|在线|状态|实体> &7单独授权"));
    }

    private void showHelp(Player p) {
        p.sendMessage(color("&e/dsauth &7- 查看授权状态"));
        p.sendMessage(color("&e/dsauth on|off &7- 开启/关闭数据授权"));
        p.sendMessage(color("&e/dsauth 位置|插件|区块|背包|玩家|世界|在线|状态|实体 &7- 单独授权/取消某一项"));
        p.sendMessage(color("&7注意: AI 每次请求数据/执行指令, 都会弹出按钮需要你再次点击确认"));
    }

    private String normalize(String s) {
        switch (s.toLowerCase()) {
            case "位置":
            case "position":
                return "position";
            case "插件":
            case "plugins":
                return "plugins";
            case "区块":
            case "chunk":
                return "chunk";
            case "背包":
            case "inventory":
                return "inventory";
            case "玩家":
            case "玩家状态":
            case "player":
                return "player";
            case "世界":
            case "世界信息":
            case "world":
                return "world";
            case "在线":
            case "在线人数":
            case "online":
                return "online";
            case "状态":
            case "服务器状态":
            case "status":
                return "status";
            case "实体":
            case "实体统计":
            case "entities":
                return "entities";
            default:
                return null;
        }
    }

    private String catName(String key) {
        switch (key) {
            case "position":
                return "位置";
            case "plugins":
                return "插件列表";
            case "chunk":
                return "区块信息";
            case "inventory":
                return "背包";
            case "player":
                return "玩家状态";
            case "world":
                return "世界信息";
            case "online":
                return "在线人数";
            case "status":
                return "服务器状态";
            case "entities":
                return "实体统计";
            default:
                return key;
        }
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
