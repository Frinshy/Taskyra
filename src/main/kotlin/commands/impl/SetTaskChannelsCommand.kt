package de.frinshy.commands.impl

import de.frinshy.commands.impl.TaskState
import de.frinshy.commands.Command
import de.frinshy.commands.SlashCommand
import de.frinshy.config.BotConfig
import de.frinshy.utils.updateChannelSummary
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.channel

@SlashCommand(name = "settaskchannels", description = "Set the channels for pending, in-progress, and completed tasks")
class SetTaskChannelsCommand : Command {

    override fun defineOptions(builder: ChatInputCreateBuilder) {
        builder.apply {
            channel("pending", "Channel for pending tasks") {
                required = true
            }
            channel("inprogress", "Channel for tasks currently being worked on") {
                required = true
            }
            channel("completed", "Channel for completed tasks") {
                required = true
            }
        }
    }

    override suspend fun execute(event: ChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val deferredResponse = interaction.deferEphemeralResponse()

        val pendingChannelId = interaction.command.options["pending"]?.value?.toString()
        val inProgressChannelId = interaction.command.options["inprogress"]?.value?.toString()
        val completedChannelId = interaction.command.options["completed"]?.value?.toString()

        if (pendingChannelId == null || inProgressChannelId == null || completedChannelId == null) {
            deferredResponse.respond {
                content = "❌ All channels must be specified."
            }
            return
        }

        val guildId = interaction.data.guildId.value?.toString() ?: return

        val currentConfig = BotConfig.instance
        val updatedGuilds =
            if (currentConfig.guilds.any { it.guildId == guildId }) {
                currentConfig.guilds.map {
                    if (it.guildId == guildId) it.copy(
                        pendingTasksChannelId = pendingChannelId,
                        inProgressTasksChannelId = inProgressChannelId,
                        completedTasksChannelId = completedChannelId
                    ) else it
                }
            } else {
                currentConfig.guilds + listOf(
                    de.frinshy.config.GuildConfig(
                        guildId = guildId,
                        pendingTasksChannelId = pendingChannelId,
                        inProgressTasksChannelId = inProgressChannelId,
                        completedTasksChannelId = completedChannelId
                    )
                )
            }

        BotConfig.updateInstance(currentConfig.copy(guilds = updatedGuilds))

        try {
            updateChannelSummary(guildId, TaskState.PENDING)
            updateChannelSummary(guildId, TaskState.IN_PROGRESS)
            updateChannelSummary(guildId, TaskState.COMPLETED)

            deferredResponse.respond {
                content = "✅ Task channels have been configured successfully!\n" +
                        "📋 Pending: <#$pendingChannelId>\n" +
                        "🔄 In Progress: <#$inProgressChannelId>\n" +
                        "✅ Completed: <#$completedChannelId>"
            }
        } catch (e: Exception) {
            deferredResponse.respond {
                content = "❌ Failed to initialize channels: ${e.message}"
            }
        }
    }
}
