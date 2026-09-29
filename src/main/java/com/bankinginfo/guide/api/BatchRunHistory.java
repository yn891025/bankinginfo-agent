package com.bankinginfo.guide.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Control-M 수행 이력(src/main/resources/batch/*.csv).
 * 같은 ODATE·작업이 여러 파일에 있으면 뒤에 읽은 파일(최신 스냅샷)의 값을 사용합니다.
 * MCP 연동이 켜져 있으면 조회한 ODATE의 이력을 MCP 서버 결과로 덮어씁니다(cache-ttl 동안 재사용).
 */
@Component
public class BatchRunHistory {

    private static final Logger log = LoggerFactory.getLogger(BatchRunHistory.class);

    /** 오래된 스냅샷부터 나열합니다. */
    private static final List<String> FILES = List.of("batch/batch-job-run-history.csv", "batch/batch-job-run-status.csv");
    private static final DateTimeFormatter RAW = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, Map<String, JobRun>> byOdate = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> avgDurationSec = Map.of();
    private final Map<String, Instant> syncedAt = new ConcurrentHashMap<>();
    private final BatchMcpClient mcp;
    private final Duration cacheTtl;
    private volatile String lastSyncAt;
    private volatile String lastError;

    public BatchRunHistory(BatchMcpClient mcp, @Value("${batch.mcp.cache-ttl:15s}") Duration cacheTtl) {
        this.mcp = mcp;
        this.cacheTtl = cacheTtl;
        for (var file : FILES) {
            for (var row : readCsv(file)) {
                var run = toRun(row);
                byOdate.computeIfAbsent(run.odate(), key -> new LinkedHashMap<>()).put(run.jobName(), run);
            }
        }
        recomputeAverages();
    }

    public List<JobRun> runs(String odate) {
        sync(odate);
        return List.copyOf(byOdate.getOrDefault(odate, Map.of()).values());
    }

    public List<String> odates() {
        return List.copyOf(new TreeSet<>(byOdate.keySet()));
    }

    /** 정상 종료 이력의 평균 수행시간(초). 이력이 없으면 null. */
    public Integer avgDurationSec(String jobName) {
        return avgDurationSec.get(jobName);
    }

    /** 현재 데이터 출처. mode: MCP(연동 중) 또는 CSV(파일), error: 마지막 MCP 호출 실패 사유. */
    public DataSource source() {
        return new DataSource(mcp.enabled() ? "MCP" : "CSV", lastSyncAt, lastError);
    }

    /** MCP 연동 시 cache-ttl이 지난 ODATE만 다시 조회합니다. 실패하면 기존 데이터를 유지합니다. */
    private void sync(String odate) {
        if (!mcp.enabled()) return;
        var last = syncedAt.get(odate);
        if (last != null && last.plus(cacheTtl).isAfter(Instant.now())) return;
        try {
            Map<String, JobRun> runs = new LinkedHashMap<>();
            for (var row : mcp.fetchRuns(odate)) {
                var run = toRun(row);
                if (odate.equals(run.odate())) runs.put(run.jobName(), run);
            }
            byOdate.put(odate, runs);
            recomputeAverages();
            lastSyncAt = LocalDateTime.now().format(DATE_TIME);
            lastError = null;
        } catch (RuntimeException exception) {
            log.warn("MCP 수행 이력 조회 실패(ODATE {}): {}", odate, exception.getMessage());
            lastError = exception.getMessage();
        }
        syncedAt.put(odate, Instant.now());
    }

    private void recomputeAverages() {
        avgDurationSec = byOdate.values().stream().flatMap(runs -> runs.values().stream())
                .filter(run -> "OK".equals(run.status()) && run.durationSec() != null)
                .collect(Collectors.groupingBy(JobRun::jobName, Collectors.averagingInt(JobRun::durationSec)))
                .entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> (int) Math.round(entry.getValue())));
    }

    private static JobRun toRun(Map<String, String> row) {
        var start = parse(row.get("REAL_ST_YS"));
        var end = parse(row.get("REAL_END_YS"));
        var state = row.getOrDefault("CTM_STATE", "");
        var runCount = row.getOrDefault("RUN_COUNT", "");
        return new JobRun(row.get("ODATE"), row.get("JOB_NM"),
                HtmlUtils.htmlUnescape(row.getOrDefault("JOB_TITLE", "")).replace("&#37", "%"),
                state, status(state), runCount.isBlank() ? 0 : Integer.parseInt(runCount.trim()), start, end,
                start != null && end != null ? (int) Duration.between(start, end).toSeconds() : null,
                List.of(row.getOrDefault("CHRG_USER_NM", "-").split(",")));
    }

    /** Control-M 상태를 대시보드 상태(OK/RUNNING/ERROR/WAIT)로 변환합니다. */
    static String status(String ctmState) {
        return switch (ctmState) {
            case "Ended OK" -> "OK";
            case "Ended Not OK" -> "ERROR";
            case "Executing" -> "RUNNING";
            default -> "WAIT";
        };
    }

    private static LocalDateTime parse(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value.trim(), RAW);
    }

    private static List<Map<String, String>> readCsv(String path) {
        String text;
        try (var in = new ClassPathResource(path).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("수행 이력 파일을 읽을 수 없습니다: " + path, exception);
        }
        return csvRows(text);
    }

    /** 헤더 행을 키로 하는 CSV 행 목록. MCP 툴 결과(CSV 텍스트)도 이 형식으로 해석합니다. */
    static List<Map<String, String>> csvRows(String text) {
        var records = parseCsv(text.startsWith("\uFEFF") ? text.substring(1) : text);
        if (records.isEmpty()) return List.of();
        var header = records.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (var record : records.subList(1, records.size())) {
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < record.size() ? record.get(i) : "");
            rows.add(row);
        }
        return rows;
    }

    /** 따옴표 안의 쉼표·줄바꿈을 지원하는 RFC 4180 CSV 파서. */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        var field = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                record.add(field.toString());
                field.setLength(0);
                if (!(record.size() == 1 && record.get(0).isEmpty())) records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    public record JobRun(String odate, String jobName, String title, String ctmState, String status, int runCount,
                         LocalDateTime start, LocalDateTime end, Integer durationSec, List<String> users) {}

    public record DataSource(String mode, String syncedAt, String error) {}
}
