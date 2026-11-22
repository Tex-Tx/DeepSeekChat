package com.deepseekchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DeepSeek Chat Completions API 客户端
 * 接口: https://api.deepseek.com/chat/completions
 * 模型: deepseek-v4-flash
 */
public class DeepSeekAPI {

    private final ConfigManager config;

    public DeepSeekAPI(ConfigManager config) {
        this.config = config;
    }

    /**
     * 发送对话请求, 返回 AI 回复内容
     *
     * @param apiKey       DeepSeek API Key
     * @param model        模型名称 (公共API/个人API 可各自指定)
     * @param apiUrl       API 接口地址 (公共API/个人API 可各自指定)
     * @param history      会话历史 (不含 system 消息)
     * @param systemPrompt 系统提示词
     * @param summary      被压缩掉的早期对话摘要 (可为 null)
     */
    public String chat(String apiKey, String model, String apiUrl,
                       List<ConversationSession.Message> history, String systemPrompt, String summary)
            throws IOException, DeepSeekAPIException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);
        }
        if (summary != null && !summary.isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", "以下是之前的对话摘要，请结合它理解上下文：\n" + summary);
            messages.add(sys);
        }
        for (ConversationSession.Message msg : history) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role);
            m.addProperty("content", msg.content);
            messages.add(m);
        }
        body.add("messages", messages);

        // 关闭深度思考 (V4 默认开启思考模式, 需显式禁用)
        if (config.isThinkingDisabled()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }

        int timeout = config.getTimeoutSeconds() * 1000;
        URL url = new URL(apiUrl + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setDoOutput(true);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = code >= 400
                    ? readStream(conn.getErrorStream())
                    : readStream(conn.getInputStream());

            if (code != 200) {
                String errMsg = extractErrorMessage(responseBody);
                throw new DeepSeekAPIException(code, "HTTP " + code + (errMsg.isEmpty() ? "" : " - " + errMsg));
            }

            JsonObject resp = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonArray choices = resp.has("choices") ? resp.getAsJsonArray("choices") : null;
            if (choices == null || choices.size() == 0) {
                throw new DeepSeekAPIException("API 返回异常: 缺少 choices 字段");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new DeepSeekAPIException("API 返回异常: 缺少回复内容");
            }
            String content = message.get("content").getAsString();
            // 截断超长回复; 截断时保留末尾的 [AGENT_REQUEST] 标签, 避免 Agent 功能失效
            int maxLen = config.getMaxReplyLength();
            if (content.length() > maxLen) {
                String lastTag = findLastTag(content);
                String truncated = content.substring(0, maxLen);
                if (!lastTag.isEmpty() && !truncated.endsWith(lastTag)) {
                    truncated = truncated + "\n" + lastTag;
                }
                content = truncated + "...(已截断)";
            }
            return content;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 从回复末尾往前找最后一个完整的 [AGENT_REQUEST] 标签
     * 标签 JSON 内可能出现 {name} 占位符, 必须用括号配对定位完整标签, 不能用正则非贪婪匹配
     */
    private String findLastTag(String text) {
        int idx = text.length();
        while (true) {
            int start = text.lastIndexOf("[AGENT_REQUEST]", idx - 1);
            if (start < 0) {
                return "";
            }
            int open = text.indexOf('{', start);
            if (open < 0) {
                return "";
            }
            int end = AgentManager.findTagEnd(text, open);
            if (end >= 0) {
                return text.substring(start, end + 1);
            }
            idx = start; // 该标签括号不完整, 继续往前找
        }
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            JsonObject obj = new JsonParser().parse(body).getAsJsonObject();
            if (obj.has("error")) {
                JsonObject err = obj.getAsJsonObject("error");
                if (err.has("message") && !err.get("message").isJsonNull()) {
                    return err.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
