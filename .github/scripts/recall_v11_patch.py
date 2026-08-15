#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def read(rel: str) -> str:
    return (root / rel).read_text(encoding="utf-8")


def write(rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.strip() + "\n", encoding="utf-8")


def replace_required(rel: str, old: str, new: str, count: int = -1) -> None:
    text = read(rel)
    if old not in text:
        raise SystemExit(f"Expected text not found in {rel}: {old[:80]!r}")
    text = text.replace(old, new, count)
    write(rel, text)


# ---------------------------------------------------------------------------
# Product identity / build metadata
# ---------------------------------------------------------------------------
build = read("app/build.gradle")
build = re.sub(r'applicationId\s+"[^"]+"', 'applicationId "online.pcguys.recall"', build, count=1)
build = re.sub(r'versionCode\s+\d+', 'versionCode 110', build, count=1)
build = re.sub(r'versionName\s+"[^"]+"', 'versionName "1.1.0"', build, count=1)
write("app/build.gradle", build)

# Recording should be ON by default once Android grants Notification Access.
replace_required(
    "app/src/main/java/com/android/alftendev/utils/MySharedPref.kt",
    "return MyApplication.sharedPref.getBoolean(RECORD_NOTIFICATIONS_ENABLED, false)",
    "return MyApplication.sharedPref.getBoolean(RECORD_NOTIFICATIONS_ENABLED, true)",
)
replace_required(
    "app/src/main/java/com/android/alftendev/activities/home/SettingsActivity.kt",
    ".getBoolean(RECORD_NOTIFICATIONS_ENABLED, false)",
    ".getBoolean(RECORD_NOTIFICATIONS_ENABLED, true)",
)

# Device PIN/password/pattern is a valid authenticator, not only strong biometrics.
replace_required(
    "app/src/main/java/com/android/alftendev/utils/AuthUtils.kt",
    "BiometricManager.Authenticators.BIOMETRIC_STRONG))",
    "BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL))",
)

# ---------------------------------------------------------------------------
# Strings / language
# ---------------------------------------------------------------------------
strings_path = "app/src/main/res/values/strings.xml"
strings = read(strings_path)


def set_string(name: str, value: str) -> None:
    global strings
    pattern = rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)'
    updated, n = re.subn(pattern, rf'\1{value}\2', strings, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"String {name} not found")
    strings = updated


set_string("app_name", "Recall")
set_string("all_notification", "Timeline")
set_string("deleted_notification", "Dismissed")
set_string("graph", "Insights")
set_string("show_chat", "Conversation")
set_string("search", "Search your notification memory")
set_string("authInfo", "Lock Recall with device security")
set_string("notification_recovery_service", "Recall notification access")
set_string("enable_notifications_recording", "Record notifications")
set_string("notification_count", "Notifications archived:")
set_string("delete_notification", "Delete from Recall")
set_string("confirm_delete_noti_warning", "Delete this notification from Recall? This cannot be undone.")
set_string("explain_not_permission", "Recall needs Notification Access before it can remember new notifications.")

extra_strings = r'''
    <string name="saved_notifications">Saved</string>
    <string name="recall_subtitle">Your notification memory</string>
    <string name="recall_intro">Everything your phone told you, searchable later.</string>
    <string name="recording_on">Recording • On-device only</string>
    <string name="recording_off">Notification access required • Tap to fix</string>
    <string name="memory_stats">%1$d shown • %2$d saved</string>
    <string name="save_notification">Save</string>
    <string name="saved_notification">Saved</string>
    <string name="share_notification">Share</string>
    <string name="copy_notification">Copy</string>
    <string name="copied_notification">Notification copied</string>
    <string name="notification_details">Notification details</string>
    <string name="notification_from">From %1$s</string>
    <string name="dismissed_badge">Dismissed</string>
    <string name="open_source_app">Open app</string>
    <string name="delete_from_recall">Delete</string>
    <string name="insights_title">Notification insights</string>
    <string name="insights_subtitle">See which apps are competing for your attention.</string>
    <string name="notifications_archived">%1$d notifications archived</string>
    <string name="top_source">Top source: %1$s • %2$s%%</string>
    <string name="top_sources">Top sources</string>
    <string name="all_time_local">All time • Calculated on your device</string>
    <string name="no_notification_data">Not enough notification history yet.</string>
'''
if "saved_notifications" not in strings:
    strings = strings.replace("</resources>", extra_strings + "\n</resources>")
write(strings_path, strings)

# Keep Italian resources from overriding the application name.
it_path = root / "app/src/main/res/values-it-rIT/strings.xml"
if it_path.exists():
    it = it_path.read_text(encoding="utf-8")
    it = re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)', r'\1Recall\2', it, count=1, flags=re.S)
    it_path.write_text(it, encoding="utf-8")

