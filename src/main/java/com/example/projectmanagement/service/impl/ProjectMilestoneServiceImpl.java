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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
public class ProjectMilestoneServiceImpl implements ProjectMilestoneService {

    static final BigDecimal FULL_ALLOCATION = new BigDecimal("100.00");
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Milestones in these statuses still own their planned date range, so a new or edited
     * milestone may not overlap them. COMPLETED and CANCELLED are terminal and release the range.
     */
    static final Set<MilestoneExecutionStatus> DATE_BLOCKING_STATUSES = EnumSet.of(
            MilestoneExecutionStatus.PLANNED,
            MilestoneExecutionStatus.IN_PROGRESS,
            MilestoneExecutionStatus.ON_HOLD);

    /** Base the allocation % was applied to, and the resulting amount. */
    public record MilestoneEstimate(BigDecimal baseAmount, BigDecimal estimatedAmount) {}

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
        // The cascade depends on every milestone in the project, not just the current page.
        Map<Long, MilestoneEstimate> estimates =
                estimateAmounts(project.getProjectBudget(), milestoneRepository.findAllByProjectId(projectId));
        return milestoneRepository.findByProjectFiltered(projectId, status, readiness, pageable)
                .map(m -> toResponse(m, project, estimates));
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

        // Money side of the cascade: what the milestones already claim, and what is still unbilled.
        BigDecimal budget = project.getProjectBudget();
        Map<Long, MilestoneEstimate> estimates = estimateAmounts(budget, all);
        BigDecimal allocatedAmount = budget == null ? null : estimates.values().stream()
                .map(MilestoneEstimate::estimatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingAmount = budget == null ? null : budget.subtract(allocatedAmount);

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
                .projectBudget(budget)
                .allocatedAmount(allocatedAmount)
                .remainingAmount(remainingAmount)
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

        List<ProjectMilestone> projectMilestones = new ArrayList<>(milestoneRepository.findAllByProjectId(projectId));

        validateDates(milestone, errors);
        validateMilestoneAllocation(milestone, errors);
        validateDateOverlap(milestone, projectMilestones, errors);
        throwIfErrors(errors);

        projectMilestones.add(milestone);
        validateProjectAllocation(projectMilestones);

        milestoneRepository.save(milestone);
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project,
                estimateAmounts(project.getProjectBudget(), projectMilestones));
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

        List<ProjectMilestone> projectMilestones = milestoneRepository.findAllByProjectId(project.getId());

        validateDates(milestone, errors);
        validateMilestoneAllocation(milestone, errors);
        validateDateOverlap(milestone, projectMilestones, errors);
        throwIfErrors(errors);

        validateProjectAllocation(projectMilestones);
        recomputeReadiness(projectMilestones);
        milestoneRepository.flush();
        return toResponse(milestone, project,
                estimateAmounts(project.getProjectBudget(), projectMilestones));
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
        return toResponse(milestone, project,
                estimateAmounts(project.getProjectBudget(), projectMilestones));
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
        return toResponse(milestone, project,
                estimateAmounts(project.getProjectBudget(), projectMilestones));
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

