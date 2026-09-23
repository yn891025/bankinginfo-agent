package com.bankinginfo.guide.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Control-M 수행 이력(src/main/resources/batch/*.csv).
 * 같은 ODATE·작업이 여러 파일에 있으면 뒤에 읽은 파일(최신 스냅샷)의 값을 사용합니다.
 */
@Component
public class BatchRunHistory {

    /** 오래된 스냅샷부터 나열합니다. */
    private static final List<String> FILES = List.of("batch/batch-job-run-history.csv", "batch/batch-job-run-status.csv");
    private static final DateTimeFormatter RAW = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Map<String, Map<String, JobRun>> byOdate = new HashMap<>();
    private final Map<String, Integer> avgDurationSec = new HashMap<>();

    public BatchRunHistory() {
        for (var file : FILES) {
            for (var row : readCsv(file)) {
                var run = toRun(row);
                byOdate.computeIfAbsent(run.odate(), key -> new LinkedHashMap<>()).put(run.jobName(), run);
            }
        }
        byOdate.values().stream().flatMap(runs -> runs.values().stream())
                .filter(run -> "OK".equals(run.status()) && run.durationSec() != null)
                .collect(Collectors.groupingBy(JobRun::jobName, Collectors.averagingInt(JobRun::durationSec)))
                .forEach((job, avg) -> avgDurationSec.put(job, (int) Math.round(avg)));
    }

    public List<JobRun> runs(String odate) {
        return List.copyOf(byOdate.getOrDefault(odate, Map.of()).values());
    }

    public List<String> odates() {
        return List.copyOf(new TreeSet<>(byOdate.keySet()));
    }

    /** 정상 종료 이력의 평균 수행시간(초). 이력이 없으면 null. */
    public Integer avgDurationSec(String jobName) {
        return avgDurationSec.get(jobName);
    }

    private static JobRun toRun(Map<String, String> row) {
        var start = parse(row.get("REAL_ST_YS"));
        var end = parse(row.get("REAL_END_YS"));
        var state = row.get("CTM_STATE");
        return new JobRun(row.get("ODATE"), row.get("JOB_NM"), HtmlUtils.htmlUnescape(row.get("JOB_TITLE")).replace("&#37", "%"),
                state, status(state), Integer.parseInt(row.get("RUN_COUNT")), start, end,
                start != null && end != null ? (int) Duration.between(start, end).toSeconds() : null,
                List.of(row.get("CHRG_USER_NM").split(",")));
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
        var records = parseCsv(text.startsWith("﻿") ? text.substring(1) : text);
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
}