# ---------------------------------------------------------------------------
# Modern visual system
# ---------------------------------------------------------------------------
write("app/src/main/res/values/colors.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#F6F8FC</color>
    <color name="background_dark">#0B0E13</color>
    <color name="top_bar_background">#FFFFFF</color>
    <color name="top_bar_dark_background">#11151C</color>

    <color name="cyan_light">#DCE9FF</color>
    <color name="cyan_white">#FFFFFF</color>
    <color name="cyan_dark">#6B8FC9</color>

    <color name="blue">#4F7DF3</color>
    <color name="blue_white">#AFC6FF</color>
    <color name="blue_dark">#3867DE</color>

    <color name="background_dark_cardview">#171B22</color>
    <color name="background_light_cardview">#FFFFFF</color>

    <color name="text_color_black">#111827</color>
    <color name="text_color_white">#F8FAFC</color>
    <color name="recall_muted_light">#667085</color>
    <color name="recall_muted_dark">#98A2B3</color>
    <color name="recall_success">#34D399</color>
    <color name="recall_warning">#F59E0B</color>
    <color name="recall_danger">#EF4444</color>
</resources>
''')

write("app/src/main/res/values/themes.xml", r'''
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.Simplenotlistener" parent="Theme.Material3.DayNight">
        <item name="colorPrimary">@color/blue</item>
        <item name="colorPrimaryVariant">@color/blue_dark</item>
        <item name="colorOnPrimary">@color/cyan_white</item>
        <item name="colorSecondary">@color/blue</item>
        <item name="colorSecondaryVariant">@color/blue_white</item>
        <item name="colorOnSecondary">@color/text_color_white</item>
        <item name="cardBackgroundColor">@color/background_light_cardview</item>
        <item name="android:textColor">@color/text_color_black</item>
        <item name="backgroundColor">@color/background</item>
        <item name="android:fontFamily">sans</item>
        <item name="fontFamily">sans</item>
        <item name="android:windowOptOutEdgeToEdgeEnforcement" tools:targetApi="35">true</item>
    </style>

    <style name="Theme.light" parent="Theme.Material3.DayNight">
        <item name="colorPrimary">@color/blue</item>
        <item name="colorPrimaryVariant">@color/blue_dark</item>
        <item name="colorOnPrimary">@color/cyan_white</item>
        <item name="colorSecondary">@color/blue</item>
        <item name="colorSecondaryVariant">@color/blue_white</item>
        <item name="colorOnSecondary">@color/text_color_white</item>
        <item name="cardBackgroundColor">@color/background_light_cardview</item>
        <item name="android:textColor">@color/text_color_black</item>
        <item name="backgroundColor">@color/background</item>
        <item name="android:fontFamily">sans</item>
        <item name="fontFamily">sans</item>
        <item name="android:windowOptOutEdgeToEdgeEnforcement" tools:targetApi="35">true</item>
    </style>

    <style name="Theme.dark" parent="Theme.Material3.DayNight">
        <item name="colorPrimary">@color/blue</item>
        <item name="colorPrimaryVariant">@color/blue_dark</item>
        <item name="colorOnPrimary">@color/cyan_white</item>
        <item name="colorSecondary">@color/blue_white</item>
        <item name="colorSecondaryVariant">@color/blue</item>
        <item name="colorOnSecondary">@color/text_color_white</item>
        <item name="cardBackgroundColor">@color/background_dark_cardview</item>
        <item name="android:textColor">@color/text_color_white</item>
        <item name="backgroundColor">@color/background_dark</item>
        <item name="android:fontFamily">sans</item>
        <item name="fontFamily">sans</item>
        <item name="android:windowOptOutEdgeToEdgeEnforcement" tools:targetApi="35">true</item>
    </style>

    <style name="Theme.Simplenotlistener.AppWidgetContainerParent" parent="@android:style/Theme.DeviceDefault">
        <item name="appWidgetRadius">16dp</item>
        <item name="appWidgetInnerRadius">8dp</item>
    </style>

    <style name="Theme.Simplenotlistener.AppWidgetContainer" parent="Theme.Simplenotlistener.AppWidgetContainerParent">
        <item name="appWidgetPadding">16dp</item>
    </style>
</resources>
''')

# ---------------------------------------------------------------------------
# Timeline / search UI
# ---------------------------------------------------------------------------
write("app/src/main/res/layout/activity_notification_list.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/notificationsDrawerLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?backgroundColor"
    tools:context=".activities.home.AllNotificationsActivity">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="?backgroundColor"
        android:orientation="vertical">

        <LinearLayout
            android:id="@+id/recallHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="18dp"
            android:paddingTop="18dp"
            android:paddingEnd="18dp"
            android:paddingBottom="8dp"
            android:visibility="gone">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/recall_subtitle"
                android:textColor="?android:textColor"
                android:textSize="22sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="3dp"
                android:text="@string/recall_intro"
                android:textColor="?android:attr/textColorSecondary"
                android:textSize="14sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:id="@+id/tvRecordingStatus"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:background="@drawable/recall_status_pill"
                    android:clickable="true"
                    android:focusable="true"
                    android:paddingStart="10dp"
                    android:paddingTop="6dp"
                    android:paddingEnd="10dp"
                    android:paddingBottom="6dp"
                    android:text="@string/recording_on"
                    android:textSize="12sp"
                    android:textStyle="bold" />

                <Space
                    android:layout_width="0dp"
                    android:layout_height="1dp"
                    android:layout_weight="1" />

                <TextView
                    android:id="@+id/tvMemoryStats"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="?android:attr/textColorSecondary"
                    android:textSize="12sp" />
            </LinearLayout>
        </LinearLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/searchContainer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="14dp"
            android:layout_marginTop="8dp"
            android:layout_marginEnd="14dp"
            android:layout_marginBottom="8dp"
            app:boxBackgroundMode="outline"
            app:boxCornerRadiusBottomEnd="18dp"
            app:boxCornerRadiusBottomStart="18dp"
            app:boxCornerRadiusTopEnd="18dp"
            app:boxCornerRadiusTopStart="18dp"
            app:hintEnabled="false"
            app:startIconDrawable="@drawable/baseline_manage_search_24">

            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/etSearch"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/search"
                android:inputType="text"
                android:maxLines="1"
                android:minHeight="54dp"
                android:paddingStart="4dp"
                android:paddingEnd="12dp"
                android:textSize="15sp" />
        </com.google.android.material.textfield.TextInputLayout>

        <LinearLayout
            android:id="@+id/llSelectionBar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="14dp"
            android:layout_marginEnd="14dp"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingVertical="6dp"
            android:visibility="gone">

            <com.google.android.material.checkbox.MaterialCheckBox
                android:id="@+id/cbSelectAll"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/all" />

            <Space
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/btnDeleteSelected"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/delete_from_recall"
                android:padding="12dp"
                android:src="@drawable/baseline_delete_24" />
        </LinearLayout>

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/lvAll"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:clipToPadding="false"
            android:paddingBottom="18dp" />
    </LinearLayout>

    <com.google.android.material.navigation.NavigationView
        android:id="@+id/notificationsNavView"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        app:headerLayout="@layout/nav_header"
        app:menu="@menu/drawer_list" />
</androidx.drawerlayout.widget.DrawerLayout>
''')

