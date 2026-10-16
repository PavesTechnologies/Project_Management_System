package com.example.projectmanagement.service;

import com.example.projectmanagement.dto.BulkDeleteResultDto;
import com.example.projectmanagement.dto.EpicDto;
import com.example.projectmanagement.entity.*;
import com.example.projectmanagement.dto.*;
//import com.example.projectmanagement.entity.Epic.EpicStatus;
import com.example.projectmanagement.entity.Epic.Priority;
import com.example.projectmanagement.repository.*;
import com.example.projectmanagement.repository.StoryRepository;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EpicService {

    @Autowired
    private EpicRepository epicRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private StoryRepository storyRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private RiskLinkRepository riskLinkRepository;

    @Autowired
    private RiskRepository riskRepository;

    @Autowired
    private MitigationPlanRepository mitigationPlanRepository;

    @Autowired
    private RiskAttachmentRepository riskAttachmentRepository;

    @Autowired
    private ProjectMilestoneRepository projectMilestoneRepository;

    // ✅ Create Epic
    public EpicDto createEpic(EpicDto epicDto, Long userId) {

        Epic epic = convertToEntity(epicDto);
        epic.setCreatedBy(userId);

        boolean exists = epicRepository.existsByNameAndProjectId(epic.getName(), epic.getProject().getId());
        if (exists) {
            throw new IllegalArgumentException("Epic name already exists in this project");
        }

        // Set dynamic status
        if (epicDto.getStatusId() != null) {
            Status status = statusRepository.findById(epicDto.getStatusId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid status"));
            epic.setStatus(status);
        }

        Epic savedEpic = epicRepository.save(epic);
        return convertToDtoBatch(List.of(savedEpic)).get(0);
    }

    // ✅ Get All Epics
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EpicDto> getAllEpics() {
        return convertToDtoBatch(epicRepository.findAll());
    }

    // ✅ Get by ID
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public EpicDto getEpicById(Long id) {
        Optional<Epic> optionalEpic = epicRepository.findById(id);
        return optionalEpic.map(epic -> convertToDtoBatch(List.of(epic)).get(0)).orElse(null);
    }

    // ✅ Update Epic
    public EpicDto updateEpic(Long id, EpicDto epicDto) {
        Optional<Epic> optionalEpic = epicRepository.findById(id);
        if (optionalEpic.isPresent()) {
            Epic existingEpic = optionalEpic.get();

            existingEpic.setName(epicDto.getName());
            existingEpic.setDescription(epicDto.getDescription());

            // ✅ Convert String to Enum
            if (epicDto.getStatusId() != null) {
                Status status = statusRepository.findById(epicDto.getStatusId())
                        .orElseThrow(() -> new IllegalArgumentException("Invalid status"));
                validateEpicStatusPromotion(existingEpic, status);
                existingEpic.setStatus(status);
            }

            if (epicDto.getPriority() != null) {
                existingEpic.setPriority(Epic.Priority.valueOf(epicDto.getPriority()));
            }

            // ✅ Convert LocalDateTime to LocalDate
            if (epicDto.getDueDate() != null) {
                existingEpic.setDueDate(epicDto.getDueDate().toLocalDate());
            }
            if (epicDto.getStartDate() != null) {
                existingEpic.setStartDate(epicDto.getStartDate());
            }
            if (epicDto.getProjectId() != null) {
                Project project = projectRepository.findById(epicDto.getProjectId()).orElse(null);
                existingEpic.setProject(project);
            }

            Epic updatedEpic = epicRepository.save(existingEpic);
            return convertToDtoBatch(List.of(updatedEpic)).get(0);
        }
        return null;
    }

    // ✅ Delete Epic
    @Transactional
    public boolean deleteEpic(Long id) {

        Epic epic = epicRepository.findById(id)
                .orElse(null);

        if (epic == null) {
            return false;
        }

        /*
         * DEMO BEHAVIOR:
         * Currently, when an Epic is deleted, all Risks linked to this Epic
         * are also deleted along with their child records:
         * RiskLink, MitigationPlan, and RiskAttachment.
         *
         * TODO:
         * In production, do not directly hard-delete Risks here.
         * Instead:
         * 1. Ask user whether to delete risks or only unlink them
         * 2. Prefer soft delete for Risk records
         * 3. If the Risk is linked to other Story/Task/Sprint items,
         * only delete this RiskLink and keep the Risk
         * 4. Preserve mitigation plans and attachments for audit/history if needed
         */
        List<RiskLink> riskLinks = riskLinkRepository.findByLinkedTypeAndLinkedId(
                RiskLink.LinkedType.Epic,
                id);

        if (!riskLinks.isEmpty()) {

            List<Risk> risksToDelete = riskLinks.stream()
                    .map(RiskLink::getRisk)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            // 1. Delete risk links first
            riskLinkRepository.deleteAll(riskLinks);

            // 2. Delete child records of risks before deleting risks
            mitigationPlanRepository.deleteByRiskIn(risksToDelete);
            riskAttachmentRepository.deleteByRiskIn(risksToDelete);

            // 3. Delete associated risks
            riskRepository.deleteAll(risksToDelete);
        }

        // Milestones may optionally reference an epic; unlink them so the FK does not block deletion.
        projectMilestoneRepository.clearEpic(id);

        epicRepository.delete(epic);

        return true;
    }

    public BulkDeleteResultDto bulkDeleteEpics(List<Long> ids) {
        List<Long> deletedIds = new ArrayList<>();
        List<Long> notFoundIds = new ArrayList<>();
        for (Long id : ids) {
            boolean deleted = deleteEpic(id);
            if (deleted) {
                deletedIds.add(id);
            } else {
                notFoundIds.add(id);
            }
        }
        String message = deletedIds.size() + " epic(s) deleted successfully" +
                (notFoundIds.isEmpty() ? "." : "; " + notFoundIds.size() + " epic(s) not found.");
        return new BulkDeleteResultDto(deletedIds.size(), notFoundIds.size(), deletedIds, notFoundIds, message);
    }

    // ✅ DTO Conversion (batched: computes progress for a whole list of epics using
    // a constant number of queries instead of 2 extra queries per epic)
    private List<EpicDto> convertToDtoBatch(List<Epic> epics) {
        if (epics.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toList());
        List<Long> projectIds = epics.stream()
                .filter(e -> e.getProject() != null)
                .map(e -> e.getProject().getId())
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Integer> maxSortOrderByProject = projectIds.isEmpty() ? Collections.emptyMap()
                : statusRepository.findMaxSortOrderByProjectIds(projectIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Integer) row[1]));

        List<Story> stories = storyRepository.findByEpicIdIn(epicIds);
        Map<Long, List<Story>> storiesByEpicId = stories.stream()
                .collect(Collectors.groupingBy(s -> s.getEpic().getId()));

        List<Long> storyIds = stories.stream().map(Story::getId).collect(Collectors.toList());
        Map<Long, List<Task>> tasksByStoryId = storyIds.isEmpty() ? Collections.emptyMap()
                : taskRepository.findByStoryIdIn(storyIds).stream()
                        .collect(Collectors.groupingBy(t -> t.getStory().getId()));

        return epics.stream()
                .map(epic -> convertToDto(epic, storiesByEpicId, tasksByStoryId, maxSortOrderByProject))
                .collect(Collectors.toList());
    }

    private EpicDto convertToDto(Epic epic, Map<Long, List<Story>> storiesByEpicId,
            Map<Long, List<Task>> tasksByStoryId, Map<Long, Integer> maxSortOrderByProject) {
        EpicDto dto = new EpicDto();
        dto.setId(epic.getId());
        dto.setName(epic.getName());
        dto.setDescription(epic.getDescription());
        dto.setStartDate(epic.getStartDate());
        dto.setCreatedBy(epic.getCreatedBy());
        // Convert enum to String
        if (epic.getStatus() != null) {
            dto.setStatusId(epic.getStatus().getId());
            dto.setStatusName(epic.getStatus().getName());
        }

        if (epic.getPriority() != null) {
            dto.setPriority(epic.getPriority().name()); // Priority to String
        }

        dto.setProgressPercentage(
                calculateProgressPercentage(epic, storiesByEpicId, tasksByStoryId, maxSortOrderByProject));

        // Convert LocalDate to LocalDateTime for DTO
        if (epic.getDueDate() != null) {
            dto.setDueDate(epic.getDueDate().atStartOfDay()); // LocalDate -> LocalDateTime
        }

        if (epic.getProject() != null) {
            dto.setProjectId(epic.getProject().getId());
        }

        return dto;
    }

    private Epic convertToEntity(EpicDto dto) {
        Epic epic = new Epic();
        epic.setName(dto.getName());
        epic.setDescription(dto.getDescription());
        epic.setStartDate(dto.getStartDate());

        // Convert String to Enum safely
        if (epic.getStatus() != null) {
            dto.setStatusId(epic.getStatus().getId());
            dto.setStatusName(epic.getStatus().getName());
        }

        if (dto.getPriority() != null) {
            epic.setPriority(Epic.Priority.valueOf(dto.getPriority().toUpperCase()));
        }

        // Convert LocalDateTime to LocalDate
        if (dto.getDueDate() != null) {
            epic.setDueDate(dto.getDueDate().toLocalDate());
        }

        if (dto.getProjectId() != null) {
            Project project = projectRepository.findById(dto.getProjectId()).orElse(null);
            epic.setProject(project);
        }

        return epic;
    }

    private Integer calculateProgressPercentage(Epic epic, Map<Long, List<Story>> storiesByEpicId,
            Map<Long, List<Task>> tasksByStoryId, Map<Long, Integer> maxSortOrderByProject) {
        if (epic.getId() == null || epic.getProject() == null) return 0;

        Integer maxSortOrder = maxSortOrderByProject.get(epic.getProject().getId());
        if (maxSortOrder == null) return 0;

        List<Story> stories = storiesByEpicId.getOrDefault(epic.getId(), Collections.emptyList());
        if (stories.isEmpty()) return 0;

        int totalItems = 0;
        int completedItems = 0;

        for (Story story : stories) {
            totalItems++;
            if (story.getStatus() != null && story.getStatus().getSortOrder() >= maxSortOrder) {
                completedItems++;
            }

            List<Task> tasks = tasksByStoryId.getOrDefault(story.getId(), Collections.emptyList());
            for (Task task : tasks) {
                totalItems++;
                if (task.getStatus() != null && task.getStatus().getSortOrder() >= maxSortOrder) {
                    completedItems++;
                }
            }
        }

        if (totalItems == 0) return 0;
        return (int) Math.round((completedItems * 100.0) / totalItems);
    }

    private void validateEpicStatusPromotion(Epic epic, Status newStatus) {
        if (epic == null || newStatus == null) {
            return;
        }

        Status currentStatus = epic.getStatus();
        if (currentStatus == null || newStatus.getSortOrder() > currentStatus.getSortOrder()) {
            long lowerStoryCount = epic.getId() == null ? 0L
                    : storyRepository.countByEpicIdAndStatusSortOrderLessThan(epic.getId(), newStatus.getSortOrder());
            if (lowerStoryCount > 0) {
                throw new IllegalArgumentException("Cannot advance epic to status '" + newStatus.getName()
                        + "' while some related stories remain in a lower status.");
            }
        }
    }

    // ✅ Optional methods to implement
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EpicDto> getEpicsByProjectId(Long projectId) {
        return convertToDtoBatch(epicRepository.findByProjectId(projectId));
    }

    public List<EpicDto> getEpicsByOrganizationId(Long organizationId) {
        // Assuming you have org info in project or epics
        throw new UnsupportedOperationException("Unimplemented method 'getEpicsByOrganizationId'");
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EpicDto> getEpicsByStatus(Long statusId) {
        Status status = statusRepository.findById(statusId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid status ID"));

        return convertToDtoBatch(epicRepository.findByStatus(status));
    }

    public Page<EpicDto> searchEpics(String name, Priority priority, Long projectId, Pageable pageable) {
        throw new UnsupportedOperationException("Unimplemented method 'searchEpics'");
    }

}
