package com.example.projectmanagement.enums;

import java.util.EnumSet;
import java.util.Set;

public enum MilestoneExecutionStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    ON_HOLD,
    CANCELLED;

    /**
     * Transitions allowed through the generic status-change endpoint.
     * COMPLETED is reachable only through the dedicated complete action,
     * and COMPLETED / CANCELLED are terminal.
     */
    public Set<MilestoneExecutionStatus> allowedStatusChanges() {
        return switch (this) {
            case PLANNED -> EnumSet.of(IN_PROGRESS, ON_HOLD, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(ON_HOLD, CANCELLED);
            case ON_HOLD -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case COMPLETED, CANCELLED -> EnumSet.noneOf(MilestoneExecutionStatus.class);
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
