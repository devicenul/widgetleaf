package io.devnullapps.widgetleaf.services

import android.os.SystemClock
import io.devnullapps.widgetleaf.BuildConfig
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Thread-safe in-memory ring buffer storing the most recent [MAX_EVENTS] lifecycle, focus,
 * widget interaction, and dismissal diagnostics so users can inspect or copy on-device logs
 * without `adb`.
 */
object OverlayDiagnosticsLog {

    const val MAX_EVENTS = 50

    data class Entry(
        val wallTime: String,
        val uptimeMs: Long,
        val event: String,
        val detail: String
    )

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX_EVENTS)

    fun record(
        event: String,
        detail: String = "",
        uptimeMs: Long = SystemClock.uptimeMillis()
    ) {
        val wallTime = try {
            LocalTime.now().format(timeFormatter)
        } catch (_: Throwable) {
            "00:00:00.000"
        }
        synchronized(lock) {
            if (entries.size >= MAX_EVENTS) {
                entries.removeFirst()
            }
            entries.addLast(
                Entry(
                    wallTime = wallTime,
                    uptimeMs = uptimeMs,
                    event = event,
                    detail = detail
                )
            )
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) {
        entries.toList()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun formatReport(): String {
        val current = snapshot()
        return buildString {
            appendLine(
                "WidgetLeaf v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Diagnostics (${current.size} events)"
            )
            if (current.isEmpty()) {
                append("No events recorded yet.")
                return@buildString
            }
            var prevUptimeMs: Long? = null
            current.forEachIndexed { index, entry ->
                val deltaMs = prevUptimeMs?.let { (entry.uptimeMs - it).coerceAtLeast(0L) } ?: 0L
                prevUptimeMs = entry.uptimeMs
                append(entry.wallTime)
                append(" [+").append(deltaMs).append("ms] ")
                append(entry.event)
                if (entry.detail.isNotBlank()) {
                    append(": ").append(entry.detail)
                }
                if (index < current.lastIndex) {
                    appendLine()
                }
            }
        }
    }
}
