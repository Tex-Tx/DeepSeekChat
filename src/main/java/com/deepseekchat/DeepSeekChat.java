package com.deepseekchat;

import com.deepseekchat.command.DSCommand;
import com.deepseekchat.listener.JoinListener;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * DeepSeekChat 主插件类 (BungeeCord / Waterfall 代理服插件)
 */
public final class DeepSeekChat extends Plugin {

    private static DeepSeekChat instance;

    private ConfigManager configManager;
    private DatabaseManager database;
    private TokenManager tokenManager;
    private SessionManager sessionManager;
    private DeepSeekAPI deepSeekAPI;
    private AgentManager agentManager;

    public static DeepSeekChat getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        this.configManager = new ConfigManager(this);
        this.database = new DatabaseManager(this, configManager);
        this.tokenManager = new TokenManager(database);
        this.sessionManager = new SessionManager();
        this.deepSeekAPI = new DeepSeekAPI(configManager);
        this.agentManager = new AgentManager(this);

        // 注册与子服通信的插件消息通道 (热加载重复注册时忽略)
        try {
            getProxy().registerChannel(AgentManager.CHANNEL);
        } catch (IllegalStateException e) {
            getLogger().info("插件消息通道已存在, 跳过注册");
        }
        getProxy().getPluginManager().registerListener(this, agentManager);

        getProxy().getPluginManager().registerCommand(this, new DSCommand(this, agentManager));
        getProxy().getPluginManager().registerListener(this, new JoinListener(this));

        // 异步初始化数据库, 避免卡代理主线程
        getProxy().getScheduler().runAsync(this, () -> {
            if (database.connect()) {
                getLogger().info("MySQL 连接成功");
            } else {
                getLogger().warning("MySQL 连接失败, 请检查 config.yml 中的 mysql 配置!");
            }
        });

        getLogger().info("DeepSeekChat v" + getDescription().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        if (sessionManager != null) {
            sessionManager.clearAll();
        }
        instance = null;
        getLogger().info("DeepSeekChat 已卸载");
    }

    /**
     * 重载配置文件与数据库连接
     */
    public void reloadPlugin() {
        configManager.reload();
        getProxy().getScheduler().runAsync(this, () -> {
            database.close();
            if (database.connect()) {
                getLogger().info("MySQL 重新连接成功");
            } else {
                getLogger().warning("MySQL 重新连接失败!");
            }
        });
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public TokenManager getTokenManager() {
        return tokenManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public DeepSeekAPI getDeepSeekAPI() {
        return deepSeekAPI;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }
}
