package com.example.projectmanagement.config;

import com.example.projectmanagement.service.ProjectMilestoneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Starts any milestone whose planned start date has already passed, once, at startup.
 * <p>
 * {@code @Scheduled} never replays a slot it missed, so an outage across the hourly
 * {@code MilestoneAutoStartScheduler} run would otherwise leave milestones PLANNED until the next
 * hour. This makes the app correct the moment it comes back up instead. Idempotent and safe to run
 * alongside the scheduler: both delegate to the same query, which matches only PLANNED milestones.
 */
@Slf4j
@Component
public class MilestoneAutoStartRunner implements ApplicationRunner {

    private final ProjectMilestoneService milestoneService;

    public MilestoneAutoStartRunner(ProjectMilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int started = milestoneService.startDueMilestones(LocalDate.now());
            if (started > 0) {
                log.info("Milestone auto-start catch-up started {} milestone(s) at boot", started);
            }
        } catch (Exception e) {
            // Never block startup: the hourly scheduler retries within the hour.
            log.warn("Milestone auto-start catch-up skipped: {}", e.getMessage());
        }
    }
}
