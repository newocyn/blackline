package online.pcguys.pockettally

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TallyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val redoStacks = mutableMapOf<String, ArrayDeque<TallyEvent>>()

    @Synchronized
    fun tallies(): List<Tally> {
        ensureSeeded()
        return decodeTallies(prefs.getString(KEY_TALLIES, null))
    }

    @Synchronized
    fun events(): List<TallyEvent> = decodeEvents(prefs.getString(KEY_EVENTS, null))

    fun settings(): PocketSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return PocketSettings()
        return runCatching {
            val json = JSONObject(raw)
            PocketSettings(
                volumeKeys = json.optBoolean("volumeKeys", true),
                haptics = json.optBoolean("haptics", true),
                sounds = json.optBoolean("sounds", false),
                keepAwake = json.optBoolean("keepAwake", false),
                confirmReset = json.optBoolean("confirmReset", true),
                selectedId = if (json.isNull("selectedId")) null else json.optString("selectedId").ifBlank { null }
            )
        }.getOrDefault(PocketSettings())
    }

    @Synchronized
    fun saveSettings(settings: PocketSettings) {
        val json = JSONObject()
            .put("volumeKeys", settings.volumeKeys)
            .put("haptics", settings.haptics)
            .put("sounds", settings.sounds)
            .put("keepAwake", settings.keepAwake)
            .put("confirmReset", settings.confirmReset)
            .put("selectedId", settings.selectedId ?: JSONObject.NULL)
        prefs.edit().putString(KEY_SETTINGS, json.toString()).apply()
    }

    fun selectedTally(): Tally? {
        val all = tallies()
        val selectedId = settings().selectedId
        return all.firstOrNull { it.id == selectedId } ?: all.firstOrNull()
    }

    fun select(id: String) {
        saveSettings(settings().copy(selectedId = id))
    }

    @Synchronized
    fun add(tally: Tally): Tally {
        val all = tallies().toMutableList()
        all.add(0, tally)
        saveTallies(all)
        select(tally.id)
        return tally
    }

    @Synchronized
    fun update(tally: Tally): Tally {
        val normalized = tally.copy(
            name = tally.name.trim().ifBlank { "Untitled tally" }.take(48),
            unit = tally.unit.trim().ifBlank { "count" }.take(24),
            step = tally.step.coerceIn(1L, MAX_STEP),
            goal = tally.goal?.takeIf { it > 0L },
            value = if (tally.allowNegative) tally.value else tally.value.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis()
        )
        val all = tallies().map { if (it.id == normalized.id) normalized else it }
        saveTallies(all)
        return normalized
    }

    @Synchronized
    fun delete(id: String) {
        val remaining = tallies().filterNot { it.id == id }.toMutableList()
        if (remaining.isEmpty()) remaining += defaultTally()
        saveTallies(remaining)
        saveEvents(events().filterNot { it.tallyId == id })
        redoStacks.remove(id)
        val selected = settings().selectedId
        if (selected == id) select(remaining.first().id)
    }

    @Synchronized
    fun move(id: String, direction: Int) {
        val all = tallies().toMutableList()
        val current = all.indexOfFirst { it.id == id }
        if (current < 0) return
        val target = (current + direction).coerceIn(0, all.lastIndex)
        if (current == target) return
        val item = all.removeAt(current)
        all.add(target, item)
        saveTallies(all)
    }

    @Synchronized
    fun applyDelta(id: String, delta: Long, source: String): Tally? {
        val all = tallies().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0 || delta == 0L) return all.getOrNull(index)
        val tally = all[index]
        val rawAfter = TallyMath.safeAdd(tally.value, delta)
        val after = if (tally.allowNegative) rawAfter else rawAfter.coerceAtLeast(0L)
        if (after == tally.value) return tally
        val updated = tally.copy(value = after, updatedAt = System.currentTimeMillis())
        all[index] = updated
        val event = TallyEvent(
            tallyId = tally.id,
            delta = TallyMath.appliedDelta(tally.value, after),
            before = tally.value,
            after = after,
            source = source.take(24)
        )
        val history = events().toMutableList().apply {
            add(0, event)
            if (size > MAX_EVENTS) subList(MAX_EVENTS, size).clear()
        }
        saveTallies(all)
        saveEvents(history)
        redoStacks[id]?.clear()
        return updated
    }

    @Synchronized
    fun reset(id: String): Tally? {
        val tally = tallies().firstOrNull { it.id == id } ?: return null
        if (tally.value == 0L) return tally
        return applyDelta(id, -tally.value, "Reset")
    }

    @Synchronized
    fun undo(id: String): Tally? {
        val all = tallies().toMutableList()
        val tallyIndex = all.indexOfFirst { it.id == id }
        if (tallyIndex < 0) return null
        val history = events().toMutableList()
        val eventIndex = history.indexOfFirst { it.tallyId == id }
        if (eventIndex < 0) return all[tallyIndex]
        val event = history.removeAt(eventIndex)
        all[tallyIndex] = all[tallyIndex].copy(value = event.before, updatedAt = System.currentTimeMillis())
        redoStacks.getOrPut(id) { ArrayDeque() }.addFirst(event)
        saveTallies(all)
        saveEvents(history)
        return all[tallyIndex]
    }

    @Synchronized
    fun redo(id: String): Tally? {
        val event = redoStacks[id]?.removeFirstOrNull() ?: return tallies().firstOrNull { it.id == id }
        val all = tallies().toMutableList()
        val tallyIndex = all.indexOfFirst { it.id == id }
        if (tallyIndex < 0) return null
        all[tallyIndex] = all[tallyIndex].copy(value = event.after, updatedAt = System.currentTimeMillis())
        val history = events().toMutableList().apply { add(0, event.copy(timestamp = System.currentTimeMillis())) }
        saveTallies(all)
        saveEvents(history.take(MAX_EVENTS))
        return all[tallyIndex]
    }

    fun canUndo(id: String): Boolean = events().any { it.tallyId == id }

    fun canRedo(id: String): Boolean = redoStacks[id]?.isNotEmpty() == true

    fun todayEvents(tallyId: String? = null): List<TallyEvent> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return events().filter { it.timestamp >= start && (tallyId == null || it.tallyId == tallyId) }
    }

    fun exportCsv(tallyId: String? = null): String {
        val tallyNames = tallies().associate { it.id to it.name }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("timestamp,counter,delta,before,after,source")
            events().asReversed()
                .filter { tallyId == null || it.tallyId == tallyId }
                .forEach { event ->
                    append(csv(stamp.format(Date(event.timestamp))))
                    append(',').append(csv(tallyNames[event.tallyId] ?: "Deleted tally"))
                    append(',').append(event.delta)
                    append(',').append(event.before)
                    append(',').append(event.after)
                    append(',').append(csv(event.source))
                    appendLine()
                }
        }
    }

    fun exportBackup(): String {
        val root = JSONObject()
            .put("schema", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("tallies", JSONArray(prefs.getString(KEY_TALLIES, "[]") ?: "[]"))
            .put("events", JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]"))
            .put("settings", JSONObject(prefs.getString(KEY_SETTINGS, "{}") ?: "{}"))
        return root.toString(2)
    }

    @Synchronized
    fun importBackup(raw: String): Result<Int> = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("schema", 0) == 1) { "Unsupported backup version" }
        val importedTallies = decodeTallies(root.getJSONArray("tallies").toString())
        require(importedTallies.isNotEmpty()) { "The backup contains no counters" }
        val ids = importedTallies.map { it.id }.toSet()
        val importedEvents = decodeEvents(root.optJSONArray("events")?.toString()).filter { it.tallyId in ids }
        val importedSettings = root.optJSONObject("settings") ?: JSONObject()
        prefs.edit()
            .putString(KEY_TALLIES, encodeTallies(importedTallies).toString())
            .putString(KEY_EVENTS, encodeEvents(importedEvents.take(MAX_EVENTS)).toString())
            .putString(KEY_SETTINGS, importedSettings.toString())
            .apply()
        redoStacks.clear()
        importedTallies.size
    }

    private fun ensureSeeded() {
        if (!prefs.contains(KEY_TALLIES)) {
            val seed = defaultTally()
            saveTallies(listOf(seed))
            saveSettings(PocketSettings(selectedId = seed.id))
        }
    }

    private fun defaultTally() = Tally(name = "Quick tally", unit = "count")

    private fun saveTallies(tallies: List<Tally>) {
        prefs.edit().putString(KEY_TALLIES, encodeTallies(tallies).toString()).apply()
    }

    private fun saveEvents(events: List<TallyEvent>) {
        prefs.edit().putString(KEY_EVENTS, encodeEvents(events).toString()).apply()
    }

    private fun encodeTallies(tallies: List<Tally>) = JSONArray().apply {
        tallies.forEach { tally ->
            put(JSONObject()
                .put("id", tally.id)
                .put("name", tally.name)
                .put("unit", tally.unit)
                .put("value", tally.value)
                .put("step", tally.step)
                .put("goal", tally.goal ?: JSONObject.NULL)
                .put("accent", tally.accent)
                .put("allowNegative", tally.allowNegative)
                .put("createdAt", tally.createdAt)
                .put("updatedAt", tally.updatedAt))
        }
    }

    private fun encodeEvents(events: List<TallyEvent>) = JSONArray().apply {
        events.forEach { event ->
            put(JSONObject()
                .put("id", event.id)
                .put("tallyId", event.tallyId)
                .put("delta", event.delta)
                .put("before", event.before)
                .put("after", event.after)
                .put("timestamp", event.timestamp)
                .put("source", event.source))
        }
    }

    private fun decodeTallies(raw: String?): List<Tally> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            Tally(
                id = json.getString("id"),
                name = json.optString("name", "Untitled tally"),
                unit = json.optString("unit", "count"),
                value = json.optLong("value", 0L),
                step = json.optLong("step", 1L).coerceIn(1L, MAX_STEP),
                goal = if (json.isNull("goal")) null else json.optLong("goal").takeIf { it > 0L },
                accent = json.optInt("accent", Color.rgb(245, 245, 240)),
                allowNegative = json.optBoolean("allowNegative", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())

    private fun decodeEvents(raw: String?): List<TallyEvent> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            TallyEvent(
                id = json.getString("id"),
                tallyId = json.getString("tallyId"),
                delta = json.optLong("delta"),
                before = json.optLong("before"),
                after = json.optLong("after"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                source = json.optString("source", "Tap")
            )
        }
    }.getOrDefault(emptyList())

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        private const val PREFS_NAME = "pocket_tally_data"
        private const val KEY_TALLIES = "tallies"
        private const val KEY_EVENTS = "events"
        private const val KEY_SETTINGS = "settings"
        private const val MAX_EVENTS = 5_000
        private const val MAX_STEP = 1_000_000_000L
    }
}
