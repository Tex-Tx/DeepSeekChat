package com.deepseekchat;

/**
 * 玩家数据实体 (来自 MySQL ds_players 表, 以玩家名为唯一键)
 */
public class PlayerData {

    private final String name;
    private String uuid;
    private long tokens;
    private String apiKey;

    public PlayerData(String name, String uuid, long tokens, String apiKey) {
        this.name = name;
        this.uuid = uuid;
        this.tokens = tokens;
        this.apiKey = apiKey;
    }

    public String getName() {
        return name;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public long getTokens() {
        return tokens;
    }

    public void setTokens(long tokens) {
        this.tokens = tokens;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
