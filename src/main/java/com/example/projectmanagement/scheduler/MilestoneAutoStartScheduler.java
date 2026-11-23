package com.example.projectmanagement.scheduler;

import com.example.projectmanagement.service.ProjectMilestoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Starts milestones on their planned start date so a project manager does not have to.
 * <p>
 * Only PLANNED milestones are picked up; ON_HOLD is left alone because a pause is a human
 * decision. Idempotent: once a milestone is IN_PROGRESS it no longer matches the query, so a
 * re-run (or a catch-up run after downtime) changes nothing.
 * <p>
 * Missed runs need no replay: the query selects {@code plannedStartDate <= today}, so whatever
 * an outage skipped is picked up by the next run. Running hourly bounds how late that can be,
 * and {@code MilestoneAutoStartRunner} closes the remaining gap at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneAutoStartScheduler {

    private final ProjectMilestoneService milestoneService;

    /** Hourly at :10. Override via the milestone.autostart.scheduler.cron property. */
    @Scheduled(cron = "${milestone.autostart.scheduler.cron:0 10 0 * * *}")
    public void startDueMilestones() {
        LocalDate today = LocalDate.now();
        log.info("=== MilestoneAutoStartScheduler FIRED — starting milestones planned on or before {} ===", today);
        try {
            int started = milestoneService.startDueMilestones(today);
            log.info("=== MilestoneAutoStartScheduler DONE — {} milestone(s) started ===", started);
        } catch (Exception e) {
            log.error("=== MilestoneAutoStartScheduler ERROR: {} ===", e.getMessage(), e);
        }
    }
}
