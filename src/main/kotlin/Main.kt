package de.frinshy

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.on
import io.github.cdimascio.dotenv.dotenv
import utils.ButtonRegistry
import kotlin.time.ExperimentalTime

suspend fun main() {
    Main().main()
}

class Main {
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
            println("⚠️ Unable to set starting presence at this stage: ${'$'}{ex.message}")
        }

        // Status rotator will be started on ReadyEvent to ensure the gateway is connected.

        BotEventHandler.registerEvents()
        ButtonRegistry.registerButtons()
        run()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun run() {
        bot.on<ModalSubmitInteractionCreateEvent> {
            val interaction = this.interaction
            try {
                val modalId = interaction.modalId
                val interactionAge = kotlin.time.Clock.System.now() - interaction.id.timestamp
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
