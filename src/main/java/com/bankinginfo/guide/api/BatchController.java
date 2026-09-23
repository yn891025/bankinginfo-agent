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
    private static final double INITIAL_CLOCK_MINUTE = 120;
    private static final double SIMULATION_SPEED = 10;
    private static final String FAILING_JOB = "bmap_fcp_mas_load";
    private static final String EXTRACT_JOB = "bmap_fcp_iyul_load";
    private static final String GROUP = "sbdmap";
    private static final String OWNER = "하태영";
    private static final List<String> SUB_OWNERS = List.of("이동호", "민사엽");
    private static final String MANAGER = "노인우";
    private static final int AT_1100 = 11 * 60;

    /** 작업 목록 원본: docs/batch-job-list.csv (Control-M 작업 정의). 선행 작업 순서(위상 정렬)대로 나열합니다. */
    private static final List<JobDef> JOBS = List.of(
            job("bmap_fcp_mas_unload.sh", "금융소비자보호 컴플라이언스 KPI 데이터 unload", List.of(), List.of(), AT_1100, 5),
            job("v_bmap_fcp_mas_load.sh", "금융소비자보호 컴플라이언스 KPI 데이터 load", List.of("bmap_fcp_mas_unload.sh"), List.of(), 0, 12),
            job("bmap_fcp_cus_div.sh", "금융소비자보호 분리보관 데이터 삭제", List.of(), List.of("bmap_map_cus_div.sh"), AT_1100, 6),
            job("bmap_fcp_yunbo_load", "금융소비자 보호시스템 데이터 적재 (연대보증)", List.of(),
                    List.of("v_dwm_care_sobija_confirm.sh_yundae"), 0, 20),
            job("bmap_fcp_rstr_load", "금융소비자 보호시스템 데이터 적재 (구속성)", List.of(),
                    List.of("v_bdwh_dwr_rep_trankusocsungDD.sh"), 0, 18),
            job("bmap_fcp_iyul3_load", "금융소비자 보호시스템 데이터 적재 (3%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_3"), 0, 15),
            job("bmap_fcp_iyul10_load", "금융소비자 보호시스템 데이터 적재 (10%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_10"), 0, 15),
            job("bmap_fcp_iyul12_load", "금융소비자 보호시스템 데이터 적재 (12%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_12"), 0, 15),
            job(EXTRACT_JOB, "금융소비자 보호시스템 데이터 적재 (금리인상)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_yundae"), 0, 25),
            job("bmap_fcp_iyul_ihgb_load", "금융소비자 보호시스템 데이터 적재 (금리인하요구권거절)", List.of(EXTRACT_JOB),
                    List.of("byeqd2270.sh_2", "bypq_irtminoti.sh"), 0, 22),
            job("bmap_fcp_iyul13_load", "금융소비자 보호시스템 데이터 적재 (313%)",
                    List.of("bmap_fcp_iyul10_load", EXTRACT_JOB, "bmap_fcp_iyul3_load", "bmap_fcp_iyul12_load"), List.of("byeqd2270.sh"), 0, 16),
            job(FAILING_JOB, "금융소비자 보호시스템 데이터구축",
                    List.of("bmap_fcp_yunbo_load", EXTRACT_JOB, "bmap_fcp_rstr_load", "bmap_fcp_iyul3_load", "bmap_fcp_iyul10_load",
                            "bmap_fcp_iyul12_load", "bmap_fcp_iyul13_load", "bmap_fcp_iyul_ihgb_load"), List.of(), 0, 35),
            job("bmap_fcp_mas.ul", "금융소비자 보호시스템 데이터구축 전체로드", List.of(FAILING_JOB), List.of(), 0, 30),
            job("bmap_fcp_mail_send01", "금융소비자 보호점검관련 메일발송", List.of("bmap_fcp_mas.ul"), List.of(), 0, 3));

    private static JobDef job(String name, String description, List<String> predecessors, List<String> externalPredecessors,
                              int startMinute, int avgMinutes) {
        return new JobDef(name, description, GROUP, OWNER, SUB_OWNERS, MANAGER, predecessors, externalPredecessors,
                startMinute, avgMinutes);
    }

    private final Instant startedAt = Instant.now();

    @GetMapping("/owners")
    public List<String> owners() {
        Set<String> owners = new LinkedHashSet<>();
        JOBS.forEach(job -> {
            owners.add(job.owner());
            owners.addAll(job.subOwners());
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
        for (var def : JOBS) {
            if (personSet.isEmpty()
                    || roleSet.contains("owner") && personSet.contains(def.owner())
                    || roleSet.contains("subOwner") && def.subOwners().stream().anyMatch(personSet::contains)
                    || roleSet.contains("manager") && personSet.contains(def.manager())) {
                matched.add(def.name());
            }
        }

        // 작업 목록에 없는 타 시스템 선행 조건은 완료된 외부 노드로 표시합니다.
        List<BatchJob> flowJobs = new ArrayList<>();
        Set<String> externalConditions = new LinkedHashSet<>();
        JOBS.forEach(def -> externalConditions.addAll(def.externalPredecessors()));
        externalConditions.forEach(name -> flowJobs.add(new BatchJob(name, "타 시스템 선행 조건", "타 시스템", "-", "-", "-",
                List.of(), "OK", null, null, null, 0)));
        flowJobs.addAll(jobs);

        // 조회 대상 작업과 직접 연결된 타 담당자 작업도 흐름 파악을 위해 함께 표시합니다.
        Set<String> included = new LinkedHashSet<>(matched);
        for (var job : flowJobs) {
            if (matched.contains(job.jobName())) included.addAll(job.predecessors());
            if (job.predecessors().stream().anyMatch(matched::contains)) included.add(job.jobName());
        }

        List<FlowNode> nodes = new ArrayList<>();
        List<FlowEdge> edges = new ArrayList<>();
        for (var job : flowJobs) {
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
            double start = Math.max(def.predecessors().stream().mapToDouble(endMinutes::get).max().orElse(BATCH_START_MINUTE),
                    def.startMinute()) + 1;
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
        List<String> predecessors = new ArrayList<>(def.externalPredecessors());
        predecessors.addAll(def.predecessors());
        return new BatchJob(def.name(), def.description(), def.group(), def.owner(), String.join(", ", def.subOwners()),
                def.manager(), predecessors, status, start == null ? null : start.format(DATE_TIME),
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
                if (predJob == null) continue;
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
            lines.add(new LogLine(fmt(start.plusSeconds(job.durationSec() / 3)), "INFO", "원천 적재 건수 집계 완료: %,d건".formatted(total + 4)));
            lines.add(new LogLine(fmt(start.plusSeconds(job.durationSec() / 2)), "INFO", "데이터구축 대상 건수 집계 완료: %,d건".formatted(total)));
            lines.add(new LogLine(fmt(end.minusSeconds(20)), "WARN", "건수 불일치 감지 - 원천 %,d건 / 구축 %,d건 (차이 4건)".formatted(total + 4, total)));
            var extractStart = LocalDateTime.parse(jobs.get(EXTRACT_JOB).startTime(), DATE_TIME);
            lines.add(new LogLine(fmt(end.minusSeconds(18)), "WARN", "불일치 데이터 발생 시각: %s ~ %s (%s 적재 시작 이후)"
                    .formatted(extractStart.toLocalTime().plusSeconds(7), extractStart.toLocalTime().plusSeconds(18), EXTRACT_JOB)));
            lines.add(new LogLine(fmt(end.minusSeconds(2)), "ERROR", "com.bank.batch.ValidationException: 정합성 검증 실패 (CODE=BV-2031)"));
            lines.add(new LogLine(fmt(end.minusSeconds(2)), "ERROR", "    at com.bank.batch.fcp.FcpMasterBuilder.verify(FcpMasterBuilder.java:142)"));
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
                        "데이터구축 정합성 검증 단계에서 원천 적재 건수와 구축 대상 건수가 4건 차이 나 작업이 RC=8로 종료되었습니다.",
                        "선행 작업 %s이 %s에 적재를 시작한 이후 원천(DWH)에 반영된 지연 데이터 4건이 누락되어, 적재 기준 시점이 어긋난 것으로 판단됩니다."
                                .formatted(EXTRACT_JOB, LocalDateTime.parse(jobs.get(EXTRACT_JOB).startTime(), DATE_TIME).toLocalTime()),
                        List.of(EXTRACT_JOB + " 적재 조건에 기준시각(ODATE 23:59:59) 컷오프가 적용되어 있는지 확인",
                                "불일치 데이터 4건의 계좌번호를 조회하여 원천 지연 반영 여부 확인",
                                EXTRACT_JOB + " 재수행 후 " + job.jobName() + " 재실행 (Rerun)",
                                "후행 작업 " + String.join(", ", successors(job.jobName())) + " 정상 수행 여부 확인"),
                        List.of(new BatchCase("WIKI-BAT-2025-032", "금소법 데이터 적재 컷오프 누락 조치", "91%"),
                                new BatchCase("WIKI-BAT-2024-118", "데이터구축 건수 불일치 재수행 절차", "83%")));
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
                        .filter(pred -> pred != null && "ERROR".equals(pred.status())).findFirst();
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

    /** predecessors: 작업 목록 내 선행 작업, externalPredecessors: 타 시스템 선행 조건, startMinute: 작업수행시각(ODATE 00:00 기준 분) */
    private record JobDef(String name, String description, String group, String owner, List<String> subOwners,
                          String manager, List<String> predecessors, List<String> externalPredecessors,
                          int startMinute, int avgMinutes) {}

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
