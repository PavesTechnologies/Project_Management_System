package com.example.projectmanagement.dto.milestone;

import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class MilestoneResponse {
    private Long id;
    private Long projectId;
    private String milestoneCode;
    private String name;
    private String description;
    private Long epicId;
    private String epicName;
    private LocalDate plannedStartDate;
    private LocalDate plannedDueDate;
    private LocalDate actualCompletionDate;
    private MilestoneExecutionStatus executionStatus;
    private boolean billable;
    private BigDecimal allocationPercentage;
    /**
     * Display-only estimate (project budget x allocation %). AR computes the
     * billed amount from its own contract value; this is never synchronised.
     */
    private BigDecimal estimatedAmount;
    private String currency;
    private MilestoneBillingReadiness billingReadiness;
    private List<String> readinessReasons;
    private List<MilestoneExecutionStatus> allowedStatusChanges;
    /** False once COMPLETED: a completed milestone is read-only. */
    private boolean canEdit;
    private boolean canComplete;
    private boolean canDelete;
    private Long version;
    private Long createdBy;
    private Long updatedBy;
    private Long completedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
