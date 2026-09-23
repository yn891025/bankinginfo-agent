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
                .andExpect(jsonPath("$.jobs.length()").value(16))
                .andExpect(jsonPath("$.summary.OK").value(16));
    }

    @Test
    void returnsTodayBatchWithErrorAndBlockedSuccessors() throws Exception {
        mockMvc.perform(get("/api/batch/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ERROR").value(1))
                .andExpect(jsonPath("$.jobs[?(@.jobName == 'ACC_STM_GEN_04')].status").value("WAIT"));
    }

    @Test
    void returnsFlowForSelectedOwnerWithLinkedJobs() throws Exception {
        mockMvc.perform(get("/api/batch/flow").param("persons", "이영희").param("roles", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.job.jobName == 'LON_INT_CAL_01')].external").value(false))
                .andExpect(jsonPath("$.nodes[?(@.job.jobName == 'ACC_BAL_EXT_02')].external").value(true));
    }

    @Test
    void returnsLogsWithAiAnalysisForFailedJob() throws Exception {
        mockMvc.perform(get("/api/batch/logs").param("jobName", "ACC_TXN_CHK_03"))
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
