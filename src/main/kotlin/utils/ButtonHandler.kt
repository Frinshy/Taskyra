package utils

import de.frinshy.commands.impl.Task
import de.frinshy.commands.impl.TaskManager
import de.frinshy.commands.impl.Priority
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.interaction.ComponentInteractionCreateEvent
import dev.kord.rest.builder.component.ActionRowBuilder
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner

enum class ButtonType {
    START_TASK,
    COMPLETE_TASK,
    SELECT_USERS,
    DELETE_TASK,
    PAUSE_TASK,
    ASSIGN_ME,
    REOPEN_TASK,
    EDIT_TASK,
    CHANGE_PRIORITY
}

abstract class TaskButton(
    open val type: ButtonType,
    open val label: String,
    open val style: ButtonStyle
) {
    open val id: String get() = type.name.lowercase().replace("_", "-")

    open suspend fun handle(task: Task, event: ComponentInteractionCreateEvent) {
        event.interaction.deferPublicMessageUpdate()
        val guildId = event.interaction.data.guildId.value?.toString() ?: return
        handle(task, guildId)
    }

    open suspend fun handle(task: Task, guildId: String) {
        handle(task)
    }

    open suspend fun handle(task: Task) {}

    fun addToActionRow(taskId: String, row: ActionRowBuilder) {
        row.interactionButton(style, "$id-$taskId") { label = this@TaskButton.label }
    }
}

object ButtonRegistry {
    private var _buttons: List<TaskButton>? = null

    fun registerButtons() {
        val reflections = Reflections("utils", SubTypesScanner())
        val buttonClasses: Set<Class<out TaskButton>> =
            reflections.getSubTypesOf(TaskButton::class.java)
        _buttons = buttonClasses.mapNotNull {
            try {
                it.getDeclaredConstructor().newInstance()
            } catch (_: Exception) {
                null
            }
        }
    }

    val buttons: List<TaskButton>
        get() = _buttons ?: emptyList()

    fun getButtonByType(type: ButtonType): TaskButton? =
        buttons.find { type == it.type }
}

suspend fun handleButtonClick(event: ComponentInteractionCreateEvent): Boolean {
    val componentId = event.interaction.componentId

    // If the user clicked the 'Edit fields' button, open the modal for editing
    if (componentId.startsWith("edit-openmodal-")) {
        val taskId = componentId.removePrefix("edit-openmodal-")
        val task = TaskManager.getTaskById(taskId)
        if (task == null) {
            event.interaction.respondEphemeral { content = "❌ Task not found." }
            return true
        }
        try {
            event.interaction.modal("Edit Task", "edit-task-modal-$taskId") {
                actionRow {
                    textInput(TextInputStyle.Short, "title", "Task Title") {
                        this.placeholder = "Enter task title"
                        this.value = task.title
                        this.required = true
                    }
                }
                actionRow {
                    textInput(TextInputStyle.Paragraph, "description", "Task Description") {
                        this.placeholder = "Enter task description"
                        this.value = task.description
                        this.required = true
                    }
                }
                actionRow {
                    textInput(TextInputStyle.Short, "priority", "Priority (low, medium, high)") {
                        this.placeholder = "low, medium, or high"
                        this.value = task.priority.name.lowercase()
                        this.required = false
                        allowedLength = 1..10
                    }
                }
            }
        } catch (e: Exception) {
            event.interaction.respondEphemeral { content = "❌ Failed to open edit modal: ${e.message}" }
        }
        return true
    }

    // Special-case: handle priority select menu interactions which use ids like "priority-select-<taskId>"
    if (componentId.startsWith("priority-select-")) {
        try {
            val taskId = componentId.removePrefix("priority-select-")

            // Much simpler way to get selected values using Kord's proper API
            val selectedValues = event.interaction.data.data.values.value
            val selected = selectedValues?.firstOrNull()?.lowercase()

            if (selected == null) {
                event.interaction.respondEphemeral { content = "❌ No priority selected." }
                return true
            }

            val priority = when (selected) {
                "low" -> Priority.LOW
                "high" -> Priority.HIGH
                else -> Priority.MEDIUM
            }

            TaskManager.updateTask(taskId, newPriority = priority)
            val guildId = event.interaction.data.guildId?.value?.toString() ?: return false
            TaskManager.getTaskById(taskId)?.let { t -> TaskManager.updateTaskEmbed(guildId, t) }
            val pretty = selected.replaceFirstChar { it.uppercase() }
            event.interaction.respondEphemeral { content = "✅ Priority updated to ${pretty} for task." }
            return true
        } catch (e: Exception) {
            event.interaction.respondEphemeral { content = "❌ Failed to update priority: ${e.message}" }
            return true
        }
    }

    val button: TaskButton? = ButtonRegistry.buttons
        .filter { componentId.startsWith(it.id + "-") }
        .maxByOrNull { it.id.length }

    if (button == null) {
        return false
    }

    val taskId = componentId.removePrefix(button.id + "-")
    val task = TaskManager.getTaskById(taskId) ?: return false

    button.handle(task, event)

    return true
}
