package com.ghissue.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ghissue.app.CreateIssueActivity
import com.ghissue.app.R
import com.ghissue.app.storage.PrefsStore

open class CreateIssueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefsStore = PrefsStore(context)
        for (widgetId in appWidgetIds) {
            prefsStore.clearWidgetRepo(widgetId)
        }
    }

    companion object {
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val intent = Intent(context, CreateIssueActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prefsStore = PrefsStore(context)
            val widgetRepo = prefsStore.getWidgetRepo(widgetId)
            val repoLabel = widgetRepo?.second
                ?: prefsStore.repoName.ifBlank { null }
                ?: ""

            val color = prefsStore.getWidgetColor(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_create_issue).apply {
                setOnClickPendingIntent(R.id.widgetButton, pendingIntent)
                setTextViewText(R.id.widgetRepoLabel, repoLabel)
                setInt(R.id.widgetBg, "setColorFilter", color)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
