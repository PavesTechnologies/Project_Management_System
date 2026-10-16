package com.example.projectmanagement.audit.base;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_trail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_name", nullable = false, length = 150)
    private AuditEntityType entityName;

    public enum AuditEntityType {
        TASK,
        EPIC,
        STORY,
        BUG,
        SPRINT, COMMENT,
        MILESTONE
    }


    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "old_data", columnDefinition = "JSON")
    private String oldData;

    @Column(name = "new_data", columnDefinition = "JSON")
    private String newData;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "host")
    private String host;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "timestamp", updatable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
}
