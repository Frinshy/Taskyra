@file:Suppress("UNUSED_PARAMETER", "unused")

package de.frinshy

import dev.kord.core.Kord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.count

/**
 * Simple status rotator handler.
 * - Call StatusRotator.start(kord) once during startup.
 * - Call StatusRotator.stop() to cancel the background job.
 */
object StatusRotator {
    private var scope: CoroutineScope? = null

    // Default statuses. One entry includes a placeholder {guilds} that will be
    // replaced at runtime with the bot's guild (server) count.
    private val DEFAULT_STATUSES = listOf(
        "🛠️ Organizing tasks",
        "🤝 Helping the bot team",
        "📋 Keeping task lists tidy",
        "🌐 Supporting {guilds} servers"
    )

    // Default rotation interval (seconds)
    // For testing, use a short default interval. Change back to 300L for production.
    private const val DEFAULT_INTERVAL_SECONDS: Long = 10L

    /**
     * Start the rotator using internal defaults.
     */
    fun start(kord: Kord) {
        if (scope != null) {
            println("ℹ️ StatusRotator already running, start() ignored")
            return
        }

        val statuses = DEFAULT_STATUSES
        val intervalSeconds = DEFAULT_INTERVAL_SECONDS

        val job = SupervisorJob()
        val s = CoroutineScope(Dispatchers.Default + job)
        scope = s

        s.launch {
            // small delay to ensure the gateway has fully settled after ReadyEvent
            delay(500)
            println("ℹ️ StatusRotator: initial delay complete, starting rotation loop")
            println("ℹ️ StatusRotator: statuses=${statuses}")
            var idx = 0
            while (isActive) {
                var message = statuses[idx % statuses.size]
                // Fetch guild count each time we set a new status (fresh value every iteration)
                val guildCount = runCatching { kord.guilds.count() }.getOrNull()
                val displayGuildCount = guildCount?.toString() ?: "many"
                // If message contains {guilds}, replace with the freshly fetched count.
                if (message.contains("{guilds}")) {
                    message = message.replace("{guilds}", displayGuildCount)
                }
                println("🔄 StatusRotator: attempting to set presence to: \"$message")
                try {
                    kord.editPresence {
                        playing(message)
                    }
                    println("✅ StatusRotator: presence updated to: \"$message\"")
                } catch (e: Exception) {
                    println("❌ StatusRotator: failed to update presence: ${e.message}")
                    e.printStackTrace()
                }
                idx++
                delay(intervalSeconds * 1000)
            }
        }

        println("✅ StatusRotator started with ${statuses.size} message(s), rotating every ${intervalSeconds} seconds")
    }

    @Suppress("unused")
    fun stop() {
        scope?.cancel()
        scope = null
        println("🛑 StatusRotator stopped")
    }
}
