package com.example.projectmanagement.entity;

import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A configurable delivery milestone of a project.
 * Synchronised to AR through CDC on the project_milestones table, so the column
 * set is part of the integration contract (see db/project_milestones.sql).
 */
@Entity
@Table(name = "project_milestones",
        uniqueConstraints = @UniqueConstraint(name = "uk_milestone_project_code",
                columnNames = {"project_id", "milestone_code"}),
        indexes = {
                @Index(name = "idx_milestone_project_sequence", columnList = "project_id, sequence_order"),
                @Index(name = "idx_milestone_project_status", columnList = "project_id, execution_status"),
                @Index(name = "idx_milestone_project_readiness", columnList = "project_id, billing_readiness")
        })
@Check(name = "chk_milestone_allocation_range",
        constraints = "allocation_percentage >= 0 AND allocation_percentage <= 100")
@Check(name = "chk_milestone_planned_dates",
        constraints = "planned_due_date IS NULL OR planned_start_date IS NULL OR planned_due_date >= planned_start_date")
@Getter
@Setter
public class ProjectMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "epic_id")
    private Epic epic;

    @Column(name = "milestone_code", nullable = false, length = 50, updatable = false)
    private String milestoneCode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_due_date")
    private LocalDate plannedDueDate;

    @Column(name = "actual_completion_date")
    private LocalDate actualCompletionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 20)
    private MilestoneExecutionStatus executionStatus = MilestoneExecutionStatus.PLANNED;

    @Column(name = "billable", nullable = false)
    private boolean billable;

    @Column(name = "allocation_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal allocationPercentage = BigDecimal.ZERO;

    /** Derived by the service on every write; persisted so CDC carries it to AR. */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_readiness", nullable = false, length = 30)
    private MilestoneBillingReadiness billingReadiness = MilestoneBillingReadiness.NOT_READY;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "completed_by")
    private Long completedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
