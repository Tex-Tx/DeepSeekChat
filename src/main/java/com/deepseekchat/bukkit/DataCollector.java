package com.deepseekchat.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏数据采集器 (子服端)
 * 仅在玩家二次确认且该类别已授权时被调用
 */
public class DataCollector {

    private final BukkitPlugin plugin;

    public DataCollector(BukkitPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 采集玩家级数据 (需要在目标子服上有该玩家)
     */
    public JsonObject collect(String category, Player player) {
        JsonObject out = new JsonObject();
        switch (category) {
            case "position": {
                Location l = player.getLocation();
                out.addProperty("世界", l.getWorld() == null ? "?" : l.getWorld().getName());
                out.addProperty("X", round(l.getX()));
                out.addProperty("Y", round(l.getY()));
                out.addProperty("Z", round(l.getZ()));
                out.addProperty("朝向yaw", (int) l.getYaw());
                out.addProperty("俯仰pitch", (int) l.getPitch());
                break;
            }
            case "chunk": {
                Chunk c = player.getLocation().getChunk();
                out.addProperty("区块坐标", c.getWorld().getName() + " (" + c.getX() + ", " + c.getZ() + ")");
                out.addProperty("区块实体数", c.getEntities().length);
                out.addProperty("生物群系", player.getLocation().getBlock().getBiome().name());
                break;
            }
            case "inventory": {
                ItemStack[] contents = player.getInventory().getContents();
                List<String> items = new ArrayList<>();
                for (ItemStack it : contents) {
                    if (it != null && it.getType() != Material.AIR) {
                        items.add(it.getType().name() + "x" + it.getAmount());
                        if (items.size() >= 12) {
                            break;
                        }
                    }
                }
                out.addProperty("背包", items.isEmpty() ? "(空)" : String.join(", ", items));
                break;
            }
            case "player": {
                out.addProperty("生命值", round(player.getHealth()) + " / " + Math.round(player.getMaxHealth()));
                out.addProperty("饥饿值", player.getFoodLevel());
                out.addProperty("饱和度", round(player.getSaturation()));
                out.addProperty("经验等级", player.getLevel());
                out.addProperty("游戏模式", player.getGameMode().name());
                out.addProperty("飞行状态", player.isFlying() ? "飞行中" : "在地面");
                out.addProperty("所在世界", player.getWorld().getName());
                break;
            }
            case "world": {
                World w = player.getWorld();
                long time = w.getTime() % 24000L;
                String phase;
                if (time < 12000) {
                    phase = "白天";
                } else if (time < 13800) {
                    phase = "黄昏";
                } else if (time < 22200) {
                    phase = "夜晚";
                } else {
                    phase = "黎明";
                }
                out.addProperty("世界名", w.getName());
                out.addProperty("游戏时间", time + " 刻度 (" + phase + ")");
                out.addProperty("天气", w.hasStorm() ? (w.isThundering() ? "雷雨" : "下雨") : "晴朗");
                out.addProperty("难度", w.getDifficulty().name());
                Location spawn = w.getSpawnLocation();
                out.addProperty("出生点", "X=" + spawn.getBlockX() + ", Y=" + spawn.getBlockY() + ", Z=" + spawn.getBlockZ());
                break;
            }
            default:
                throw new IllegalArgumentException("未知数据类别: " + category);
        }
        return out;
    }

    /**
     * 采集服务器级数据 (不依赖具体玩家, 可针对任意子服)
     */
    public JsonObject collectServer(String category) {
        JsonObject out = new JsonObject();
        switch (category) {
            case "plugins": {
                Plugin[] plugins = plugin.getServer().getPluginManager().getPlugins();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < plugins.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(plugins[i].getName());
                    if (sb.length() > 400) {
                        sb.append(" ...");
                        break;
                    }
                }
                out.addProperty("插件列表", sb.toString() + " (共" + plugins.length + "个)");
                break;
            }
            case "online": {
                java.util.Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
                List<String> names = new ArrayList<>();
                for (Player p : online) {
                    names.add(p.getName());
                    if (names.size() >= 30) {
                        names.add("...");
                        break;
                    }
                }
                out.addProperty("在线人数", online.size());
                out.addProperty("在线玩家", names.isEmpty() ? "(无)" : String.join(", ", names));
                break;
            }
            case "status": {
                Runtime rt = Runtime.getRuntime();
                long max = rt.maxMemory() / 1048576L;
                long used = max - (rt.freeMemory() / 1048576L);
                out.addProperty("内存占用", used + "MB / " + max + "MB");
                List<String> worlds = new ArrayList<>();
                for (World w : plugin.getServer().getWorlds()) {
                    worlds.add(w.getName() + "(" + w.getPlayers().size() + "人)");
                }
                out.addProperty("世界列表", worlds.isEmpty() ? "(无)" : String.join(", ", worlds));
                break;
            }
            case "entities": {
                List<String> stat = new ArrayList<>();
                for (World w : plugin.getServer().getWorlds()) {
                    stat.add(w.getName() + ": 实体" + w.getEntities().size() + ", 区块" + w.getLoadedChunks().length);
                }
                out.addProperty("实体统计", stat.isEmpty() ? "(无)" : String.join("; ", stat));
                break;
            }
            default:
                throw new IllegalArgumentException("未知服务器级数据类别: " + category);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
