package com.example.projectmanagement.dto.milestone;

import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MilestoneStatusChangeRequest {

    /** Target status. COMPLETED is not accepted here; use the complete action. */
    @NotNull(message = "Status is required")
    private MilestoneExecutionStatus status;

    @NotNull(message = "Version is required")
    private Long version;
}