write("app/src/main/res/drawable/recall_status_pill.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#162235" />
    <corners android:radius="999dp" />
</shape>
''')

write("app/src/main/res/layout/custom_notification_layout.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="12dp"
    android:layout_marginTop="5dp"
    android:layout_marginEnd="12dp"
    android:layout_marginBottom="5dp"
    app:cardBackgroundColor="?cardBackgroundColor"
    app:cardCornerRadius="18dp"
    app:cardElevation="0dp"
    app:strokeColor="#223A4A66"
    app:strokeWidth="1dp">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/llNotificationAdapter"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:minHeight="94dp"
        android:padding="14dp">

        <com.google.android.material.imageview.ShapeableImageView
            android:id="@+id/ivIcon"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:contentDescription="@string/icon"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:shapeAppearanceOverlay="@style/ShapeAppearance.Material3.Corner.Medium"
            tools:srcCompat="@tools:sample/avatars" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/tvAppName"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="?android:attr/textColorSecondary"
            android:textSize="12sp"
            android:textStyle="bold"
            app:layout_constraintEnd_toStartOf="@id/tvDate"
            app:layout_constraintStart_toEndOf="@id/ivIcon"
            app:layout_constraintTop_toTopOf="parent"
            tools:text="Messages" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/tvDate"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:textColor="?android:attr/textColorSecondary"
            android:textSize="11sp"
            app:layout_constraintEnd_toStartOf="@id/btnSave"
            app:layout_constraintTop_toTopOf="parent"
            tools:text="7:10 AM" />

        <ImageButton
            android:id="@+id/btnSave"
            android:layout_width="38dp"
            android:layout_height="38dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/save_notification"
            android:padding="8dp"
            android:src="@drawable/baseline_bookmark_border_24"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/tvNome"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:layout_marginTop="5dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="?android:textColor"
            android:textSize="17sp"
            android:textStyle="bold"
            app:layout_constraintEnd_toStartOf="@id/btnSave"
            app:layout_constraintStart_toEndOf="@id/ivIcon"
            app:layout_constraintTop_toBottomOf="@id/tvAppName"
            tools:text="Jake Lawrence" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/tvDescrizione"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:layout_marginTop="4dp"
            android:ellipsize="end"
            android:maxLines="2"
            android:textColor="?android:attr/textColorSecondary"
            android:textSize="14sp"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toEndOf="@id/ivIcon"
            app:layout_constraintTop_toBottomOf="@id/tvNome"
            tools:text="What times?" />

        <TextView
            android:id="@+id/tvDeletedBadge"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:background="@drawable/recall_badge"
            android:paddingStart="8dp"
            android:paddingTop="3dp"
            android:paddingEnd="8dp"
            android:paddingBottom="3dp"
            android:text="@string/dismissed_badge"
            android:textColor="@color/recall_warning"
            android:textSize="11sp"
            android:textStyle="bold"
            android:visibility="gone"
            app:layout_constraintStart_toStartOf="@id/tvDescrizione"
            app:layout_constraintTop_toBottomOf="@id/tvDescrizione" />

        <com.google.android.material.checkbox.MaterialCheckBox
            android:id="@+id/cbSelect"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:visibility="gone"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />
    </androidx.constraintlayout.widget.ConstraintLayout>
</com.google.android.material.card.MaterialCardView>
''')

