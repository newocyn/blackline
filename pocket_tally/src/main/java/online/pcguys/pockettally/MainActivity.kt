package online.pcguys.pockettally

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private enum class Screen { COUNTERS, FOCUS, INSIGHTS, SETTINGS }

    private val background = Color.rgb(8, 8, 8)
    private val panel = Color.rgb(18, 18, 18)
    private val panelRaised = Color.rgb(25, 25, 25)
    private val line = Color.rgb(52, 52, 52)
    private val paper = Color.rgb(245, 245, 240)
    private val ink = Color.rgb(8, 8, 8)
    private val muted = Color.rgb(152, 152, 147)
    private val faint = Color.rgb(95, 95, 91)
    private val danger = Color.rgb(255, 151, 151)

    private lateinit var store: TallyStore
    private lateinit var root: LinearLayout
    private var screen = Screen.COUNTERS
    private var tone: ToneGenerator? = null

    private var focusValue: TextView? = null
    private var focusMeta: TextView? = null
    private var focusRing: ProgressRingView? = null
    private var focusToday: TextView? = null
    private var focusRate: TextView? = null
    private var focusGoal: TextView? = null
    private var dashboardToday: TextView? = null
    private val dashboardValues = mutableMapOf<String, TextView>()
    private val dashboardProgress = mutableMapOf<String, ProgressBar>()

    private var pendingCsv = ""
    private var pendingBackup = ""

    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { writeText(it, pendingCsv, "CSV exported") }
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeText(it, pendingBackup, "Backup saved") }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBackup(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TallyStore(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = background
        window.navigationBarColor = background
        handleIntent(intent)
        applyWindowSettings()
        render()
        maybeShowOnboarding()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        render()
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != Screen.COUNTERS) {
            screen = Screen.COUNTERS
            render()
        } else {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val settings = store.settings()
        if (settings.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) {
                val tally = store.selectedTally()
                if (tally != null) {
                    val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) tally.step else -tally.step
                    performDelta(delta, "Volume key")
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleIntent(intent: Intent?) {
        val tallyId = intent?.getStringExtra(TallyWidgetProvider.EXTRA_OPEN_TALLY)
        if (!tallyId.isNullOrBlank() && store.tallies().any { it.id == tallyId }) {
            store.select(tallyId)
            screen = Screen.FOCUS
        }
    }

    private fun applyWindowSettings() {
        if (store.settings().keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun render() {
        focusValue = null
        focusMeta = null
        focusRing = null
        focusToday = null
        focusRate = null
        focusGoal = null
        dashboardToday = null
        dashboardValues.clear()
        dashboardProgress.clear()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val content = when (screen) {
            Screen.COUNTERS -> countersScreen()
            Screen.FOCUS -> focusScreen()
            Screen.INSIGHTS -> insightsScreen()
            Screen.SETTINGS -> settingsScreen()
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun countersScreen(): View {
        val scroll = pageScroll()
        val body = pageBody()
        body.addView(header(
            eyebrow = "BLACKLINE // OFFLINE UTILITY",
            title = "Pocket Tally",
            subtitle = "Count anything. Keep every click.",
            actionLabel = "+ NEW",
            action = ::showTemplateDialog
        ))

        val selected = store.selectedTally()
        if (selected != null) body.addView(activeCounterHero(selected), top(18))

        val today = store.todayEvents()
        val dashboardMetrics = metricStrip(listOf(
            "TODAY" to today.size.toString(),
            "COUNTERS" to store.tallies().size.toString(),
            "NET" to signed(today.sumOf { it.delta })
        ))
        body.addView(dashboardMetrics, top(12))
        dashboardToday = ((dashboardMetrics.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)

        body.addView(sectionHeading("YOUR COUNTERS", "Tap a card for focus mode. Long-press to manage."), top(24))
        store.tallies().forEach { tally -> body.addView(counterCard(tally), top(10)) }
        body.addView(outlineButton("ADD FROM A PURPOSE-BUILT TEMPLATE", ::showTemplateDialog), top(14))
        body.addView(privacyPill(), top(14))
        scroll.addView(body)
        return scroll
    }

    private fun activeCounterHero(tally: Tally): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(panel, 26f, tally.accent, 1)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                store.select(tally.id)
                screen = Screen.FOCUS
                render()
            }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(label("ACTIVE COUNTER", 10f, tally.accent, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(label("STEP ±" + format(tally.step), 10f, muted, true))
        card.addView(topRow)

        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val value = label(format(tally.value), 60f, paper, true).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setAutoSizeTextTypeUniformWithConfiguration(36, 64, 2, TypedValue.COMPLEX_UNIT_SP)
            setOnLongClickListener {
                copyValue(tally)
                true
            }
        }
        dashboardValues[tally.id] = value
        valueRow.addView(value, LinearLayout.LayoutParams(0, dp(78), 1f))
        valueRow.addView(label(tally.unit.uppercase(Locale.getDefault()), 11f, muted, true).apply {
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, dp(12))
        })
        card.addView(valueRow)

        if (tally.goal != null) {
            val progress = horizontalProgress(tally)
            dashboardProgress[tally.id] = progress
            card.addView(progress, top(8, height = 7))
            card.addView(label(goalLine(tally), 11f, muted, false), top(8))
        } else {
            card.addView(label("Tap anywhere to enter distraction-free focus mode.", 12f, muted, false), top(2))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(actionButton("−", false, tally.accent, "Decrease " + tally.name) {
            store.select(tally.id)
            performDelta(-tally.step, "Dashboard")
        }, weighted(1f, end = 6))
        controls.addView(actionButton("+", true, tally.accent, "Increase " + tally.name) {
            store.select(tally.id)
            performDelta(tally.step, "Dashboard")
        }, weighted(2f, start = 6))
        card.addView(controls, top(16))
        return card
    }

    private fun counterCard(tally: Tally): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = interactive(panel, line, 20f)
            isClickable = true
            isFocusable = true
            contentDescription = tally.name + ", " + format(tally.value) + " " + tally.unit
            setOnClickListener {
                store.select(tally.id)
                screen = Screen.FOCUS
                render()
            }
            setOnLongClickListener {
                showCounterActions(tally)
                true
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label(tally.name, 17f, paper, true))
        titleColumn.addView(label(tally.unit.uppercase(Locale.getDefault()) + "  //  STEP " + format(tally.step), 9f, muted, true), top(3))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label(format(tally.value), 34f, tally.accent, true).apply {
            gravity = Gravity.END
            setAutoSizeTextTypeUniformWithConfiguration(22, 36, 2, TypedValue.COMPLEX_UNIT_SP)
        }
        dashboardValues[tally.id] = value
        header.addView(value, LinearLayout.LayoutParams(dp(132), dp(54)))
        card.addView(header)

        if (tally.goal != null) {
            val progress = horizontalProgress(tally)
            dashboardProgress[tally.id] = progress
            card.addView(progress, top(12, height = 5))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(compactButton("−" + format(tally.step), false, tally.accent) {
            store.select(tally.id)
            performDelta(-tally.step, "Dashboard")
        }, weighted(1f, end = 5))
        row.addView(compactButton("FOCUS", false, tally.accent) {
            store.select(tally.id)
            screen = Screen.FOCUS
            render()
        }, weighted(1f, start = 5, end = 5))
        row.addView(compactButton("+" + format(tally.step), true, tally.accent) {
            store.select(tally.id)
            performDelta(tally.step, "Dashboard")
        }, weighted(1f, start = 5))
        card.addView(row, top(12))
        return card
    }

    private fun focusScreen(): View {
        val tally = store.selectedTally()
        if (tally == null) {
            screen = Screen.COUNTERS
            return countersScreen()
        }
        val scroll = pageScroll()
        val body = pageBody()
        body.addView(header(
            eyebrow = "FOCUS MODE // VOLUME READY",
            title = tally.name,
            subtitle = "One screen. Zero missed clicks.",
            actionLabel = "SWITCH",
            action = ::showCounterPicker
        ))

        val ringFrame = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Increase " + tally.name
            setOnClickListener { performDelta(currentTallyStep(), "Ring tap") }
        }
        val ring = ProgressRingView(this).apply {
            accentColor = tally.accent
            progress = progressOf(tally)
        }
        focusRing = ring
        ringFrame.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
        }
        focusValue = label(format(tally.value), 72f, paper, true).apply {
            gravity = Gravity.CENTER
            setAutoSizeTextTypeUniformWithConfiguration(38, 76, 2, TypedValue.COMPLEX_UNIT_SP)
        }
        center.addView(focusValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)))
        center.addView(label(tally.unit.uppercase(Locale.getDefault()), 11f, tally.accent, true).apply {
            gravity = Gravity.CENTER
        })
        focusMeta = label(goalLine(tally), 12f, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        center.addView(focusMeta)
        ringFrame.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(ringFrame, top(16, height = 302))

        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        countRow.addView(actionButton("−", false, tally.accent, "Decrease by " + tally.step) {
            performDelta(-currentTallyStep(), "Button")
        }, weighted(1f, end = 7, height = 92))
        countRow.addView(actionButton("+", true, tally.accent, "Increase by " + tally.step) {
            performDelta(currentTallyStep(), "Button")
        }, weighted(1f, start = 7, height = 92))
        body.addView(countRow, top(14))

        body.addView(sectionHeading("STEP SIZE", "Change how much each tap or volume click counts."), top(22))
        val steps = listOf(1L, 5L, 10L, tally.step).distinct()
        val stepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        steps.forEachIndexed { index, step ->
            val active = tally.step == step
            stepRow.addView(compactButton("±" + format(step), active, tally.accent) {
                store.update((store.selectedTally() ?: return@compactButton).copy(step = step))
                render()
            }, weighted(1f, start = if (index == 0) 0 else 4, end = 4))
        }
        stepRow.addView(compactButton("CUSTOM", false, tally.accent, ::showStepDialog), weighted(1.35f, start = 4))
        body.addView(stepRow, top(9))

        body.addView(volumeCallout(tally), top(14))

        val utilityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        utilityRow.addView(compactButton("UNDO", false, tally.accent) { undo() }, weighted(1f, end = 4))
        utilityRow.addView(compactButton("REDO", false, tally.accent) { redo() }, weighted(1f, start = 4, end = 4))
        utilityRow.addView(compactButton("± VALUE", false, tally.accent, ::showDeltaDialog), weighted(1f, start = 4, end = 4))
        utilityRow.addView(compactButton("RESET", false, danger, ::requestReset), weighted(1f, start = 4))
        body.addView(utilityRow, top(12))

        body.addView(sectionHeading("LIVE SIGNAL", "A lightweight view of this counter's momentum."), top(24))
        val todayEvents = store.todayEvents(tally.id)
        val metrics = metricStrip(listOf(
            "ACTIONS" to todayEvents.size.toString(),
            "RATE" to rateLabel(tally.id),
            "REMAIN" to remainingLabel(tally)
        ))
        focusToday = ((metrics.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)
        focusRate = ((metrics.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
        focusGoal = ((metrics.getChildAt(2) as LinearLayout).getChildAt(1) as TextView)
        body.addView(metrics, top(10))

        val manageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manageRow.addView(outlineButton("EDIT COUNTER") { showCounterEditor(current = store.selectedTally()) }, weighted(1f, end = 6))
        manageRow.addView(outlineButton("SHARE SNAPSHOT", ::shareSelected), weighted(1f, start = 6))
        body.addView(manageRow, top(14))
        scroll.addView(body)
        return scroll
    }

    private fun insightsScreen(): View {
        val tally = store.selectedTally()
        val scroll = pageScroll()
        val body = pageBody()
        body.addView(header(
            eyebrow = "ACTIVITY // LOCAL HISTORY",
            title = "Insights",
            subtitle = tally?.name ?: "All counters",
            actionLabel = "FILTER",
            action = ::showCounterPicker
        ))

        if (tally == null) {
            body.addView(emptyState("No counters yet", "Create a counter to begin collecting activity."), top(24))
            scroll.addView(body)
            return scroll
        }

        val allEvents = store.events().filter { it.tallyId == tally.id }
        val todayEvents = store.todayEvents(tally.id)
        body.addView(metricStrip(listOf(
            "TODAY" to todayEvents.size.toString(),
            "NET" to signed(todayEvents.sumOf { it.delta }),
            "ALL TIME" to allEvents.size.toString()
        )), top(18))

        body.addView(sectionHeading("SEVEN DAYS", "Each bar is an action, regardless of direction."), top(24))
        val chart = ActivityChartView(this)
        val chartData = sevenDayActivity(allEvents)
        chart.setData(chartData.first, chartData.second, tally.accent)
        body.addView(chart, top(8, height = 200))

        val todayNet = todayEvents.sumOf { it.delta }
        val summary = card().apply {
            addView(label("TODAY'S SIGNAL", 10f, tally.accent, true))
            addView(label(
                todayEvents.size.toString() + " actions moved the counter " + signed(todayNet) + ".",
                18f,
                paper,
                true
            ), top(8))
            addView(label("Current value: " + format(tally.value) + " " + tally.unit + ".  " + goalLine(tally), 12f, muted, false), top(7))
        }
        body.addView(summary, top(12))

        body.addView(sectionHeading("RECENT ACTIVITY", "Undo removes the most recent entry from this ledger."), top(24))
        if (allEvents.isEmpty()) {
            body.addView(emptyState("No activity yet", "Use the buttons, volume keys, or widget to record your first count."), top(10))
        } else {
            allEvents.take(60).forEach { event -> body.addView(historyRow(event, tally.accent), top(7)) }
        }

        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(outlineButton("EXPORT CSV") { exportCsv(tally.id) }, weighted(1f, end = 6))
        exportRow.addView(outlineButton("SHARE SUMMARY", ::shareSelected), weighted(1f, start = 6))
        body.addView(exportRow, top(18))
        scroll.addView(body)
        return scroll
    }

    private fun settingsScreen(): View {
        val scroll = pageScroll()
        val body = pageBody()
        body.addView(header(
            eyebrow = "CONTROL // BEHAVIOR",
            title = "Settings",
            subtitle = "Tune Pocket Tally to the way you count."
        ))

        body.addView(sectionHeading("INPUT & FEEDBACK", "Every preference is stored only on this device."), top(22))
        body.addView(settingSwitch(
            "VOLUME BUTTONS",
            "Volume up counts forward; volume down counts backward while the app is open.",
            store.settings().volumeKeys
        ) { checked -> store.saveSettings(store.settings().copy(volumeKeys = checked)) }, top(9))
        body.addView(settingSwitch(
            "HAPTIC FEEDBACK",
            "A subtle tactile click confirms each count.",
            store.settings().haptics
        ) { checked -> store.saveSettings(store.settings().copy(haptics = checked)) }, top(8))
        body.addView(settingSwitch(
            "SOUND FEEDBACK",
            "Play a short tone after each successful count.",
            store.settings().sounds
        ) { checked -> store.saveSettings(store.settings().copy(sounds = checked)) }, top(8))
        body.addView(settingSwitch(
            "KEEP SCREEN AWAKE",
            "Useful for attendance, inventory, traffic, and event counting.",
            store.settings().keepAwake
        ) { checked ->
            store.saveSettings(store.settings().copy(keepAwake = checked))
            applyWindowSettings()
        }, top(8))
        body.addView(settingSwitch(
            "CONFIRM RESET",
            "Ask before resetting a counter to zero.",
            store.settings().confirmReset
        ) { checked -> store.saveSettings(store.settings().copy(confirmReset = checked)) }, top(8))

        body.addView(sectionHeading("DATA PORTABILITY", "Your counters are yours. Take them with you."), top(24))
        body.addView(outlineButton("EXPORT ALL ACTIVITY AS CSV") { exportCsv(null) }, top(9))
        body.addView(outlineButton("SAVE COMPLETE JSON BACKUP", ::exportBackup), top(8))
        body.addView(outlineButton("RESTORE FROM JSON BACKUP", ::selectBackup), top(8))

        body.addView(sectionHeading("QUICK ACCESS", "The widget follows whichever counter is currently selected."), top(24))
        body.addView(card().apply {
            addView(label("HOME-SCREEN WIDGET", 11f, paper, true))
            addView(label("Add Pocket Tally from your launcher's widget picker to count without opening the app.", 13f, muted, false), top(7))
            addView(label("TIP  //  Open a counter before leaving the app to make it the active widget counter.", 10f, faint, true), top(12))
        }, top(9))

        body.addView(sectionHeading("ABOUT", "A focused Blackline mobile utility."), top(24))
        body.addView(card().apply {
            addView(label("POCKET TALLY  1.0.0", 14f, paper, true))
            addView(label("Native Android • Offline-first • No account • No analytics • No ads in this build", 12f, muted, false), top(7))
            addView(label("Counts, goals, history, exports, and settings remain on your device unless you explicitly share or export them.", 12f, muted, false), top(10))
        }, top(9))
        scroll.addView(body)
        return scroll
    }

    private fun bottomNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(11, 11, 11), 0f, line, 1)
        }
        listOf(
            Screen.COUNTERS to "COUNTERS",
            Screen.FOCUS to "FOCUS",
            Screen.INSIGHTS to "INSIGHTS",
            Screen.SETTINGS to "SETTINGS"
        ).forEach { item ->
            val active = screen == item.first
            val button = label(item.second, 9f, if (active) ink else muted, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minHeight = dp(52)
                background = interactive(if (active) paper else Color.TRANSPARENT, if (active) paper else Color.TRANSPARENT, 16f)
                setOnClickListener {
                    screen = item.first
                    render()
                }
            }
            nav.addView(button, weighted(1f, start = 3, end = 3, height = 52))
        }
        return nav
    }

    private fun performDelta(delta: Long, source: String) {
        val tally = store.selectedTally() ?: return
        val before = tally.value
        val updated = store.applyDelta(tally.id, delta, source) ?: return
        if (updated.value == before) {
            if (!tally.allowNegative && delta < 0) toast("This counter cannot go below zero")
            return
        }
        feedback(delta > 0)
        refreshLiveViews()
        TallyWidgetProvider.updateAll(this)
    }

    private fun refreshLiveViews() {
        val selected = store.selectedTally() ?: return
        focusValue?.text = format(selected.value)
        focusMeta?.text = goalLine(selected)
        focusRing?.apply {
            accentColor = selected.accent
            progress = progressOf(selected)
            contentDescription = goalLine(selected)
        }
        focusToday?.text = store.todayEvents(selected.id).size.toString()
        focusRate?.text = rateLabel(selected.id)
        focusGoal?.text = remainingLabel(selected)
        focusValue?.animate()?.cancel()
        focusValue?.scaleX = 1.06f
        focusValue?.scaleY = 1.06f
        focusValue?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(110)?.start()

        val today = store.todayEvents()
        dashboardToday?.text = today.size.toString()
        store.tallies().forEach { tally ->
            dashboardValues[tally.id]?.text = format(tally.value)
            dashboardProgress[tally.id]?.progress = (progressOf(tally) * 1000).toInt()
        }
    }

    private fun feedback(positive: Boolean) {
        val settings = store.settings()
        if (settings.haptics) {
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (settings.sounds) {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 28)
            tone?.startTone(if (positive) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 45)
        }
    }

    private fun undo() {
        val tally = store.selectedTally() ?: return
        if (!store.canUndo(tally.id)) {
            toast("Nothing to undo")
            return
        }
        store.undo(tally.id)
        refreshLiveViews()
        TallyWidgetProvider.updateAll(this)
    }

    private fun redo() {
        val tally = store.selectedTally() ?: return
        if (!store.canRedo(tally.id)) {
            toast("Nothing to redo")
            return
        }
        store.redo(tally.id)
        refreshLiveViews()
        TallyWidgetProvider.updateAll(this)
    }

    private fun requestReset() {
        val tally = store.selectedTally() ?: return
        if (!store.settings().confirmReset) {
            reset(tally)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reset " + tally.name + "?")
            .setMessage("The counter will return to zero. You can undo this action.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ -> reset(tally) }
            .show()
    }

    private fun reset(tally: Tally) {
        store.reset(tally.id)
        refreshLiveViews()
        TallyWidgetProvider.updateAll(this)
    }

    private fun showTemplateDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(container) }
        lateinit var dialog: AlertDialog
        TallyTemplates.all.forEach { template ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = interactive(panelRaised, line, 16f)
                isClickable = true
                addView(label(template.title, 13f, template.accent, true))
                addView(label(template.subtitle, 12f, muted, false), top(4))
                setOnClickListener {
                    dialog.dismiss()
                    showCounterEditor(template = template)
                }
            }
            container.addView(row, top(7))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Choose a starting point")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showCounterEditor(current: Tally? = null, template: TallyTemplate? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(8))
        }
        val seedName = current?.name ?: template?.name ?: "Quick tally"
        val seedUnit = current?.unit ?: template?.unit ?: "count"
        val seedValue = current?.value ?: 0L
        val seedStep = current?.step ?: template?.step ?: 1L
        val seedGoal = current?.goal ?: template?.goal
        var selectedAccent = current?.accent ?: template?.accent ?: paper

        val name = field(seedName, "Counter name")
        val unit = field(seedUnit, "Unit")
        val value = numberField(seedValue.toString(), "Current value", signed = true)
        val step = numberField(seedStep.toString(), "Step size")
        val goal = numberField(seedGoal?.toString() ?: "", "Goal (optional)")
        form.addView(formLabel("NAME"))
        form.addView(name, top(5))
        form.addView(formLabel("UNIT"), top(13))
        form.addView(unit, top(5))
        form.addView(formLabel("CURRENT VALUE"), top(13))
        form.addView(value, top(5))
        form.addView(formLabel("STEP SIZE"), top(13))
        form.addView(step, top(5))
        form.addView(formLabel("GOAL"), top(13))
        form.addView(goal, top(5))

        form.addView(formLabel("COUNTER COLOR"), top(15))
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accents = listOf(
            paper,
            Color.rgb(183, 255, 205),
            Color.rgb(173, 216, 255),
            Color.rgb(208, 190, 255),
            Color.rgb(255, 214, 153),
            Color.rgb(255, 177, 177)
        )
        val swatches = mutableListOf<View>()
        accents.forEachIndexed { index, color ->
            val swatch = View(this).apply {
                contentDescription = "Color option " + (index + 1)
                isClickable = true
                background = rounded(color, 14f, if (color == selectedAccent) paper else line, if (color == selectedAccent) 3 else 1)
            }
            swatches += swatch
            colorRow.addView(swatch, weighted(1f, start = if (index == 0) 0 else 5, end = 5, height = 42))
            swatch.setOnClickListener {
                selectedAccent = color
                swatches.forEachIndexed { swatchIndex, view ->
                    val candidate = accents[swatchIndex]
                    view.background = rounded(candidate, 14f, if (candidate == selectedAccent) paper else line, if (candidate == selectedAccent) 3 else 1)
                }
            }
        }
        form.addView(colorRow, top(7))

        val negativeSwitch = Switch(this).apply {
            text = "Allow negative values"
            setTextColor(paper)
            textSize = 13f
            isChecked = current?.allowNegative ?: template?.allowNegative ?: false
            buttonTintList = ColorStateList.valueOf(paper)
        }
        form.addView(negativeSwitch, top(12))

        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current == null) "New counter" else "Edit counter")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (current == null) "Create" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedStep = step.text.toString().toLongOrNull()?.coerceIn(1L, 1_000_000_000L) ?: 1L
                val parsedGoal = goal.text.toString().toLongOrNull()?.takeIf { it > 0L }
                val parsedValue = value.text.toString().toLongOrNull() ?: 0L
                val allowNegative = negativeSwitch.isChecked
                val normalizedValue = if (allowNegative) parsedValue else parsedValue.coerceAtLeast(0L)
                val tally = if (current == null) {
                    Tally(
                        name = name.text.toString().trim().ifBlank { "Untitled tally" },
                        unit = unit.text.toString().trim().ifBlank { "count" },
                        value = normalizedValue,
                        step = parsedStep,
                        goal = parsedGoal,
                        accent = selectedAccent,
                        allowNegative = allowNegative
                    )
                } else {
                    current.copy(
                        name = name.text.toString().trim().ifBlank { "Untitled tally" },
                        unit = unit.text.toString().trim().ifBlank { "count" },
                        value = normalizedValue,
                        step = parsedStep,
                        goal = parsedGoal,
                        accent = selectedAccent,
                        allowNegative = allowNegative
                    )
                }
                if (current == null) store.add(tally) else store.update(tally)
                store.select(tally.id)
                dialog.dismiss()
                screen = Screen.FOCUS
                render()
                TallyWidgetProvider.updateAll(this)
            }
        }
        dialog.show()
    }

    private fun showCounterActions(tally: Tally) {
        val options = arrayOf("Edit", "Duplicate", "Move up", "Move down", "Share snapshot", "Reset", "Delete")
        AlertDialog.Builder(this)
            .setTitle(tally.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCounterEditor(current = tally)
                    1 -> {
                        val duplicate = Tally(
                            id = UUID.randomUUID().toString(),
                            name = tally.name + " copy",
                            unit = tally.unit,
                            value = tally.value,
                            step = tally.step,
                            goal = tally.goal,
                            accent = tally.accent,
                            allowNegative = tally.allowNegative
                        )
                        store.add(duplicate)
                        render()
                    }
                    2 -> { store.move(tally.id, -1); render() }
                    3 -> { store.move(tally.id, 1); render() }
                    4 -> { store.select(tally.id); shareSelected() }
                    5 -> { store.select(tally.id); requestReset() }
                    6 -> confirmDelete(tally)
                }
                TallyWidgetProvider.updateAll(this)
            }
            .show()
    }

    private fun confirmDelete(tally: Tally) {
        AlertDialog.Builder(this)
            .setTitle("Delete " + tally.name + "?")
            .setMessage("Its value and activity history will be removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.delete(tally.id)
                screen = Screen.COUNTERS
                render()
                TallyWidgetProvider.updateAll(this)
            }
            .show()
    }

    private fun showCounterPicker() {
        val tallies = store.tallies()
        val names = tallies.map { it.name + "  ·  " + format(it.value) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Active counter")
            .setItems(names) { _, index ->
                store.select(tallies[index].id)
                render()
                TallyWidgetProvider.updateAll(this)
            }
            .setNeutralButton("New") { _, _ -> showTemplateDialog() }
            .show()
    }

    private fun showStepDialog() {
        val tally = store.selectedTally() ?: return
        val input = numberField(tally.step.toString(), "Step size")
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom step size")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().toLongOrNull()?.coerceIn(1L, 1_000_000_000L) ?: tally.step
                store.update(tally.copy(step = value))
                render()
            }
            .show()
    }

    private fun showDeltaDialog() {
        val input = numberField("", "Example: 24 or -8", signed = true)
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Add a custom value")
            .setMessage("Apply any positive or negative change to the active counter.")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val delta = input.text.toString().toLongOrNull() ?: 0L
                performDelta(delta, "Custom value")
            }
            .show()
    }

    private fun maybeShowOnboarding() {
        val prefs = getSharedPreferences("pocket_tally_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
            addView(label("COUNT WITH YOUR HANDS FULL", 20f, paper, true))
            addView(label("Use the large on-screen controls—or press your phone's volume buttons—to count without hunting for a tiny target.", 14f, muted, false), top(10))
            addView(label("MULTIPLE COUNTERS", 10f, paper, true), top(18))
            addView(label("Keep separate tallies for inventory, attendance, laps, rows, traffic, scores, and anything else.", 13f, muted, false), top(5))
            addView(label("PRIVATE BY DEFAULT", 10f, paper, true), top(18))
            addView(label("No account, no analytics, and no network connection required.", 13f, muted, false), top(5))
        }
        AlertDialog.Builder(this)
            .setTitle("Pocket Tally")
            .setView(content)
            .setCancelable(false)
            .setPositiveButton("Start counting") { _, _ ->
                prefs.edit().putBoolean("onboarding_complete", true).apply()
            }
            .show()
    }

    private fun exportCsv(tallyId: String?) {
        pendingCsv = store.exportCsv(tallyId)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        createCsv.launch(if (tallyId == null) "pocket-tally-all-" + date + ".csv" else "pocket-tally-" + date + ".csv")
    }

    private fun exportBackup() {
        pendingBackup = store.exportBackup()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        createBackup.launch("pocket-tally-backup-" + date + ".json")
    }

    private fun selectBackup() {
        openBackup.launch(arrayOf("application/json", "text/plain"))
    }

    private fun importBackup(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read backup")
        }.onSuccess { raw ->
            store.importBackup(raw)
                .onSuccess { count ->
                    toast("Restored " + count + " counters")
                    screen = Screen.COUNTERS
                    render()
                    TallyWidgetProvider.updateAll(this)
                }
                .onFailure { toast(it.message ?: "Backup could not be restored") }
        }.onFailure {
            toast("Backup could not be read")
        }
    }

    private fun writeText(uri: Uri, content: String, success: String) {
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Could not open file")
        }.onSuccess { toast(success) }
            .onFailure { toast("Export failed") }
    }

    private fun shareSelected() {
        val tally = store.selectedTally() ?: return
        val today = store.todayEvents(tally.id)
        val text = buildString {
            append(tally.name).append(": ").append(format(tally.value)).append(' ').append(tally.unit)
            append("\nToday: ").append(today.size).append(" actions, ").append(signed(today.sumOf { it.delta })).append(" net")
            tally.goal?.let { append("\nGoal: ").append(format(it)).append(" • ").append(goalLine(tally)) }
            append("\n\nCounted with Pocket Tally.")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share tally"))
    }

    private fun copyValue(tally: Tally) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(tally.name, tally.value.toString()))
        toast("Value copied")
    }

    private fun header(
        eyebrow: String,
        title: String,
        subtitle: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(eyebrow, 9f, muted, true))
        titles.addView(label(title, 31f, paper, true).apply { letterSpacing = -0.02f }, top(5))
        titles.addView(label(subtitle, 13f, muted, false), top(5))
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && action != null) {
            top.addView(compactButton(actionLabel, true, paper, action), LinearLayout.LayoutParams(dp(78), dp(44)).apply {
                marginStart = dp(12)
            })
        }
        wrapper.addView(top)
        return wrapper
    }

    private fun sectionHeading(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 11f, paper, true))
            addView(label(subtitle, 11f, muted, false), top(4))
        }
    }

    private fun metricStrip(items: List<Pair<String, String>>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, item ->
            val metric = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(panel, 15f, line, 1)
                addView(label(item.first, 8f, faint, true))
                addView(label(item.second, 17f, paper, true), top(3))
            }
            row.addView(metric, weighted(1f, start = if (index == 0) 0 else 4, end = if (index == items.lastIndex) 0 else 4))
        }
        return row
    }

    private fun volumeCallout(tally: Tally): View {
        val enabled = store.settings().volumeKeys
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(if (enabled) Color.rgb(24, 29, 26) else panel, 17f, if (enabled) tally.accent else line, 1)
            addView(label(if (enabled) "VOLUME CONTROLS ON" else "VOLUME CONTROLS OFF", 10f, if (enabled) tally.accent else muted, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("DOWN  −" + format(tally.step) + "    UP  +" + format(tally.step), 10f, paper, true))
        }
    }

    private fun historyRow(event: TallyEvent, accent: Int): View {
        val stamp = SimpleDateFormat("MMM d  •  h:mm a", Locale.getDefault()).format(Date(event.timestamp))
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, 15f, line, 1)
            val deltaText = label(signed(event.delta), 18f, if (event.delta >= 0) accent else danger, true).apply {
                gravity = Gravity.CENTER
            }
            addView(deltaText, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            val detail = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(event.before.toString() + " → " + event.after.toString(), 13f, paper, true))
                addView(label(stamp + "  //  " + event.source.uppercase(Locale.getDefault()), 9f, muted, true), top(4))
            }
            addView(detail, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun settingSwitch(title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(12), dp(14))
            background = rounded(panel, 18f, line, 1)
            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 12f, paper, true))
                addView(label(subtitle, 11f, muted, false), top(4))
            }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = checked
                contentDescription = title
                thumbTintList = ColorStateList.valueOf(paper)
                trackTintList = ColorStateList.valueOf(Color.rgb(75, 75, 72))
                setOnCheckedChangeListener { _, isChecked -> changed(isChecked) }
            })
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(16))
        background = rounded(panel, 19f, line, 1)
    }

    private fun emptyState(title: String, message: String): View = card().apply {
        gravity = Gravity.CENTER
        addView(label(title, 18f, paper, true).apply { gravity = Gravity.CENTER })
        addView(label(message, 13f, muted, false).apply { gravity = Gravity.CENTER }, top(7))
    }

    private fun privacyPill(): View = label("OFFLINE  •  NO ACCOUNT  •  NO ANALYTICS", 9f, faint, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(13), dp(12), dp(13))
    }

    private fun horizontalProgress(tally: Tally): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (progressOf(tally) * 1000).toInt()
            progressTintList = ColorStateList.valueOf(tally.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(40, 40, 40))
            contentDescription = goalLine(tally)
        }
    }

    private fun actionButton(text: String, filled: Boolean, accent: Int, description: String, action: () -> Unit): TextView {
        return label(text, 31f, if (filled) ink else accent, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = description
            background = interactive(if (filled) accent else panelRaised, accent, 24f)
            setOnClickListener { action() }
        }
    }

    private fun compactButton(text: String, filled: Boolean, accent: Int, action: () -> Unit): TextView {
        return label(text, 10f, if (filled) ink else paper, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            isClickable = true
            isFocusable = true
            background = interactive(if (filled) accent else panelRaised, if (filled) accent else line, 14f)
            setOnClickListener { action() }
        }
    }

    private fun outlineButton(text: String, action: () -> Unit): TextView {
        return label(text, 10f, paper, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            isClickable = true
            isFocusable = true
            background = interactive(panel, line, 16f)
            setOnClickListener { action() }
        }
    }

    private fun field(value: String, hint: String): EditText {
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(paper)
            setHintTextColor(faint)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(panelRaised, 13f, line, 1)
            minHeight = dp(50)
        }
    }

    private fun numberField(value: String, hint: String, signed: Boolean = false): EditText {
        return field(value, hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            selectAll()
        }
    }

    private fun formLabel(value: String): TextView = label(value, 9f, muted, true)

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (bold && size <= 13f) letterSpacing = 0.1f
            setLineSpacing(0f, 1.12f)
        }
    }

    private fun pageScroll(): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun pageBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(30))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun interactive(fill: Int, stroke: Int, radius: Float): RippleDrawable {
        val ripple = if (fill == paper) Color.rgb(190, 190, 185) else Color.rgb(72, 72, 72)
        return RippleDrawable(
            ColorStateList.valueOf(ripple),
            rounded(fill, radius, stroke, if (stroke == Color.TRANSPARENT) 0 else 1),
            null
        )
    }

    private fun weighted(
        weight: Float,
        start: Int = 0,
        end: Int = 0,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ) = LinearLayout.LayoutParams(0, if (height > 0) dp(height) else height, weight).apply {
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    private fun top(
        margin: Int,
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ) = LinearLayout.LayoutParams(width, if (height > 0) dp(height) else height).apply {
        topMargin = dp(margin)
    }

    private fun progressOf(tally: Tally): Float {
        return TallyMath.progress(tally.value, tally.goal)
    }

    private fun goalLine(tally: Tally): String {
        val goal = tally.goal
        return if (goal == null) {
            "Step ±" + format(tally.step) + "  •  " + tally.unit
        } else {
            val remaining = max(0L, goal - tally.value)
            val percent = (progressOf(tally) * 100).toInt()
            if (remaining == 0L) "Goal reached  •  " + percent + "%" else format(remaining) + " to goal  •  " + percent + "%"
        }
    }

    private fun remainingLabel(tally: Tally): String {
        val goal = tally.goal ?: return "—"
        return format(max(0L, goal - tally.value))
    }

    private fun currentTallyStep(): Long = store.selectedTally()?.step ?: 1L

    private fun rateLabel(tallyId: String): String {
        val cutoff = System.currentTimeMillis() - 5 * 60_000L
        val recent = store.events().filter { it.tallyId == tallyId && it.timestamp >= cutoff }
        if (recent.isEmpty()) return "0/min"
        val elapsedMinutes = ((System.currentTimeMillis() - (recent.minOfOrNull { it.timestamp } ?: cutoff)) / 60_000.0).coerceAtLeast(0.25)
        return String.format(Locale.US, "%.1f/min", recent.size / elapsedMinutes)
    }

    private fun sevenDayActivity(events: List<TallyEvent>): Pair<List<String>, List<Int>> {
        val labels = mutableListOf<String>()
        val values = mutableListOf<Int>()
        val format = SimpleDateFormat("EEE", Locale.getDefault())
        for (daysAgo in 6 downTo 0) {
            val start = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -daysAgo)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            labels += format.format(start.time).uppercase(Locale.getDefault()).take(3)
            values += events.count { it.timestamp >= start.timeInMillis && it.timestamp < end.timeInMillis }
        }
        return labels to values
    }

    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    private fun signed(value: Long): String = if (value > 0) "+" + format(value) else format(value)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
