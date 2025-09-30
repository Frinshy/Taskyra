@file:OptIn(ExperimentalTime::class)

package de.frinshy.commands.impl

import de.frinshy.Main
import de.frinshy.config.BotConfig
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.embed
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import utils.ButtonRegistry.getButtonByType
import utils.ButtonType
import utils.TaskButton
import java.io.File
import kotlin.time.ExperimentalTime

@Serializable
enum class TaskState {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}

@Serializable
enum class Priority {
    LOW,
    MEDIUM,
    HIGH
}

@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String,
    var state: TaskState = TaskState.PENDING,
    var assignedUsers: MutableList<String> = mutableListOf(),
    var priority: Priority = Priority.MEDIUM,
    var messageId: String? = null
)

object TaskManager {
    private val file = File("config/tasks.json")
    private val json = Json { prettyPrint = true }
    private var tasks: MutableList<Task> = loadTasks().toMutableList()

    fun addTask(task: Task) {
        tasks.add(task)
        saveTasks()
    }

    fun updateTaskState(id: String, newState: TaskState) {
        tasks.find { it.id == id }?.let {
            it.state = newState
            saveTasks()
        }
    }

    fun assignUserToTask(taskId: String, userId: String): Boolean {
        tasks.find { it.id == taskId }?.let { task ->
            if (!task.assignedUsers.contains(userId)) {
                task.assignedUsers.add(userId)
                saveTasks()
                return true
            }
        }
        return false
    }

    fun unassignUserFromTask(taskId: String, userId: String) {
        tasks.find { it.id == taskId }?.let { task ->
            if (task.assignedUsers.remove(userId)) {
                saveTasks()
            }
        }
    }

    fun getTaskById(id: String): Task? = tasks.find { it.id == id }

    fun removeTask(id: String) {
        tasks.removeAll { it.id == id }
        saveTasks()
    }

    fun removeTaskByMessageId(messageId: String) {
        tasks.removeAll { it.messageId == messageId }
        saveTasks()
    }

    @Deprecated("Use updateTaskState instead")
    fun markTaskCompleted(id: String) {
        updateTaskState(id, TaskState.COMPLETED)
    }

    fun getTasks(): List<Task> = tasks

    // Returns all tasks for a given guild
    fun getTasksByGuild(guildId: String): List<Task> = getTasks().filter { it.id.startsWith("${guildId}_") }

    // Returns all tasks for a given guild and state
    fun getTasksByState(guildId: String, state: TaskState): List<Task> =
        getTasksByGuild(guildId).filter { it.state == state }

    private fun saveTasks() {
        file.writeText(json.encodeToString(tasks))
    }

