package com.dataclean.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OkHttp 工具类
 * 用于调用大模型 API
 */
@Slf4j
public class OkHttpUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 日志/异常中外部响应的最大保留长度，避免把完整敏感响应写入日志 */
    private static final int MAX_DETAIL_CHARS = 200;

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() <= MAX_DETAIL_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_DETAIL_CHARS) + "...(truncated)";
    }

    /**
     * 同步调用AI接口
     */
    public static String callAiApi(String apiUrl, String apiKey, String model, double temperature,
                                    int maxTokens, int timeoutMs, String prompt) throws IOException {
        OkHttpClient client = createStreamingClient(timeoutMs);
        String jsonBody = buildRequestBody(model, temperature, maxTokens, prompt, false);

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                // 仅记录状态码与响应长度，避免把供应商错误信息（可能回显密钥）写入日志
                log.error("AI API 调用失败: HTTP {}, 响应长度={}", response.code(),
                        errorBody != null ? errorBody.length() : 0);
                throw new IOException("AI API 调用失败: HTTP " + response.code());
            }

            String responseBody = response.body().string();
            return extractContent(responseBody);
        }
    }

    /**
     * 流式调用AI接口(SSE) — 使用原始 OkHttp 实现，不依赖 okhttp-sse
     */
    public static void streamAiApi(String apiUrl, String apiKey, String model, double temperature,
                                    int maxTokens, int timeoutMs, String prompt, Consumer<String> onData,
                                    Consumer<Throwable> onError, Runnable onComplete) {
        OkHttpClient client = createStreamingClient(timeoutMs);
        String jsonBody = buildRequestBody(model, temperature, maxTokens, prompt, true);

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();

        // 异步执行，避免阻塞主线程
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("SSE 连接失败", e);
                onError.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                log.info("SSE 连接已打开");
                try (ResponseBody body = response.body()) {
                    if (body == null) {
                        onError.accept(new IOException("响应体为空"));
                        return;
                    }
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        // SSE 格式: "data: {...}"
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                onComplete.run();
                                return;
                            }
                            try {
                                String content = extractDeltaContent(data);
                                if (content != null && !content.isEmpty()) {
                                    onData.accept(content);
                                }
                            } catch (Exception e) {
                                // 不打印模型响应原文，避免日志留存敏感内容
                                log.error("解析SSE数据失败: length={}, error={}",
                                        data != null ? data.length() : 0,
                                        e.getClass().getSimpleName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("读取SSE流失败", e);
                    onError.accept(e);
                }
                log.info("SSE 连接已关闭");
                onComplete.run();
            }
        });
    }

    /**
     * 构建请求体
     */
    private static String buildRequestBody(String model, double temperature, int maxTokens,
                                           String prompt, boolean stream) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", stream);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        return root.toString();
    }

    /**
     * 提取响应内容
     */
    private static String extractContent(String responseBody) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).path("message");
            return message.path("content").asText("");
        }
        return "";
    }

    /**
     * 提取流式响应的增量内容
     */
    private static String extractDeltaContent(String data) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(data);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode delta = choices.get(0).path("delta");
            if (delta.has("content")) {
                return delta.path("content").asText("");
            }
        }
        return null;
    }

    /**
     * 创建WebFlux使用的OkHttpClient
     */
    public static OkHttpClient createStreamingClient(int timeoutMs) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }
}
