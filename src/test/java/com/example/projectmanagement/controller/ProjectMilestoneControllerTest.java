package com.example.projectmanagement.controller;

import com.example.projectmanagement.config.TokenStore;
import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.dto.milestone.MilestoneCreateRequest;
import com.example.projectmanagement.dto.milestone.MilestoneResponse;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import com.example.projectmanagement.exception.ForbiddenException;
import com.example.projectmanagement.entity.ProjectMilestone;
import com.example.projectmanagement.scheduler.RmsPollingService;
import com.example.projectmanagement.security.Securityconfig;
import com.example.projectmanagement.service.ProjectMilestoneService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectMilestoneController.class)
@Import(Securityconfig.class)
class ProjectMilestoneControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectMilestoneService milestoneService;
    @MockBean
    private JwtDecoder jwtDecoder;
    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private RmsPollingService rmsPollingService;

    private static RequestPostProcessor asRole(String authority) {
        return jwt().jwt(j -> j.claim("user_id", "7").claim("name", "Priya").claim("roles", List.of("Project Manager")))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private static MilestoneResponse sample() {
        return MilestoneResponse.builder()
                .id(1L).projectId(10L).milestoneCode("PAY-M001").name("Go Live")
                .executionStatus(MilestoneExecutionStatus.PLANNED)
                .billable(true).allocationPercentage(new BigDecimal("30.00"))
                .billingReadiness(MilestoneBillingReadiness.NOT_READY)
                .readinessReasons(List.of()).allowedStatusChanges(List.of()).version(0L)
                .build();
    }

    @Test
    void generalRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/projects/10/milestones").with(asRole("ROLE_GENERAL")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(milestoneService);
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/projects/10/milestones"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void projectManagerListsMilestonesWithCappedPageSize() throws Exception {
        when(milestoneService.listMilestones(eq(10L), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(sample()), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/projects/10/milestones").param("size", "5000")
                        .param("status", "PLANNED").with(asRole("ROLE_PROJECT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].milestoneCode").value("PAY-M001"))
                .andExpect(jsonPath("$.content[0].allocationPercentage").value(30.00));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<UserDto> user = ArgumentCaptor.forClass(UserDto.class);
        verify(milestoneService).listMilestones(eq(10L), eq(MilestoneExecutionStatus.PLANNED), isNull(),
                pageable.capture(), user.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(user.getValue().getId()).isEqualTo(7L);
    }

    @Test
    void createReturns201() throws Exception {
        when(milestoneService.createMilestone(eq(10L), any(MilestoneCreateRequest.class), any()))
                .thenReturn(sample());

        mockMvc.perform(post("/api/projects/10/milestones").with(asRole("ROLE_PROJECT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Go Live\",\"billable\":true,\"allocationPercentage\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createValidatesBody() throws Exception {
        mockMvc.perform(post("/api/projects/10/milestones").with(asRole("ROLE_PROJECT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"billable\":true,\"allocationPercentage\":30.555}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.allocationPercentage").exists());
        verifyNoInteractions(milestoneService);
    }

    @Test
    void serviceForbiddenMapsTo403() throws Exception {
        when(milestoneService.getMilestone(eq(1L), any())).thenThrow(new ForbiddenException("no access"));
        mockMvc.perform(get("/api/milestones/1").with(asRole("ROLE_PROJECT_MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        when(milestoneService.getMilestone(eq(2L), any())).thenThrow(new EntityNotFoundException("missing"));
        mockMvc.perform(get("/api/milestones/2").with(asRole("ROLE_PROJECT_MANAGER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleVersionMapsTo409() throws Exception {
        when(milestoneService.changeStatus(eq(1L), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(ProjectMilestone.class, 1L));
        mockMvc.perform(patch("/api/milestones/1/status").with(asRole("ROLE_PROJECT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/milestones/1").with(asRole("ROLE_PROJECT_MANAGER")))
                .andExpect(status().isNoContent());
        verify(milestoneService).deleteMilestone(eq(1L), any());
    }
}
