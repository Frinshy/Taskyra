package de.frinshy

import commands.impl.TaskManager
import de.frinshy.commands.CommandHandler
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.ComponentInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.on
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.flow.count
import kotlinx.datetime.Clock
import utils.ButtonRegistry
import utils.handleButtonClick

object BotEventHandler {
    fun registerEvents() {
        Main.bot.on<ReadyEvent> { handleReady(this) }
        Main.bot.on<ChatInputCommandInteractionCreateEvent> { handleChatInputCommand(this) }
        Main.bot.on<ComponentInteractionCreateEvent> { handleComponentInteraction(this) }
    }

    private suspend fun handleReady(event: ReadyEvent) {
        println("✅ Logged in as ${event.self.username}")
        println("ℹ️ Bot ID: ${event.self.id}")
        val guildCount = runCatching { Main.bot.guilds.count() }.getOrNull()
        println("ℹ️ Connected to ${guildCount ?: "unknown"} guild(s)")

        CommandHandler.registerCommands()
        println("🔧 Command registration complete!")

        // Set a starting presence now that the gateway is connected.
        try {
            Main.bot.editPresence {
                playing("Starting up...")
            }
            println("✅ Presence set to 'Starting up...' on ReadyEvent")
        } catch (ex: Exception) {
            println("⚠️ Failed to set starting presence on ReadyEvent: ${ex.message}")
        }

        // Start the status rotator now that the bot is ready and connected.
        try {
            StatusRotator.start(Main.bot)
        } catch (ex: Exception) {
            println("⚠️ Could not start status rotator on ReadyEvent: ${ex.message}")
        }
    }

    private suspend fun handleChatInputCommand(event: ChatInputCommandInteractionCreateEvent) {
        CommandHandler.handleCommand(event)
    }

    private suspend fun handleComponentInteraction(event: ComponentInteractionCreateEvent) {
        try {
            handleButtonClick(event).also { hasWorked ->
                if (!hasWorked) println("⚠️ No handler found for button click: ${event.interaction.componentId}")
            }

        } catch (e: Exception) {
            println("❌ Error executing command '${event.interaction.componentId}': ${e.message}")
        }
    }

    // Modal Handlers
    suspend fun handleAssignUserModal(event: ModalSubmitInteractionCreateEvent) {
        val interaction = event.interaction
        val taskId = interaction.modalId.removePrefix("assign-user-modal-")
        val userInput = interaction.textInputs["user-id"]?.value?.trim()
        if (userInput.isNullOrBlank()) {
            interaction.respondEphemeral { content = "❌ Please provide a valid user ID, mention, or name." }
            return
        }
        val userId = if (userInput.startsWith("<@") && userInput.endsWith(">")) {
            userInput.removePrefix("<@").removeSuffix(">").removePrefix("!")
        } else userInput
        val task = TaskManager.getTaskById(taskId)
        if (task == null) {
            interaction.respondEphemeral { content = "❌ Task not found." }
            return
        }
        val success = TaskManager.assignUserToTask(taskId, userId)
        val displayName = if (userId.matches(Regex("\\d+"))) "<@$userId>" else "\"$userId\""
        if (success) {
            val guildId = interaction.data.guildId.value?.toString() ?: return
            TaskManager.getTaskById(taskId)?.let { t ->
                TaskManager.updateTaskEmbed(guildId, t)
            }
            interaction.respondEphemeral { content = "✅ Successfully assigned $displayName to task \"${task.title}\"!" }
        } else {
            interaction.respondEphemeral { content = "⚠️ $displayName is already assigned to task \"${task.title}\"." }
        }
    }

    suspend fun handleEditTaskModal(event: ModalSubmitInteractionCreateEvent) {
        val interaction = event.interaction
        val taskId = interaction.modalId.removePrefix("edit-task-modal-")
        val newTitle = interaction.textInputs["title"]?.value?.trim()
        val newDescription = interaction.textInputs["description"]?.value?.trim()
        if (newTitle.isNullOrBlank() || newDescription.isNullOrBlank()) {
            interaction.respondEphemeral { content = "❌ Please provide both title and description." }
            return
        }
        val task = TaskManager.getTaskById(taskId)
        if (task == null) {
            interaction.respondEphemeral { content = "❌ Task not found." }
            return
        }
        TaskManager.updateTask(taskId, newTitle, newDescription)
        val guildId = interaction.data.guildId.value?.toString() ?: return
        TaskManager.getTaskById(taskId)?.let { t ->
            TaskManager.updateTaskEmbed(guildId, t)
        }
        interaction.respondEphemeral { content = "✅ Task \"${newTitle}\" has been updated successfully!" }
    }
}

class Main() {

    companion object {
        lateinit var bot: Kord
            private set
    }

    // Status rotation is handled by StatusRotator (see src/main/kotlin/StatusRotator.kt)
    // It is started during initialization below.

    suspend fun main() {
        val dotenv = dotenv()
        val token = dotenv["BOT_TOKEN"] ?: error("DISCORD_TOKEN not found in .env")

        bot = Kord(token)

        // Attempt to set a short-lived 'starting' presence. This may fail if the gateway
        // is not yet connected; it's safe because it's wrapped in try/catch.
        try {
            bot.editPresence {
                playing("Starting up...")
            }
            println("ℹ️ Attempted to set starting presence: 'Starting up...'")
        } catch (ex: Exception) {
            println("⚠️ Unable to set starting presence at this stage: ${ex.message}")
        }

        // Status rotator will be started on ReadyEvent to ensure the gateway is connected.

        BotEventHandler.registerEvents()
        ButtonRegistry.registerButtons()
        run()
    }

    suspend fun run() {
        bot.on<ModalSubmitInteractionCreateEvent> {
            val interaction = this.interaction
            try {
                val modalId = interaction.modalId
                val interactionAge = Clock.System.now() - interaction.id.timestamp
                if (interactionAge.inWholeMinutes > 14) {
                    println("⚠️ Ignoring expired modal interaction: $modalId")
                    return@on
                }
                when {
                    modalId.startsWith("assign-user-modal-") -> BotEventHandler.handleAssignUserModal(this)
                    modalId.startsWith("edit-task-modal-") -> BotEventHandler.handleEditTaskModal(this)
                 else -> interaction.respondEphemeral { content = "❌ Unknown modal submission." }
                 }
             } catch (e: Exception) {
                 println("❌ Error handling modal submission: ${e.message}")
                 e.printStackTrace()
                 interaction.respondEphemeral { content = "❌ An error occurred while processing your request." }
             }
         }
         bot.login()
     }
 }

suspend fun main() {
    Main().main()
}
