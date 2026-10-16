package com.example.projectmanagement.dto.milestone;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Editable milestone details. Execution status, completion data, code and
 * billing readiness are deliberately absent: they change only through
 * dedicated actions or are derived by the backend.
 */
@Data
public class MilestoneUpdateRequest {

    @NotBlank(message = "Milestone name is required")
    @Size(max = 200, message = "Milestone name must be at most 200 characters")
    private String name;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    private Long epicId;

    private LocalDate plannedStartDate;

    private LocalDate plannedDueDate;

    @NotNull(message = "Billable flag is required")
    private Boolean billable;

    @NotNull(message = "Allocation percentage is required")
    @DecimalMin(value = "0.00", message = "Allocation percentage cannot be negative")
    @DecimalMax(value = "100.00", message = "Allocation percentage cannot exceed 100")
    @Digits(integer = 3, fraction = 2, message = "Allocation percentage allows at most 2 decimal places")
    private BigDecimal allocationPercentage;

    /** Version the client last read; a mismatch returns 409. */
    @NotNull(message = "Version is required")
    private Long version;
}