write("app/src/main/res/drawable/recall_badge.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#22F59E0B" />
    <corners android:radius="999dp" />
</shape>
''')

write("app/src/main/res/drawable/baseline_bookmark_border_24.xml", r'''
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="?android:attr/textColorSecondary" android:pathData="M17,3H7c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z" />
</vector>
''')
write("app/src/main/res/drawable/baseline_bookmark_24.xml", r'''
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@color/blue" android:pathData="M17,3H7c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z" />
</vector>
''')

# ---------------------------------------------------------------------------
# Saved memory without touching the ObjectBox schema
# ---------------------------------------------------------------------------
write("app/src/main/java/com/android/alftendev/utils/RecallSaved.kt", r'''
package com.android.alftendev.utils

import android.content.Context

object RecallSaved {
    private const val PREFS = "recall_saved_v1"
    private const val KEY = "saved_ids"

    private fun ids(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    fun isSaved(context: Context, id: Long): Boolean = ids(context).contains(id.toString())

    fun toggle(context: Context, id: Long): Boolean {
        val values = ids(context)
        val key = id.toString()
        val nowSaved = if (values.contains(key)) {
            values.remove(key)
            false
        } else {
            values.add(key)
            true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, values).apply()
        return nowSaved
    }

    fun remove(context: Context, id: Long) {
        val values = ids(context)
        if (values.remove(id.toString())) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, values).apply()
        }
    }

    fun savedIds(context: Context): Set<Long> = ids(context).mapNotNull { it.toLongOrNull() }.toSet()

    fun count(context: Context): Int = ids(context).size
}
''')

write("app/src/main/java/com/android/alftendev/activities/home/SavedNotificationsActivity.kt", r'''
package com.android.alftendev.activities.home

import com.android.alftendev.activities.NotificationListViewerBaseActivity
import com.android.alftendev.models.Notifications
import com.android.alftendev.utils.DBUtils.notificationWithoutChat
import com.android.alftendev.utils.DBUtils.notificationWithoutChatWithSearch
import com.android.alftendev.utils.RecallSaved

class SavedNotificationsActivity : NotificationListViewerBaseActivity() {
    override fun getNotifications(): List<Notifications> {
        val ids = RecallSaved.savedIds(this)
        return notificationWithoutChat().filter { ids.contains(it.entityId) }
    }

    override fun getNotificationsBySearch(filter: String): List<Notifications> {
        val ids = RecallSaved.savedIds(this)
        return notificationWithoutChatWithSearch(filter).filter { ids.contains(it.entityId) }
    }
}
''')

# Add Saved screen to manifest without rewriting the notification listener declaration.
manifest_path = "app/src/main/AndroidManifest.xml"
manifest = read(manifest_path)
needle = '''        <activity\n            android:name=".activities.home.AllNotificationsActivity"'''
if ".activities.home.SavedNotificationsActivity" not in manifest:
    saved_entry = '''        <activity\n            android:name=".activities.home.SavedNotificationsActivity"\n            android:exported="false"\n            android:label="@string/saved_notifications" />\n'''
    if needle not in manifest:
        raise SystemExit("Could not locate AllNotificationsActivity manifest entry")
    manifest = manifest.replace(needle, saved_entry + needle, 1)
write(manifest_path, manifest)

# ---------------------------------------------------------------------------
# Drawer identity / navigation
# ---------------------------------------------------------------------------
write("app/src/main/res/layout/nav_header.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="148dp"
    android:background="?backgroundColor"
    android:gravity="bottom"
    android:orientation="vertical"
    android:padding="20dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textColor="?android:textColor"
        android:textSize="28sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:text="@string/recall_subtitle"
        android:textColor="?android:attr/textColorSecondary"
        android:textSize="14sp" />
</LinearLayout>
''')

