package com.example.projectmanagement.service.impl;

import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.dto.milestone.*;
import com.example.projectmanagement.entity.Epic;
import com.example.projectmanagement.entity.Project;
import com.example.projectmanagement.entity.ProjectMilestone;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import com.example.projectmanagement.exception.ForbiddenException;
import com.example.projectmanagement.exception.ValidationException;
import com.example.projectmanagement.repository.EpicRepository;
import com.example.projectmanagement.repository.ProjectMilestoneRepository;
import com.example.projectmanagement.repository.ProjectRepository;
import com.example.projectmanagement.service.ProjectAccessService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectMilestoneServiceImplTest {

    private static final long PROJECT_ID = 10L;
    private static final long PM_ID = 7L;

    private ProjectMilestoneRepository milestoneRepository;
    private EpicRepository epicRepository;
    private ProjectRepository projectRepository;
    private ProjectMilestoneServiceImpl service;

    private Project project;
    private final List<ProjectMilestone> stored = new ArrayList<>();
    private final UserDto pm = new UserDto(PM_ID, "Priya", "priya@example.com", List.of("Project Manager"));

    @BeforeEach
    void setUp() {
        milestoneRepository = mock(ProjectMilestoneRepository.class);
        epicRepository = mock(EpicRepository.class);
        projectRepository = mock(ProjectRepository.class);
        service = new ProjectMilestoneServiceImpl(milestoneRepository, epicRepository,
                new ProjectAccessService(projectRepository), mock(EntityManager.class));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectKey("pay");
        project.setOwnerId(PM_ID);
        project.setProjectBudget(new BigDecimal("1000000.00"));
        project.setProjectBudgetCurrency("INR");

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projectRepository.findMemberIdsByProjectId(PROJECT_ID)).thenReturn(Set.of());
        when(milestoneRepository.findAllByProjectId(PROJECT_ID)).thenAnswer(inv -> new ArrayList<>(stored));
        when(milestoneRepository.save(any())).thenAnswer(inv -> {
            ProjectMilestone m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) (stored.size() + 1));
                m.setVersion(0L);
                stored.add(m);
            }
            return m;
        });
        when(milestoneRepository.findWithDetailsById(anyLong())).thenAnswer(inv ->
                stored.stream().filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        doAnswer(inv -> stored.remove(inv.<ProjectMilestone>getArgument(0)))
                .when(milestoneRepository).delete(any());
    }

    /* ---------------- helpers ---------------- */

    private ProjectMilestone existing(String pct, boolean billable, MilestoneExecutionStatus status) {
        ProjectMilestone m = new ProjectMilestone();
        m.setId((long) (stored.size() + 1));
        m.setProject(project);
        m.setMilestoneCode("PAY-M00" + m.getId());
        m.setName("Milestone " + m.getId());
        m.setSequenceOrder(m.getId().intValue());
        m.setBillable(billable);
        m.setAllocationPercentage(new BigDecimal(pct));
        m.setExecutionStatus(status);
        m.setVersion(0L);
        if (status == MilestoneExecutionStatus.COMPLETED) {
            m.setActualCompletionDate(LocalDate.now().minusDays(1));
        }
        stored.add(m);
        return m;
    }

    private MilestoneCreateRequest createRequest(String pct, boolean billable) {
        MilestoneCreateRequest r = new MilestoneCreateRequest();
        r.setName("UAT Complete");
        r.setBillable(billable);
        r.setAllocationPercentage(new BigDecimal(pct));
        return r;
    }

    private MilestoneUpdateRequest updateRequestFrom(ProjectMilestone m) {
        MilestoneUpdateRequest r = new MilestoneUpdateRequest();
        r.setName(m.getName());
        r.setBillable(m.isBillable());
        r.setAllocationPercentage(m.getAllocationPercentage());
        r.setVersion(m.getVersion());
        return r;
    }

    private MilestoneStatusChangeRequest statusRequest(MilestoneExecutionStatus status, long version) {
        MilestoneStatusChangeRequest r = new MilestoneStatusChangeRequest();
        r.setStatus(status);
        r.setVersion(version);
        return r;
    }

    private MilestoneCompleteRequest completeRequest(LocalDate date, long version) {
        MilestoneCompleteRequest r = new MilestoneCompleteRequest();
        r.setActualCompletionDate(date);
        r.setVersion(version);
        return r;
    }

    private static List<String> errorsOf(Throwable t) {
        return ((ValidationException) t).getErrors();
    }

    /* ---------------- creation & validation ---------------- */

    @Test
    void createGeneratesCodeAndDefaultsToPlanned() {
        MilestoneResponse response = service.createMilestone(PROJECT_ID, createRequest("30", true), pm);

        assertThat(response.getMilestoneCode()).isEqualTo("PAY-M001");
        assertThat(response.getExecutionStatus()).isEqualTo(MilestoneExecutionStatus.PLANNED);
        assertThat(response.getAllocationPercentage()).isEqualByComparingTo("30.00");
        assertThat(response.getEstimatedAmount()).isEqualByComparingTo("300000.00");
        assertThat(response.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.NOT_READY);
        assertThat(response.isCanDelete()).isTrue();
    }

    @Test
    void createRejectsDuplicateCode() {
        when(milestoneRepository.existsByProjectIdAndMilestoneCode(PROJECT_ID, "UAT")).thenReturn(true);
        MilestoneCreateRequest request = createRequest("10", true);
        request.setMilestoneCode("uat");

        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, request, pm))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(errorsOf(t)).anyMatch(e -> e.contains("already exists")));
    }

    @Test
    void createRejectsAllocationAbove100Percent() {
        existing("60", true, MilestoneExecutionStatus.PLANNED);
        existing("30", true, MilestoneExecutionStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, createRequest("20", true), pm))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(errorsOf(t)).anyMatch(e -> e.contains("110.00%")));
        verify(milestoneRepository, never()).save(any());
    }

    @Test
    void createDoesNotRedistributeExistingAllocations() {
        ProjectMilestone first = existing("50", true, MilestoneExecutionStatus.PLANNED);

        service.createMilestone(PROJECT_ID, createRequest("25", true), pm);

        assertThat(first.getAllocationPercentage()).isEqualByComparingTo("50");
    }

    @Test
    void nonBillableMilestoneMustHaveZeroAllocation() {
        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, createRequest("10", false), pm))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(errorsOf(t)).contains("Non-billable milestones must have 0% allocation"));
    }

    @Test
    void nonBillableMilestoneIsNeverBillable() {
        MilestoneResponse response = service.createMilestone(PROJECT_ID, createRequest("0", false), pm);
        assertThat(response.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.NOT_BILLABLE);
        assertThat(response.getEstimatedAmount()).isNull();
    }

    @Test
    void billableMilestoneNeedsPositiveAllocation() {
        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, createRequest("0", true), pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void createRejectsDueDateBeforeStartDate() {
        MilestoneCreateRequest request = createRequest("10", true);
        request.setPlannedStartDate(LocalDate.of(2026, 5, 10));
        request.setPlannedDueDate(LocalDate.of(2026, 5, 1));

        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, request, pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void createRejectsEpicFromAnotherProject() {
        Project other = new Project();
        other.setId(99L);
        Epic epic = new Epic();
        epic.setId(5L);
        epic.setProject(other);
        when(epicRepository.findById(5L)).thenReturn(Optional.of(epic));
        MilestoneCreateRequest request = createRequest("10", true);
        request.setEpicId(5L);

        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, request, pm))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(errorsOf(t)).contains("Epic does not belong to this project"));
    }

    /* ---------------- authorization ---------------- */

    @Test
    void nonProjectManagerIsForbidden() {
        UserDto general = new UserDto(PM_ID, "Gen", "g@example.com", List.of("General"));
        assertThatThrownBy(() -> service.getSummary(PROJECT_ID, general))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void projectManagerOutsideProjectIsForbidden() {
        UserDto outsider = new UserDto(55L, "Out", "o@example.com", List.of("PROJECT_MANAGER"));
        assertThatThrownBy(() -> service.createMilestone(PROJECT_ID, createRequest("10", true), outsider))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void projectManagerWhoIsMemberIsAllowed() {
        when(projectRepository.findMemberIdsByProjectId(PROJECT_ID)).thenReturn(Set.of(55L));
        UserDto member = new UserDto(55L, "Mem", "m@example.com", List.of("Project_Manager"));
        assertThat(service.getSummary(PROJECT_ID, member).getTotal()).isZero();
    }

    /* ---------------- transitions & completion ---------------- */

    @Test
    void validTransitionPlannedToInProgress() {
        ProjectMilestone m = existing("100", true, MilestoneExecutionStatus.PLANNED);
        MilestoneResponse response = service.changeStatus(m.getId(),
                statusRequest(MilestoneExecutionStatus.IN_PROGRESS, 0), pm);
        assertThat(response.getExecutionStatus()).isEqualTo(MilestoneExecutionStatus.IN_PROGRESS);
        assertThat(response.isCanComplete()).isTrue();
    }

    @Test
    void statusEndpointCannotComplete() {
        ProjectMilestone m = existing("100", true, MilestoneExecutionStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.changeStatus(m.getId(),
                statusRequest(MilestoneExecutionStatus.COMPLETED, 0), pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void terminalStatusCannotChange() {
        ProjectMilestone m = existing("100", true, MilestoneExecutionStatus.COMPLETED);
        assertThatThrownBy(() -> service.changeStatus(m.getId(),
                statusRequest(MilestoneExecutionStatus.IN_PROGRESS, 0), pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void completingWithFullAllocationMakesMilestoneReady() {
        ProjectMilestone m = existing("60", true, MilestoneExecutionStatus.IN_PROGRESS);
        existing("40", true, MilestoneExecutionStatus.PLANNED);

        MilestoneResponse response = service.completeMilestone(m.getId(), completeRequest(null, 0), pm);

        assertThat(response.getExecutionStatus()).isEqualTo(MilestoneExecutionStatus.COMPLETED);
        assertThat(response.getActualCompletionDate()).isEqualTo(LocalDate.now());
        assertThat(response.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.READY_FOR_BILLING);
        assertThat(response.getReadinessReasons()).isEmpty();
        assertThat(m.getCompletedBy()).isEqualTo(PM_ID);
    }

    @Test
    void completedMilestoneIsReadyEvenWhenProjectIsNotFullyAllocated() {
        ProjectMilestone m = existing("20", true, MilestoneExecutionStatus.IN_PROGRESS);

        MilestoneResponse response = service.completeMilestone(m.getId(), completeRequest(null, 0), pm);

        assertThat(service.getSummary(PROJECT_ID, pm).isAllocationComplete()).isFalse();
        assertThat(response.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.READY_FOR_BILLING);
        assertThat(response.getReadinessReasons()).isEmpty();
    }

    @Test
    void notCompletedMilestoneIsNotReady() {
        ProjectMilestone m = existing("100", true, MilestoneExecutionStatus.PLANNED);
        MilestoneResponse response = service.changeStatus(m.getId(),
                statusRequest(MilestoneExecutionStatus.IN_PROGRESS, 0), pm);

        assertThat(response.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.NOT_READY);
        assertThat(response.getReadinessReasons()).containsExactly("Milestone is not completed");
    }

    @Test
    void completionRequiresInProgressAndNoFutureDate() {
        ProjectMilestone planned = existing("50", true, MilestoneExecutionStatus.PLANNED);
        ProjectMilestone running = existing("50", true, MilestoneExecutionStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.completeMilestone(planned.getId(), completeRequest(null, 0), pm))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.completeMilestone(running.getId(),
                completeRequest(LocalDate.now().plusDays(1), 0), pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void cancellingExcludesAllocationButDoesNotAffectOthersBilling() {
        ProjectMilestone done = existing("70", true, MilestoneExecutionStatus.COMPLETED);
        ProjectMilestone other = existing("30", true, MilestoneExecutionStatus.PLANNED);
        ProjectMilestoneServiceImpl.recomputeReadiness(stored);
        assertThat(done.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.READY_FOR_BILLING);

        MilestoneResponse cancelled = service.changeStatus(other.getId(),
                statusRequest(MilestoneExecutionStatus.CANCELLED, 0), pm);

        assertThat(cancelled.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.NOT_BILLABLE);
        assertThat(cancelled.getAllocationPercentage()).isEqualByComparingTo("30");
        assertThat(done.getBillingReadiness()).isEqualTo(MilestoneBillingReadiness.READY_FOR_BILLING);
        assertThat(service.getSummary(PROJECT_ID, pm).getAllocatedPercentage()).isEqualByComparingTo("70");
    }

    /* ---------------- updates ---------------- */

    @Test
    void editingKeepsIdentityAndAllocation() {
        ProjectMilestone m = existing("40", true, MilestoneExecutionStatus.PLANNED);
        Integer internalOrder = m.getSequenceOrder();
        MilestoneUpdateRequest request = updateRequestFrom(m);
        request.setName("Renamed");

        MilestoneResponse response = service.updateMilestone(m.getId(), request, pm);

        assertThat(response.getName()).isEqualTo("Renamed");
        assertThat(m.getSequenceOrder()).isEqualTo(internalOrder);
        assertThat(response.getAllocationPercentage()).isEqualByComparingTo("40");
        assertThat(response.getId()).isEqualTo(m.getId());
        assertThat(response.getMilestoneCode()).isEqualTo(m.getMilestoneCode());
    }

    @Test
    void completedMilestoneIsReadOnly() {
        ProjectMilestone m = existing("40", true, MilestoneExecutionStatus.COMPLETED);
        String originalName = m.getName();
        MilestoneUpdateRequest request = updateRequestFrom(m);
        request.setName("Renamed after completion");

        assertThatThrownBy(() -> service.updateMilestone(m.getId(), request, pm))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(errorsOf(t)).containsExactly("Completed milestones can't be changed"));
        assertThat(m.getName()).isEqualTo(originalName);

        MilestoneResponse view = service.getMilestone(m.getId(), pm);
        assertThat(view.isCanEdit()).isFalse();
        assertThat(view.isCanDelete()).isFalse();
        assertThat(view.isCanComplete()).isFalse();
        assertThat(view.getAllowedStatusChanges()).isEmpty();
    }

    @Test
    void allocationIsLockedAfterCompletion() {
        ProjectMilestone m = existing("40", true, MilestoneExecutionStatus.COMPLETED);
        MilestoneUpdateRequest request = updateRequestFrom(m);
        request.setAllocationPercentage(new BigDecimal("50"));

        assertThatThrownBy(() -> service.updateMilestone(m.getId(), request, pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void updateRejectsAllocationAbove100Percent() {
        existing("70", true, MilestoneExecutionStatus.PLANNED);
        ProjectMilestone m = existing("30", true, MilestoneExecutionStatus.PLANNED);
        MilestoneUpdateRequest request = updateRequestFrom(m);
        request.setAllocationPercentage(new BigDecimal("31"));

        assertThatThrownBy(() -> service.updateMilestone(m.getId(), request, pm))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void staleVersionIsRejected() {
        ProjectMilestone m = existing("40", true, MilestoneExecutionStatus.PLANNED);
        m.setVersion(3L);
        MilestoneUpdateRequest request = updateRequestFrom(m);
        request.setVersion(2L);

        assertThatThrownBy(() -> service.updateMilestone(m.getId(), request, pm))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void onlyPlannedMilestonesCanBeDeleted() {
        ProjectMilestone running = existing("40", true, MilestoneExecutionStatus.IN_PROGRESS);
        ProjectMilestone planned = existing("10", true, MilestoneExecutionStatus.PLANNED);

        assertThatThrownBy(() -> service.deleteMilestone(running.getId(), pm))
                .isInstanceOf(ValidationException.class);
        service.deleteMilestone(planned.getId(), pm);
        assertThat(stored).containsExactly(running);
    }

    /* ---------------- money ---------------- */

    @Test
    void estimatedAmountRoundsHalfUpToTwoDecimals() {
        assertThat(ProjectMilestoneServiceImpl.estimateAmount(new BigDecimal("1000.00"), new BigDecimal("33.33")))
                .isEqualByComparingTo("333.30");
        assertThat(ProjectMilestoneServiceImpl.estimateAmount(new BigDecimal("10.01"), new BigDecimal("12.50")))
                .isEqualByComparingTo("1.25");
        assertThat(ProjectMilestoneServiceImpl.estimateAmount(null, new BigDecimal("10"))).isNull();
    }
}
