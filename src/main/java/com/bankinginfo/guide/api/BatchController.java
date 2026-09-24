package com.bankinginfo.guide.api;

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
 * 배치 에이전트 API. 작업 정의(docs/batch-job-list.csv)와 Control-M 수행 이력(BatchRunHistory)을 사용합니다.
 */
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private static final DateTimeFormatter ODATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String GROUP = "sbdmap";
    private static final String OWNER = "하태영";
    private static final List<String> SUB_OWNERS = List.of("이동호", "민사엽");
    private static final String MANAGER = "노인우";

    /** 작업 목록 원본: docs/batch-job-list.csv (Control-M 작업 정의). */
    private static final List<JobDef> JOBS = List.of(
            job("bmap_fcp_mas_unload.sh", "금융소비자보호 컴플라이언스 KPI 데이터 unload", List.of(), List.of()),
            job("v_bmap_fcp_mas_load.sh", "금융소비자보호 컴플라이언스 KPI 데이터 load", List.of("bmap_fcp_mas_unload.sh"), List.of()),
            job("bmap_fcp_cus_div.sh", "금융소비자보호 분리보관 데이터 삭제", List.of(), List.of("bmap_map_cus_div.sh")),
            job("bmap_fcp_yunbo_load", "금융소비자 보호시스템 데이터 적재 (연대보증)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_yundae")),
            job("bmap_fcp_rstr_load", "금융소비자 보호시스템 데이터 적재 (구속성)", List.of(), List.of("v_bdwh_dwr_rep_trankusocsungDD.sh")),
            job("bmap_fcp_iyul3_load", "금융소비자 보호시스템 데이터 적재 (3%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_3")),
            job("bmap_fcp_iyul10_load", "금융소비자 보호시스템 데이터 적재 (10%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_10")),
            job("bmap_fcp_iyul12_load", "금융소비자 보호시스템 데이터 적재 (12%)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_12")),
            job("bmap_fcp_iyul_load", "금융소비자 보호시스템 데이터 적재 (금리인상)", List.of(), List.of("v_dwm_care_sobija_confirm.sh_yundae")),
            job("bmap_fcp_iyul_ihgb_load", "금융소비자 보호시스템 데이터 적재 (금리인하요구권거절)", List.of("bmap_fcp_iyul_load"),
                    List.of("byeqd2270.sh_2", "bypq_irtminoti.sh")),
            job("bmap_fcp_iyul13_load", "금융소비자 보호시스템 데이터 적재 (313%)",
                    List.of("bmap_fcp_iyul10_load", "bmap_fcp_iyul_load", "bmap_fcp_iyul3_load", "bmap_fcp_iyul12_load"), List.of("byeqd2270.sh")),
            job("bmap_fcp_mas_load", "금융소비자 보호시스템 데이터구축",
                    List.of("bmap_fcp_yunbo_load", "bmap_fcp_iyul_load", "bmap_fcp_rstr_load", "bmap_fcp_iyul3_load", "bmap_fcp_iyul10_load",
                            "bmap_fcp_iyul12_load", "bmap_fcp_iyul13_load", "bmap_fcp_iyul_ihgb_load"), List.of()),
            job("bmap_fcp_mas.ul", "금융소비자 보호시스템 데이터구축 전체로드", List.of("bmap_fcp_mas_load"), List.of()),
            job("bmap_fcp_mail_send01", "금융소비자 보호점검관련 메일발송", List.of("bmap_fcp_mas.ul"), List.of()));

    private static JobDef job(String name, String description, List<String> predecessors, List<String> externalPredecessors) {
        return new JobDef(name, description, GROUP, OWNER, SUB_OWNERS, MANAGER, predecessors, externalPredecessors);
    }

    private final BatchRunHistory runHistory;

    public BatchController(BatchRunHistory runHistory) {
        this.runHistory = runHistory;
    }

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

    /** 대시보드: 해당 ODATE에 수행 이력이 있는 작업의 현황. */
    @GetMapping("/jobs")
    public BatchStatus jobs(@RequestParam(required = false) String odate) {
        var date = parseOdate(odate);
        Map<String, JobDef> defs = new HashMap<>();
        JOBS.forEach(def -> defs.put(def.name(), def));
        var jobs = runHistory.runs(date).stream().map(run -> toJob(run, defs.get(run.jobName()))).toList();
        Map<String, Long> summary = new LinkedHashMap<>();
        for (var status : List.of("OK", "RUNNING", "ERROR", "WAIT")) {
            summary.put(status, jobs.stream().filter(job -> job.status().equals(status)).count());
        }
        return new BatchStatus(date, LocalDateTime.now().format(DATE_TIME), summary, jobs, runHistory.odates(),
                JOBS.stream().map(JobDef::name).toList(), runHistory.source());
    }

    @GetMapping("/flow")
    public FlowResponse flow(@RequestParam(required = false) String odate,
                             @RequestParam(required = false) List<String> persons,
                             @RequestParam(required = false) List<String> roles) {
        var date = parseOdate(odate);
        var jobs = historyJobs(date);
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
                List.of(), "OK", null, null, null, null, null, null)));
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
        return new FlowResponse(date, nodes, edges);
    }

    private String parseOdate(String odate) {
        if (odate == null || odate.isBlank()) return LocalDate.now().format(ODATE);
        try {
            return LocalDate.parse(odate.replace("-", ""), ODATE).format(ODATE);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ODATE 형식은 yyyyMMdd 입니다.");
        }
    }

    /**
     * 등록된 전체 작업의 수행 이력 기준 상태. 해당 ODATE에 이력이 없는 작업은 NONE(미수행)으로 표시하고,
     * 작업 목록에 없는 이력 작업은 뒤에 덧붙입니다.
     */
    private List<BatchJob> historyJobs(String odate) {
        Map<String, BatchRunHistory.JobRun> runs = new LinkedHashMap<>();
        runHistory.runs(odate).forEach(run -> runs.put(run.jobName(), run));
        List<BatchJob> result = new ArrayList<>();
        for (var def : JOBS) {
            var run = runs.remove(def.name());
            result.add(run != null ? toJob(run, def)
                    : new BatchJob(def.name(), def.description(), def.group(), def.owner(), String.join(", ", def.subOwners()),
                            def.manager(), predecessors(def), "NONE", null, null, null,
                            runHistory.avgDurationSec(def.name()), null, null));
        }
        runs.values().forEach(run -> result.add(toJob(run, null)));
        return result;
    }

    private BatchJob toJob(BatchRunHistory.JobRun run, JobDef def) {
        var users = run.users();
        return new BatchJob(run.jobName(), def == null ? run.title() : def.description(), def == null ? GROUP : def.group(),
                def == null ? users.get(0) : def.owner(),
                def == null ? String.join(", ", users.subList(1, users.size())) : String.join(", ", def.subOwners()),
                def == null ? "-" : def.manager(), def == null ? List.of() : predecessors(def), run.status(),
                run.start() == null ? null : run.start().format(DATE_TIME), run.end() == null ? null : run.end().format(DATE_TIME),
                run.durationSec(), runHistory.avgDurationSec(run.jobName()), run.ctmState(), run.runCount());
    }

    private static List<String> predecessors(JobDef def) {
        List<String> predecessors = new ArrayList<>(def.externalPredecessors());
        predecessors.addAll(def.predecessors());
        return predecessors;
    }

    /** predecessors: 작업 목록 내 선행 작업, externalPredecessors: 타 시스템 선행 조건 */
    private record JobDef(String name, String description, String group, String owner, List<String> subOwners,
                          String manager, List<String> predecessors, List<String> externalPredecessors) {}

    /** avgDurationSec: 정상 종료 이력의 평균 수행시간(이력이 없으면 null) */
    public record BatchJob(String jobName, String description, String group, String owner, String subOwner,
                           String manager, List<String> predecessors, String status, String startTime,
                           String endTime, Integer durationSec, Integer avgDurationSec, String ctmState, Integer runCount) {}

    /** dataOdates: 수행 이력이 있는 ODATE 목록, definedJobs: 등록된 전체 작업명(즐겨찾기 정리용), source: 데이터 출처(MCP/CSV) */
    public record BatchStatus(String odate, String refreshedAt, Map<String, Long> summary, List<BatchJob> jobs,
                              List<String> dataOdates, List<String> definedJobs, BatchRunHistory.DataSource source) {}

    public record FlowNode(BatchJob job, boolean external) {}

    public record FlowEdge(String from, String to) {}

    public record FlowResponse(String odate, List<FlowNode> nodes, List<FlowEdge> edges) {}

}