    @Override
    @Transactional
    public int startDueMilestones(LocalDate asOf) {
        List<ProjectMilestone> due = milestoneRepository.findPlannedDueToStart(asOf);
        if (due.isEmpty()) return 0;

        int started = 0;
        for (ProjectMilestone m : due) {
            // Re-check the transition rather than trusting the query: PLANNED -> IN_PROGRESS only.
            if (!m.getExecutionStatus().allowedStatusChanges().contains(MilestoneExecutionStatus.IN_PROGRESS)) {
                continue;
            }
            m.setExecutionStatus(MilestoneExecutionStatus.IN_PROGRESS);
            // No user drove this: a null updatedBy marks the change as system-driven rather than
            // leaving the last human editor to look responsible for it.
            m.setUpdatedBy(null);
            started++;
            log.info("Auto-started milestone {} (id={}, planned start {})",
                    m.getMilestoneCode(), m.getId(), m.getPlannedStartDate());
        }

        if (started > 0) {
            recomputeReadiness(due);
            milestoneRepository.flush();
        }
        return started;
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

    /**
     * A milestone may not overlap the planned range of another milestone that is still open
     * (PLANNED / IN_PROGRESS / ON_HOLD). Once a milestone is COMPLETED or CANCELLED its range is
     * released and a later milestone may sit inside it.
     * <p>
     * Ranges are inclusive on both ends. A milestone missing either date has no comparable range,
     * so it neither blocks nor is blocked.
     */
    static void validateDateOverlap(ProjectMilestone candidate,
                                    Collection<ProjectMilestone> projectMilestones,
                                    List<String> errors) {
        if (!DATE_BLOCKING_STATUSES.contains(candidate.getExecutionStatus())) return;

        LocalDate start = candidate.getPlannedStartDate();
        LocalDate due = candidate.getPlannedDueDate();
        if (start == null || due == null || due.isBefore(start)) return;

        for (ProjectMilestone other : projectMilestones) {
            if (other == candidate) continue;
            if (candidate.getId() != null && Objects.equals(candidate.getId(), other.getId())) continue;
            if (!DATE_BLOCKING_STATUSES.contains(other.getExecutionStatus())) continue;

            LocalDate otherStart = other.getPlannedStartDate();
            LocalDate otherDue = other.getPlannedDueDate();
            if (otherStart == null || otherDue == null) continue;

            if (!start.isAfter(otherDue) && !otherStart.isAfter(due)) {
                errors.add("Planned dates overlap milestone " + other.getMilestoneCode()
                        + " (" + otherStart + " to " + otherDue + ", " + other.getExecutionStatus()
                        + "); complete or cancel it before scheduling inside its dates");
            }
        }
    }

    /**
     * Option B billing: every billable milestone takes its allocation % of the amount still
     * unbilled, not of the full budget. Milestones consume the budget in {@code sequenceOrder}
     * (creation order), so editing dates never shifts the amounts.
     * <pre>
     *   base(1) = projectBudget
     *   amount(i) = base(i) * pct(i) / 100
     *   base(i+1) = base(i) - amount(i)
     * </pre>
     * Example: budget 40000, two milestones at 15% -> 6000.00 then 5100.00.
     * Cancelled and non-billable milestones consume nothing and get no estimate.
     */
    static Map<Long, MilestoneEstimate> estimateAmounts(BigDecimal projectBudget,
                                                        Collection<ProjectMilestone> milestones) {
        Map<Long, MilestoneEstimate> estimates = new HashMap<>();
        if (projectBudget == null) return estimates;

        List<ProjectMilestone> ordered = new ArrayList<>(milestones);
        ordered.sort(Comparator.comparing(m -> m.getSequenceOrder() == null
                ? Integer.MAX_VALUE
                : m.getSequenceOrder()));

        BigDecimal remaining = projectBudget;
        for (ProjectMilestone m : ordered) {
            if (!countsTowardAllocation(m)) continue;
            BigDecimal pct = m.getAllocationPercentage();
            if (pct == null || pct.signum() <= 0) continue;

            BigDecimal amount = estimateAmount(remaining, pct);
            estimates.put(m.getId(), new MilestoneEstimate(remaining, amount));
            remaining = remaining.subtract(amount);
        }
        return estimates;
    }

    /** Amount a percentage claims from a base. The base is the unbilled remainder, not the budget. */
    static BigDecimal estimateAmount(BigDecimal base, BigDecimal percentage) {
        if (base == null || percentage == null) return null;
        return base.multiply(percentage).divide(HUNDRED, 2, RoundingMode.HALF_UP);
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

    /** Convenience for single-milestone responses: re-derives the cascade from the whole project. */
    private MilestoneResponse toResponse(ProjectMilestone m, Project project) {
        return toResponse(m, project,
                estimateAmounts(project.getProjectBudget(),
                        milestoneRepository.findAllByProjectId(project.getId())));
    }

    private MilestoneResponse toResponse(ProjectMilestone m, Project project,
                                         Map<Long, MilestoneEstimate> estimates) {
        MilestoneExecutionStatus status = m.getExecutionStatus();
        MilestoneEstimate estimate = m.getId() == null ? null : estimates.get(m.getId());
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
                .baseAmount(estimate != null ? estimate.baseAmount() : null)
                .estimatedAmount(estimate != null ? estimate.estimatedAmount() : null)
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
