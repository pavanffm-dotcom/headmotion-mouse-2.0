package com.assistive.headmouse.agent.jarvis.autonomous

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Real-time Information Tools (R30, R31).
 * Provides grounded device context, calendar/clock info, and live web query results.
 */
object InformationTools {

    /**
     * Returns true local device time, date, day of week, and timezone.
     */
    fun getCurrentTime(): String {
        val now = Date()
        val sdf = SimpleDateFormat("hh:mm a, EEEE, MMMM dd, yyyy", Locale.getDefault())
        val tz = TimeZone.getDefault().displayName
        return "${sdf.format(now)} ($tz)"
    }

    /**
     * Executes real web search and formats structured results for the AI reasoning engine.
     */
    suspend fun queryWeb(query: String): String {
        val results = WebSearchEngine.search(query)
        return formatResults(query, results)
    }

    /**
     * Formats structured WebSearchResults into a grounded text block for reasoning engines.
     */
    fun formatResults(query: String, results: List<WebSearchResult>): String {
        if (results.isEmpty()) {
            return "Web Search for \"$query\": No results could be retrieved from search providers."
        }

        val sb = StringBuilder()
        sb.append("Live Web Search Results for \"$query\":\n")
        for ((idx, r) in results.withIndex()) {
            sb.append("${idx + 1}. ${r.title}\n   ${r.snippet}\n   Source: ${r.url}\n")
        }
        return sb.toString().trim()
    }

    /**
     * Reads actual system hardware state (battery percentage, charging status, Wi-Fi connectivity).
     */
    fun getDeviceStatus(context: Context): String {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else -1
        val isCharging: Boolean = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val freeGb = (freeBytes / (1024 * 1024 * 1024.0)).let { String.format(Locale.US, "%.1f", it) }

        return "Battery: $batteryPct% ${if (isCharging) "(Charging)" else ""}, Network: ${if (isWifi) "Wi-Fi" else if (isCellular) "Mobile Data" else "Offline"}, Storage Free: ${freeGb}GB"
    }
}
