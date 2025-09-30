@file:OptIn(ExperimentalTime::class)

package de.frinshy.utils

import de.frinshy.Main
import de.frinshy.Main.Companion.bot
import de.frinshy.commands.impl.Task
import de.frinshy.commands.impl.TaskManager
import de.frinshy.commands.impl.TaskState
import de.frinshy.config.BotConfig
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.embed
import kotlin.time.ExperimentalTime

suspend fun updateChannelSummary(guildId: String, taskState: TaskState) {
    try {
        val config = BotConfig.instance.guilds.find { it.guildId == guildId } ?: return
        val channelId = when (taskState) {
            TaskState.PENDING -> config.pendingTasksChannelId
            TaskState.IN_PROGRESS -> config.inProgressTasksChannelId
            TaskState.COMPLETED -> config.completedTasksChannelId
        }

        val channel = Main.bot.getChannelOf<TextChannel>(Snowflake(channelId ?: return)) ?: return
        val tasks = TaskManager.getTasksByState(guildId, taskState)
        val (title, colorValue) = when (taskState) {
            TaskState.PENDING -> Pair("📋 Pending Tasks Summary", 0xFFD700)
            TaskState.IN_PROGRESS -> Pair("⚠️ Tasks In Progress Summary", 0xFF8C00)
            TaskState.COMPLETED -> Pair("✅ Completed Tasks Summary", 0x43B581)
        }
        val summaryMessageId = when (taskState) {
            TaskState.PENDING -> config.pendingTasksSummaryMessageId
            TaskState.IN_PROGRESS -> config.inProgressTasksSummaryMessageId
            TaskState.COMPLETED -> config.completedTasksSummaryMessageId
        }
        var summaryMessage: Message? = null
        if (summaryMessageId != null) {
            try {
                summaryMessage = channel.getMessage(Snowflake(summaryMessageId))
                summaryMessage.edit {
                    embeds = mutableListOf()
                    embed {
                        this.title = title
                        this.color = dev.kord.common.Color(colorValue)
                        this.description =
                            "Total ${taskState.name.lowercase().replace("_", " ")} tasks: **${tasks.size}**"
                        this.timestamp = kotlin.time.Clock.System.now()
                    }
                }
            } catch (_: Exception) {
                summaryMessage = null
            }
        }
        if (summaryMessage == null) {
            summaryMessage = channel.createMessage {
                embed {
                    this.title = title
                    this.color = dev.kord.common.Color(colorValue)
                    this.description = "Total ${taskState.name.lowercase().replace("_", " ")} tasks: **${tasks.size}**"
                    this.timestamp = kotlin.time.Clock.System.now()
                }
            }
            // Update the summary message ID in the correct GuildConfig
            val updatedGuilds = BotConfig.instance.guilds.map {
                if (it.guildId == guildId) {
                    when (taskState) {
                        TaskState.PENDING -> it.copy(pendingTasksSummaryMessageId = summaryMessage.id.toString())
                        TaskState.IN_PROGRESS -> it.copy(inProgressTasksSummaryMessageId = summaryMessage.id.toString())
                        TaskState.COMPLETED -> it.copy(completedTasksSummaryMessageId = summaryMessage.id.toString())
                    }
                } else it
            }
            BotConfig.updateInstance(BotConfig.instance.copy(guilds = updatedGuilds))
        }
    } catch (e: Exception) {
        println("❌ Failed to update channel summary: ${e.message}")
    }
}

