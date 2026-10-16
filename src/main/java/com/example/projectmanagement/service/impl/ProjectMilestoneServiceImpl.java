package com.example.projectmanagement.service.impl;

import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.dto.milestone.*;
import com.example.projectmanagement.entity.Epic;
import com.example.projectmanagement.entity.Project;
import com.example.projectmanagement.entity.ProjectMilestone;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import com.example.projectmanagement.exception.ValidationException;
import com.example.projectmanagement.repository.EpicRepository;
import com.example.projectmanagement.repository.ProjectMilestoneRepository;
import com.example.projectmanagement.service.ProjectAccessService;
import com.example.projectmanagement.service.ProjectMilestoneService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class ProjectMilestoneServiceImpl implements ProjectMilestoneService {

    static final BigDecimal FULL_ALLOCATION = new BigDecimal("100.00");

    private final ProjectMilestoneRepository milestoneRepository;
    private final EpicRepository epicRepository;
    private final ProjectAccessService projectAccessService;
    private final EntityManager entityManager;

    public ProjectMilestoneServiceImpl(ProjectMilestoneRepository milestoneRepository,
                                       EpicRepository epicRepository,
                                       ProjectAccessService projectAccessService,
                                       EntityManager entityManager) {
        this.milestoneRepository = milestoneRepository;
        this.epicRepository = epicRepository;
        this.projectAccessService = projectAccessService;
        this.entityManager = entityManager;
    }

    /* ======================= READS ======================= */

    @Override
    @Transactional(readOnly = true)
    public Page<MilestoneResponse> listMilestones(Long projectId, MilestoneExecutionStatus status,
                                                  MilestoneBillingReadiness readiness, Pageable pageable,
                                                  UserDto currentUser) {
        Project project = projectAccessService.requireProjectManagerAccess(projectId, currentUser);
        return milestoneRepository.findByProjectFiltered(projectId, status, readiness, pageable)
                .map(m -> toResponse(m, project));
    }

    @Override
    @Transactional(readOnly = true)
    public MilestoneSummaryResponse getSummary(Long projectId, UserDto currentUser) {
        Project project = projectAccessService.requireProjectManagerAccess(projectId, currentUser);
        List<ProjectMilestone> all = milestoneRepository.findAllByProjectId(projectId);

        Map<MilestoneExecutionStatus, Long> byStatus = new EnumMap<>(MilestoneExecutionStatus.class);
        for (ProjectMilestone m : all) {
            byStatus.merge(m.getExecutionStatus(), 1L, Long::sum);
        }
        BigDecimal allocated = activeAllocationTotal(all);

        return MilestoneSummaryResponse.builder()
                .projectId(projectId)
                .total(all.size())
                .planned(byStatus.getOrDefault(MilestoneExecutionStatus.PLANNED, 0L))
                .inProgress(byStatus.getOrDefault(MilestoneExecutionStatus.IN_PROGRESS, 0L))
                .onHold(byStatus.getOrDefault(MilestoneExecutionStatus.ON_HOLD, 0L))
                .completed(byStatus.getOrDefault(MilestoneExecutionStatus.COMPLETED, 0L))
                .cancelled(byStatus.getOrDefault(MilestoneExecutionStatus.CANCELLED, 0L))
                .readyForBilling(all.stream()
                        .filter(m -> m.getBillingReadiness() == MilestoneBillingReadiness.READY_FOR_BILLING)
                        .count())
                .allocatedPercentage(allocated)
                .remainingPercentage(FULL_ALLOCATION.subtract(allocated).max(BigDecimal.ZERO))
                .allocationComplete(allocated.compareTo(FULL_ALLOCATION) == 0)
                .projectBudget(project.getProjectBudget())
                .currency(project.getProjectBudgetCurrency())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public MilestoneResponse getMilestone(Long milestoneId, UserDto currentUser) {
        ProjectMilestone milestone = findMilestone(milestoneId);
        Project project = projectAccessService.requireProjectManagerAccess(milestone.getProject().getId(), currentUser);
        return toResponse(milestone, project);
    }

    /* ======================= WRITES ======================= */

    @Override
    @Transactional
    public MilestoneResponse createMilestone(Long projectId, MilestoneCreateRequest request, UserDto currentUser) {
        Project project = projectAccessService.requireProjectManagerAccess(projectId, currentUser);
        lockProject(project);

        List<String> errors = new ArrayList<>();
        ProjectMilestone milestone = new ProjectMilestone();
        milestone.setProject(project);
        milestone.setMilestoneCode(resolveMilestoneCode(project, request.getMilestoneCode(), errors));
        milestone.setName(request.getName().trim());
        milestone.setDescription(trimToNull(request.getDescription()));
        milestone.setEpic(resolveEpic(request.getEpicId(), projectId, errors));
        // Display order is no longer user-facing; keep the NOT NULL column populated in creation order.
        milestone.setSequenceOrder(milestoneRepository.findMaxSequenceOrder(projectId) + 1);
        milestone.setPlannedStartDate(request.getPlannedStartDate());
        milestone.setPlannedDueDate(request.getPlannedDueDate());
        milestone.setBillable(Boolean.TRUE.equals(request.getBillable()));
        milestone.setAllocationPercentage(normalizePercentage(request.getAllocationPercentage()));
        milestone.setExecutionStatus(MilestoneExecutionStatus.PLANNED);
        milestone.setCreatedBy(currentUser.getId());
        milestone.setUpdatedBy(currentUser.getId());

        validateDates(milestone, errors);
        validateMilestoneAllocation(milestone, errors);
        throwIfErrors(errors);

        List<ProjectMilestone> projectMilestones = new ArrayList<>(milestoneRepository.findAllByProjectId(projectId));
        projectMilestones.add(milestone);
        validateProjectAllocation(projectMilestones);

        milestoneRepository.save(milestone);
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project);
    }

    @Override
    @Transactional
    public MilestoneResponse updateMilestone(Long milestoneId, MilestoneUpdateRequest request, UserDto currentUser) {
        ProjectMilestone milestone = findMilestone(milestoneId);
        Project project = projectAccessService.requireProjectManagerAccess(milestone.getProject().getId(), currentUser);
        lockProject(project);
        checkVersion(milestone, request.getVersion());

        if (milestone.getExecutionStatus() == MilestoneExecutionStatus.COMPLETED) {
            throw new ValidationException(List.of("Completed milestones can't be changed"));
        }

        List<String> errors = new ArrayList<>();
        BigDecimal newPercentage = normalizePercentage(request.getAllocationPercentage());
        boolean newBillable = Boolean.TRUE.equals(request.getBillable());

        if (milestone.getExecutionStatus().isTerminal()
                && (newBillable != milestone.isBillable()
                || newPercentage.compareTo(milestone.getAllocationPercentage()) != 0)) {
            errors.add("Billable flag and allocation cannot be changed once a milestone is "
                    + milestone.getExecutionStatus());
        }

        milestone.setName(request.getName().trim());
        milestone.setDescription(trimToNull(request.getDescription()));
        milestone.setEpic(resolveEpic(request.getEpicId(), project.getId(), errors));
        milestone.setPlannedStartDate(request.getPlannedStartDate());
        milestone.setPlannedDueDate(request.getPlannedDueDate());
        milestone.setBillable(newBillable);
        milestone.setAllocationPercentage(newPercentage);
        milestone.setUpdatedBy(currentUser.getId());

        validateDates(milestone, errors);
        validateMilestoneAllocation(milestone, errors);
        throwIfErrors(errors);

        List<ProjectMilestone> projectMilestones = milestoneRepository.findAllByProjectId(project.getId());
        validateProjectAllocation(projectMilestones);
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project);
    }

    @Override
    @Transactional
    public MilestoneResponse changeStatus(Long milestoneId, MilestoneStatusChangeRequest request, UserDto currentUser) {
        ProjectMilestone milestone = findMilestone(milestoneId);
        Project project = projectAccessService.requireProjectManagerAccess(milestone.getProject().getId(), currentUser);
        lockProject(project);
        checkVersion(milestone, request.getVersion());

        MilestoneExecutionStatus current = milestone.getExecutionStatus();
        MilestoneExecutionStatus target = request.getStatus();
        if (target == MilestoneExecutionStatus.COMPLETED) {
            throw new ValidationException(List.of("Use the complete action to mark a milestone as completed"));
        }
        if (!current.allowedStatusChanges().contains(target)) {
            throw new ValidationException(List.of("Cannot change milestone status from " + current + " to " + target));
        }

        milestone.setExecutionStatus(target);
        milestone.setUpdatedBy(currentUser.getId());

        List<ProjectMilestone> projectMilestones = milestoneRepository.findAllByProjectId(project.getId());
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project);
    }

    @Override
    @Transactional
    public MilestoneResponse completeMilestone(Long milestoneId, MilestoneCompleteRequest request, UserDto currentUser) {
        ProjectMilestone milestone = findMilestone(milestoneId);
        Project project = projectAccessService.requireProjectManagerAccess(milestone.getProject().getId(), currentUser);
        lockProject(project);
        checkVersion(milestone, request.getVersion());

        if (milestone.getExecutionStatus() != MilestoneExecutionStatus.IN_PROGRESS) {
            throw new ValidationException(List.of("Only an IN_PROGRESS milestone can be completed (current: "
                    + milestone.getExecutionStatus() + ")"));
        }
        LocalDate completionDate = request.getActualCompletionDate() != null
                ? request.getActualCompletionDate()
                : LocalDate.now();
        if (completionDate.isAfter(LocalDate.now())) {
            throw new ValidationException(List.of("Completion date cannot be in the future"));
        }

        milestone.setExecutionStatus(MilestoneExecutionStatus.COMPLETED);
        milestone.setActualCompletionDate(completionDate);
        milestone.setCompletedBy(currentUser.getId());
        milestone.setUpdatedBy(currentUser.getId());

        List<ProjectMilestone> projectMilestones = milestoneRepository.findAllByProjectId(project.getId());
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project);
    }

    @Override
    @Transactional
    public void deleteMilestone(Long milestoneId, UserDto currentUser) {
        ProjectMilestone milestone = findMilestone(milestoneId);
        Project project = projectAccessService.requireProjectManagerAccess(milestone.getProject().getId(), currentUser);
        lockProject(project);

        if (milestone.getExecutionStatus() != MilestoneExecutionStatus.PLANNED) {
            throw new ValidationException(List.of("Only PLANNED milestones can be deleted; cancel it instead"));
        }
        milestoneRepository.delete(milestone);
        milestoneRepository.flush();

        recomputeReadiness(milestoneRepository.findAllByProjectId(project.getId()));
    }

    /* ======================= RULES ======================= */

    /** Sum of allocation % over billable, non-cancelled milestones. */
    static BigDecimal activeAllocationTotal(Collection<ProjectMilestone> milestones) {
        return milestones.stream()
                .filter(ProjectMilestoneServiceImpl::countsTowardAllocation)
                .map(ProjectMilestone::getAllocationPercentage)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean countsTowardAllocation(ProjectMilestone m) {
        return m.isBillable() && m.getExecutionStatus() != MilestoneExecutionStatus.CANCELLED;
    }

    static void validateMilestoneAllocation(ProjectMilestone m, List<String> errors) {
        BigDecimal pct = m.getAllocationPercentage();
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(FULL_ALLOCATION) > 0) {
            errors.add("Allocation percentage must be between 0 and 100");
        } else if (!m.isBillable() && pct.signum() != 0) {
            errors.add("Non-billable milestones must have 0% allocation");
        } else if (m.isBillable() && pct.signum() <= 0) {
            errors.add("Billable milestones need an allocation percentage greater than 0");
        }
    }

    static void validateProjectAllocation(Collection<ProjectMilestone> milestones) {
        BigDecimal total = activeAllocationTotal(milestones);
        if (total.compareTo(FULL_ALLOCATION) > 0) {
            throw new ValidationException(List.of("Total allocation of active billable milestones would be "
                    + total.toPlainString() + "%; it cannot exceed 100%"));
        }
    }

    static void validateDates(ProjectMilestone m, List<String> errors) {
        if (m.getPlannedStartDate() != null && m.getPlannedDueDate() != null
                && m.getPlannedDueDate().isBefore(m.getPlannedStartDate())) {
            errors.add("Planned due date cannot be before the planned start date");
        }
    }

    /** Single source of truth for billing readiness. Reasons are empty when READY_FOR_BILLING. */
    static List<String> readinessReasons(ProjectMilestone m) {
        List<String> reasons = new ArrayList<>();
        if (!m.isBillable()) {
            reasons.add("Milestone is not billable");
            return reasons;
        }
        if (m.getExecutionStatus() == MilestoneExecutionStatus.CANCELLED) {
            reasons.add("Milestone is cancelled");
            return reasons;
        }
        if (m.getExecutionStatus() != MilestoneExecutionStatus.COMPLETED) {
            reasons.add("Milestone is not completed");
        } else if (m.getActualCompletionDate() == null) {
            reasons.add("Actual completion date is not recorded");
        }
        if (m.getAllocationPercentage() == null || m.getAllocationPercentage().signum() <= 0) {
            reasons.add("Allocation percentage is not set");
        }
        return reasons;
    }

    /**
     * READY_FOR_BILLING = billable, not cancelled, COMPLETED with a completion date and a positive allocation.
     * The project-wide 100% allocation is a planning target only and does not gate billing.
     */
    static MilestoneBillingReadiness computeReadiness(ProjectMilestone m) {
        if (!m.isBillable() || m.getExecutionStatus() == MilestoneExecutionStatus.CANCELLED) {
            return MilestoneBillingReadiness.NOT_BILLABLE;
        }
        return readinessReasons(m).isEmpty()
                ? MilestoneBillingReadiness.READY_FOR_BILLING
                : MilestoneBillingReadiness.NOT_READY;
    }

    /** Recomputes and stores readiness; only rows whose value changes are dirtied (and emit a CDC update). */
    public static int recomputeReadiness(Collection<ProjectMilestone> milestones) {
        int changed = 0;
        for (ProjectMilestone m : milestones) {
            MilestoneBillingReadiness readiness = computeReadiness(m);
            if (m.getBillingReadiness() != readiness) {
                m.setBillingReadiness(readiness);
                changed++;
            }
        }
        return changed;
    }

    static BigDecimal estimateAmount(BigDecimal projectBudget, BigDecimal percentage) {
        if (projectBudget == null || percentage == null) return null;
        return projectBudget.multiply(percentage)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /* ======================= HELPERS ======================= */

    private ProjectMilestone findMilestone(Long milestoneId) {
        return milestoneRepository.findWithDetailsById(milestoneId)
                .orElseThrow(() -> new EntityNotFoundException("Milestone not found with id: " + milestoneId));
    }

    /** Serialises milestone writes per project so allocation totals cannot race. */
    private void lockProject(Project project) {
        entityManager.lock(project, LockModeType.PESSIMISTIC_WRITE);
    }

    private static void checkVersion(ProjectMilestone milestone, Long expectedVersion) {
        if (!Objects.equals(milestone.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(ProjectMilestone.class, milestone.getId());
        }
    }

    private String resolveMilestoneCode(Project project, String requestedCode, List<String> errors) {
        String code = trimToNull(requestedCode);
        if (code != null) {
            code = code.toUpperCase(Locale.ROOT);
            if (milestoneRepository.existsByProjectIdAndMilestoneCode(project.getId(), code)) {
                errors.add("Milestone code " + code + " already exists in this project");
            }
            return code;
        }
        long next = milestoneRepository.countByProjectId(project.getId()) + 1;
        String generated;
        do {
            generated = String.format("%s-M%03d", project.getProjectKey().toUpperCase(Locale.ROOT), next++);
        } while (milestoneRepository.existsByProjectIdAndMilestoneCode(project.getId(), generated));
        return generated;
    }

    private Epic resolveEpic(Long epicId, Long projectId, List<String> errors) {
        if (epicId == null) return null;
        Optional<Epic> epic = epicRepository.findById(epicId);
        if (epic.isEmpty()) {
            errors.add("Epic not found with id: " + epicId);
            return null;
        }
        if (epic.get().getProject() == null || !Objects.equals(epic.get().getProject().getId(), projectId)) {
            errors.add("Epic does not belong to this project");
            return null;
        }
        return epic.get();
    }

    private static BigDecimal normalizePercentage(BigDecimal value) {
        return value == null ? new BigDecimal("0.00") : value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void throwIfErrors(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private MilestoneResponse toResponse(ProjectMilestone m, Project project) {
        MilestoneExecutionStatus status = m.getExecutionStatus();
        return MilestoneResponse.builder()
                .id(m.getId())
                .projectId(project.getId())
                .milestoneCode(m.getMilestoneCode())
                .name(m.getName())
                .description(m.getDescription())
                .epicId(m.getEpic() != null ? m.getEpic().getId() : null)
                .epicName(m.getEpic() != null ? m.getEpic().getName() : null)
                .plannedStartDate(m.getPlannedStartDate())
                .plannedDueDate(m.getPlannedDueDate())
                .actualCompletionDate(m.getActualCompletionDate())
                .executionStatus(status)
                .billable(m.isBillable())
                .allocationPercentage(m.getAllocationPercentage())
                .estimatedAmount(m.isBillable()
                        ? estimateAmount(project.getProjectBudget(), m.getAllocationPercentage())
                        : null)
                .currency(project.getProjectBudgetCurrency())
                .billingReadiness(m.getBillingReadiness())
                .readinessReasons(readinessReasons(m))
                .allowedStatusChanges(new ArrayList<>(status.allowedStatusChanges()))
                .canEdit(status != MilestoneExecutionStatus.COMPLETED)
                .canComplete(status == MilestoneExecutionStatus.IN_PROGRESS)
                .canDelete(status == MilestoneExecutionStatus.PLANNED)
                .version(m.getVersion())
                .createdBy(m.getCreatedBy())
                .updatedBy(m.getUpdatedBy())
                .completedBy(m.getCompletedBy())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
