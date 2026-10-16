package com.example.projectmanagement.dto.milestone;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MilestoneCreateRequest {

    /** Optional; generated as {projectKey}-M{nnn} when blank. Immutable after creation. */
    @Size(max = 50, message = "Milestone code must be at most 50 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9_-]*$",
            message = "Milestone code may contain only letters, digits, '-' and '_'")
    private String milestoneCode;

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

    @DecimalMin(value = "0.00", message = "Allocation percentage cannot be negative")
    @DecimalMax(value = "100.00", message = "Allocation percentage cannot exceed 100")
    @Digits(integer = 3, fraction = 2, message = "Allocation percentage allows at most 2 decimal places")
    private BigDecimal allocationPercentage;
}
