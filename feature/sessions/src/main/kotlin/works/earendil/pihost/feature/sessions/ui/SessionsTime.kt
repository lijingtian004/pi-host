package works.earendil.pihost.feature.sessions.ui

import java.time.Duration
import java.time.Instant

/**
 * 列表里的「修改时间相对化」与体积显示（规格书 §4.1）。
 *
 * 纯函数 + 可注入 `now`：UI 测试不必等真时钟走一分钟，
 * 「3 分钟前」这类断言直接喂一个 [Instant] 就能钉死。
 */
object SessionsTime {

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前；设备时钟超前时明说。 */
    fun relative(modified: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(modified, now).seconds
        if (seconds < 0) return SessionsStrings.FUTURE
        if (seconds < 60) return SessionsStrings.JUST_NOW
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes${SessionsStrings.MINUTES_AGO}"
        val hours = minutes / 60
        if (hours < 24) return "$hours${SessionsStrings.HOURS_AGO}"
        return "${hours / 24}${SessionsStrings.DAYS_AGO}"
    }

    /** 体积：B / KB / MB 三档，整除取整（列表页只要「量级」，不要小数点噪声）。 */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024L * 1024)} MB"
    }

    /** 标题截断（§4.1「firstMessage 截断」）：换行压成空格，超上限加省略号。 */
    fun truncate(text: String, limit: Int = 80): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (flat.length <= limit) return flat
        return flat.take(limit - 1).trimEnd() + "…"
    }
}
