package com.deepseekchat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Agent 管理器 (BungeeCord 端)
 *
 * 职责:
 * 1. 负责整条对话流水线 (额度校验/扣除/退款、上下文压缩、API 调用、回复展示)
 * 2. 解析 AI 回复中的 [AGENT_REQUEST] 标签 (请求数据/执行指令/提问)
 * 3. 管理所有二次确认: 通过聊天文字按钮 (RUN_COMMAND) 让玩家点击同意/拒绝
 * 4. 通过 BungeeCord 插件消息通道与玩家所在子服通信 (按子服名称精确路由)
 */
public class AgentManager implements Listener {

    public static final String CHANNEL = "deepseek:main";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeepSeekChat plugin;
    /** 待玩家二次确认的操作 (token -> action) */
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();
    // 已消费的确认 token (防止二次点击误报"不存在"), 只保留最近 2 分钟
    private final Map<String, Long> consumed = new ConcurrentHashMap<>();
    /** 已确认、正在等待子服返回结果的操作 (token -> action) */
    private final Map<String, PendingAction> waitingResponse = new ConcurrentHashMap<>();
    /** AI 提问后等待玩家回答 (playerName -> 待答问题) */
    private final Map<String, PendingQuestion> awaitingReply = new ConcurrentHashMap<>();
    /** 记录玩家最近一次已确认执行的数据请求 (用于检测重复请求循环) */
    private final Map<String, TaskStep> lastDataRequest = new ConcurrentHashMap<>();

    private static class TaskStep {
        final String category;
        String result = "";
        boolean corrected;

        TaskStep(String category) {
            this.category = category;
        }
    }

    public AgentManager(DeepSeekChat plugin) {
        this.plugin = plugin;
    }

    // ==================== Agent 请求结构 ====================

    public static class AgentRequest {
        public String type;      // data | command | question
        public String category;  // position | plugins | chunk | inventory | online | status
        public String server;    // 目标子服名 (可省略, 省略则默认玩家当前所在子服)
        public String command;
        public String scope;     // 指令执行位置: 省略或非bungee = 子服; "bungee"/"bc"/"proxy" = 代理服
        public String text;
        public String reason;
    }

    public static class AgentParseResult {
        public final String cleanText;
        public final List<AgentRequest> requests;

        public AgentParseResult(String cleanText, List<AgentRequest> requests) {
            this.cleanText = cleanText;
            this.requests = requests;
        }
    }

    /**
     * AI 向玩家提问后, 等待玩家提交回答的状态
     */
    public static class PendingQuestion {
        public final String playerName;
        public final String question;
        public final int depth;

        public PendingQuestion(String playerName, String question, int depth) {
            this.playerName = playerName;
            this.question = question;
            this.depth = depth;
        }
    }

    /**
     * 从 AI 回复中解析出 [AGENT_REQUEST] 标签, 并返回去除标签后的纯文本
     *
     * 注意: 标签 JSON 内可能出现 {name} 之类的占位符, 正则非贪婪匹配会被其中的 "}" 提前截断,
     * 因此必须用括号配对扫描来定位完整的标签区间。
     */
    public static AgentParseResult parseAgentRequests(String reply) {
        List<AgentRequest> requests = new ArrayList<>();
        List<int[]> tagSpans = new ArrayList<>(); // [起始, 结束](含) 的完整标签区间
        int idx = 0;
        while (true) {
            int start = reply.indexOf("[AGENT_REQUEST]", idx);
            if (start < 0) {
                break;
            }
            int open = reply.indexOf('{', start);
            if (open < 0) {
                idx = start + 1;
                continue;
            }
            int end = findTagEnd(reply, open);
            if (end < 0) {
                idx = start + 1;
                continue;
            }
            tagSpans.add(new int[]{start, end});
            String json = reply.substring(open, end + 1);
            try {
                JsonObject o = new JsonParser().parse(json).getAsJsonObject();
                AgentRequest r = new AgentRequest();
                r.type = o.has("type") ? o.get("type").getAsString() : "";
                r.category = o.has("category") ? o.get("category").getAsString() : null;
                r.server = o.has("server") ? o.get("server").getAsString() : null;
                r.command = o.has("command") ? o.get("command").getAsString() : null;
                r.scope = o.has("scope") ? o.get("scope").getAsString() : null;
                r.text = o.has("text") ? o.get("text").getAsString() : null;
                r.reason = o.has("reason") ? o.get("reason").getAsString() : null;
                requests.add(r);
            } catch (Exception ignored) {
            }
            idx = end + 1;
        }
        // 按标签区间剔除标签, 保留其余文字 (同样避免 {name} 干扰)
        String clean;
        if (tagSpans.isEmpty()) {
            clean = reply.trim();
        } else {
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            for (int[] span : tagSpans) {
                sb.append(reply, pos, span[0]);
                pos = span[1] + 1;
            }
            sb.append(reply.substring(pos));
            clean = sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
        }
        return new AgentParseResult(clean, requests);
    }

