package com.galaxyrio.gracelauncher.assistant

import java.io.File
import javax.crypto.KeyGenerator
import org.json.JSONObject

/** SYNTHETIC fixtures shaped like mcp_coach.py / assistant_context_builder.py output. Not real data. */
internal object HealthFixtures {
    const val TOKEN_A = "hc_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    const val TOKEN_B = "hc_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    fun vault(dir: File = java.nio.file.Files.createTempDirectory("hv").toFile()): HealthVault {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return HealthVault(dir, GcmAead { key })
    }
    val status get() = JSONObject("""{"latest_metric_date":"2026-11-17","days_behind":1,"days_with_data_last_7":6,
        "coverage_by_metric_last_7":{"steps":6,"sleep":5,"resting_heart_rate":0,"weight":0},"coverage_start":"2026-11-12","coverage_end":"2026-11-18",
        "today_is_partial":true,"fresh_enough_for_current_coaching":true,"connections":[{"provider":"google","status":"active"}],
        "reauthorization_may_be_required":false,"timezone":"Europe/Berlin","timezone_source":"host","checked_at":"2026-11-18T09:12:00+01:00"}""")
    fun window(days: Int = 7, steps: String = """{"avg":7412.4,"min":0.0,"max":11020.0,"count":6}""", hr: String = "null",
        sleep: String = """{"rolling_avg":6.9,"nightly_stats":{"avg":6.9,"min":5.1,"max":8.0,"count":5}}""", weight: String = "null",
        dwd: Int = 6, cov: Int = 6, partial: Boolean = true) = """{"days":$days,"today_is_partial":$partial,"range":{"start":"2026-11-12","end":"2026-11-18"},
        "days_with_data":$dwd,"coverage_by_metric":{"steps":$cov,"sleep":5,"resting_hr":${if (hr == "null") 0 else 5},"weight":0},
        "latest_date_by_metric":{"steps":"2026-11-17","sleep":"2026-11-17","resting_hr":null,"weight":null},
        "steps":$steps,"sleep_hours":$sleep,"resting_hr":$hr,"weight":$weight}"""
    fun profile(goal: String = "run 5k") = """{"weekly_schedule":{},"short_term_goals":["$goal"],"long_term_goals":[],"sleep_goal_hours":8.0,"wake_time":"07:00","bedtime":"23:00","step_goal":8000}"""
    fun chat(w7: String = window(), profile: String = profile(), today: String = """{"metric_date":"2026-11-18","steps_total":1204}""") = JSONObject(
        """{"generated_at":"2026-11-18T09:12:00+01:00","mode":"chat_reply","timezone":"Europe/Berlin","timezone_source":"host",
        "windows":{"7d":$w7,"30d":${window(30)},"last_night":null,"today":$today},"data_completeness":{"days_with_data":6,"days_requested":7},"user_profile":$profile}""")
    val weekly get() = JSONObject("""{"mode":"weekly_coach","timezone":"Europe/Berlin","windows":{"14d":${window(14)},
        "streaks":{"steps_goal_streak_days":3,"goal":8000,"window_days":30},"anomalies":[{"date":"2026-11-14","type":"low_steps","value":2100,"avg":7400}]},
        "user_profile":${profile()}}""")
    fun snapshot(weekly: Boolean = false) = HealthSnapshot(status, linkedMapOf("chat_reply" to chat()).also { if (weekly) it["weekly_coach"] = this.weekly })
}
