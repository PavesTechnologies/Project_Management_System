package com.example.projectmanagement.repository;

import com.example.projectmanagement.entity.ProjectMilestone;
import com.example.projectmanagement.enums.MilestoneBillingReadiness;
import com.example.projectmanagement.enums.MilestoneExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMilestoneRepository extends JpaRepository<ProjectMilestone, Long> {

    @Query(value = "SELECT m FROM ProjectMilestone m LEFT JOIN FETCH m.epic " +
            "WHERE m.project.id = :projectId " +
            "AND (:status IS NULL OR m.executionStatus = :status) " +
            "AND (:readiness IS NULL OR m.billingReadiness = :readiness)",
            countQuery = "SELECT COUNT(m) FROM ProjectMilestone m " +
                    "WHERE m.project.id = :projectId " +
                    "AND (:status IS NULL OR m.executionStatus = :status) " +
                    "AND (:readiness IS NULL OR m.billingReadiness = :readiness)")
    Page<ProjectMilestone> findByProjectFiltered(@Param("projectId") Long projectId,
                                                 @Param("status") MilestoneExecutionStatus status,
                                                 @Param("readiness") MilestoneBillingReadiness readiness,
                                                 Pageable pageable);

    @Query("SELECT m FROM ProjectMilestone m WHERE m.project.id = :projectId")
    List<ProjectMilestone> findAllByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT m FROM ProjectMilestone m LEFT JOIN FETCH m.epic JOIN FETCH m.project WHERE m.id = :id")
    Optional<ProjectMilestone> findWithDetailsById(@Param("id") Long id);

    boolean existsByProjectIdAndMilestoneCode(Long projectId, String milestoneCode);

    long countByProjectId(Long projectId);

    @Query("SELECT COALESCE(MAX(m.sequenceOrder), 0) FROM ProjectMilestone m WHERE m.project.id = :projectId")
    int findMaxSequenceOrder(@Param("projectId") Long projectId);

    /** Unlinks an epic before it is deleted; bumps version so AR's stale-event check accepts the change. */
    @Transactional
    @Modifying
    @Query("UPDATE ProjectMilestone m SET m.epic = NULL, m.version = m.version + 1 WHERE m.epic.id = :epicId")
    int clearEpic(@Param("epicId") Long epicId);
}
