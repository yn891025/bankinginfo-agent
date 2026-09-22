package com.bankinginfo.guide.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentController {

    @PostMapping("/web/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        var systemName = blank(request.systemName()) ? "인터넷뱅킹 포털" : request.systemName();
        var title = blank(request.vulnTitle()) ? "입력값 검증 취약점" : request.vulnTitle();

        return new AnalyzeResponse(
                "AN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "HIGH",
                "입력값이 검증 없이 쿼리 구성에 사용되어 비정상 요청에 노출될 수 있습니다.",
                List.of(
                        new SimilarCase("WIKI-2025-041", "검색 조건 SQL Injection 조치", "92%", "PreparedStatement 적용 및 허용 목록 검증"),
                        new SimilarCase("WIKI-2024-118", "고객 조회 API 입력값 검증", "84%", "숫자형 변환과 길이 제한 적용"),
                        new SimilarCase("WIKI-2024-077", "동적 쿼리 보안 개선", "76%", "동적 절 조건 제거 및 파라미터 바인딩")),
                "var sql = \"SELECT * FROM account WHERE customer_id = ?\";\n"
                        + "try (var statement = connection.prepareStatement(sql)) {\n"
                        + "    statement.setLong(1, Long.parseLong(customerId));\n"
                        + "    return execute(statement);\n"
                        + "}",
                List.of(
                        "고객 조회 정상·오류 케이스 회귀 테스트",
                        "특수문자 및 과도한 길이 입력 차단 확인",
                        "DB 실행 계획과 응답시간 변화 확인",
                        "동일 파라미터를 사용하는 연관 API 점검"),
                new Impact("중간", List.of("고객 조회 API", "계좌 검색 화면", systemName + " 공통 DAO")),
                "공통 입력 검증 유틸이 이미 존재하는지 확인한 뒤 적용 범위를 결정하세요.",
                title);
    }

    @PostMapping("/batch/analyze")
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, String> batchAnalyze() {
        return Map.of("status", "PLANNED", "message", "배치 에이전트 연동 방식은 상세 기획 후 제공됩니다.");
    }

    @PostMapping("/assetize")
    public AssetizeResponse assetize(@RequestBody AssetizeRequest request) {
        var now = LocalDateTime.now();
        return new AssetizeResponse(
                "WIKI-" + now.getYear() + "-" + String.format("%03d", (now.getDayOfYear() % 900) + 100),
                "웹 취약점 조치 사례/" + request.vulnTitle(),
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                "SAVED");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AnalyzeRequest(String vulnId, String vulnTitle, String systemName, String url,
                                 String checkQuarter, String symptom, String asIsCode) {}

    public record SimilarCase(String id, String title, String similarity, String summary) {}

    public record Impact(String level, List<String> targets) {}

    public record AnalyzeResponse(String analysisId, String riskLevel, String summary,
                                  List<SimilarCase> similarCases, String toBeCode,
                                  List<String> checklist, Impact impact, String recommendation,
                                  String vulnTitle) {}

    public record AssetizeRequest(String analysisId, String vulnTitle, String actionResult, String note) {}

    public record AssetizeResponse(String documentId, String path, String savedAt, String status) {}
}
