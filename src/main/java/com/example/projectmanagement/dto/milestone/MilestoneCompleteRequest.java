package com.example.projectmanagement.dto.milestone;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Data;

import java.time.LocalDate;

@Data
public class MilestoneCompleteRequest {

    /** Defaults to today when omitted. */
    @PastOrPresent(message = "Completion date cannot be in the future")
    private LocalDate actualCompletionDate;

    @NotNull(message = "Version is required")
    private Long version;
}
