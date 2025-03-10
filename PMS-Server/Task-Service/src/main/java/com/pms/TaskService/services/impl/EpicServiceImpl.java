package com.pms.TaskService.services.impl;

import com.pms.TaskService.clients.ProjectFeignClient;
import com.pms.TaskService.clients.UserFeignClient;
import com.pms.TaskService.dto.EpicDTO;
import com.pms.TaskService.dto.ProjectDTO;
import com.pms.TaskService.dto.UserDTO;
import com.pms.TaskService.entities.Epic;
import com.pms.TaskService.entities.enums.Status;
import com.pms.TaskService.event.TaskEvent;
import com.pms.TaskService.event.enums.Actions;
import com.pms.TaskService.event.enums.EventType;
import com.pms.TaskService.exceptions.ResourceNotFound;
import com.pms.TaskService.producer.CalendarEventProducer;
import com.pms.TaskService.producer.TaskEventProducer;
import com.pms.TaskService.repository.EpicRepository;
import com.pms.TaskService.services.EpicService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EpicServiceImpl implements EpicService {

    private final ModelMapper modelMapper;
    private final EpicRepository epicRepository;
    private final TaskEventProducer producer;
    private final ProjectFeignClient projectFeignClient;
    private final UserFeignClient userFeignClient;
    private final CalendarEventProducer calendarEventProducer;


    /**
     * Generates a TaskEvent for an Epic.
     * @param epic The Epic entity.
     * @return TaskEvent
     */
    private TaskEvent getEpicTaskEvent(Epic epic) {
        return TaskEvent.builder()
                .entityId(epic.getId())
                .title(epic.getTitle())
                .projectId(epic.getProjectId())
                .eventType(EventType.EPIC)
                .priority(epic.getPriority())
                .deadline(epic.getDeadLine())
                .description(epic.getDescription())
                .createdDate(epic.getCreatedDate())
                .assignees(epic.getAssignees())
                .build();
    }

    /**
     * Converts an EpicDTO to an Epic entity.
     * @param epicDTO The DTO containing Epic details.
     * @return The corresponding Epic entity.
     */
    private Epic convertToEpicEntity(EpicDTO epicDTO) {
        return modelMapper.map(epicDTO, Epic.class);
    }

    /**
     * Converts an Epic entity to an EpicDTO.
     * @param epic The Epic entity.
     * @return The corresponding EpicDTO.
     */
    private EpicDTO convertToEpicDTo(Epic epic) {
        return modelMapper.map(epic, EpicDTO.class);
    }

    /**
     * Retrieves an Epic entity by ID.
     * @param epicId The ID of the Epic.
     * @return The Epic entity.
     * @throws ResourceNotFound if not found.
     */
    private Epic getEpicEntity(String epicId) {
        return epicRepository.findById(epicId)
                .orElseThrow(() -> new ResourceNotFound("Epic not found: " + epicId));
    }

    @Override
    @Transactional
    public EpicDTO createEpic(EpicDTO epicDTO) {
        // get the project ID
        String projectId = epicDTO.getProjectId();
        // call the project Service to ensure that given ProjectId is Valid
        ProjectDTO projectDTO = projectFeignClient.getProject(projectId);
        Epic epic = convertToEpicEntity(epicDTO);
        epic.setStatus(Status.TODO);
        epic.setCreatedDate(LocalDate.now());

        Epic savedEpic = epicRepository.save(epic);
        // add the epic within the current project
        projectFeignClient.addEpicToProject(savedEpic.getProjectId(),savedEpic.getId());

        TaskEvent taskEvent  = getEpicTaskEvent(savedEpic);
        taskEvent.setEventType(EventType.CALENDER);
        taskEvent.setAction(Actions.CREATED);

        calendarEventProducer.sendTaskEvent(taskEvent);

        return convertToEpicDTo(savedEpic);
    }

    @Override
    @Transactional
    public EpicDTO updateEpicStatus(String epicId, Status newStatus) {

        Epic existingEpic = getEpicEntity(epicId);
        Status oldStatus = existingEpic.getStatus();

        existingEpic.setStatus(newStatus);
        existingEpic.setUpdatedDate(LocalDate.now());
        Epic savedEpic = epicRepository.save(existingEpic);

        if (!oldStatus.equals(newStatus)) {
            TaskEvent taskEvent = getEpicTaskEvent(savedEpic);
            taskEvent.setOldStatus(oldStatus);
            taskEvent.setNewStatus(newStatus);
            producer.sendTaskEvent(taskEvent);
        }

        // create an EPIC event and produce it
        TaskEvent taskEvent = getEpicTaskEvent(existingEpic);
        taskEvent.setAction(Actions.STATUS_CHANGED);
        taskEvent.setOldStatus(oldStatus);
        taskEvent.setNewStatus(newStatus);
        taskEvent.setUpdatedDate(LocalDate.now());
        taskEvent.setAssignees(savedEpic.getAssignees());

        producer.sendTaskEvent(taskEvent);

        taskEvent.setEventType(EventType.CALENDER);

        calendarEventProducer.sendTaskEvent(taskEvent);

        return convertToEpicDTo(savedEpic);
    }

    @Override
    @Transactional
    public EpicDTO deleteEpic(String epicId) {
        Epic epic = getEpicEntity(epicId);
        // if epic is already archived then throw error
        if (epic.getStatus() == Status.COMPLETED)
            throw new ResourceNotFound("No Epic found : "+epicId );

        // Check if any associated stories or tasks are not completed
        boolean hasIncompleteEpics = epic.getStories().stream()
                .anyMatch(story -> story.getStatus() != Status.COMPLETED);
        boolean hasIncompleteTasks = epic.getTasks().stream()
                .allMatch(task -> task.getStatus() != Status.COMPLETED);
        if (hasIncompleteTasks) {
            throw new IllegalStateException("Epic cannot be deleted as it has incomplete stories or tasks.");
        }

        // set the epic as ARCHIVED
        epic.setStatus(Status.COMPLETED);
        // Perform deletion
        epicRepository.save(epic);

        TaskEvent taskEvent = getEpicTaskEvent(epic);
        taskEvent.setEventType(EventType.CALENDER);
        taskEvent.setAction(Actions.DELETED);
        taskEvent.setNewStatus(Status.COMPLETED);

        calendarEventProducer.sendTaskEvent(taskEvent);

        return convertToEpicDTo(epic);
    }

    @Override
    public EpicDTO getEpicById(String epicId) {
        Epic epic = getEpicEntity(epicId);
        return convertToEpicDTo(epic);
    }

    @Override
    public List<EpicDTO> getAllActiveEpics() {
        List<Epic> epics = epicRepository.findAll();
        return epics.stream()
                .filter(epic -> epic.getStatus() != Status.COMPLETED)
                .map(this::convertToEpicDTo)
                .toList();
    }

    @Override
    public EpicDTO assignMemberToEpic(String epicId, String memberId) {

            Epic existingEpic = getEpicEntity(epicId);
            // fetch the member from the UserService that member Exist
            UserDTO  user = userFeignClient.getUserById(memberId);
            // set the new Member into the epic
            existingEpic.getAssignees().add(memberId);

            Epic savedEpic = epicRepository.save(existingEpic);

            // inform user that he is assigned to this epic
            TaskEvent taskEvent = getEpicTaskEvent(existingEpic);

            taskEvent.setAction(Actions.ASSIGNED);
            taskEvent.setAssignees(Set.of(memberId));
            taskEvent.setDescription("You are assigned to Epic");

            // produce to the broker
            producer.sendTaskEvent(taskEvent);

            return convertToEpicDTo(savedEpic);

    }

    @Override
    public EpicDTO removeMemberFromEpic(String epicId, String memberId) {

        UserDTO userDTO = userFeignClient.getUserById(memberId );
        Epic exitingEpic = getEpicEntity(epicId);

        exitingEpic.getAssignees().remove(memberId);
        Epic savedEpic = epicRepository.save(exitingEpic);

        TaskEvent taskEvent = getEpicTaskEvent(savedEpic);
        taskEvent.setAction(Actions.UNASSIGNED);
        taskEvent.setAssignees(Set.of(memberId));
        taskEvent.setDescription("You are unassigned from the Epic "+ epicId);
        // produce to the broker
        producer.sendTaskEvent(taskEvent);

        return  convertToEpicDTo(savedEpic);
    }

    @Override
    public List<UserDTO> getAssignedMembers(String epicId) {
        Epic epic =  getEpicEntity(epicId) ;
        List<UserDTO> users = new LinkedList<>();
        for ( String userId: epic.getAssignees()) {
            UserDTO user = userFeignClient.getUserById(userId);
            users.add(user);
        }
        return users;
    }
}