write("app/src/main/res/menu/drawer_list.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/navAllNotification" android:icon="@drawable/baseline_home_24" android:title="@string/all_notification" />
    <item android:id="@+id/navSaved" android:icon="@drawable/baseline_bookmark_24" android:title="@string/saved_notifications" />
    <item android:id="@+id/navChats" android:icon="@drawable/baseline_chat_24" android:title="@string/chat" />
    <item android:id="@+id/navGroupChat" android:icon="@drawable/baseline_groups_24" android:title="@string/group_chat" />
    <item android:id="@+id/navDeletedNotification" android:icon="@drawable/baseline_delete_outline_24" android:title="@string/deleted_notification" />
    <item android:id="@+id/navGraph" android:icon="@drawable/baseline_pie_chart_outline_24" android:title="@string/graph" />
    <item android:id="@+id/navAdvancedSearch" android:icon="@drawable/baseline_manage_search_24" android:title="@string/advanced_search" />
    <item android:id="@+id/navSettings" android:icon="@drawable/baseline_settings_24" android:title="@string/settings" />
</menu>
''')

# Patch the shared list activity to support Saved + recording/status overview.
base_path = "app/src/main/java/com/android/alftendev/activities/NotificationListViewerBaseActivity.kt"
base = read(base_path)
base = base.replace("import android.view.View\n", "import android.view.View\nimport android.graphics.Color\n")
base = base.replace("import android.widget.LinearLayout\n", "import android.widget.LinearLayout\nimport android.widget.TextView\n")
base = base.replace(
    "import com.android.alftendev.activities.home.SearchActivity\n",
    "import com.android.alftendev.activities.home.SearchActivity\nimport com.android.alftendev.activities.home.SavedNotificationsActivity\n",
)
base = base.replace(
    "import com.android.alftendev.utils.MySharedPref\n",
    "import com.android.alftendev.utils.MySharedPref\nimport com.android.alftendev.utils.RecallSaved\n",
)
base = base.replace(
    "    private lateinit var btnDeleteSelected: ImageButton\n",
    "    private lateinit var btnDeleteSelected: ImageButton\n    private var recallHeader: View? = null\n    private var tvMemoryStats: TextView? = null\n    private var tvRecordingStatus: TextView? = null\n",
)
base = base.replace(
    "            onSelectionModeChange(false)\n",
    "            onSelectionModeChange(false)\n            updateRecallHeader(notifications.size)\n",
    1,
)
base = base.replace(
    "        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)\n",
    '''        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)\n        recallHeader = findViewById(R.id.recallHeader)\n        tvMemoryStats = findViewById(R.id.tvMemoryStats)\n        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)\n\n        if (javaClass.simpleName == "AllNotificationsActivity") {\n            recallHeader?.visibility = View.VISIBLE\n            supportActionBar?.title = getString(R.string.app_name)\n            supportActionBar?.subtitle = getString(R.string.recall_subtitle)\n        } else {\n            recallHeader?.visibility = View.GONE\n        }\n        updateRecordingStatus()\n''',
    1,
)
nav_needle = '''                R.id.navChats -> {'''
if "R.id.navSaved" not in base:
    nav_saved = '''                R.id.navSaved -> {\n                    if (javaClass.simpleName == "SavedNotificationsActivity") {\n                        return@setNavigationItemSelectedListener true\n                    }\n                    val navIntent = Intent(\n                        this,\n                        SavedNotificationsActivity::class.java\n                    ).setAction(Intent.ACTION_MAIN)\n                    startActivity(navIntent)\n                    if (javaClass.simpleName != "AllNotificationsActivity") {\n                        finishAndRemoveTask()\n                    }\n                    true\n                }\n\n'''
    if nav_needle not in base:
        raise SystemExit("Could not patch Saved navigation")
    base = base.replace(nav_needle, nav_saved + nav_needle, 1)
method_needle = "    override fun onSelectionModeChange(isSelectionMode: Boolean) {"
if "private fun updateRecallHeader" not in base:
    methods = '''    private fun updateRecallHeader(shown: Int) {\n        if (javaClass.simpleName != "AllNotificationsActivity") return\n        tvMemoryStats?.text = getString(R.string.memory_stats, shown, RecallSaved.count(this))\n        updateRecordingStatus()\n    }\n\n    private fun updateRecordingStatus() {\n        if (javaClass.simpleName != "AllNotificationsActivity") return\n        val enabled = isNotificationServiceEnabled(this)\n        tvRecordingStatus?.apply {\n            text = getString(if (enabled) R.string.recording_on else R.string.recording_off)\n            setTextColor(Color.parseColor(if (enabled) "#34D399" else "#F59E0B"))\n            setOnClickListener {\n                if (!isNotificationServiceEnabled(this@NotificationListViewerBaseActivity)) {\n                    askNotificationServicePermission(this@NotificationListViewerBaseActivity)\n                }\n            }\n        }\n    }\n\n'''
    if method_needle not in base:
        raise SystemExit("Could not insert Recall header helpers")
    base = base.replace(method_needle, methods + method_needle, 1)
base = base.replace(
    "        MyApplication.executor.execute { refreshList(getNotifications()) }\n",
    "        updateRecordingStatus()\n        MyApplication.executor.execute { refreshList(getNotifications()) }\n",
    1,
)
write(base_path, base)

# ---------------------------------------------------------------------------
# Human-friendly timestamps
# ---------------------------------------------------------------------------
write("app/src/main/java/com/android/alftendev/utils/DateUtils.kt", r'''
package com.android.alftendev.utils

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {
    fun dateFormatter(date: Date): String = smartDateFormatter(date)

    fun smartDateFormatter(date: Date): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)

        if (sameDay(now, target)) return time

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(yesterday, target)) return "Yesterday • $time"

        val datePattern = if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) "MMM d" else "MMM d, yyyy"
        val day = SimpleDateFormat(datePattern, Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(date)
        return "$day • $time"
    }

    fun fullDateFormatter(date: Date): String {
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        return "$day at $time"
    }

    fun dateFormatterOnlyDayMonthYear(date: Date): String {
        val formatter = DateFormat.getDateInstance(DateFormat.SHORT)
        return formatter.format(date)
    }

    fun areDatesEqual(date1: Date?, date2: Date): Boolean {
        if (date1 == null) return false
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return sameDay(cal1, cal2)
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
''')

# ---------------------------------------------------------------------------
# Utility-first notification details bottom sheet
# ---------------------------------------------------------------------------
write("app/src/main/res/layout/custom_my_dialog.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="20dp"
        android:paddingTop="16dp"
        android:paddingEnd="20dp"
        android:paddingBottom="28dp">

        <View
            android:layout_width="44dp"
            android:layout_height="4dp"
            android:layout_gravity="center_horizontal"
            android:layout_marginBottom="18dp"
            android:background="@drawable/recall_sheet_handle" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <ImageView
                android:id="@+id/ivDialogIcon"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:contentDescription="@string/icon" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/tvDialogApp"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:maxLines="1"
                    android:textColor="?android:attr/textColorSecondary"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/tvDialogDate"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="?android:attr/textColorSecondary"
                    android:textSize="12sp" />
            </LinearLayout>
        </LinearLayout>

        <TextView
            android:id="@+id/tvDialogTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:textColor="?android:textColor"
            android:textSize="22sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvDismissedBadge"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:background="@drawable/recall_badge"
            android:paddingStart="8dp"
            android:paddingTop="3dp"
            android:paddingEnd="8dp"
            android:paddingBottom="3dp"
            android:text="@string/dismissed_badge"
            android:textColor="@color/recall_warning"
            android:textSize="11sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <TextView
            android:id="@+id/tvDialogText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:lineSpacingExtra="3dp"
            android:textColor="?android:textColor"
            android:textIsSelectable="true"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/tvDialogBigText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:lineSpacingExtra="3dp"
            android:textColor="?android:attr/textColorSecondary"
            android:textIsSelectable="true"
            android:textSize="14sp"
            android:visibility="gone" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="22dp"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/bSaveNotification"
                style="@style/Widget.Material3.Button.OutlinedButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:text="@string/save_notification"
                app:icon="@drawable/baseline_bookmark_border_24" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/bCopyNotification"
                style="@style/Widget.Material3.Button.OutlinedButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:layout_weight="1"
                android:text="@string/copy_notification" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/bShareNotification"
                style="@style/Widget.Material3.Button.OutlinedButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:text="@string/share_notification" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/bOpenNotificationApp"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:layout_weight="1"
                android:text="@string/open_source_app" />
        </LinearLayout>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/bShowChat"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/show_chat" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/bNotiAdapterDelete"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/delete_from_recall"
            android:textColor="@color/recall_danger" />
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
''')

