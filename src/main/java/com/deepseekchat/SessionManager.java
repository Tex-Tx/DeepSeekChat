package com.deepseekchat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器, 每个玩家独立会话 (仅内存, 上下文不落库)
 * 代理服全局以玩家名为会话键
 */
public class SessionManager {

    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    public ConversationSession getSession(String playerName) {
        String key = playerName.toLowerCase();
        return sessions.computeIfAbsent(key, n -> new ConversationSession(n, n));
    }

    public void removeSession(String playerName) {
        sessions.remove(playerName.toLowerCase());
    }

    public void clearAll() {
        sessions.clear();
    }
}
