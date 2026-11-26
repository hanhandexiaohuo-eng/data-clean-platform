package com.dataclean.service;

import com.dataclean.config.ScanProperties;
import com.dataclean.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 受控 HTTP 扫描服务实现（{@code upload.scan-provider=http}）。
 *
 * <p>协议：{@code POST <scan-url>}，{@code multipart/form-data} 携带 {@code file} 与
 * {@code fileType}；扫描通过返回 200 + {@code {"clean":true}}，检出风险返回
 * {@code {"clean":false}}。任何超时、非 200、响应不可解析或服务不可达都按不可用处理，
 * 默认拒绝上传（fail-closed）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只上传文件字节与类型，不携带数据库凭据、AI Key、应用 Cookie 或其它请求头；</li>
 *   <li>错误信息与日志不包含扫描地址、令牌、样本内容或内部路径；</li>
 *   <li>响应体有大小上限，避免异常服务返还超大内容。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "upload.scan-provider", havingValue = "http")
public class HttpFileScanService implements FileScanService {

    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final ScanProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void scan(MultipartFile file, String safeFileName, String fileType) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            log.error("扫描服务未配置地址，拒绝上传: type={}", fileType);
            throw unavailable();
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                .connectTimeout(Duration.ofMillis(Math.min(3000, Math.max(500, properties.getTimeoutMs()))))
                .readTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                .build();

        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", displayName(safeFileName), streamingBody(file))
                .addFormDataPart("fileType", fileType == null ? "" : fileType)
                .build();

        Request.Builder builder = new Request.Builder()
                .url(properties.getUrl())
                .post(body)
                .header("Accept", "application/json");
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.header("X-Scan-Token", properties.getToken());
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                log.warn("文件安全检测不可用: type={}, status={}", fileType, response.code());
                throw unavailable();
            }
            String payload = readLimited(response.body() == null ? null : response.body().byteStream());
            JsonNode node = objectMapper.readTree(payload);
            JsonNode cleanNode = node == null ? null : node.get("clean");
            if (cleanNode == null || !cleanNode.isBoolean()) {
                log.warn("文件安全检测响应不合法: type={}", fileType);
                throw unavailable();
            }
            if (!cleanNode.asBoolean()) {
                // 不记录检测原因与样本内容，避免恶意样本信息进入日志
                log.warn("文件安全检测未通过，已拒绝: type={}, size={}", fileType, file.getSize());
                throw new BusinessException(400, "文件未通过安全检测，已拒绝上传");
            }
            log.info("文件安全检测通过: type={}, size={}", fileType, file.getSize());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 只记录异常类型，不输出扫描地址、请求头或样本内容
            log.warn("文件安全检测调用失败: type={}, error={}", fileType, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private RequestBody streamingBody(MultipartFile file) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET_STREAM;
            }

            @Override
            public long contentLength() {
                return file.getSize();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream input = file.getInputStream()) {
                    sink.writeAll(Okio.source(input));
                }
            }
        };
    }

    private String readLimited(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > properties.getMaxResponseBytes()) {
                    throw new IOException("scan_response_too_large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String displayName(String safeFileName) {
        String name = safeFileName == null || safeFileName.isBlank() ? "upload" : safeFileName;
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "文件安全检测暂不可用，请稍后重试");
    }
}
