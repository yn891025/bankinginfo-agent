package com.bankinginfo.guide.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 배치 에이전트 Mock API.
 * 당일 ODATE는 서버 기동 시점부터 시뮬레이션 시계가 흐르므로 새로고침할 때마다 배치 진행 상황이 바뀝니다.
 */
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private static final DateTimeFormatter ODATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_START_MINUTE = 10;
    private static final double INITIAL_CLOCK_MINUTE = 50;
    private static final double SIMULATION_SPEED = 10;
    private static final String FAILING_JOB = "ACC_TXN_CHK_03";
    private static final String EXTRACT_JOB = "ACC_TXN_EXT_01";

    private static final List<JobDef> JOBS = List.of(
            new JobDef("COM_DAY_OPEN_01", "일마감 배치 개시", "공통", "홍길동", "김철수", "박민수", List.of(), 2),
            new JobDef("ACC_TXN_EXT_01", "계좌 거래내역 추출", "계좌", "홍길동", "이영희", "박민수", List.of("COM_DAY_OPEN_01"), 12),
            new JobDef("ACC_BAL_EXT_02", "계좌 잔액 추출", "계좌", "홍길동", "김철수", "박민수", List.of("COM_DAY_OPEN_01"), 8),
            new JobDef("DEP_INT_CAL_01", "수신 이자 계산", "수신", "김철수", "홍길동", "최민호", List.of("ACC_BAL_EXT_02"), 25),
            new JobDef("LON_INT_CAL_01", "여신 이자 계산", "여신", "이영희", "김철수", "최민호", List.of("ACC_BAL_EXT_02"), 30),
            new JobDef(FAILING_JOB, "거래내역 정합성 검증", "계좌", "홍길동", "이영희", "박민수", List.of("ACC_TXN_EXT_01"), 6),
            new JobDef("DEP_INT_PST_02", "수신 이자 원장 반영", "수신", "김철수", "이영희", "최민호", List.of("DEP_INT_CAL_01"), 10),
            new JobDef("LON_OVD_UPD_02", "연체 정보 갱신", "여신", "이영희", "홍길동", "최민호", List.of("LON_INT_CAL_01"), 15),
            new JobDef("ACC_STM_GEN_04", "전자 거래명세 생성", "계좌", "홍길동", "김철수", "박민수", List.of(FAILING_JOB), 9),
            new JobDef("DWH_ACC_LOD_01", "정보계 계좌 적재", "정보계", "홍길동", "이영희", "박민수", List.of("ACC_TXN_EXT_01"), 18),
            new JobDef("DWH_LON_LOD_02", "정보계 여신 적재", "정보계", "이영희", "김철수", "최민호", List.of("LON_OVD_UPD_02"), 14),
            new JobDef("GL_JNL_SUM_01", "총계정원장 분개 집계", "원장", "박민수", "홍길동", "최민호", List.of(FAILING_JOB, "DEP_INT_PST_02"), 11),
            new JobDef("NTF_SMS_SND_01", "고객 알림 발송", "채널", "김철수", "홍길동", "박민수", List.of("ACC_STM_GEN_04"), 7),
            new JobDef("GL_DAY_CLS_02", "일마감 원장 확정", "원장", "박민수", "이영희", "최민호", List.of("GL_JNL_SUM_01", "LON_OVD_UPD_02"), 8),
            new JobDef("RPT_DAY_GEN_01", "일일 경영 보고서 생성", "정보계", "김철수", "박민수", "최민호", List.of("GL_DAY_CLS_02", "DWH_ACC_LOD_01", "DWH_LON_LOD_02"), 16),
            new JobDef("EXT_BOK_SND_01", "한국은행 보고 전송", "대외", "박민수", "김철수", "최민호", List.of("RPT_DAY_GEN_01"), 5));

    private final Instant startedAt = Instant.now();

    @GetMapping("/owners")
    public List<String> owners() {
        Set<String> owners = new LinkedHashSet<>();
        JOBS.forEach(job -> {
            owners.add(job.owner());
            owners.add(job.subOwner());
            owners.add(job.manager());
        });
        return List.copyOf(owners);
    }

    @GetMapping("/jobs")
    public BatchStatus jobs(@RequestParam(required = false) String odate) {
        var date = parseOdate(odate);
        var jobs = simulate(date);
        Map<String, Long> summary = new LinkedHashMap<>();
        for (var status : List.of("OK", "RUNNING", "ERROR", "WAIT")) {
            summary.put(status, jobs.stream().filter(job -> job.status().equals(status)).count());
        }
        return new BatchStatus(date.format(ODATE), LocalDateTime.now().format(DATE_TIME), summary, jobs);
    }

    @GetMapping("/flow")
    public FlowResponse flow(@RequestParam(required = false) String odate,
                             @RequestParam(required = false) List<String> persons,
                             @RequestParam(required = false) List<String> roles) {
        var date = parseOdate(odate);
        var jobs = simulate(date);
        var roleSet = roles == null || roles.isEmpty() ? Set.of("owner", "subOwner", "manager") : Set.copyOf(roles);
        var personSet = persons == null ? Set.<String>of() : Set.copyOf(persons);

        Set<String> matched = new LinkedHashSet<>();
        for (var job : jobs) {
            if (personSet.isEmpty()
                    || roleSet.contains("owner") && personSet.contains(job.owner())
                    || roleSet.contains("subOwner") && personSet.contains(job.subOwner())
                    || roleSet.contains("manager") && personSet.contains(job.manager())) {
                matched.add(job.jobName());
            }
        }

        // 조회 대상 작업과 직접 연결된 타 담당자 작업도 흐름 파악을 위해 함께 표시합니다.
        Set<String> included = new LinkedHashSet<>(matched);
        for (var job : jobs) {
            if (matched.contains(job.jobName())) included.addAll(job.predecessors());
            if (job.predecessors().stream().anyMatch(matched::contains)) included.add(job.jobName());
        }

        List<FlowNode> nodes = new ArrayList<>();
        List<FlowEdge> edges = new ArrayList<>();
        for (var job : jobs) {
            if (!included.contains(job.jobName())) continue;
            nodes.add(new FlowNode(job, !matched.contains(job.jobName())));
            job.predecessors().stream().filter(included::contains)
                    .forEach(pred -> edges.add(new FlowEdge(pred, job.jobName())));
        }
        return new FlowResponse(date.format(ODATE), nodes, edges);
    }

    @GetMapping("/logs")
    public LogResponse logs(@RequestParam(required = false) String odate, @RequestParam String jobName) {
        var date = parseOdate(odate);
        var jobs = simulate(date);
        var job = jobs.stream().filter(item -> item.jobName().equals(jobName)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다: " + jobName));
        Map<String, BatchJob> byName = new HashMap<>();
        jobs.forEach(item -> byName.put(item.jobName(), item));
        return new LogResponse(job, buildLogs(date, job, byName), analyze(date, job, byName));
    }

    private LocalDate parseOdate(String odate) {
        if (odate == null || odate.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(odate.replace("-", ""), ODATE);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ODATE 형식은 yyyyMMdd 입니다.");
        }
    }

    /** 시뮬레이션 시계(ODATE 00:00 기준 경과 분). 과거 일자는 모두 종료, 미래 일자는 시작 전입니다. */
    private double clockMinute(LocalDate date) {
        var today = LocalDate.now();
        if (date.isBefore(today)) return Double.MAX_VALUE;
        if (date.isAfter(today)) return -1;
        var elapsedSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
        return INITIAL_CLOCK_MINUTE + elapsedSeconds * SIMULATION_SPEED / 60.0;
    }

    private List<BatchJob> simulate(LocalDate date) {
        var clock = clockMinute(date);
        var failToday = date.equals(LocalDate.now());
        var base = date.atStartOfDay();
        Map<String, BatchJob> done = new HashMap<>();
        Map<String, Double> endMinutes = new HashMap<>();
        List<BatchJob> result = new ArrayList<>();

        for (var def : JOBS) {
            var avgSec = def.avgMinutes() * 60;
            var ready = def.predecessors().stream().allMatch(pred -> "OK".equals(done.get(pred).status()));
            if (!ready) {
                result.add(toJob(def, "WAIT", null, null, null, avgSec));
                done.put(def.name(), result.get(result.size() - 1));
                continue;
            }
            double start = def.predecessors().stream().mapToDouble(endMinutes::get).max().orElse(BATCH_START_MINUTE) + 1;
            double duration = def.avgMinutes() * (0.8 + hash(date, def.name()) % 45 / 100.0);
            var fails = failToday && def.name().equals(FAILING_JOB);
            if (fails) duration = duration * 0.6;
            double end = start + duration;
            BatchJob job;
            if (clock < start) {
                job = toJob(def, "WAIT", null, null, null, avgSec);
            } else if (clock < end) {
                job = toJob(def, "RUNNING", base.plusSeconds((long) (start * 60)), null,
                        (int) ((clock - start) * 60), avgSec);
            } else {
                job = toJob(def, fails ? "ERROR" : "OK", base.plusSeconds((long) (start * 60)),
                        base.plusSeconds((long) (end * 60)), (int) (duration * 60), avgSec);
                endMinutes.put(def.name(), end);
            }
            done.put(def.name(), job);
            result.add(job);
        }
        return result;
    }

    private BatchJob toJob(JobDef def, String status, LocalDateTime start, LocalDateTime end, Integer durationSec, int avgSec) {
        return new BatchJob(def.name(), def.description(), def.group(), def.owner(), def.subOwner(), def.manager(),
                def.predecessors(), status, start == null ? null : start.format(DATE_TIME),
                end == null ? null : end.format(DATE_TIME), durationSec, avgSec);
    }

    private List<LogLine> buildLogs(LocalDate date, BatchJob job, Map<String, BatchJob> jobs) {
        List<LogLine> lines = new ArrayList<>();
        var odate = date.format(ODATE);
        if (job.startTime() == null) {
            var at = date.atTime(0, BATCH_START_MINUTE).format(DATE_TIME);
            lines.add(new LogLine(at, "INFO", "작업 등록 확인 - JOB=%s ODATE=%s".formatted(job.jobName(), odate)));
            for (var pred : job.predecessors()) {
                var predJob = jobs.get(pred);
                if ("ERROR".equals(predJob.status())) {
                    lines.add(new LogLine(predJob.endTime(), "WARN", "선행 작업 %s 오류로 실행이 보류되었습니다.".formatted(pred)));
                } else if (!"OK".equals(predJob.status())) {
                    lines.add(new LogLine(at, "INFO", "선행 작업 %s 완료 대기 중 (상태: %s)".formatted(pred, predJob.status())));
                }
            }
            if (job.predecessors().isEmpty()) lines.add(new LogLine(at, "INFO", "ODATE 도래 전으로 실행 대기 중입니다."));
            return lines;
        }

        var start = LocalDateTime.parse(job.startTime(), DATE_TIME);
        var total = 1_000_000 + hash(date, job.jobName()) * 1_283;
        lines.add(new LogLine(fmt(start), "INFO", "작업 시작 - JOB=%s ODATE=%s GROUP=%s".formatted(job.jobName(), odate, job.group())));
        if (!job.predecessors().isEmpty()) {
            lines.add(new LogLine(fmt(start.plusSeconds(1)), "INFO", "선행 작업 확인 완료: " + String.join(", ", job.predecessors())));
        }
        lines.add(new LogLine(fmt(start.plusSeconds(3)), "INFO", "DB 커넥션 획득 (pool=BATCH_POOL, active=4/20)"));
        lines.add(new LogLine(fmt(start.plusSeconds(5)), "INFO", "처리 대상 건수: %,d건".formatted(total)));

        if ("ERROR".equals(job.status())) {
            var end = LocalDateTime.parse(job.endTime(), DATE_TIME);
            lines.add(new LogLine(fmt(start.plusSeconds(job.durationSec() / 3)), "INFO", "원장 거래내역 건수 집계 완료: %,d건".formatted(total + 4)));
            lines.add(new LogLine(fmt(start.plusSeconds(job.durationSec() / 2)), "INFO", "추출 파일 건수 집계 완료: %,d건".formatted(total)));
            lines.add(new LogLine(fmt(end.minusSeconds(20)), "WARN", "건수 불일치 감지 - 원장 %,d건 / 추출 %,d건 (차이 4건)".formatted(total + 4, total)));
            var extractStart = LocalDateTime.parse(jobs.get(EXTRACT_JOB).startTime(), DATE_TIME);
            lines.add(new LogLine(fmt(end.minusSeconds(18)), "WARN", "불일치 거래 발생 시각: %s ~ %s (%s 추출 시작 이후)"
                    .formatted(extractStart.toLocalTime().plusSeconds(7), extractStart.toLocalTime().plusSeconds(18), EXTRACT_JOB)));
            lines.add(new LogLine(fmt(end.minusSeconds(2)), "ERROR", "com.bank.batch.ValidationException: 정합성 검증 실패 (CODE=BV-2031)"));
            lines.add(new LogLine(fmt(end.minusSeconds(2)), "ERROR", "    at com.bank.batch.acc.TxnConsistencyChecker.verify(TxnConsistencyChecker.java:142)"));
            lines.add(new LogLine(fmt(end), "ERROR", "작업 비정상 종료 (RC=8) - 후행 작업 %s 실행 보류"
                    .formatted(String.join(", ", successors(job.jobName())))));
            return lines;
        }

        var elapsed = job.durationSec();
        var finished = "OK".equals(job.status());
        for (int percent = 25; percent <= 75; percent += 25) {
            var at = start.plusSeconds((long) job.avgDurationSec() * percent / 100);
            if (!finished && at.isAfter(start.plusSeconds(elapsed))) break;
            lines.add(new LogLine(fmt(at), "INFO", "처리 진행률 %d%% (%,d / %,d건)".formatted(percent, total * percent / 100, total)));
        }
        if (finished && elapsed > job.avgDurationSec() * 1.15) {
            var ratio = Math.round((elapsed / (double) job.avgDurationSec() - 1) * 100);
            lines.add(new LogLine(fmt(start.plusSeconds(elapsed * 9L / 10)), "WARN",
                    "수행시간이 평균 대비 %d%% 증가했습니다. (평균 %s)".formatted(ratio, minutes(job.avgDurationSec()))));
        }
        if (finished) {
            var end = LocalDateTime.parse(job.endTime(), DATE_TIME);
            lines.add(new LogLine(fmt(end.minusSeconds(1)), "INFO", "COMMIT 완료 (%,d건)".formatted(total)));
            lines.add(new LogLine(fmt(end), "INFO", "작업 정상 종료 (RC=0) - 수행시간 " + minutes(elapsed)));
        } else {
            lines.add(new LogLine(fmt(start.plusSeconds(elapsed)), "INFO", "처리 중입니다... (경과 %s)".formatted(minutes(elapsed))));
        }
        return lines;
    }

    private LogAnalysis analyze(LocalDate date, BatchJob job, Map<String, BatchJob> jobs) {
        switch (job.status()) {
            case "ERROR":
                return new LogAnalysis("HIGH",
                        "거래내역 정합성 검증 단계에서 원장과 추출 파일 건수가 4건 차이 나 작업이 RC=8로 종료되었습니다.",
                        "선행 작업 %s이 %s에 추출을 시작한 이후 발생한 지연 거래 4건이 원장에만 반영되어, 추출 기준 시점이 어긋난 것으로 판단됩니다."
                                .formatted(EXTRACT_JOB, LocalDateTime.parse(jobs.get(EXTRACT_JOB).startTime(), DATE_TIME).toLocalTime()),
                        List.of(EXTRACT_JOB + " 추출 조건에 기준시각(ODATE 23:59:59) 컷오프가 적용되어 있는지 확인",
                                "불일치 거래 4건의 거래번호를 조회하여 지연 입금 여부 확인",
                                EXTRACT_JOB + " 재수행 후 " + job.jobName() + " 재실행 (Rerun)",
                                "후행 작업 " + String.join(", ", successors(job.jobName())) + " 정상 수행 여부 확인"),
                        List.of(new BatchCase("WIKI-BAT-2025-032", "거래내역 추출 컷오프 누락 조치", "91%"),
                                new BatchCase("WIKI-BAT-2024-118", "정합성 검증 건수 불일치 재수행 절차", "83%")));
            case "RUNNING":
                var start = LocalDateTime.parse(job.startTime(), DATE_TIME);
                var expected = start.plusSeconds(job.avgDurationSec());
                return new LogAnalysis("NONE",
                        "작업이 수행 중이며 현재까지 오류나 경고는 없습니다.",
                        "평균 수행시간(%s) 기준 예상 종료 시각은 %s 입니다.".formatted(minutes(job.avgDurationSec()), expected.toLocalTime()),
                        List.of("예상 종료 시각 이후에도 종료되지 않으면 DB 세션 및 Lock 상태를 확인하세요."),
                        List.of());
            case "WAIT":
                var blocker = job.predecessors().stream().map(jobs::get)
                        .filter(pred -> "ERROR".equals(pred.status())).findFirst();
                if (blocker.isPresent()) {
                    return new LogAnalysis("MEDIUM",
                            "선행 작업 오류로 실행이 보류된 상태입니다.",
                            "선행 작업 %s 가 오류(RC=8)로 종료되어 후행 조건이 충족되지 않았습니다.".formatted(blocker.get().jobName()),
                            List.of(blocker.get().jobName() + " 로그 분석 결과에 따라 원인 조치",
                                    "선행 작업 재수행 완료 후 본 작업이 자동 실행되는지 확인"),
                            List.of());
                }
                return new LogAnalysis("NONE", "선행 작업 완료를 기다리는 정상 대기 상태입니다.",
                        date.isAfter(LocalDate.now()) ? "ODATE가 아직 도래하지 않았습니다." : "선행 작업이 수행 중이거나 대기 중입니다.",
                        List.of("선행 작업 진행 상황은 Flow Chart에서 확인할 수 있습니다."), List.of());
            default:
                if (job.durationSec() > job.avgDurationSec() * 1.15) {
                    return new LogAnalysis("LOW",
                            "작업은 정상 종료되었으나 수행시간이 평균보다 길었습니다.",
                            "처리 대상 건수 증가 또는 동시간대 DB 부하로 인한 지연으로 추정됩니다.",
                            List.of("동시간대 수행 작업과 DB 대기 이벤트(Wait Event) 확인",
                                    "지연이 3일 이상 지속되면 SQL 실행계획 점검"),
                            List.of(new BatchCase("WIKI-BAT-2025-011", "야간 배치 수행시간 증가 분석", "72%")));
                }
                return new LogAnalysis("NONE", "작업이 정상 종료되었으며 특이사항이 없습니다.",
                        "수행시간이 평균 범위 내이며 경고 로그가 없습니다.", List.of("추가 조치가 필요하지 않습니다."), List.of());
        }
    }

    private List<String> successors(String jobName) {
        return JOBS.stream().filter(job -> job.predecessors().contains(jobName)).map(JobDef::name).toList();
    }

    private static int hash(LocalDate date, String name) {
        return Math.floorMod((date.format(ODATE) + name).hashCode(), 1000);
    }

    private static String fmt(LocalDateTime time) {
        return time.format(DATE_TIME);
    }

    private static String minutes(int seconds) {
        return "%d분 %02d초".formatted(seconds / 60, seconds % 60);
    }

    private record JobDef(String name, String description, String group, String owner, String subOwner,
                          String manager, List<String> predecessors, int avgMinutes) {}

    public record BatchJob(String jobName, String description, String group, String owner, String subOwner,
                           String manager, List<String> predecessors, String status, String startTime,
                           String endTime, Integer durationSec, int avgDurationSec) {}

    public record BatchStatus(String odate, String refreshedAt, Map<String, Long> summary, List<BatchJob> jobs) {}

    public record FlowNode(BatchJob job, boolean external) {}

    public record FlowEdge(String from, String to) {}

    public record FlowResponse(String odate, List<FlowNode> nodes, List<FlowEdge> edges) {}

    public record LogLine(String time, String level, String message) {}

    public record BatchCase(String id, String title, String similarity) {}

    public record LogAnalysis(String severity, String summary, String cause, List<String> actions,
                              List<BatchCase> similarCases) {}

    public record LogResponse(BatchJob job, List<LogLine> lines, LogAnalysis analysis) {}
}
