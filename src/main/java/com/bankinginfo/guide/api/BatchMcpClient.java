package com.bankinginfo.guide.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Control-M 수행 이력을 제공하는 MCP 서버(Streamable HTTP) 클라이언트.
 * 툴 결과는 기존 CSV와 같은 컬럼(CTM_STATE, ODATE, JOB_NM ...)의 CSV 텍스트 또는 JSON 행 배열이어야 합니다.
 */
@Component
public class BatchMcpClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final boolean enabled;
    private final String url;
    private final String tool;
    private final String odateArgument;
    private final String authToken;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;

    public BatchMcpClient(ObjectMapper mapper,
                          @Value("${batch.mcp.enabled:false}") boolean enabled,
                          @Value("${batch.mcp.url:}") String url,
                          @Value("${batch.mcp.tool:get_batch_run_status}") String tool,
                          @Value("${batch.mcp.odate-argument:odate}") String odateArgument,
                          @Value("${batch.mcp.auth-token:}") String authToken,
                          @Value("${batch.mcp.timeout:10s}") Duration timeout) {
        this.mapper = mapper;
        this.enabled = enabled && !url.isBlank();
        this.url = url;
        this.tool = tool;
        this.odateArgument = odateArgument;
        this.authToken = authToken;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean enabled() {
        return enabled;
    }

    /** 해당 ODATE의 수행 이력 행을 MCP 툴로 조회합니다. */
    public synchronized List<Map<String, String>> fetchRuns(String odate) {
        var arguments = Map.of(odateArgument, odate);
        try {
            if (sessionId == null) initialize();
            return toRows(callTool(arguments));
        } catch (SessionExpiredException expired) {
            // 서버가 세션을 만료시키면 한 번만 다시 초기화합니다.
            sessionId = null;
            initialize();
            return toRows(callTool(arguments));
        }
    }

    private void initialize() {
        sessionId = null;
        request("initialize", Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "bankinginfo-batch-agent", "version", "1.0")));
        notify("notifications/initialized");
    }

    private JsonNode callTool(Map<String, String> arguments) {
        var result = request("tools/call", Map.of("name", tool, "arguments", arguments));
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("MCP 툴 오류: " + text(result));
        }
        return result;
    }

    private JsonNode request(String method, Object params) {
        var id = ids.incrementAndGet();
        var response = post(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        if (response.statusCode() == 404 && sessionId != null) throw new SessionExpiredException();
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("MCP 서버 응답 오류(HTTP " + response.statusCode() + ")");
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
        var message = findMessage(response, id);
        if (message.has("error")) {
            throw new IllegalStateException("MCP 오류: " + message.path("error").path("message").asText());
        }
        return message.path("result");
    }

    private void notify(String method) {
        post(Map.of("jsonrpc", "2.0", "method", method));
    }

    private HttpResponse<String> post(Map<String, Object> body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            if (!authToken.isBlank()) builder.header("Authorization", "Bearer " + authToken);
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("MCP 서버에 연결할 수 없습니다: " + url, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP 호출이 중단되었습니다.", exception);
        }
    }

    /** 응답이 JSON이면 그대로, SSE이면 data 이벤트 중 요청 id와 일치하는 메시지를 찾습니다. */
    private JsonNode findMessage(HttpResponse<String> response, long id) {
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (!contentType.contains("text/event-stream")) return mapper.readTree(response.body());
            var data = new StringBuilder();
            for (var line : (response.body() + "\n\n").split("\\r?\\n", -1)) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty() && data.length() > 0) {
                    var message = mapper.readTree(data.toString());
                    data.setLength(0);
                    if (message.path("id").asLong(-1) == id) return message;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("MCP 응답을 해석할 수 없습니다.", exception);
        }
        throw new IllegalStateException("MCP 응답에 요청 결과가 없습니다.");
    }

    /** structuredContent 또는 text content를 수행 이력 행으로 변환합니다. */
    private List<Map<String, String>> toRows(JsonNode result) {
        if (result.has("structuredContent")) {
            var rows = jsonRows(result.path("structuredContent"));
            if (rows != null) return rows;
        }
        var text = text(result).trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                var rows = jsonRows(mapper.readTree(text));
                if (rows != null) return rows;
            } catch (IOException ignored) {
                // JSON이 아니면 CSV로 처리합니다.
            }
        }
        return BatchRunHistory.csvRows(text);
    }

    /** 행 배열 또는 행 배열을 가진 객체(예: {"rows": [...]})를 지원합니다. */
    private static List<Map<String, String>> jsonRows(JsonNode node) {
        if (node.isObject()) {
            for (var field : node) {
                if (field.isArray()) return jsonRows(field);
            }
            return null;
        }
        if (!node.isArray()) return null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (var item : node) {
            Map<String, String> row = new LinkedHashMap<>();
            item.fields().forEachRemaining(entry -> row.put(entry.getKey(),
                    entry.getValue().isNull() ? "" : entry.getValue().asText()));
            rows.add(row);
        }
        return rows;
    }

    private static String text(JsonNode result) {
        var text = new StringBuilder();
        result.path("content").forEach(item -> {
            if ("text".equals(item.path("type").asText())) text.append(item.path("text").asText());
        });
        return text.toString();
    }

    private static class SessionExpiredException extends RuntimeException {}
}
