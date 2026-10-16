package com.example.projectmanagement.config;

import com.example.projectmanagement.repository.ProjectMilestoneRepository;
import com.example.projectmanagement.service.impl.ProjectMilestoneServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-evaluates the stored billing_readiness of every milestone at startup so rows saved
 * under an earlier readiness rule match the current one. Idempotent: unchanged rows are not written.
 */
@Slf4j
@Component
public class MilestoneReadinessBackfillRunner implements ApplicationRunner {

    private final ProjectMilestoneRepository milestoneRepository;

    public MilestoneReadinessBackfillRunner(ProjectMilestoneRepository milestoneRepository) {
        this.milestoneRepository = milestoneRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int changed = ProjectMilestoneServiceImpl.recomputeReadiness(milestoneRepository.findAll());
            if (changed > 0) {
                log.info("Milestone readiness backfill updated {} milestone(s)", changed);
            }
        } catch (Exception e) {
            // Never block startup over a derived column; the next write to the project recomputes it.
            log.warn("Milestone readiness backfill skipped: {}", e.getMessage());
        }
    }
}
