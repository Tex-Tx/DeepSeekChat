package com.deepseekchat.bukkit;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * 玩家游戏数据授权管理 (子服端)
 *
 * 玩家可单独选择授权类别:
 * position(位置) / chunk(区块信息) / inventory(背包) / player(玩家状态) / world(世界信息) 为玩家级数据;
 * plugins(插件列表) / online(在线人数) / status(服务器状态) / entities(实体统计) 为服务器级数据
 * (服务器级数据同样需玩家授权才可查)
 * 数据保存在 auth.yml, 只有"总开关 + 对应类别"都开启时才会把数据发给 AI
 */
public class AuthManager {

    public static final String[] CATEGORIES =
            {"position", "plugins", "chunk", "inventory", "player", "world", "online", "status", "entities"};

    private final BukkitPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public AuthManager(BukkitPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private String key(String name) {
        return name.toLowerCase();
    }

    /**
     * 数据授权总开关 (玩家 /dsauth on|off)
     */
    public boolean isEnabled(String name) {
        return data.getBoolean("players." + key(name) + ".enabled", false);
    }

    public void setEnabled(String name, boolean enabled) {
        data.set("players." + key(name) + ".enabled", enabled);
        save();
    }

    /**
     * 单个数据类别是否授权
     */
    public boolean isAuthorized(String name, String category) {
        return data.getBoolean("players." + key(name) + ".categories." + category, false);
    }

    public void setAuthorized(String name, String category, boolean authorized) {
        data.set("players." + key(name) + ".categories." + category, authorized);
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存授权文件失败: " + e.getMessage());
        }
    }
}
