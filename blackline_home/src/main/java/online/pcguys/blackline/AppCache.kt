package online.pcguys.blackline

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import java.util.Locale

object AppCache {
    data class Entry(
        val info: ResolveInfo,
        val label: String,
        val pkg: String,
        val icon: Drawable
    )

    @Volatile
    private var cached: List<Entry> = emptyList()

    fun current(): List<Entry> = cached

    @Synchronized
    fun load(packageManager: PackageManager, ownPackage: String, force: Boolean = false): List<Entry> {
        if (!force && cached.isNotEmpty()) return cached
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cached = packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != ownPackage }
            .distinctBy { it.activityInfo.packageName }
            .map {
                Entry(
                    info = it,
                    label = it.loadLabel(packageManager).toString(),
                    pkg = it.activityInfo.packageName,
                    icon = it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase(Locale.US) }
            .toList()
        return cached
    }

    @Synchronized
    fun invalidate() {
        cached = emptyList()
    }
}
