package com.example.projectmanagement.service;

import com.example.projectmanagement.dto.UserDto;
import com.example.projectmanagement.entity.Project;
import com.example.projectmanagement.exception.ForbiddenException;
import com.example.projectmanagement.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Project-level authorization guard. Role names are normalised exactly like the
 * JWT authorities converter in Securityconfig ("Project Manager" -> PROJECT_MANAGER).
 */
@Service
public class ProjectAccessService {

    public static final String PROJECT_MANAGER = "PROJECT_MANAGER";

    private final ProjectRepository projectRepository;

    public ProjectAccessService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * Returns the project when the user holds the PROJECT_MANAGER role and is the
     * project's owner or one of its members; otherwise throws ForbiddenException.
     */
    public Project requireProjectManagerAccess(Long projectId, UserDto user) {
        if (user == null || user.getId() == null) {
            throw new ForbiddenException("Authentication required");
        }
        if (!hasRole(user, PROJECT_MANAGER)) {
            throw new ForbiddenException("Only project managers can manage milestones");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));

        if (!Objects.equals(project.getOwnerId(), user.getId())) {
            Set<Long> members = projectRepository.findMemberIdsByProjectId(projectId);
            if (members == null || !members.contains(user.getId())) {
                throw new ForbiddenException("You do not have access to this project");
            }
        }
        return project;
    }

    public static boolean hasRole(UserDto user, String role) {
        List<String> roles = user.getRoles();
        if (roles == null) return false;
        return roles.stream()
                .filter(Objects::nonNull)
                .map(r -> r.replace(" ", "_").toUpperCase())
                .anyMatch(role::equals);
    }
}
