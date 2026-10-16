package com.example.projectmanagement.controller;

import com.example.projectmanagement.audit.annotation.AuditLog;
import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.dto.milestone.*;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import com.example.projectmanagement.security.CurrentUser;
import com.example.projectmanagement.service.ProjectMilestoneService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Project milestones. Only PROJECT_MANAGER users who own or belong to the
 * project may see or change milestones (enforced again in the service).
 */
@CrossOrigin
@RestController
@AuditLog(entity = "Milestone")
@RequestMapping("/api")
public class ProjectMilestoneController {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "plannedDueDate", "name", "milestoneCode", "allocationPercentage");
    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectMilestoneService milestoneService;

    public ProjectMilestoneController(ProjectMilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @GetMapping("/projects/{projectId}/milestones")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Page<MilestoneResponse>> listMilestones(
            @PathVariable Long projectId,
            @RequestParam(required = false) MilestoneExecutionStatus status,
            @RequestParam(required = false) MilestoneBillingReadiness readiness,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            @CurrentUser UserDto currentUser) {
        String sortField = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(dir, sortField).and(Sort.by(Sort.Direction.ASC, "id")));
        return ResponseEntity.ok(milestoneService.listMilestones(projectId, status, readiness, pageable, currentUser));
    }

    @GetMapping("/projects/{projectId}/milestones/summary")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneSummaryResponse> getSummary(@PathVariable Long projectId,
                                                               @CurrentUser UserDto currentUser) {
        return ResponseEntity.ok(milestoneService.getSummary(projectId, currentUser));
    }

    @PostMapping("/projects/{projectId}/milestones")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneResponse> createMilestone(@PathVariable Long projectId,
                                                             @Valid @RequestBody MilestoneCreateRequest request,
                                                             @CurrentUser UserDto currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(milestoneService.createMilestone(projectId, request, currentUser));
    }

    @GetMapping("/milestones/{milestoneId}")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneResponse> getMilestone(@PathVariable Long milestoneId,
                                                          @CurrentUser UserDto currentUser) {
        return ResponseEntity.ok(milestoneService.getMilestone(milestoneId, currentUser));
    }

    @PutMapping("/milestones/{milestoneId}")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneResponse> updateMilestone(@PathVariable Long milestoneId,
                                                             @Valid @RequestBody MilestoneUpdateRequest request,
                                                             @CurrentUser UserDto currentUser) {
        return ResponseEntity.ok(milestoneService.updateMilestone(milestoneId, request, currentUser));
    }

    @PatchMapping("/milestones/{milestoneId}/status")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneResponse> changeStatus(@PathVariable Long milestoneId,
                                                          @Valid @RequestBody MilestoneStatusChangeRequest request,
                                                          @CurrentUser UserDto currentUser) {
        return ResponseEntity.ok(milestoneService.changeStatus(milestoneId, request, currentUser));
    }

    @PatchMapping("/milestones/{milestoneId}/complete")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<MilestoneResponse> completeMilestone(@PathVariable Long milestoneId,
                                                               @Valid @RequestBody MilestoneCompleteRequest request,
                                                               @CurrentUser UserDto currentUser) {
        return ResponseEntity.ok(milestoneService.completeMilestone(milestoneId, request, currentUser));
    }

    @DeleteMapping("/milestones/{milestoneId}")
    @PreAuthorize("hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteMilestone(@PathVariable Long milestoneId,
                                                @CurrentUser UserDto currentUser) {
        milestoneService.deleteMilestone(milestoneId, currentUser);
        return ResponseEntity.noContent().build();
    }
}
