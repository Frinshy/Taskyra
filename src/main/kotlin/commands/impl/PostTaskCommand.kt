package de.frinshy.commands.impl

import de.frinshy.commands.Command
import de.frinshy.commands.SlashCommand
import de.frinshy.config.BotConfig
import de.frinshy.utils.updateChannelSummary
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.string
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@SlashCommand(name = "posttask", description = "Post a new task to the pending tasks channel")
class PostTaskCommand : Command {

    override fun defineOptions(builder: ChatInputCreateBuilder) {
        builder.apply {
            string("title", "The task title/name") {
                required = true
            }
            string("description", "The detailed task description") {
                required = false
            }
            string("priority", "Optional priority: low, medium, or high") {
                required = false
                choice("Low", "low")
                choice("Medium", "medium")
                choice("High", "high")
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun execute(event: ChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction

        println("🔄 PostTaskCommand.execute() called - User: ${'$'}{interaction.user.id}, Command: ${'$'}{interaction.invokedCommandName}")

        val deferredResponse = interaction.deferEphemeralResponse()
        val guildId = interaction.data.guildId.value?.toString() ?: return
        val guildConfig = BotConfig.instance.guilds.find { it.guildId == guildId }
        if (guildConfig == null) {
            deferredResponse.respond {
                content = "❌ No pending tasks channel is set for this server. Please use `/settaskchannels` first."
            }
            return
        }
        val pendingChannelId = guildConfig.pendingTasksChannelId
        if (pendingChannelId == null) {
            deferredResponse.respond {
                content =
                    "❌ No pending tasks channel is set. Please use `/settaskchannels` first to configure all task channels."
            }
            return
        }

        val titleOption = interaction.command.options["title"]
        val titleText = titleOption?.value?.toString()?.trim()
        if (titleText.isNullOrBlank()) {
            deferredResponse.respond {
                content = "❌ Please provide a task title."
            }
            return
        }

        val descriptionOption = interaction.command.options["description"]
        val descriptionText = descriptionOption?.value?.toString()?.trim()

        val priorityOption = interaction.command.options["priority"]
        val priorityText = priorityOption?.value?.toString()?.trim()?.lowercase()
        val priorityValue = when (priorityText) {
            "low" -> Priority.LOW
            "high" -> Priority.HIGH
            else -> Priority.MEDIUM
        }

        try {
            val channel = interaction.kord.getChannelOf<TextChannel>(Snowflake(pendingChannelId))
            if (channel == null) {
                deferredResponse.respond {
                    content = "❌ Pending tasks channel not found. Please check your channel configuration."
                }
                return
            }

            val taskId = "${Clock.System.now().epochSeconds}_${(1000..9999).random()}"
            println("📝 Creating task with ID: $taskId, Title: \"$titleText\"")

            val task = Task(
                id = taskId,
                title = titleText,
                description = descriptionText ?: "No description provided",
                state = TaskState.PENDING,
                priority = priorityValue,
                messageId = null
            )

            var postedMessageId: String? = null

            val message = TaskManager.sendTaskEmbedMessage(channel, task)

            postedMessageId = message.id.toString()

            task.messageId = postedMessageId
            TaskManager.addTask(task)

            updateChannelSummary(guildId, TaskState.PENDING)

            deferredResponse.respond {
                content = "✅ Task \"$titleText\" has been created and posted to the pending tasks channel!"
            }

        } catch (e: Exception) {
            println("❌ PostTaskCommand.execute() failed: ${'$'}{e.message}")
            deferredResponse.respond {
                content = "❌ Failed to create task: ${'$'}{e.message}"
            }
        }
    }
}
