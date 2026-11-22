package com.deepseekchat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * MySQL 连接池管理 (玩家 Token / 个人 API Key 存取)
 */
public class DatabaseManager {

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS ds_players (" +
                    "  name VARCHAR(32) NOT NULL, " +
                    "  uuid VARCHAR(36) DEFAULT NULL, " +
                    "  tokens BIGINT NOT NULL DEFAULT 0, " +
                    "  api_key VARCHAR(255) DEFAULT NULL, " +
                    "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "  PRIMARY KEY (name)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final DeepSeekChat plugin;
    private final ConfigManager config;
    private final int poolSize;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    private final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
    private volatile boolean connected;

    public DatabaseManager(DeepSeekChat plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.poolSize = config.getMysqlPoolSize();
        this.username = config.getMysqlUsername();
        this.password = config.getMysqlPassword();
        this.jdbcUrl = "jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
                + "/" + config.getMysqlDatabase()
                + "?useSSL=" + config.isMysqlUseSsl()
                + "&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true";
    }

    /**
     * 建立连接池, 返回是否至少建立了一个可用连接
     */
    public boolean connect() {
        close();
        connected = false;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("未找到 MySQL JDBC 驱动: " + e.getMessage());
            return false;
        }
        for (int i = 0; i < poolSize; i++) {
            try {
                Connection conn = createConnection();
                if (i == 0) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate(CREATE_TABLE_SQL);
                    }
                }
                pool.offer(conn);
            } catch (SQLException e) {
                plugin.getLogger().warning("初始化数据库连接池失败: " + e.getMessage());
            }
        }
        connected = !pool.isEmpty();
        return connected;
    }

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * 从池中获取连接 (池空则临时新建)
     */
    public Connection getConnection() {
        Connection conn = pool.poll();
        if (conn != null) {
            try {
                if (conn.isClosed() || !conn.isValid(2)) {
                    try {
                        conn.close();
                    } catch (SQLException ignored) {
                    }
                    conn = createConnection();
                }
            } catch (SQLException e) {
                try {
                    conn = createConnection();
                } catch (SQLException e2) {
                    plugin.getLogger().severe("获取数据库连接失败: " + e2.getMessage());
                    return null;
                }
            }
            return conn;
        }
        try {
            return createConnection();
        } catch (SQLException e) {
            plugin.getLogger().severe("创建数据库连接失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 归还连接
     */
    public void release(Connection conn) {
        if (conn == null) {
            return;
        }
        if (!pool.offer(conn)) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public DeepSeekChat getPlugin() {
        return plugin;
    }

    /**
     * 关闭所有连接
     */
    public void close() {
        Connection conn;
        while ((conn = pool.poll()) != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
        }
        connected = false;
    }
}