write("app/src/main/res/drawable/recall_sheet_handle.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#667085" />
    <corners android:radius="999dp" />
</shape>
''')

write("app/src/main/java/com/android/alftendev/adapters/NotificationsAdapter.kt", r'''
package com.android.alftendev.adapters

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.android.alftendev.R
import com.android.alftendev.jetpackactivities.ChatUIActivity
import com.android.alftendev.models.Notifications
import com.android.alftendev.models.getParsedNoti
import com.android.alftendev.utils.DBUtils
import com.android.alftendev.utils.DateUtils.fullDateFormatter
import com.android.alftendev.utils.DateUtils.smartDateFormatter
import com.android.alftendev.utils.RecallSaved
import com.android.alftendev.utils.Utils
import com.android.alftendev.utils.computables.AppIcon
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView

@SuppressLint("NotifyDataSetChanged")
class NotificationsAdapter(
    private var notifications: List<Notifications>,
    private val context: Context,
    private val selectionListener: OnSelectionChangeListener
) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    var isSelectionMode = false
    val selectedItemsIds = mutableSetOf<Long>()

    interface OnSelectionChangeListener {
        fun onSelectionModeChange(isSelectionMode: Boolean)
        fun onSelectionCountChange(count: Int)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppName: MaterialTextView = view.findViewById(R.id.tvAppName)
        val tvName: MaterialTextView = view.findViewById(R.id.tvNome)
        val tvDescription: MaterialTextView = view.findViewById(R.id.tvDescrizione)
        val tvDate: MaterialTextView = view.findViewById(R.id.tvDate)
        val tvDeletedBadge: TextView = view.findViewById(R.id.tvDeletedBadge)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val btnSave: ImageButton = view.findViewById(R.id.btnSave)
        val cbSelect: MaterialCheckBox = view.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.custom_notification_layout, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val notification = notifications[position]
        val packageTarget = notification.packageName.target
        val packageId = packageTarget.pkg
        val appLabel = packageTarget.name?.takeIf { it.isNotBlank() } ?: packageId
        val parsedNoti = getParsedNoti(notification.title, notification.text, packageId, context)

        viewHolder.tvAppName.text = appLabel
        viewHolder.tvName.text = parsedNoti.title.ifBlank { appLabel }
        viewHolder.tvDescription.text = parsedNoti.text.ifBlank { context.getString(R.string.null_value) }
        viewHolder.tvDate.text = smartDateFormatter(notification.time)
        viewHolder.ivIcon.setImageIcon(parsedNoti.icon)
        viewHolder.tvDeletedBadge.visibility = if (notification.isDeleted) View.VISIBLE else View.GONE

        val saved = RecallSaved.isSaved(context, notification.entityId)
        viewHolder.btnSave.setImageResource(if (saved) R.drawable.baseline_bookmark_24 else R.drawable.baseline_bookmark_border_24)
        viewHolder.btnSave.contentDescription = context.getString(if (saved) R.string.saved_notification else R.string.save_notification)

        viewHolder.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        viewHolder.btnSave.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        viewHolder.cbSelect.isChecked = selectedItemsIds.contains(notification.entityId)

        viewHolder.btnSave.setOnClickListener {
            val nowSaved = RecallSaved.toggle(context, notification.entityId)
            viewHolder.btnSave.setImageResource(if (nowSaved) R.drawable.baseline_bookmark_24 else R.drawable.baseline_bookmark_border_24)
        }

        viewHolder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(notification.entityId)
            } else {
                showDetails(notification, AppIcon.compute(packageId))
            }
        }

        viewHolder.cbSelect.setOnClickListener { toggleSelection(notification.entityId) }

        viewHolder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                isSelectionMode = true
                selectionListener.onSelectionModeChange(true)
                toggleSelection(notification.entityId)
                notifyDataSetChanged()
            }
            true
        }
    }

    override fun getItemCount() = notifications.size

    private fun toggleSelection(entityId: Long) {
        if (selectedItemsIds.contains(entityId)) selectedItemsIds.remove(entityId) else selectedItemsIds.add(entityId)
        selectionListener.onSelectionCountChange(selectedItemsIds.size)
        if (selectedItemsIds.isEmpty()) exitSelectionMode() else notifyDataSetChanged()
    }

    fun selectAll() {
        selectedItemsIds.clear()
        notifications.forEach { selectedItemsIds.add(it.entityId) }
        selectionListener.onSelectionCountChange(selectedItemsIds.size)
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedItemsIds.clear()
        selectionListener.onSelectionCountChange(0)
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItemsIds.clear()
        selectionListener.onSelectionModeChange(false)
        selectionListener.onSelectionCountChange(0)
        notifyDataSetChanged()
    }

    fun updateData(newList: List<Notifications>) {
        notifications = newList
        notifyDataSetChanged()
    }

    @SuppressLint("InflateParams")
    private fun showDetails(notification: Notifications, icon: Drawable?) {
        val view = LayoutInflater.from(context).inflate(R.layout.custom_my_dialog, null, false)
        val sheet = BottomSheetDialog(context)
        sheet.setContentView(view)

        val packageTarget = notification.packageName.target
        val packageId = packageTarget.pkg
        val appLabel = packageTarget.name?.takeIf { it.isNotBlank() } ?: packageId
        val parsed = getParsedNoti(notification.title, notification.text, packageId, context)
        val title = parsed.title.ifBlank { appLabel }
        val text = parsed.text.ifBlank { notification.text.ifBlank { context.getString(R.string.null_value) } }
        val expanded = notification.bigText?.trim().orEmpty()

        view.findViewById<ImageView>(R.id.ivDialogIcon).setImageDrawable(icon)
        view.findViewById<TextView>(R.id.tvDialogApp).text = appLabel
        view.findViewById<TextView>(R.id.tvDialogDate).text = fullDateFormatter(notification.time)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        view.findViewById<TextView>(R.id.tvDialogText).text = text
        view.findViewById<TextView>(R.id.tvDismissedBadge).visibility = if (notification.isDeleted) View.VISIBLE else View.GONE

        val bigTextView = view.findViewById<TextView>(R.id.tvDialogBigText)
        if (expanded.isNotBlank() && expanded != text) {
            bigTextView.text = expanded
            bigTextView.visibility = View.VISIBLE
        }

        val saveButton = view.findViewById<MaterialButton>(R.id.bSaveNotification)
        fun refreshSaveButton() {
            val saved = RecallSaved.isSaved(context, notification.entityId)
            saveButton.text = context.getString(if (saved) R.string.saved_notification else R.string.save_notification)
            saveButton.setIconResource(if (saved) R.drawable.baseline_bookmark_24 else R.drawable.baseline_bookmark_border_24)
        }
        refreshSaveButton()
        saveButton.setOnClickListener {
            RecallSaved.toggle(context, notification.entityId)
            refreshSaveButton()
            notifyDataSetChanged()
        }

        val shareBody = buildString {
            append(title)
            append("\n")
            append(if (expanded.isNotBlank()) expanded else text)
            append("\n\n")
            append(appLabel)
            append(" • ")
            append(fullDateFormatter(notification.time))
        }

        view.findViewById<MaterialButton>(R.id.bCopyNotification).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Recall notification", shareBody))
            Toast.makeText(context, R.string.copied_notification, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.bShareNotification).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareBody)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_notification)))
        }

        view.findViewById<MaterialButton>(R.id.bOpenNotificationApp).setOnClickListener {
            Utils.openApp(packageId, context)
        }

        view.findViewById<MaterialButton>(R.id.bShowChat).setOnClickListener {
            val intent = Intent(context, ChatUIActivity::class.java).setAction(Intent.ACTION_MAIN)
            intent.putExtra("pkgName", packageId)
            intent.putExtra("title", notification.title)
            context.startActivity(intent)
            sheet.dismiss()
        }

        view.findViewById<MaterialButton>(R.id.bNotiAdapterDelete).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_notification)
                .setMessage(R.string.confirm_delete_noti_warning)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    DBUtils.deleteNotificationById(notification.entityId)
                    RecallSaved.remove(context, notification.entityId)
                    notifications = notifications.filterNot { it.entityId == notification.entityId }
                    notifyDataSetChanged()
                    sheet.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        sheet.setOnShowListener {
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        }
        sheet.show()
    }
}
''')

# ---------------------------------------------------------------------------
# Insights: readable donut + ranked sources instead of clipped pie labels
# ---------------------------------------------------------------------------
write("app/src/main/res/layout/activity_pie_graph.xml", r'''
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?backgroundColor">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="18dp">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/insights_title"
            android:textColor="?android:textColor"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="@string/insights_subtitle"
            android:textColor="?android:attr/textColorSecondary"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/tvInsightTotal"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:textColor="?android:textColor"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvInsightPeriod"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:text="@string/all_time_local"
            android:textColor="?android:attr/textColorSecondary"
            android:textSize="12sp" />

        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            app:cardBackgroundColor="?cardBackgroundColor"
            app:cardCornerRadius="22dp"
            app:cardElevation="0dp">

            <com.github.mikephil.charting.charts.PieChart
                android:id="@+id/pieChart"
                android:layout_width="match_parent"
                android:layout_height="330dp"
                android:padding="12dp" />
        </com.google.android.material.card.MaterialCardView>

        <TextView
            android:id="@+id/tvInsightTop"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:textColor="?android:textColor"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="@string/top_sources"
            android:textColor="?android:textColor"
            android:textSize="18sp"
            android:textStyle="bold" />

        <LinearLayout
            android:id="@+id/llInsightList"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:orientation="vertical" />
    </LinearLayout>
