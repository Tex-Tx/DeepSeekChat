package com.deepseekchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个玩家的独立会话, 负责上下文(历史消息)管理
 */
public class ConversationSession {

    private final String playerId;
    private final String playerName;
    private final List<Message> history = new ArrayList<>();
    /** 被压缩掉的早期对话摘要 */
    private volatile String summary;
    private volatile boolean busy;
    /** /ds stop 强制打断标志: 置位后当前进行中的轮次会在关键节点中止 */
    private volatile boolean cancelled;
    /** 当前轮次序号: 用于防止被打断的旧轮次结束后误清 busy 状态 */
    private volatile long roundSeq = 0;

    public ConversationSession(String playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    /**
     * 强制打断: 置取消标志 (当前进行中的轮次会在关键节点中止, 不再弹出确认/续推)
     */
    public void cancelCurrent() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void clearCancel() {
        this.cancelled = false;
    }

    /**
     * 开启新一轮对话, 返回本轮序号 (用于校验旧轮次结束后是否还有权修改 busy)
     */
    public long newRound() {
        return ++roundSeq;
    }

    /**
     * 该轮次序号是否仍是"当前轮次"
     */
    public boolean isCurrentRound(long id) {
        return id == roundSeq;
    }

    /**
     * 添加用户消息, 超过上限自动裁剪最旧消息
     */
    public void addUserMessage(String content, int maxHistory) {
        history.add(new Message("user", content));
        trim(maxHistory);
    }

    /**
     * 添加系统注入消息 (数据快照/执行结果/系统通知等), 同样参与裁剪
     * 以 system 角色存储, 避免与玩家发言混淆、挤占用户消息配额
     */
    public void addSystemMessage(String content, int maxHistory) {
        history.add(new Message("system", content));
        trim(maxHistory);
    }

    /**
     * 添加AI回复
     */
    public void addAssistantMessage(String content, int maxHistory) {
        history.add(new Message("assistant", content));
        trim(maxHistory);
    }

    /**
     * 回滚到指定历史长度 (请求失败时移除本轮加入的所有消息)
     */
    public void rollbackTo(int size) {
        if (size < 0) {
            size = 0;
        }
        while (history.size() > size) {
            history.remove(history.size() - 1);
        }
    }

    private void trim(int maxHistory) {
        if (maxHistory <= 0) {
            history.clear();
            return;
        }
        while (history.size() > maxHistory) {
            history.remove(0);
        }
    }

    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public String getSummary() {
        return summary;
    }

    /**
     * 自动压缩: 将早期对话替换为摘要, 仅保留最近 keepAfter 条消息
     */
    public void compress(String newSummary, int keepAfter) {
        this.summary = newSummary;
        int remove = Math.max(0, history.size() - Math.max(0, keepAfter));
        if (remove > 0) {
            history.subList(0, remove).clear();
        }
    }

    /**
     * 清空上下文 (用于 /ds clear 或强制结束对话)
     */
    public void clear() {
        history.clear();
        summary = null;
    }

    public void reset() {
        clear();
        setBusy(false);
    }

    /**
     * 对话消息 (role: system/user/assistant)
     */
    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
