package com.deepseekchat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Token 与玩家数据管理 (以玩家名为唯一键, 代理服跨服/离线均可操作)
 */
public class TokenManager {

    private final DatabaseManager database;

    public TokenManager(DatabaseManager database) {
        this.database = database;
    }

    /**
     * 加载玩家数据, 不存在则自动创建
     *
     * @param name 玩家名
     * @param uuid 玩家UUID (可为 null, 用于登记)
     */
    public PlayerData loadPlayer(String name, String uuid) {
        String key = name.toLowerCase();
        Connection conn = database.getConnection();
        if (conn == null) {
            return null;
        }
        try {
            String sql = "SELECT uuid, tokens, api_key FROM ds_players WHERE name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(key, rs.getString("uuid"), rs.getLong("tokens"), rs.getString("api_key"));
                    }
                }
            }
            // 不存在则插入
            String insert = "INSERT INTO ds_players (name, uuid, tokens, api_key) VALUES (?, ?, 0, NULL)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, key);
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
            return new PlayerData(key, uuid, 0L, null);
        } catch (SQLException e) {
            database.getPlugin().getLogger().severe("加载玩家数据失败: " + e.getMessage());
            return null;
        } finally {
            database.release(conn);
        }
    }

    public long getTokens(String name) {
        PlayerData data = loadPlayer(name, null);
        return data == null ? 0 : data.getTokens();
    }

    /**
     * 增加/扣除 Token (amount 可为负数)
     */
    public boolean addTokens(String name, long amount) {
        Connection conn = database.getConnection();
        if (conn == null) {
            return false;
        }
        String sql = "UPDATE ds_players SET tokens = GREATEST(0, tokens + ?), updated_at = NOW() WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, name.toLowerCase());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            database.getPlugin().getLogger().severe("更新 Token 失败: " + e.getMessage());
            return false;
        } finally {
            database.release(conn);
        }
    }

    /**
     * 设置玩家 Token 数量
     */
    public boolean setTokens(String name, long amount) {
        Connection conn = database.getConnection();
        if (conn == null) {
            return false;
        }
        String sql = "UPDATE ds_players SET tokens = ?, updated_at = NOW() WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Math.max(0, amount));
            ps.setString(2, name.toLowerCase());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            database.getPlugin().getLogger().severe("设置 Token 失败: " + e.getMessage());
            return false;
        } finally {
            database.release(conn);
        }
    }

    /**
     * 保存/清除玩家个人 API Key (key 传 null 表示清除)
     */
    public boolean setApiKey(String name, String apiKey) {
        Connection conn = database.getConnection();
        if (conn == null) {
            return false;
        }
        String sql = "UPDATE ds_players SET api_key = ?, updated_at = NOW() WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, apiKey);
            ps.setString(2, name.toLowerCase());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            database.getPlugin().getLogger().severe("保存 API Key 失败: " + e.getMessage());
            return false;
        } finally {
            database.release(conn);
        }
    }
}