</ScrollView>
''')

write("app/src/main/java/com/android/alftendev/activities/home/PieGraphActivity.kt", r'''
package com.android.alftendev.activities.home

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.android.alftendev.R
import com.android.alftendev.activities.specificactivity.SpecificGraphActivity
import com.android.alftendev.utils.AuthUtils.askAuth
import com.android.alftendev.utils.DBUtils
import com.android.alftendev.utils.MySharedPref
import com.android.alftendev.utils.UiUtils
import com.android.alftendev.utils.computables.AppIcon
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.util.Locale

class PieGraphActivity : AppCompatActivity() {
    companion object {
        const val OTHERS_MAX_VALUE = 4.5f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(UiUtils.themeValueToTheme(this, MySharedPref.getThemeOptions()))
        super.onCreate(savedInstanceState)
        askAuth(this)
        setContentView(R.layout.activity_pie_graph)
        UiUtils.uiDefaultSettings(this, false)
        supportActionBar?.title = getString(R.string.graph)

        val chart: PieChart = findViewById(R.id.pieChart)
        val totalView: TextView = findViewById(R.id.tvInsightTotal)
        val topView: TextView = findViewById(R.id.tvInsightTop)
        val list: LinearLayout = findViewById(R.id.llInsightList)

        val total = DBUtils.countNotifications()
        totalView.text = getString(R.string.notifications_archived, total)

        val percentages = DBUtils.getPercentNotifications(this)
            .filterKeys { it != getString(R.string.others) }
            .entries.sortedByDescending { it.value }

        if (percentages.isEmpty()) {
            topView.text = getString(R.string.no_notification_data)
            chart.visibility = View.GONE
            onBackPressedDispatcher.addCallback { finishAndRemoveTask() }
            return
        }

        val top = percentages.first()
        topView.text = getString(R.string.top_source, top.key, String.format(Locale.getDefault(), "%.1f", top.value))

        val topSlices = percentages.take(6)
        val shownTotal = topSlices.sumOf { it.value.toDouble() }.toFloat()
        val entries = ArrayList<PieEntry>()
        topSlices.forEach { entries.add(PieEntry(it.value, it.key)) }
        val remainder = (100f - shownTotal).coerceAtLeast(0f)
        if (remainder >= 0.5f) entries.add(PieEntry(remainder, getString(R.string.others)))

        val palette = listOf(
            Color.parseColor("#4F7DF3"), Color.parseColor("#7C5CFC"),
            Color.parseColor("#19B6A4"), Color.parseColor("#F59E0B"),
            Color.parseColor("#EC4899"), Color.parseColor("#38BDF8"),
            Color.parseColor("#64748B")
        )
        val dataSet = PieDataSet(entries, "")
        dataSet.colors = palette.take(entries.size)
        dataSet.sliceSpace = 3f
        dataSet.selectionShift = 4f
        dataSet.setDrawValues(false)

        chart.data = PieData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawEntryLabels(false)
        chart.isDrawHoleEnabled = true
        chart.holeRadius = 68f
        chart.transparentCircleRadius = 72f
        chart.setHoleColor(Color.TRANSPARENT)
        chart.centerText = total.toString()
        chart.setCenterTextSize(28f)
        chart.setCenterTextColor(if (UiUtils.isDarkThemeOn(this)) Color.WHITE else Color.parseColor("#111827"))
        chart.isRotationEnabled = false
        chart.animateY(450)
        chart.invalidate()

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onNothingSelected() = Unit
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val item = e as? PieEntry ?: return
                if (item.label == getString(R.string.others)) return
                startActivity(Intent(this@PieGraphActivity, SpecificGraphActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    putExtra("appLabel", item.label)
                })
            }
        })

        percentages.take(10).forEachIndexed { index, item ->
            list.addView(sourceRow(item.key, item.value, palette[index % palette.size]))
        }

        onBackPressedDispatcher.addCallback { finishAndRemoveTask() }
    }

    private fun sourceRow(label: String, percent: Float, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 10.dp, 4.dp, 10.dp)
            setOnClickListener {
                startActivity(Intent(this@PieGraphActivity, SpecificGraphActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    putExtra("appLabel", label)
                })
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(38.dp, 38.dp)
            val pkg = DBUtils.nameToPackageName(label)
            setImageDrawable(if (pkg.isNotBlank()) AppIcon.compute(pkg) else null)
        }
        val name = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(if (UiUtils.isDarkThemeOn(this@PieGraphActivity)) Color.WHITE else Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }
        val value = TextView(this).apply {
            text = String.format(Locale.getDefault(), "%.1f%%", percent)
            textSize = 14f
            setTextColor(color)
        }
        row.addView(icon)
        row.addView(name)
        row.addView(value)
        return row
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
''')

# ---------------------------------------------------------------------------
# Final sanity checks
# ---------------------------------------------------------------------------
required = [
    "online.pcguys.recall",
    'versionName "1.1.0"',
]
final_build = read("app/build.gradle")
for token in required:
    if token not in final_build:
        raise SystemExit(f"Missing build token: {token}")
if "DEVICE_CREDENTIAL" not in read("app/src/main/java/com/android/alftendev/utils/AuthUtils.kt"):
    raise SystemExit("Device credential auth patch missing")
if "SavedNotificationsActivity" not in read(manifest_path):
    raise SystemExit("Saved screen manifest entry missing")
print("Recall v1.1 patch complete")
