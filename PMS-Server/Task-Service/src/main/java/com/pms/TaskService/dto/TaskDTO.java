package com.pms.TaskService.dto;

import com.pms.TaskService.entities.enums.Priority;
import com.pms.TaskService.entities.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for Task entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDTO {

    private String id;
    private String title;
    private String description;
    private String projectId;
    private List<String> assignees = new ArrayList<>();
    private String creator;
    private LocalDate createdDate;
    private LocalDate updatedDate;
    private LocalDate deadline;
    private Status status;
    private Priority priority;
    private Long completionPercent;
    private boolean isBlocking;
    private String epicId;
    private String storyId;
    private List<String> subTaskIds = new ArrayList<>();
}
