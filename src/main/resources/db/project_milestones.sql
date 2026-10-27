-- Project milestones (PMS). Mirrors entity ProjectMilestone.
-- Hibernate (ddl-auto=update) creates this table automatically in dev; run this script
-- manually in environments where JPA_DDL is validate/none, BEFORE deploying the PMS build.
-- The table is captured by AR's Debezium connector (project-cdc.table-include-list);
-- its column names are part of the PMS -> AR CDC contract. Do not add a client_id column.

CREATE TABLE IF NOT EXISTS project_milestones (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    project_id              BIGINT        NOT NULL,
    epic_id                 BIGINT        NULL,
    milestone_code          VARCHAR(50)   NOT NULL,
    name                    VARCHAR(200)  NOT NULL,
    description             VARCHAR(2000) NULL,
    sequence_order          INT           NOT NULL,
    planned_start_date      DATE          NULL,
    planned_due_date        DATE          NULL,
    actual_completion_date  DATE          NULL,
    execution_status        ENUM('PLANNED','IN_PROGRESS','COMPLETED','ON_HOLD','CANCELLED') NOT NULL,
    billable                BIT           NOT NULL,
    allocation_percentage   DECIMAL(5,2)  NOT NULL,
    billing_readiness       ENUM('NOT_BILLABLE','NOT_READY','READY_FOR_BILLING') NOT NULL,
    version                 BIGINT        NOT NULL,
    created_by              BIGINT        NULL,
    updated_by              BIGINT        NULL,
    completed_by            BIGINT        NULL,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_milestone_project_code UNIQUE (project_id, milestone_code),
    CONSTRAINT fk_milestone_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_milestone_epic FOREIGN KEY (epic_id) REFERENCES epic (id),
    CONSTRAINT chk_milestone_allocation_range CHECK (allocation_percentage >= 0 AND allocation_percentage <= 100),
    CONSTRAINT chk_milestone_planned_dates CHECK (planned_due_date IS NULL OR planned_start_date IS NULL
                                                  OR planned_due_date >= planned_start_date),
    INDEX idx_milestone_project_sequence (project_id, sequence_order),
    INDEX idx_milestone_project_status (project_id, execution_status),
    INDEX idx_milestone_project_readiness (project_id, billing_readiness)
) ENGINE = InnoDB;