    private fun loadTasks(): List<Task> {
        return if (file.exists()) {
            try {
                val loadedTasks: List<Task> = json.decodeFromString(file.readText())
                loadedTasks.toList()
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
    }

    fun formatAssignedUsers(assignedUsers: List<String>): String {
        return assignedUsers.joinToString(", ") {
            if (it.matches(Regex("\\d+"))) "<@$it>" else it
        }
    }

    fun buildTaskMessage(
        task: Task,
        state: TaskState = task.state,
        timestamp: Instant = kotlin.time.Clock.System.now()
    ): MessageBuilder.() -> Unit = {
        embed {
            this.title = task.title
            this.color = when (task.priority) {
                Priority.LOW -> Color(0x43B581)    // green
                Priority.MEDIUM -> Color(0xFFD700) // gold
                Priority.HIGH -> Color(0xFF0000)   // red
            }
            this.description = task.description
            this.timestamp = timestamp
            addTaskEmbedFields(task, this)
        }
        addTaskActionRows(task.id, state, this)
    }

    suspend fun updateTaskEmbed(guildId: String, task: Task) {
        if (task.messageId == null) return
        try {
            val guildConfig = BotConfig.instance.guilds.find { it.guildId == guildId } ?: return
            val channelId = when (task.state) {
                TaskState.PENDING -> guildConfig.pendingTasksChannelId
                TaskState.IN_PROGRESS -> guildConfig.inProgressTasksChannelId
                TaskState.COMPLETED -> guildConfig.completedTasksChannelId
            } ?: return
            val channel = de.frinshy.Main.bot.getChannelOf<TextChannel>(Snowflake(channelId)) ?: return
            val message = channel.getMessage(Snowflake(task.messageId!!))
            message.edit {
                embeds = mutableListOf()
                components = mutableListOf()
                buildTaskMessage(task, task.state, kotlin.time.Clock.System.now()).invoke(this)
            }
        } catch (e: Exception) {
            println("❌ Failed to update task embed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun updateTask(
        id: String,
        newTitle: String? = null,
        newDescription: String? = null,
        newPriority: Priority? = null
    ) {
        tasks.find { it.id == id }?.let { task ->
            val updatedTask = task.copy(
                title = newTitle ?: task.title,
                description = newDescription ?: task.description,
                priority = newPriority ?: task.priority
            )
            val index = tasks.indexOf(task)
            tasks[index] = updatedTask
            saveTasks()
        }
    }

    fun updateTaskMessageId(id: String, newMessageId: String) {
        tasks.find { it.id == id }?.let { task ->
            task.messageId = newMessageId
            saveTasks()
        }
    }

    suspend fun moveTaskToCategory(
        guildId: String, taskId: String, targetState: TaskState
    ) {
        val guildConfig = BotConfig.instance.guilds.find { it.guildId == guildId } ?: return

        val channelId = when (targetState) {
            TaskState.PENDING -> guildConfig.pendingTasksChannelId
            TaskState.IN_PROGRESS -> guildConfig.inProgressTasksChannelId
            TaskState.COMPLETED -> guildConfig.completedTasksChannelId
        } ?: return

        val oldTask = getTaskById(taskId)
        val oldChannelId: String = when (oldTask?.state) {
            TaskState.PENDING -> guildConfig.pendingTasksChannelId
            TaskState.IN_PROGRESS -> guildConfig.inProgressTasksChannelId
            TaskState.COMPLETED -> guildConfig.completedTasksChannelId
            null -> return
        } ?: return

        val oldChannel = Main.bot.getChannelOf<TextChannel>(Snowflake(oldChannelId)) ?: return
        val channel = Main.bot.getChannelOf<TextChannel>(Snowflake(channelId)) ?: return
        val task = getTasks().find { it.id == taskId } ?: return

        updateTaskState(taskId, targetState)

        task.messageId?.let {
            try {
                oldChannel.getMessage(Snowflake(it)).delete()
            } catch (_: Exception) {
            }
        }

        val newMessage = sendTaskEmbedMessage(channel, task, targetState)
        updateTaskMessageId(taskId, newMessage.id.toString())
    }

    fun addTaskEmbedFields(
        task: Task, embedBuilder: EmbedBuilder
    ) {
        embedBuilder.field {
            name = "Status"
            value = when (task.state) {
                TaskState.PENDING -> "⏳ Pending"
                TaskState.IN_PROGRESS -> "🔄 In Progress"
                TaskState.COMPLETED -> "✅ Completed"
            }
            inline = true
        }

        embedBuilder.field {
            name = "Priority"
            value = when (task.priority) {
                Priority.LOW -> "🟢 Low"
                Priority.MEDIUM -> "🟡 Medium"
                Priority.HIGH -> "🔴 High"
            }
            inline = true
        }

        embedBuilder.field {
            name = "Task ID"
            val shortId = if (task.id.contains("_")) task.id.substringAfterLast("_") else task.id
            val displayShort = if (shortId.length > 8) shortId.takeLast(8) else shortId
            value = "`" + displayShort + "`"
            inline = true
        }
        embedBuilder.field {
            name = "Assigned Users"
            value = if (task.assignedUsers.isNotEmpty()) {
                formatAssignedUsers(task.assignedUsers)
            } else {
                "_No users assigned_"
            }
            inline = false
        }
    }

    fun addTaskActionRows(taskId: String, state: TaskState, builder: MessageBuilder) {
        val buttons = mutableListOf<TaskButton>()
        when (state) {
            TaskState.PENDING -> {
                getButtonByType(ButtonType.START_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.COMPLETE_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.SELECT_USERS)?.let { buttons.add(it) }
                getButtonByType(ButtonType.ASSIGN_ME)?.let { buttons.add(it) }
                getButtonByType(ButtonType.EDIT_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.CHANGE_PRIORITY)?.let { buttons.add(it) }
            }

            TaskState.IN_PROGRESS -> {
                getButtonByType(ButtonType.COMPLETE_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.PAUSE_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.SELECT_USERS)?.let { buttons.add(it) }
                getButtonByType(ButtonType.ASSIGN_ME)?.let { buttons.add(it) }
                getButtonByType(ButtonType.EDIT_TASK)?.let { buttons.add(it) }
                getButtonByType(ButtonType.CHANGE_PRIORITY)?.let { buttons.add(it) }
            }

            TaskState.COMPLETED -> {
                getButtonByType(ButtonType.REOPEN_TASK)?.let { buttons.add(it) }
            }
        }
        getButtonByType(ButtonType.DELETE_TASK)?.let { buttons.add(it) }

        buttons.chunked(5).forEach { btnGroup ->
            builder.actionRow {
                btnGroup.forEach { it.addToActionRow(taskId, this) }
            }
        }
    }

    suspend fun sendTaskEmbedMessage(channel: TextChannel, task: Task, state: TaskState = task.state): Message {
        return channel.createMessage(buildTaskMessage(task, state = state)).also { task.messageId = it.id.toString() }
    }
}
