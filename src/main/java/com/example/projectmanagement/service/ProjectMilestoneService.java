package com.example.projectmanagement.service;

import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.dto.milestone.*;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProjectMilestoneService {

    Page<MilestoneResponse> listMilestones(Long projectId, MilestoneExecutionStatus status,
                                           MilestoneBillingReadiness readiness, Pageable pageable,
                                           UserDto currentUser);

    MilestoneSummaryResponse getSummary(Long projectId, UserDto currentUser);

    MilestoneResponse getMilestone(Long milestoneId, UserDto currentUser);

    MilestoneResponse createMilestone(Long projectId, MilestoneCreateRequest request, UserDto currentUser);

    MilestoneResponse updateMilestone(Long milestoneId, MilestoneUpdateRequest request, UserDto currentUser);

    MilestoneResponse changeStatus(Long milestoneId, MilestoneStatusChangeRequest request, UserDto currentUser);

    MilestoneResponse completeMilestone(Long milestoneId, MilestoneCompleteRequest request, UserDto currentUser);

    void deleteMilestone(Long milestoneId, UserDto currentUser);
}
