package com.bankinginfo.guide;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DepartmentGuideApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsWebAnalysis() throws Exception {
        mockMvc.perform(post("/api/web/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vulnId":"WEB-001","vulnTitle":"SQL Injection","asIsCode":"query + id"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.similarCases.length()").value(3));
    }

    @Test
    void returnsDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases").value(128));
    }

    @Test
    void returnsBatchJobsForPastOdateAsCompleted() throws Exception {
        mockMvc.perform(get("/api/batch/jobs").param("odate", "20250101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(14))
                .andExpect(jsonPath("$.summary.OK").value(14));
    }

    @Test
    void returnsTodayBatchWithErrorAndBlockedSuccessors() throws Exception {
        mockMvc.perform(get("/api/batch/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ERROR").value(1))
                .andExpect(jsonPath("$.jobs[?(@.jobName == 'bmap_fcp_mail_send01')].status").value("WAIT"));
    }

    @Test
    void returnsFlowForSelectedOwnerWithLinkedJobs() throws Exception {
        mockMvc.perform(get("/api/batch/flow").param("persons", "민사엽").param("roles", "subOwner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.job.jobName == 'bmap_fcp_mas_load')].external").value(false))
                .andExpect(jsonPath("$.nodes[?(@.job.jobName == 'v_dwm_care_sobija_confirm.sh_yundae')].external").value(true))
                .andExpect(jsonPath("$.edges[?(@.from == 'bmap_fcp_iyul_load' && @.to == 'bmap_fcp_iyul_ihgb_load')]").isNotEmpty());
    }

    @Test
    void returnsLogsWithAiAnalysisForFailedJob() throws Exception {
        mockMvc.perform(get("/api/batch/logs").param("jobName", "bmap_fcp_mas_load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.severity").value("HIGH"))
                .andExpect(jsonPath("$.lines[?(@.level == 'ERROR')]").isNotEmpty());
    }

    @Test
    void rejectsInvalidOdate() throws Exception {
        mockMvc.perform(get("/api/batch/jobs").param("odate", "2026-13"))
                .andExpect(status().isBadRequest());
    }
}
