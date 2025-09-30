package utils

import config.MessageHelper
import de.frinshy.commands.impl.Task
import de.frinshy.commands.impl.TaskManager
import de.frinshy.commands.impl.TaskState
import de.frinshy.config.BotConfig
import de.frinshy.utils.updateChannelSummary
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.interaction.ComponentInteractionCreateEvent
import dev.kord.rest.builder.component.SelectOptionBuilder
import dev.kord.rest.builder.message.actionRow

class StartTaskTaskButton : TaskButton(
    type = ButtonType.START_TASK,
    label = "Start",
    style = ButtonStyle.Primary
) {
    override suspend fun handle(task: Task, guildId: String) {
        TaskManager.moveTaskToCategory(guildId, task.id, TaskState.IN_PROGRESS)
        updateChannelSummary(guildId, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.IN_PROGRESS)
    }
}

class CompleteTaskTaskButton : TaskButton(
    type = ButtonType.COMPLETE_TASK,
    label = "Complete",
    style = ButtonStyle.Success
) {
    override suspend fun handle(task: Task, guildId: String) {
        TaskManager.moveTaskToCategory(guildId, task.id, TaskState.COMPLETED)
        updateChannelSummary(guildId, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.IN_PROGRESS)
        updateChannelSummary(guildId, TaskState.COMPLETED)
    }
}

class PauseTaskTaskButton : TaskButton(
    type = ButtonType.PAUSE_TASK,
    label = "Pause",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, guildId: String) {
        TaskManager.moveTaskToCategory(guildId, task.id, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.IN_PROGRESS)
    }
}

class ReopenTaskTaskButton : TaskButton(
    type = ButtonType.REOPEN_TASK,
    label = "Reopen",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, guildId: String) {
        TaskManager.moveTaskToCategory(guildId, task.id, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.PENDING)
        updateChannelSummary(guildId, TaskState.COMPLETED)
    }
}

class DeleteTaskTaskButton : TaskButton(
    type = ButtonType.DELETE_TASK,
    label = "Delete",
    style = ButtonStyle.Danger
) {
    override suspend fun handle(task: Task, guildId: String) {
        val guildConfig = BotConfig.instance.guilds.find { it.guildId == guildId } ?: return

        val channelId = when (task.state) {
            TaskState.PENDING -> guildConfig.pendingTasksChannelId
            TaskState.IN_PROGRESS -> guildConfig.inProgressTasksChannelId
            TaskState.COMPLETED -> guildConfig.completedTasksChannelId
        }
        channelId?.let {
            val message = MessageHelper.getMessage(it, task.messageId) ?: return@let
            message.delete()
        }

        TaskManager.removeTask(task.id)
        updateChannelSummary(guildId, task.state)
    }
}

class SelectUsersTaskButton : TaskButton(
    type = ButtonType.SELECT_USERS,
    label = "Select Users",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, event: ComponentInteractionCreateEvent) {
        event.interaction.modal("Assign User to Task", "assign-user-modal-${task.id}") {
            actionRow {
                textInput(TextInputStyle.Short, "user-id", "User ID, Mention, or Name") {
                    placeholder = "@username, user ID, or any text"
                    required = true
                    allowedLength = 1..100
                }
            }
        }
    }
}

class AssignMeTaskButton : TaskButton(
    type = ButtonType.ASSIGN_ME,
    label = "Assign/Unassign Me",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, event: ComponentInteractionCreateEvent) {
        val userId = event.interaction.user.id.toString()
        val guildId = event.interaction.data.guildId.value?.toString() ?: return

        val isCurrentlyAssigned = task.assignedUsers.contains(userId)

        if (isCurrentlyAssigned) {
            TaskManager.unassignUserFromTask(task.id, userId)
            TaskManager.updateTaskEmbed(guildId, task)

            event.interaction.respondEphemeral {
                content = "✅ Successfully unassigned yourself from task \"${task.title}\"!"
            }

            return
        }

        TaskManager.assignUserToTask(task.id, userId)
        TaskManager.updateTaskEmbed(guildId, task)

        event.interaction.respondEphemeral {
            content = "✅ Successfully assigned yourself to task \"${task.title}\"!"
        }
    }
}

class EditTaskButton : TaskButton(
    type = ButtonType.EDIT_TASK,
    label = "Edit Task",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, event: ComponentInteractionCreateEvent) {
        event.interaction.modal("Edit Task", "edit-task-modal-${task.id}") {
            actionRow {
                textInput(TextInputStyle.Short, "title", "Task Title") {
                    this.placeholder = "Enter task title"
                    this.value = task.title
                    this.required = true
                }
            }
            actionRow {
                textInput(
                    TextInputStyle.Paragraph,
                    "description",
                    "Task Description"
                ) {
                    this.placeholder = "Enter task description"
                    this.value = task.description
                    this.required = true
                }
            }
        }
    }
}

class ChangePriorityTaskButton : TaskButton(
    type = ButtonType.CHANGE_PRIORITY,
    label = "Priority",
    style = ButtonStyle.Secondary
) {
    override suspend fun handle(task: Task, event: ComponentInteractionCreateEvent) {
        try {
            event.interaction.respondEphemeral {
                content = "Choose a new priority for task \"${task.title}\":"
                actionRow {
                    stringSelect("priority-select-${task.id}") {
                        placeholder = "Select priority (current: ${task.priority.name.lowercase()})"
                        options = mutableListOf(
                            SelectOptionBuilder("🔴 High", "high"),
                            SelectOptionBuilder("🟡 Medium", "medium"),
                            SelectOptionBuilder("🟢 Low", "low")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            event.interaction.respondEphemeral { content = "❌ Failed to show priority selector: ${e.message}" }
        }
    }
}