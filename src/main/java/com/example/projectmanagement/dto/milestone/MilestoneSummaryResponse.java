package com.example.projectmanagement.dto.milestone;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MilestoneSummaryResponse {
    private Long projectId;
    private long total;
    private long planned;
    private long inProgress;
    private long onHold;
    private long completed;
    private long cancelled;
    private long readyForBilling;
    /** Sum of allocation % over non-cancelled billable milestones. */
    private BigDecimal allocatedPercentage;
    private BigDecimal remainingPercentage;
    private boolean allocationComplete;
    private BigDecimal projectBudget;
    /** Sum of the cascading estimates of active billable milestones. */
    private BigDecimal allocatedAmount;
    /** Budget still unbilled: the base the next milestone's % will be applied to. */
    private BigDecimal remainingAmount;
    private String currency;
}
