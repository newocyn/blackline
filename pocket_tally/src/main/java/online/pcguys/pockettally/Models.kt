package online.pcguys.pockettally

import android.graphics.Color
import java.util.UUID

data class Tally(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val unit: String = "count",
    val value: Long = 0L,
    val step: Long = 1L,
    val goal: Long? = null,
    val accent: Int = Color.rgb(245, 245, 240),
    val allowNegative: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class TallyEvent(
    val id: String = UUID.randomUUID().toString(),
    val tallyId: String,
    val delta: Long,
    val before: Long,
    val after: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "Tap"
)

data class PocketSettings(
    val volumeKeys: Boolean = true,
    val haptics: Boolean = true,
    val sounds: Boolean = false,
    val keepAwake: Boolean = false,
    val confirmReset: Boolean = true,
    val selectedId: String? = null
)

data class TallyTemplate(
    val title: String,
    val subtitle: String,
    val name: String,
    val unit: String,
    val step: Long = 1L,
    val goal: Long? = null,
    val allowNegative: Boolean = false,
    val accent: Int
)

object TallyTemplates {
    val all = listOf(
        TallyTemplate("QUICK", "A blank everyday counter", "Quick tally", "count", accent = Color.rgb(245, 245, 240)),
        TallyTemplate("ATTENDANCE", "People entering a room", "Attendance", "people", goal = 50, accent = Color.rgb(183, 255, 205)),
        TallyTemplate("INVENTORY", "Units, boxes, or stock", "Inventory", "units", accent = Color.rgb(173, 216, 255)),
        TallyTemplate("LAPS", "Track a target number of laps", "Laps", "laps", goal = 10, accent = Color.rgb(208, 190, 255)),
        TallyTemplate("REPS", "Sets, reps, or repetitions", "Repetitions", "reps", goal = 12, accent = Color.rgb(255, 214, 153)),
        TallyTemplate("TRAFFIC", "Cars, bikes, or pedestrians", "Traffic", "vehicles", accent = Color.rgb(255, 177, 177)),
        TallyTemplate("ROWS", "Craft and row counting", "Rows", "rows", goal = 100, accent = Color.rgb(255, 199, 229)),
        TallyTemplate("SCORE", "Points can move below zero", "Score", "points", allowNegative = true, accent = Color.rgb(199, 255, 246))
    )
}
