package com.bankinginfo.guide.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return new DashboardSummary(
                128,
                94,
                34,
                87,
                List.of(
                        new Topic("입력값 검증", 31, "+8"),
                        new Topic("인증·세션", 24, "+3"),
                        new Topic("정보 노출", 18, "-2"),
                        new Topic("보안 헤더", 14, "+1")));
    }

    public record Topic(String name, int count, String change) {}

    public record DashboardSummary(int totalCases, int webCases, int batchCases,
                                   int completionRate, List<Topic> topics) {}
}
