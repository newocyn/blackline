package online.pcguys.pockettally

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.NumberFormat

class TallyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, views(context, it)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_INCREMENT && action != ACTION_DECREMENT) return
        val store = TallyStore(context)
        val tally = store.selectedTally() ?: return
        val delta = if (action == ACTION_INCREMENT) tally.step else -tally.step
        store.applyDelta(tally.id, delta, "Widget")
        updateAll(context)
    }

    private fun views(context: Context, widgetId: Int): RemoteViews {
        val tally = TallyStore(context).selectedTally()
        return RemoteViews(context.packageName, R.layout.widget_tally).apply {
            setTextViewText(R.id.widget_name, tally?.name?.uppercase() ?: "POCKET TALLY")
            setTextViewText(R.id.widget_value, NumberFormat.getIntegerInstance().format(tally?.value ?: 0L))
            setOnClickPendingIntent(R.id.widget_plus, broadcastIntent(context, ACTION_INCREMENT, widgetId * 10 + 1))
            setOnClickPendingIntent(R.id.widget_minus, broadcastIntent(context, ACTION_DECREMENT, widgetId * 10 + 2))
            setOnClickPendingIntent(R.id.widget_value, openIntent(context, tally?.id, widgetId * 10 + 3))
            setOnClickPendingIntent(R.id.widget_name, openIntent(context, tally?.id, widgetId * 10 + 4))
        }
    }

    private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TallyWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openIntent(context: Context, tallyId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TALLY, tallyId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_INCREMENT = "online.pcguys.pockettally.action.INCREMENT"
        const val ACTION_DECREMENT = "online.pcguys.pockettally.action.DECREMENT"
        const val EXTRA_OPEN_TALLY = "open_tally_id"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TallyWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val updateIntent = Intent(context, TallyWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(updateIntent)
            }
        }
    }
}