    /**
     * 从 open(第一个'{') 开始做括号配对, 返回与之匹配的 '}' 下标; 忽略字符串内的括号
     */
    public static int findTagEnd(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /** 回复中出现这些词且没有标签时, 触发一次辅助调用让模型补输出标签 */
    private static final Pattern AGENT_INTENT_PATTERN = Pattern.compile(
            "查询|查看|看看|检查|统计|传送|tp\\b|给|发|执行|建造|放置|破坏|在线|名单|坐标|立刻|马上|现在|继续|下一步|接下来|然后|帮我|帮您|多少人|在哪里|是多少|帮我查");

    private boolean hasAgentIntent(String text) {
        return text != null && AGENT_INTENT_PATTERN.matcher(text).find();
    }

    /**
     * 模型漏输出标签时的辅助判定提示词: 让模型基于对话判断是否需要操作并补一个标签
     * 注意: 该调用中模型扮演的是"标签生成器"而非对话中的助手, 必须严格限制输出
     */
    private String buildAgentFallbackPrompt(String assistantReply) {
        return "你是Minecraft服务器AI插件的\"操作标签生成器\"。你是被系统程序调用的, 不是在跟玩家对话, 不要输出聊天内容。\n"
                + "助手(另一个AI)对玩家的最新回复是: \"" + assistantReply + "\"\n"
                + "你的唯一任务: 判断助手是否应立即执行一个游戏操作(查询数据/执行指令/向玩家提问)。\n"
                + "输出规则(严格二选一, 禁止输出任何其他内容、解释或聊天文字):\n"
                + "- 需要操作, 只输出一个标签: [AGENT_REQUEST]{\"type\":\"command\",\"command\":\"完整可执行指令\",\"reason\":\"原因\"}\n"
                + "  或 [AGENT_REQUEST]{\"type\":\"data\",\"category\":\"online\",\"reason\":\"原因\"}\n"
                + "  或 [AGENT_REQUEST]{\"type\":\"question\",\"text\":\"问题内容\"}\n"
                + "  代理服(BungeeCord)命令加 scope 字段: [AGENT_REQUEST]{\"type\":\"command\",\"scope\":\"bungee\",\"command\":\"代理服命令\",\"reason\":\"原因\"}\n"
                + "- 不需要操作, 只输出: NO_AGENT\n"
                + "注意: 指令必须是完整可执行的服务器命令, 涉及玩家名用 {name} 占位。";
    }

    /** 将最近 n 条历史扁平化为纯文本 (供辅助判定使用, 避免模型代入对话角色) */
    private String renderLastHistoryAsText(ConversationSession session, int n) {
        List<ConversationSession.Message> hist = session.getHistory();
        int from = Math.max(0, hist.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < hist.size(); i++) {
            ConversationSession.Message m = hist.get(i);
            sb.append('[').append(m.role).append("] ").append(m.content).append('\n');
        }
        return sb.toString();
    }

    /** 辅助判定结果是否为"不需要操作" */
    private boolean isNoAgent(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.equalsIgnoreCase("NO_AGENT")
                || t.toUpperCase().contains("NO_AGENT")
                || t.contains("不需要操作");
    }

    // ==================== 对话流水线 ====================

    /**
     * 玩家通过 /ds send 发起一轮对话 (入口)
     */
    public void startChat(ProxiedPlayer player, String content) {
        ConversationSession session = plugin.getSessionManager().getSession(player.getName());
        if (session.isBusy()) {
            sendTo(player, "&cAI 正在思考中, 请稍候…");
            return;
        }
        runRound(player, content, 0);
    }

    /**
     * /ds stop: 强制打断正在进行的对话
     * 立即释放忙碌状态, 取消当前轮次(不再弹确认/续推), 并清空待确认/待回包/待回答状态与上下文
     */
    public void stopChat(ProxiedPlayer player) {
        String name = player.getName();
        ConversationSession session = plugin.getSessionManager().getSession(name);
        session.cancelCurrent();
        session.setBusy(false);
        session.clear();
        // 取消该玩家所有待确认操作 (含超时任务)
        pending.entrySet().removeIf(e -> {
            if (e.getValue().playerName.equalsIgnoreCase(name)) {
                if (e.getValue().timeoutTask != null) {
                    e.getValue().timeoutTask.cancel();
                }
                return true;
            }
            return false;
        });
        // 取消所有正在等待子服返回的操作
        waitingResponse.entrySet().removeIf(e -> e.getValue().playerName.equalsIgnoreCase(name));
        // 取消等待回答的AI问题
        awaitingReply.remove(name);
        lastDataRequest.remove(name);
        sendTo(player, "&e[DeepSeek] 已强制打断当前对话, 上下文已清空。可重新使用 &b/ds send&e 开始新对话。");
    }

    /**
     * 在异步线程执行一轮对话; 完成后释放忙碌状态
     */
    private void runRound(ProxiedPlayer player, String content, int depth) {
        ConversationSession session = plugin.getSessionManager().getSession(player.getName());
        // 玩家新发起一轮对话 (depth=0) 时, 重置任务链状态与打断标志
        if (depth == 0) {
            session.clearCancel();
            lastDataRequest.remove(player.getName());
        }
        // 每轮(含 Agent 续推/验证轮)都提示正在思考, 让玩家知道 AI 还在工作
        sendTo(player, "&7[DeepSeek] &e正在思考" + (depth > 0 ? " (第 " + (depth + 1) + " 轮)" : "") + "…");
        final long roundId = session.newRound();
        session.setBusy(true);
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try {
                chatRound(player, content, depth);
            } catch (Throwable t) {
                plugin.getLogger().warning("对话处理异常: " + t);
                sendTo(player, "&c对话处理出错: " + t.getMessage());
            } finally {
                // 仅当仍是当前轮次时才释放 busy, 避免被打断的旧轮次误清状态
                if (session.isCurrentRound(roundId)) {
                    session.setBusy(false);
                }
            }
        });
    }

    /**
     * 单轮对话核心: 额度校验/扣除、上下文压缩、调用 AI、展示回复、解析 Agent 请求
     */
    private void chatRound(ProxiedPlayer player, String userMessage, int depth) {
        ConversationSession session = plugin.getSessionManager().getSession(player.getName());
        long spent = 0;
        int histBefore = 0; // 本轮注入消息前的历史长度, 请求失败时用于精确回滚
        boolean usingPublic = false;
        try {
            PlayerData data = plugin.getTokenManager().loadPlayer(player.getName(), player.getUniqueId().toString());
            if (data == null) {
                sendTo(player, "&c数据库连接异常, 无法使用AI对话");
                return;
            }
            String apiKey = data.getApiKey();
            usingPublic = (apiKey == null || apiKey.trim().isEmpty());

            ConfigManager cfg = plugin.getConfigManager();
            long cost = 1; // 每次对话消耗 1 点额度
            String model = cfg.getModel();
            String apiUrl = cfg.getApiUrl();

            if (usingPublic) {
                apiKey = cfg.getPublicApiKey();
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    sendTo(player, "&c未配置 API Key! 请使用 &e/ds api set <key>&c 设置个人API, 或联系管理员配置公共API");
                    return;
                }
                model = cfg.getPublicApiModel();
                apiUrl = cfg.getPublicApiUrl();
                long balance = data.getTokens();
                boolean willCompress = cfg.getCompressThreshold() > 0
                        && session.getHistory().size() >= cfg.getCompressThreshold();
                long required = willCompress ? cost * 2 : cost;
                if (balance < required) {
                    if (willCompress && balance >= cost) {
                        willCompress = false; // 额度只够一轮, 本次跳过压缩
                    } else {
                        session.reset();
                        sendTo(player, "&c对话额度不足! 当前剩余 &e" + balance + "&c 点, 本次需要 &e" + required + "&c 点。"
                                + "\n&c对话已强制结束, 请使用 &e/ds api set <key>&c 设置个人API(不消耗额度), 或联系管理员充值。");
                        return;
                    }
                }
                // 1) 上下文过长则先自动压缩 (公共API压缩额外扣1点额度, 失败退回)
                if (willCompress && compressIfNeeded(player, cfg, apiKey, model, apiUrl, true)) {
                    spent += cost;
                }
                // 2) 主请求
                plugin.getTokenManager().addTokens(player.getName(), -cost);
                spent += cost;
                sendTo(player, "&7[DeepSeek] &f本次消耗 &e" + cost + "&f 点额度, 剩余 &e" + (balance - spent) + "&f 点");
            } else {
                // 个人API: 同样自动压缩 (不扣额度), 避免历史被硬截断丢失早期对话
                compressIfNeeded(player, cfg, apiKey, model, apiUrl, false);
            }

            // 记录本轮注入前历史长度, 请求失败时精确回滚本轮新增的消息
            histBefore = session.getHistory().size();
            if (isSystemInjection(userMessage)) {
                session.addSystemMessage(userMessage, cfg.getMaxHistory());
            } else {
                session.addUserMessage(userMessage, cfg.getMaxHistory());
            }

            String systemPrompt = buildSystemPrompt(player, cfg);
            String reply = plugin.getDeepSeekAPI().chat(
                    apiKey, model, apiUrl, session.getHistory(), systemPrompt, session.getSummary());

            AgentParseResult parsed = parseAgentRequests(reply);
            // 存入历史的是去除标签后的纯文本, 避免标签污染后续上下文
            session.addAssistantMessage(parsed.cleanText, cfg.getMaxHistory());
            sendTo(player, "&b[DeepSeek回复]&e" + escapeAmp(parsed.cleanText));

            // 玩家执行了 /ds stop: 本轮回复已展示, 但不再触发辅助判定/确认按钮/续推
            if (session.isCancelled()) {
                return;
            }

            // 模型漏输出标签但回复表现出继续操作的意图时, 用一次辅助调用让模型补输出标签 (不额外扣费)
            boolean continuation = userMessage.contains("系统·上一步执行结果")
                    || userMessage.contains("系统·上次操作结果快照");
            if (parsed.requests.isEmpty() && cfg.isAgentEnabled()
                    && (continuation || hasAgentIntent(parsed.cleanText + " " + userMessage))) {
                plugin.getLogger().info("[辅助判定] 模型回复无标签 (意图词命中=" + hasAgentIntent(parsed.cleanText + " " + userMessage)
                        + ", 续推=" + continuation + "), 触发补标签调用");
                // 辅助调用会阻塞数秒, 先提示玩家避免误以为卡住
                sendTo(player, "&7[DeepSeek] &f正在确认操作意图, 请稍候…");
                // 上下文扁平化为单条 user 消息, 模型只当"标签生成器", 不当对话助手
                List<ConversationSession.Message> clsHist = new ArrayList<>();
                clsHist.add(new ConversationSession.Message("user",
                        "对话上下文:\n" + renderLastHistoryAsText(session, 6)));
                try {
                    String fb = plugin.getDeepSeekAPI().chat(apiKey, model, apiUrl,
                            clsHist, buildAgentFallbackPrompt(parsed.cleanText), null);
                    plugin.getLogger().info("[辅助判定] 结果: " + fb);
                    AgentParseResult parsed2 = parseAgentRequests(fb);
                    // API 偶发返回乱码(无标签且非NO_AGENT)时重试一次
                    if (parsed2.requests.isEmpty() && !isNoAgent(fb)) {
                        fb = plugin.getDeepSeekAPI().chat(apiKey, model, apiUrl,
                                clsHist, buildAgentFallbackPrompt(parsed.cleanText), null);
                        plugin.getLogger().info("[辅助判定] 重试结果: " + fb);
                        parsed2 = parseAgentRequests(fb);
                    }
                    if (!parsed2.requests.isEmpty()) {
                        parsed = parsed2;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[辅助判定] 调用失败: " + e.getMessage());
                }
            }

            if (parsed.requests.isEmpty() || !cfg.isAgentEnabled()) {
                return;
            }
            if (depth >= cfg.getAgentMaxRounds()) {
                sendTo(player, "&7[DeepSeek] &e已达最大连续操作轮数 (&b" + cfg.getAgentMaxRounds()
                        + "&e 轮), 已停止, 如需继续请再发消息。");
                return;
            }
            AgentRequest req0 = parsed.requests.get(0);
            // 防循环: 模型连续多轮请求同一类数据时, 拦截并纠正一次, 再重复则停止
            if (isRepeatedDataRequest(player.getName(), req0)) {
                handleRepeatedDataRequest(player, req0, depth, cfg, apiKey, model, apiUrl, systemPrompt, session);
                return;
            }
            handleAgentRequest(player, req0, depth);
        } catch (DeepSeekAPIException e) {
            refund(player, usingPublic, spent);
            session.rollbackTo(histBefore);
            sendTo(player, "&cDeepSeek API 错误: " + e.getMessage());
        } catch (Exception e) {
            refund(player, usingPublic, spent);
            session.rollbackTo(histBefore);
            sendTo(player, "&c请求失败: " + e.getMessage());
            plugin.getLogger().warning("AI 请求异常: " + e.getMessage());
        }
    }

    /**
     * 判断注入内容是否为系统消息 (数据快照/执行结果/系统通知/纠正), 以 system 角色存储
     */
    private boolean isSystemInjection(String msg) {
        if (msg == null) {
            return false;
        }
        String t = msg.trim();
        return t.startsWith("[系统·") || t.startsWith("[系统通知]") || t.startsWith("系统警告:");
    }

    /**
     * 历史达到阈值时自动压缩为摘要; 公共API压缩额外扣 1 点额度 (失败退回)
     *
     * @return 是否执行了压缩
     */
    private boolean compressIfNeeded(ProxiedPlayer player, ConfigManager cfg,
                                     String apiKey, String model, String apiUrl, boolean usingPublic) {
        ConversationSession session = plugin.getSessionManager().getSession(player.getName());
        if (cfg.getCompressThreshold() <= 0 || session.getHistory().size() < cfg.getCompressThreshold()) {
            return false;
        }
        boolean deducted = false;
        if (usingPublic) {
            plugin.getTokenManager().addTokens(player.getName(), -1);
            deducted = true;
        }
        try {
            String summary = plugin.getDeepSeekAPI().chat(apiKey, model, apiUrl,
                    session.getHistory(), cfg.getCompressPrompt(), null);
            session.compress(summary, cfg.getKeepAfterCompress());
            sendTo(player, "&7[DeepSeek] &f上下文过长, 已自动压缩为摘要, 继续对话");
            return true;
        } catch (Exception e) {
            if (deducted) {
                plugin.getTokenManager().addTokens(player.getName(), 1);
            }
            sendTo(player, "&7[DeepSeek] &c上下文压缩失败, 继续对话 (将保留全部历史)");
            return false;
        }
    }

    /**
     * 模型出错或请求失败时不扣除额度 (已扣的自动退回)
     */
    private void refund(ProxiedPlayer player, boolean usingPublic, long spent) {
        if (usingPublic && spent > 0) {
            plugin.getTokenManager().addTokens(player.getName(), spent);
            sendTo(player, "&7[DeepSeek] &e已退回 &c" + spent + "&e 点额度 (请求失败)");
        }
    }

    private String buildSystemPrompt(ProxiedPlayer player, ConfigManager cfg) {
        // 每轮对话注入当前提问玩家的名称与UUID, 便于 AI 区分不同玩家
        String identity = "\n当前正在与你对话的玩家: " + player.getName()
                + " (UUID: " + player.getUniqueId().toString() + ")";
        String base = cfg.getSystemPrompt()
                .replace("{name}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString());
        if (cfg.isAgentEnabled()) {
            base = base + "\n" + cfg.getAgentPrompt()
                    .replace("{name}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{servers}", serverNames());
        }
        return base + identity;
    }

    private String serverNames() {
        return String.join(", ", plugin.getProxy().getServers().keySet());
    }

    // ==================== Agent 请求与二次确认 ====================

    private void handleAgentRequest(ProxiedPlayer player, AgentRequest req, int depth) {
        String token = nextToken();
        String reason = (req.reason == null || req.reason.trim().isEmpty()) ? "无" : req.reason;
        PendingAction act = new PendingAction(token, player.getName(), req.type, depth);

        switch (req.type == null ? "" : req.type.toLowerCase()) {
            case "data": {
                String cat = normalizeCategory(req.category);
                if (cat == null) {
                    sendTo(player, "&7[DeepSeek] &eAI 请求的数据类别无效, 已忽略。");
                    return;
                }
                act.category = cat;
                // 目标子服 (可省略, 默认玩家当前所在子服)
                String targetServer = null;
                if (req.server != null && !req.server.trim().isEmpty()) {
                    targetServer = req.server.trim();
                    if (plugin.getProxy().getServerInfo(targetServer) == null) {
                        sendTo(player, "&7[DeepSeek] &eAI 请求的目标子服 &c" + targetServer
                                + "&e 不存在, 已忽略。可选子服: &b" + serverNames());
                        return;
                    }
                }
                act.server = targetServer;
                pending.put(token, act);
                scheduleTimeout(act);
                sendConfirmButtons(player, buildDataConfirmText(cat, targetServer, reason), token);
                break;
            }
            case "command": {
                if (req.command == null || req.command.trim().isEmpty()) {
                    sendTo(player, "&7[DeepSeek] &eAI 请求的指令为空, 已忽略。");
                    return;
                }
                // {name} 占位符替换为实际玩家名, 确认文案与最终执行都用真实指令
                String cmd = req.command.trim().replace("{name}", player.getName());
                act.command = cmd;
                act.bungee = isBungeeScope(req.scope);
                pending.put(token, act);
                scheduleTimeout(act);
                sendConfirmButtons(player,
                        "&e[Agent] &fAI 请求执行指令\n&7将执行的指令: &b" + escapeAmp(cmd)
                                + "\n&7执行位置: " + (act.bungee ? "&d代理服 (BungeeCord)" : "&f你当前所在子服")
                                + "\n&7原因: " + escapeAmp(reason), token);
                break;
            }
            case "question": {
                act.text = req.text == null ? "" : req.text.trim();
                pending.put(token, act);
                scheduleTimeout(act);
                sendConfirmButtons(player,
                        "&e[Agent] &fAI 想问你\n&7问题内容: &b" + escapeAmp(act.text), token);
                break;
            }
            default:
                sendTo(player, "&7[DeepSeek] &e收到未知类型的Agent请求, 已忽略。");
        }
    }

    // ==================== 防重复数据请求循环 ====================

    /**
     * 判断本次请求是否为"与上一次已确认执行相同类别数据"的重复请求
     */
    private boolean isRepeatedDataRequest(String playerName, AgentRequest req) {
        if (req == null || !"data".equalsIgnoreCase(req.type == null ? "" : req.type)) {
            return false;
        }
        TaskStep st = lastDataRequest.get(playerName);
        if (st == null) {
            return false;
        }
        String cat = normalizeCategory(req.category);
        return cat != null && cat.equals(st.category);
    }

    /**
     * 模型连续重复请求同一类数据时的处理: 第一次注入纠正消息让模型继续下一步; 再次重复则停止
     * 在当轮异步任务内直接完成, 不再调度新一轮, 避免与 busy 标志产生竞争
     */
    private void handleRepeatedDataRequest(ProxiedPlayer player, AgentRequest req, int depth,
                                           ConfigManager cfg, String apiKey, String model, String apiUrl,
                                           String systemPrompt, ConversationSession session) {
        TaskStep st = lastDataRequest.get(player.getName());
        String cat = normalizeCategory(req.category);
        if (st != null && st.corrected) {
            sendTo(player, "&7[DeepSeek] &eAI 连续多次重复请求相同数据 (&b" + categoryChinese(cat)
                    + "&e), 已停止自动执行。请用 &b/ds send&e 明确告诉AI下一步要做什么。");
            return;
        }
        if (st != null) {
            st.corrected = true;
        }
        sendTo(player, "&7[DeepSeek] &e检测到AI重复请求数据, 正在提醒AI继续执行下一步…");
        String resultHint = (st == null || st.result.isEmpty()) ? "(结果已在上一步展示)" : st.result;
        String correction = "系统警告: 你刚刚已经请求过 " + categoryChinese(cat) + " 数据, 结果: " + resultHint
                + "\n不要再重复请求同类数据! 如果玩家的请求已完成, 直接向玩家总结结果并结束; 如果任务还未完成(如还要执行指令/传送), 立即输出下一步操作的标签继续执行。";
        session.addSystemMessage(correction, cfg.getMaxHistory());
        try {
            String reply2 = plugin.getDeepSeekAPI().chat(apiKey, model, apiUrl,
                    session.getHistory(), systemPrompt, session.getSummary());
            AgentParseResult parsed2 = parseAgentRequests(reply2);
            session.addAssistantMessage(parsed2.cleanText, cfg.getMaxHistory());
            sendTo(player, "&b[DeepSeek回复]&e" + escapeAmp(parsed2.cleanText));
            if (parsed2.requests.isEmpty() || !cfg.isAgentEnabled()) {
                return;
            }
            if (depth + 1 >= cfg.getAgentMaxRounds()) {
                sendTo(player, "&7[DeepSeek] &e已达最大连续操作轮数 (&b" + cfg.getAgentMaxRounds()
                        + "&e 轮), 已停止, 如需继续请再发消息。");
                return;
            }
            AgentRequest req2 = parsed2.requests.get(0);
            if (isRepeatedDataRequest(player.getName(), req2)) {
                sendTo(player, "&7[DeepSeek] &eAI 仍重复请求相同数据, 已停止自动执行。请用 &b/ds send&e 明确告诉AI下一步要做什么。");
                return;
            }
            handleAgentRequest(player, req2, depth + 1);
        } catch (Exception e) {
            plugin.getLogger().warning("AI 纠正轮请求异常: " + e.getMessage());
            sendTo(player, "&c纠正轮请求失败: " + e.getMessage());
        }
    }

    /**
     * 数据请求的二次确认文案: 明确展示"从哪个子服获取什么数据"
     */
    private String buildDataConfirmText(String cat, String server, String reason) {
        String loc = (server == null || server.isEmpty())
                ? "你当前所在子服"
                : "子服 &b" + server;
        return "&e[Agent] &fAI 请求从 " + loc + " 获取 &b" + categoryChinese(cat) + "&f 数据\n"
                + "&7获取内容: &f" + categoryDetail(cat) + "\n&7原因: " + escapeAmp(reason);
    }

    private String categoryDetail(String cat) {
        switch (cat == null ? "" : cat) {
            case "position":
                return "世界、X/Y/Z坐标、朝向、俯仰角";
            case "plugins":
                return "服务器已安装的插件名称列表";
            case "chunk":
                return "当前区块坐标、实体数量、生物群系";
            case "inventory":
                return "背包物品概览 (最多12格)";
            case "player":
                return "生命值、饥饿值、经验等级、游戏模式、飞行状态、所在世界";
            case "world":
                return "世界名、游戏时间、天气、难度、出生点坐标";
            case "online":
                return "在线人数与玩家名列表";
            case "status":
                return "内存占用、世界列表及各世界玩家数";
            case "entities":
                return "各世界实体数量与已加载区块数";
            default:
                return "未知";
        }
    }

    /**
     * 玩家点击 [同意] 按钮 (/ds confirm <token>)
     */
    public void confirm(ProxiedPlayer player, String token) {
        PendingAction act = pending.remove(token);
        if (act == null) {
            sendTo(player, consumedMessage(token));
            return;
        }
        markConsumed(token);
        if (act.timeoutTask != null) {
            act.timeoutTask.cancel();
        }
        if (!act.playerName.equalsIgnoreCase(player.getName())) {
            sendTo(player, "&c该确认请求不是发给你的");
            return;
        }
        switch (act.type) {
            case "question":
                askPlayerQuestion(player, act);
                break;
            case "data":
                requestData(player, act);
                break;
            case "command":
                if (act.bungee) {
                    executeBungeeCommand(player, act);
                } else {
                    requestCommand(player, act);
                }
                break;
        }
    }

    /**
     * 玩家点击 [拒绝] 按钮 (/ds deny <token>)
     */
    public void deny(ProxiedPlayer player, String token) {
        PendingAction act = pending.remove(token);
        if (act == null) {
            sendTo(player, consumedMessage(token));
            return;
        }
        markConsumed(token);
        if (act.timeoutTask != null) {
            act.timeoutTask.cancel();
        }
        if (!act.playerName.equalsIgnoreCase(player.getName())) {
            sendTo(player, "&c该确认请求不是发给你的");
            return;
        }
        sendTo(player, "&7[DeepSeek] &e已拒绝该操作。");
    }

    // ==================== 与子服通信 ====================

    private void requestData(ProxiedPlayer player, PendingAction act) {
        waitingResponse.put(act.token, act);
        scheduleResponseTimeout(act);
        // 记录本轮数据请求的类别, 供后续检测模型是否重复请求同一类数据
        lastDataRequest.put(player.getName(), new TaskStep(act.category));
        JsonObject o = new JsonObject();
        o.addProperty("t", "DATA");
        o.addProperty("player", act.playerName);
        o.addProperty("token", act.token);
        o.addProperty("category", act.category);
        o.addProperty("server", act.server == null ? "" : act.server);
        String target = (act.server == null || act.server.isEmpty())
                ? "你当前所在子服" : "子服 &b" + act.server;
        sendTo(player, "&7[DeepSeek] &a已确认, 正在从 " + target + " 获取 &b" + categoryChinese(act.category) + "&a 数据…");
        sendToServer(player, o, act.server);
        plugin.getLogger().info("已发送 DATA 请求 -> " + (act.server == null || act.server.isEmpty() ? "玩家当前子服" : act.server));
    }

    private void requestCommand(ProxiedPlayer player, PendingAction act) {
        waitingResponse.put(act.token, act);
        scheduleResponseTimeout(act);
        // 指令执行视为任务推进, 重置重复数据请求计数 (后续同类数据请求视为新的验证/查询)
        lastDataRequest.remove(player.getName());
        JsonObject o = new JsonObject();
        o.addProperty("t", "CMD");
        o.addProperty("player", act.playerName);
        o.addProperty("token", act.token);
        o.addProperty("command", act.command);
        o.addProperty("reason", "");
        sendTo(player, "&7[DeepSeek] &a已确认, 正在发送指令到子服执行…");
        sendToServer(player, o);
    }

    /**
     * 在代理服(BungeeCord)直接以控制台身份执行指令, 无需经过子服
     */
    private void executeBungeeCommand(ProxiedPlayer player, PendingAction act) {
        sendTo(player, "&7[DeepSeek] &a已确认, 正在代理服执行指令…");
        boolean ok;
        String err = "";
        try {
            ok = plugin.getProxy().getPluginManager()
                    .dispatchCommand(plugin.getProxy().getConsole(), act.command);
            if (!ok) {
                err = "指令执行失败 (可能命令不存在或无权限)";
            }
        } catch (Exception e) {
            ok = false;
            err = e.getMessage();
        }
        if (!ok) {
            sendTo(player, "&c[DeepSeek] 代理服指令执行失败: &e" + err);
        }
        // 执行结果注入下一轮, 让 AI 判断任务是否完成并继续
        String userMsg = ok
                ? "[系统·上一步执行结果] 指令已在代理服执行: " + act.command
                + "\n(若任务尚未完成, 你必须立即输出下一步操作的标签继续执行, 不要只回复文字)"
                : "[系统通知] 代理服指令执行失败: " + err;
        runRound(player, userMsg, act.depth + 1);
    }

    /**
     * 判断指令标签的 scope 字段是否为"代理服(BungeeCord)执行"
     */
    private boolean isBungeeScope(String scope) {
        if (scope == null) {
            return false;
        }
        String s = scope.trim().toLowerCase();
        return s.equals("bungee") || s.equals("bc") || s.equals("proxy")
                || s.equals("bungeecord") || s.contains("代理服");
    }

    /**
     * 管理员广播 Agent 开关到所有子服 (运行时同步)
     */
    public void broadcastAgentToggle(boolean enabled) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "AGENT_TOGGLE");
        o.addProperty("enabled", enabled);
        byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
        for (ServerInfo info : plugin.getProxy().getServers().values()) {
            info.sendData(CHANNEL, bytes);
        }
        plugin.getLogger().info("已广播 Agent 功能 " + (enabled ? "开启" : "关闭") + " 到所有子服");
    }

    /**
     * 查询玩家在子服上的数据授权状态
     */
    public void requestAuthSummary(ProxiedPlayer player) {
        String token = nextToken();
        PendingAction act = new PendingAction(token, player.getName(), "authquery", 0);
        pending.put(token, act);
        JsonObject o = new JsonObject();
        o.addProperty("t", "AUTH_QUERY");
        o.addProperty("player", player.getName());
        o.addProperty("token", token);
        sendToServer(player, o);
    }

    /**
     * 把消息发送到玩家当前所在的子服 (按子服名称精确路由, 避免发错服)
     */
    private void sendToServer(ProxiedPlayer player, JsonObject payload) {
        sendToServer(player, payload, null);
    }

    // ==================== AI 提问 -> 玩家回答 ====================

    /**
     * 玩家确认 AI 的提问后调用: 进入"等待玩家回答"状态, 并通知子服弹出书本 GUI
     * 玩家可通过 聊天框 / 书本编辑 / /ds <内容> 三种方式提交回答
     */
    private void askPlayerQuestion(ProxiedPlayer player, PendingAction act) {
        String question = act.text == null ? "" : act.text.trim();
        awaitingReply.put(player.getName(), new PendingQuestion(player.getName(), question, act.depth + 1));
        // 提问视为状态切换, 重置重复数据请求计数
        lastDataRequest.remove(player.getName());
        sendTo(player, "&7[DeepSeek] &aAI 向你提出一个问题, 请回答后继续对话…");
        sendTo(player, "&e问题: &f" + escapeAmp(question));
        sendTo(player, "&7请在聊天框输入你的回答, 或使用 &b/ds <内容>&7 回复, 或将书本编写完成后关闭即自动发送");
        // 通知子服: 给玩家一本预填问题的书并弹出书本 GUI (仅采集回复, 不需要授权)
        JsonObject openBook = new JsonObject();
        openBook.addProperty("t", "OPEN_BOOK");
        openBook.addProperty("player", player.getName());
        openBook.addProperty("question", question);
        sendToServer(player, openBook, null);
        // 5 分钟未回答则过期取消
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            PendingQuestion q = awaitingReply.remove(player.getName());
            if (q != null && q.question.equals(question)) {
                sendTo(player, "&c[DeepSeek] 回答超时 (5分钟), 该问题已取消。你可以再向AI提问。");
            }
        }, 5, TimeUnit.MINUTES);
    }

    /**
     * 玩家提交对 AI 问题的回答 (来源: 聊天框 / 书本编辑 / /ds 命令)
     */
    public void submitReply(ProxiedPlayer player, String answer) {
        PendingQuestion q = awaitingReply.remove(player.getName());
        if (q == null) {
            sendTo(player, "&c当前没有等待你回答的AI问题");
            return;
        }
        String a = answer == null ? "" : answer.trim();
        if (a.isEmpty()) {
            awaitingReply.put(player.getName(), q);
            sendTo(player, "&c回答不能为空, 请重新输入");
            return;
        }
        sendTo(player, "&7[DeepSeek] &f你的回答: &e" + escapeAmp(a));
        // 将玩家的真实回答作为用户消息喂回 AI, 继续对话 (AI 不会再自问自答)
        runRound(player, "你对AI问题的回答: " + a, q.depth);
    }

    /**
     * 该玩家是否正处于"等待回答AI问题"状态 (供 /ds 命令判断回复)
     */
    public boolean isAwaitingReply(String playerName) {
        return awaitingReply.containsKey(playerName);
    }

    /**
     * 玩家断开时清理等待状态
     */
    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        awaitingReply.remove(event.getPlayer().getName());
    }

    /**
     * 把消息发送到指定子服; serverName 为空则发送到玩家当前所在子服
     */
    private void sendToServer(ProxiedPlayer player, JsonObject payload, String serverName) {
        ServerInfo server;
        if (serverName != null && !serverName.isEmpty()) {
            server = plugin.getProxy().getServerInfo(serverName);
        } else {
            Server conn = player.getServer();
            server = conn == null ? null : conn.getInfo();
        }
        if (server == null) {
            sendTo(player, "&c无法确定目标子服");
            return;
        }
        server.sendData(CHANNEL, payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 接收子服返回的插件消息 (DATA_R / CMD_R / AUTH_SUMMARY)
     *
     * 注意: 子服在 BungeeCord 模式下, 发往代理的自定义通道消息会被 CraftBukkit 包装成
     * "BungeeCord" 通道 (格式: UTF(subChannel) + short(len) + payload), 因此这里同时兼容
     * 原始通道 "deepseek:main" 与 "BungeeCord" 包装两种形态; 目标玩家从待处理表中按 token 解析
     */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        String tag = event.getTag();
        byte[] data = event.getData();
        if (CHANNEL.equals(tag)) {
            // 原始自定义通道, 直接处理
        } else if ("BungeeCord".equals(tag)) {
            // BungeeCord 包装通道: 解包出子通道与载荷
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                String sub = in.readUTF();
                if (!CHANNEL.equals(sub)) {
                    return;
                }
                int len = in.readShort();
                byte[] payload = new byte[len];
                in.readFully(payload);
                data = payload;
            } catch (Exception e) {
                return;
            }
        } else {
            return;
        }
        // 拦截本插件通道的所有消息, 避免子服回包被转发到游戏客户端
        event.setCancelled(true);
        String json = new String(data, StandardCharsets.UTF_8);
        JsonObject o;
        try {
            o = new JsonParser().parse(json).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        String t = o.has("t") ? o.get("t").getAsString() : "";
        String token = o.has("token") ? o.get("token").getAsString() : "";
        plugin.getLogger().info("[通道] 代理端收到子服消息: " + t + " (tag=" + tag + ", token=" + token + ")");
        switch (t) {
            case "DATA_R":
            case "CMD_R": {
                PendingAction act = waitingResponse.remove(token);
                if (act == null) {
                    return;
                }
                ProxiedPlayer p = plugin.getProxy().getPlayer(act.playerName);
                if (p == null) {
                    return;
                }
                boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
                String userMsg;
                if (t.equals("DATA_R")) {
                    if (ok) {
                        JsonObject dataObj = o.has("data") ? o.getAsJsonObject("data") : new JsonObject();
                        String dataText = formatData(dataObj);
                        TaskStep st = lastDataRequest.get(act.playerName);
                        if (st != null) {
                            st.result = dataText;
                        }
                        // 明确标注这是"系统注入的快照"而非玩家说的话; 并强调不要重复请求同类数据, 应推进下一步
                        userMsg = "[系统·上次操作结果快照] " + dataText
                                + "\n(注意: 这是系统注入的数据查询结果, 不是玩家说的内容。这个查询已完成: 如果玩家的请求已完成, 直接向玩家展示结果; 如果任务还有后续步骤(如执行指令/传送), 立即输出下一步操作的标签继续执行。绝对不要重复请求刚刚已经获取过的同类数据!)";
                    } else {
                        String err = o.has("error") ? o.get("error").getAsString() : "未知原因";
                        sendTo(p, "&c[DeepSeek] 游戏数据获取失败: &e" + err);
                        userMsg = "[系统通知] 游戏数据获取失败: " + err;
                    }
                } else {
                    if (ok) {
                        userMsg = "[系统·上一步执行结果] 指令已执行: " + act.command
                                + "\n(若任务尚未完成, 你必须立即输出下一步操作的标签继续执行, 不要只回复文字)";
                    } else {
                        String err = o.has("error") ? o.get("error").getAsString() : "未知原因";
                        sendTo(p, "&c[DeepSeek] 指令执行失败: &e" + err);
                        userMsg = "[系统通知] 指令执行失败: " + err;
                    }
                }
                runRound(p, userMsg, act.depth + 1);
                break;
            }
            case "PLAYER_REPLY": {
                String playerName = o.has("player") ? o.get("player").getAsString() : "";
                String answer = o.has("answer") ? o.get("answer").getAsString() : "";
                ProxiedPlayer p = plugin.getProxy().getPlayer(playerName);
                if (p == null || answer == null || answer.trim().isEmpty()) {
                    return;
                }
                submitReply(p, answer);
                break;
            }
            case "AUTH_SUMMARY": {
                PendingAction act = pending.remove(token);
                if (act == null) {
                    return;
                }
                ProxiedPlayer p = plugin.getProxy().getPlayer(act.playerName);
                if (p == null) {
                    return;
                }
                boolean enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
                sendTo(p, "&6===== &b游戏数据授权 &6=====");
                sendTo(p, "&fAgent功能(子服): " + (enabled ? "&a已开启" : "&c已关闭"));
                if (o.has("categories")) {
                    JsonObject cats = o.getAsJsonObject("categories");
                    for (String k : cats.keySet()) {
                        boolean v = cats.get(k).getAsBoolean();
                        sendTo(p, "&f- &e" + categoryChinese(k) + "&f: " + (v ? "&a已授权" : "&c未授权"));
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    // ==================== 工具 ====================

    private String formatData(JsonObject data) {
        StringBuilder sb = new StringBuilder();
        for (String key : data.keySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append("=").append(data.get(key).getAsString());
        }
        return sb.toString();
    }

    private String normalizeCategory(String c) {
        if (c == null) {
            return null;
        }
        switch (c.trim().toLowerCase()) {
            case "位置":
            case "position":
                return "position";
            case "插件":
            case "插件列表":
            case "plugins":
                return "plugins";
            case "区块":
            case "区块信息":
            case "chunk":
                return "chunk";
            case "背包":
            case "背包概览":
            case "inventory":
                return "inventory";
            case "玩家状态":
            case "状态":
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

    public static String categoryChinese(String key) {
        if (key == null) {
            return "未知";
        }
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

    private String nextToken() {
        return Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + Long.toHexString(System.nanoTime());
    }

    private void markConsumed(String token) {
        consumed.put(token, System.currentTimeMillis());
    }

    /**
     * 二次点击已消费的 token 时给出更准确的提示 (区分"已处理"与"已过期")
     */
    private String consumedMessage(String token) {
        long now = System.currentTimeMillis();
        consumed.entrySet().removeIf(e -> now - e.getValue() > 120000L);
        Long t = consumed.get(token);
        if (t != null) {
            return "&c该请求已被处理, 请查看最新消息";
        }
        return "&c该确认请求不存在或已过期";
    }

    private void scheduleTimeout(PendingAction act) {
        final String token = act.token;
        final String playerName = act.playerName;
        act.timeoutTask = plugin.getProxy().getScheduler().schedule(plugin, () -> {
            PendingAction cur = pending.remove(token);
            if (cur != null && cur.playerName.equalsIgnoreCase(playerName)) {
                ProxiedPlayer p = plugin.getProxy().getPlayer(playerName);
                if (p != null) {
                    sendTo(p, "&7[DeepSeek] &eAgent操作已超时自动取消。");
                }
            }
        }, plugin.getConfigManager().getAgentConfirmTimeout(), TimeUnit.SECONDS);
    }

    private void scheduleResponseTimeout(PendingAction act) {
        final String token = act.token;
        final String playerName = act.playerName;
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            PendingAction cur = waitingResponse.remove(token);
            if (cur != null) {
                ProxiedPlayer p = plugin.getProxy().getPlayer(playerName);
                if (p != null) {
                    sendTo(p, "&7[DeepSeek] &e子服未响应, 本次操作已取消。请确认子服端已安装本插件。");
                }
            }
        }, plugin.getConfigManager().getAgentConfirmTimeout(), TimeUnit.SECONDS);
    }

    /**
     * 发送带 [同意]/[拒绝] 文字按钮的消息 (点击即确认)
     */
    private void sendConfirmButtons(ProxiedPlayer player, String text, String token) {
        TextComponent main = new TextComponent(TextComponent.fromLegacyText(color(text)));
        TextComponent yes = new TextComponent(color(" &a[同意]"));
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ds confirm " + token));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(color("&7点击同意并执行"))));
        TextComponent no = new TextComponent(color(" &c[拒绝]"));
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ds deny " + token));
        no.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(color("&7点击拒绝"))));
        player.sendMessage(main, yes, no);
    }

    private void sendTo(ProxiedPlayer player, String message) {
        String colored = color(message);
        for (String line : colored.split("\n")) {
            sendLines(player, line);
        }
    }

    private void sendLines(ProxiedPlayer player, String line) {
        while (line.length() > 256) {
            int idx = line.lastIndexOf(' ', 256);
            if (idx <= 0) {
                idx = 256;
            }
            player.sendMessage(line.substring(0, idx));
            line = line.substring(idx).trim();
        }
        if (!line.isEmpty()) {
            player.sendMessage(line);
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String escapeAmp(String text) {
        return text == null ? "" : text.replace("&", "&&");
    }

    // ==================== 待确认操作 ====================

    private static class PendingAction {
        final String token;
        final String playerName;
        final String type;   // data | command | question | authquery
        final int depth;
        String category;
        String server;   // data 请求的目标子服 (null = 玩家当前所在子服)
        String command;
        boolean bungee;  // 指令是否在代理服(BungeeCord)执行
        String text;
        ScheduledTask timeoutTask;

        PendingAction(String token, String playerName, String type, int depth) {
            this.token = token;
            this.playerName = playerName;
            this.type = type;
            this.depth = depth;
        }
    }
}
