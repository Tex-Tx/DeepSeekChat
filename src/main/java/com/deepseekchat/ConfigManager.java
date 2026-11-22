package com.deepseekchat;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置读取管理器, 集中从 config.yml 读取各项配置
 */
public class ConfigManager {

    private final DeepSeekChat plugin;
    private Configuration cfg;

    public ConfigManager(DeepSeekChat plugin) {
        this.plugin = plugin;
        this.cfg = load();
    }

    public void reload() {
        this.cfg = load();
    }

    /**
     * 加载配置: 首次运行自动生成默认 config.yml
     */
    private Configuration load() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File file = new File(dataFolder, "config.yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("生成默认 config.yml 失败: " + e.getMessage());
            }
        }
        try {
            return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } catch (IOException e) {
            plugin.getLogger().severe("加载 config.yml 失败: " + e.getMessage());
            return ConfigurationProvider.getProvider(YamlConfiguration.class).load(new StringReader(""));
        }
    }

    // ---------- 管理员 ----------
    public List<String> getAdminList() {
        List<String> list = cfg.getStringList("admins");
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                result.add(s.trim().toLowerCase());
            }
        }
        return result;
    }

    // ---------- MySQL ----------
    public String getMysqlHost() {
        return cfg.getString("mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return cfg.getInt("mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return cfg.getString("mysql.database", "deepseek_chat");
    }

    public String getMysqlUsername() {
        return cfg.getString("mysql.username", "root");
    }

    public String getMysqlPassword() {
        return cfg.getString("mysql.password", "");
    }

    public boolean isMysqlUseSsl() {
        return cfg.getBoolean("mysql.use-ssl", false);
    }

    public int getMysqlPoolSize() {
        return Math.max(1, cfg.getInt("mysql.pool-size", 5));
    }

    // ---------- 公共 API ----------
    public String getPublicApiKey() {
        return cfg.getString("public-api.api-key", "");
    }

    /**
     * 公共API使用的模型名称 (未单独配置则回退到全局 model)
     */
    public String getPublicApiModel() {
        String m = cfg.getString("public-api.model", "");
        return m.trim().isEmpty() ? getModel() : m.trim();
    }

    /**
     * 公共API使用的模型接口地址 (未单独配置则回退到全局 api-url)
     */
    public String getPublicApiUrl() {
        String url = cfg.getString("public-api.api-url", "");
        if (url.trim().isEmpty()) {
            return getApiUrl();
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // ---------- 模型 ----------
    public String getModel() {
        return cfg.getString("model", "deepseek-v4-flash");
    }

    public String getApiUrl() {
        String url = cfg.getString("api-url", "https://api.deepseek.com");
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public boolean isThinkingDisabled() {
        return cfg.getBoolean("disable-thinking", true);
    }

    public int getMaxReplyLength() {
        return Math.max(100, cfg.getInt("max-reply-length", 2000));
    }

    public int getTimeoutSeconds() {
        return Math.max(5, cfg.getInt("timeout-seconds", 60));
    }

    // ---------- 上下文 ----------
    public int getMaxHistory() {
        return Math.max(0, cfg.getInt("context.max-history", 30));
    }

    public int getCompressThreshold() {
        return Math.max(0, cfg.getInt("context.compress-threshold", 20));
    }

    public int getKeepAfterCompress() {
        return Math.max(0, cfg.getInt("context.keep-after-compress", 6));
    }

    public String getCompressPrompt() {
        return cfg.getString("context.compress-prompt", "请将我们之前的对话压缩成一段简洁的中文摘要。");
    }

    public String getSystemPrompt() {
        return cfg.getString("context.system-prompt", "你是一个友善、乐于助人的AI助手。");
    }

    // ---------- Agent ----------
    public boolean isAgentEnabled() {
        return cfg.getBoolean("agent.enabled", true);
    }

    public int getAgentConfirmTimeout() {
        return Math.max(10, cfg.getInt("agent.confirm-timeout", 60));
    }

    public int getAgentMaxRounds() {
        return Math.max(1, cfg.getInt("agent.max-rounds", 3));
    }

    public String getAgentPrompt() {
        return cfg.getString("agent.agent-prompt",
                "你是游戏助手, 可请求游戏数据或执行指令, 使用 [AGENT_REQUEST] 标签提出请求。");
    }

    /**
     * 管理员开关 Agent 功能 (同时写入配置持久化)
     */
    public void setAgentEnabled(boolean enabled) {
        cfg.set("agent.enabled", enabled);
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class)
                    .save(cfg, new File(plugin.getDataFolder(), "config.yml"));
        } catch (IOException e) {
            plugin.getLogger().severe("保存 agent.enabled 失败: " + e.getMessage());
        }
    }
}