suspend fun performStartupValidation() {
    println("🔄 Starting up - Validating task data and cleaning up channels...")
    val botConfig = BotConfig.instance
    for (guildConfig in botConfig.guilds) {
        val guildId = guildConfig.guildId
        val channelsToValidate = listOf(
            Pair(guildConfig.inProgressTasksChannelId, TaskState.IN_PROGRESS),
            Pair(guildConfig.completedTasksChannelId, TaskState.COMPLETED)
        ).mapNotNull { (channelId, state) ->
            channelId?.let { Pair(it, state) }
        }

        // Step 1: Validate that all task messages still exist for this guild
        println("📋 Validating existing task messages for guild $guildId...")
        val allTasks = TaskManager.getTasksByGuild(guildId).toMutableList()
        val tasksToRemove = mutableListOf<Task>()

        for (task in allTasks) {
            if (task.messageId != null) {
                try {
                    // Find which channel this task should be in
                    val expectedChannelId = when (task.state) {
                        TaskState.PENDING -> null // Pending tasks might not have dedicated channels
                        TaskState.IN_PROGRESS -> guildConfig.inProgressTasksChannelId
                        TaskState.COMPLETED -> guildConfig.completedTasksChannelId
                    }

                    if (expectedChannelId != null) {
                        val channel = bot.getChannelOf<TextChannel>(Snowflake(expectedChannelId))
                        if (channel != null) {
                            try {
                                channel.getMessage(Snowflake(task.messageId!!))
                                // Message exists, task is valid
                            } catch (e: Exception) {
                                println("❌ Task message not found: ${task.title} (${task.id}) - removing from system")
                                tasksToRemove.add(task)
                            }
                        } else {
                            println("⚠️  Channel not found for task: ${task.title} (${task.id})")
                        }
                    }
                } catch (e: Exception) {
                    println("❌ Error validating task ${task.id}: ${e.message}")
                    tasksToRemove.add(task)
                }
            }
        }

        // Remove invalid tasks
        tasksToRemove.forEach { task ->
            TaskManager.removeTask(task.id)
        }

        if (tasksToRemove.isNotEmpty()) {
            println("🧹 Removed ${tasksToRemove.size} orphaned tasks from system for guild $guildId")
        }

        // Step 2: Clean up channels - remove messages that aren't tracked tasks
        for ((channelId, taskState) in channelsToValidate) {
            try {
                val channel = bot.getChannelOf<TextChannel>(Snowflake(channelId))
                if (channel == null) {
                    println("⚠️  Could not find channel with ID: $channelId in guild $guildId")
                    continue
                }

                println("🧹 Cleaning up ${taskState.name.lowercase().replace("_", " ")} channel for guild $guildId...")

                // Get all tracked task message IDs for this state
                val trackedMessageIds = TaskManager.getTasksByState(guildId, taskState)
                    .mapNotNull { it.messageId }
                    .toSet()

                // Get all messages in the channel (excluding summary message)
                val summaryMessageId = when (taskState) {
                    TaskState.PENDING -> guildConfig.pendingTasksSummaryMessageId
                    TaskState.IN_PROGRESS -> guildConfig.inProgressTasksSummaryMessageId
                    TaskState.COMPLETED -> guildConfig.completedTasksSummaryMessageId
                }

                val messages = mutableListOf<Message>()
                try {
                    channel.getMessagesBefore(Snowflake.max, 100).collect { message ->
                        // Skip bot's own messages that are summary embeds
                        if (message.id.toString() != summaryMessageId && message.author?.isBot == true) {
                            messages.add(message)
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️  Could not retrieve messages from channel: ${e.message}")
                    continue
                }

                var deletedCount = 0
                for (message in messages) {
                    // If this message isn't tracked as a task, delete it
                    if (!trackedMessageIds.contains(message.id.toString())) {
                        try {
                            message.delete()
                            deletedCount++
                            kotlinx.coroutines.delay(100) // Rate limiting
                        } catch (e: Exception) {
                            println("⚠️  Could not delete message ${message.id}: ${e.message}")
                        }
                    }
                }

                if (deletedCount > 0) {
                    println(
                        "🧹 Deleted $deletedCount orphaned messages from ${
                            taskState.name.lowercase().replace("_", " ")
                        } channel in guild $guildId"
                    )
                }
            } catch (e: Exception) {
                println("❌ Error cleaning up channel $channelId in guild $guildId: ${e.message}")
            }
        }

        println("📊 Updating all category info embeds for guild $guildId...")
        for ((_, taskState) in channelsToValidate) {
            try {
                updateChannelSummary(guildId, taskState)
                kotlinx.coroutines.delay(200)
            } catch (e: Exception) {
                println("❌ Failed to update summary for ${taskState.name} in guild $guildId: ${e.message}")
            }
        }

        guildConfig.pendingTasksChannelId?.let {
            try {
                updateChannelSummary(guildId, TaskState.PENDING)
            } catch (e: Exception) {
                println("❌ Failed to update pending summary in guild $guildId: ${e.message}")
            }
        }
    }
    println("✅ Startup validation completed successfully!")
}
