package com.example.projectmanagement.audit.aspect;

import com.example.projectmanagement.audit.annotation.AuditLog;
import com.example.projectmanagement.audit.base.AuditTrail;
import com.example.projectmanagement.audit.base.AuditTrailRepository;
import com.example.projectmanagement.audit.dynamic.DynamicAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
public class AuditAspect {

    private final AuditTrailRepository auditTrailRepository;
    private final DynamicAuditService dynamicAuditService;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditTrailRepository auditTrailRepository,
                       DynamicAuditService dynamicAuditService) {
        this.auditTrailRepository = auditTrailRepository;
        this.dynamicAuditService = dynamicAuditService;

        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper = om;

        System.out.println("🔥 AuditAspect bean loaded");
    }

    @Around("@annotation(auditLog) && !within(org.springframework.data.repository.Repository+)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        return processAudit(joinPoint, auditLog);
    }

    @Around("@within(auditLog) && !within(org.springframework.data.repository.Repository+)")
    public Object auditClass(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        return processAudit(joinPoint, auditLog);
    }

    private Object processAudit(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {

        String operation = determineOperation();
        if (operation.equals("UNKNOWN")) return joinPoint.proceed();

        String rawEntity = auditLog.entity();
        String normalizedEntity = normalizeEntityName(rawEntity);

        Long entityIdFromArgs = extractEntityIdFromArgs(joinPoint);

        // Build table name: Story -> stories
        String tableName = toTableName(normalizedEntity);


        // ---------------- FETCH OLD SNAPSHOT --------------------
        Map<String, Object> oldSnapshot = null;
        if (operation.equals("UPDATE") || operation.equals("DELETE")) {
            try {
                oldSnapshot = dynamicAuditService.getRawRow(tableName, entityIdFromArgs);
                System.out.println("OLD SNAPSHOT = " + oldSnapshot);
            } catch (Exception e) {
                System.out.println("OLD SNAPSHOT FETCH FAILED: " + e.getMessage());
            }
        }

        // ---------------- Proceed with operation ----------------
        Object response = joinPoint.proceed();

        Long entityId = extractEntityIdFromResponse(response);
        if (entityId == 0L) entityId = entityIdFromArgs;

        // ---------------- FETCH NEW SNAPSHOT --------------------
        Map<String, Object> newSnapshot = null;
        if (operation.equals("CREATE") || operation.equals("UPDATE")) {
            try {
                newSnapshot = dynamicAuditService.getRawRow(tableName, entityId);
                System.out.println("NEW SNAPSHOT = " + newSnapshot);
            } catch (Exception e) {
                System.out.println("NEW SNAPSHOT FETCH FAILED: " + e.getMessage());
            }
        }

        // ---------------- Compute DIFFERENCES --------------------
        String oldDataJson = "{}";
        String newDataJson = "{}";

        if (operation.equals("UPDATE") && oldSnapshot != null && newSnapshot != null) {

            Map<String, Object> oldDiff = new HashMap<>();
            Map<String, Object> newDiff = new HashMap<>();

            for (String key : oldSnapshot.keySet()) {
                Object ov = oldSnapshot.get(key);
                Object nv = newSnapshot.get(key);

                if (!java.util.Objects.equals(ov, nv)) {
                    oldDiff.put(key, ov);
                    newDiff.put(key, nv);
                }
            }

            System.out.println("OLD CHANGED VALUES: " + oldDiff);
            System.out.println("NEW CHANGED VALUES: " + newDiff);

            oldDataJson = objectMapper.writeValueAsString(oldDiff);
            newDataJson = objectMapper.writeValueAsString(newDiff);
        }

        else if (operation.equals("CREATE")) {
            newDataJson = objectMapper.writeValueAsString(newSnapshot);
        }

        else if (operation.equals("DELETE")) {
            oldDataJson = objectMapper.writeValueAsString(oldSnapshot);
        }

        // ---------------- Save Audit Logs --------------------
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest req = (attrs != null) ? attrs.getRequest() : null;

        dynamicAuditService.saveEntityAudit(
                normalizedEntity,
                String.valueOf(entityId),
                req != null ? req.getRemoteAddr() : null,        // ipAddress
                LocalDateTime.now(),                              // timestamp
                operation,
                oldDataJson,
                newDataJson,
                req != null ? req.getRemoteHost() : null,        // host
                getUserIdFromJwt(),                               // userId
                req != null ? req.getRequestURI() : null         // endpoint
        );

        System.out.println("======= AUDIT ASPECT END =======");

        return response;
    }

    private Long getUserIdFromJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            if (jwt.hasClaim("user_id")) {
                return Long.valueOf(jwt.getClaimAsString("user_id"));
            }
        }
        return 0L;
    }

    private Long extractEntityIdFromArgs(ProceedingJoinPoint jp) {
        for (Object arg : jp.getArgs()) {
            if (arg == null) continue;
            if (arg instanceof Number) return ((Number) arg).longValue();

            try {
                Method m = arg.getClass().getMethod("getId");
                Object val = m.invoke(arg);
                if (val instanceof Number) return ((Number) val).longValue();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private Long extractEntityIdFromResponse(Object response) {
        if (response == null) return 0L;

        try {
            if (response instanceof org.springframework.http.ResponseEntity<?> re) {
                return extractEntityIdFromResponse(re.getBody());
            }

            Map<String, Object> map = objectMapper.convertValue(
                    response, new TypeReference<Map<String, Object>>() {}
            );

            if (map.containsKey("id")) {
                Object idValue = map.get("id");
                if (idValue instanceof Number) return ((Number) idValue).longValue();
            }

            Method m = response.getClass().getMethod("getId");
            Object val = m.invoke(response);
            if (val instanceof Number) return ((Number) val).longValue();

        } catch (Exception ignored) {}

        return 0L;
    }

    private String determineOperation() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest req = (attrs != null) ? attrs.getRequest() : null;
        if (req == null) return "UNKNOWN";

        return switch (req.getMethod().toUpperCase()) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> "UNKNOWN";
        };
    }

    private String normalizeEntityName(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        raw = raw.trim().toLowerCase();
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private String toTableName(String entity) {
        return switch (entity) {
            case "Story" -> "stories";
            case "Epic" -> "epic";
            case "Task" -> "tasks";
            case "Project" -> "projects";
            case "Sprint" -> "sprints";
            case "Comment" -> "comment";
            case "Status" -> "statuses";
            case "Milestone" -> "project_milestones";
            default -> entity.toLowerCase();
        };
    }


}
