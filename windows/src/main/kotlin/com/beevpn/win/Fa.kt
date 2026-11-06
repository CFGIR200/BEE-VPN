package com.beevpn.win

/// Persian digits, Jalali + Gregorian dates — the 1:1 port of the Android
/// app's Fa helpers and the iOS Theme.swift copy, so all three platforms print
/// the same numbers and the same days.

private val FA_MAP = mapOf(
    '0' to '۰', '1' to '۱', '2' to '۲', '3' to '۳', '4' to '۴',
    '5' to '۵', '6' to '۶', '7' to '۷', '8' to '۸', '9' to '۹',
)

fun faDigits(s: String): String = buildString { s.forEach { append(FA_MAP[it] ?: it) } }

fun faNum(n: Long): String = faDigits(n.toString())
fun faNum(n: Int): String = faDigits(n.toString())

/// Iran has been UTC+3:30 year-round since 2022 — the same offset the license
/// server uses, so app and bot always print the same day.
const val TEHRAN_OFFSET_SEC: Long = 12_600L

private fun utcDate(epochSec: Long): Triple<Int, Int, Int> {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = (epochSec + TEHRAN_OFFSET_SEC) * 1000L
    return Triple(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

fun gregorianString(epochSec: Long): String {
    val (y, m, d) = utcDate(epochSec)
    return faDigits("%04d-%02d-%02d".format(y, m, d))
}

/// Gregorian → Jalali, the classic jdf.scr.ir algorithm — exactly the code
/// SubscriptionInfoActivity.kt runs, so both apps print the same date.
fun jalaliString(epochSec: Long): String {
    var (gy0, gm, gd) = utcDate(epochSec)
    val gDm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    var jy = if (gy0 <= 1600) 0 else 979
    gy0 -= if (gy0 <= 1600) 621 else 1600
    val gy2 = if (gm > 2) gy0 + 1 else gy0
    var days = 365L * gy0 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
    days += -80 + gDm[gm - 1] + gd
    jy += 33 * (days / 12053).toInt()
    days %= 12053
    jy += 4 * (days / 1461).toInt()
    days %= 1461
    jy += ((days - 1) / 365).toInt()
    if (days > 365) days = (days - 1) % 365
    val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
    val jd = 1 + (if (days < 186) days % 31 else (days - 186) % 30)
    return faDigits("%04d/%02d/%02d".format(jy, jm, jd))
}

/// Human "۳ روز و ۴ ساعت" — the way the bot says it too.
fun daysLeftHuman(expiryEpochSec: Long, nowSec: Long): String {
    val rem = (expiryEpochSec - nowSec).coerceAtLeast(0)
    val days = rem / 86_400
    val hours = (rem % 86_400) / 3_600
    return when {
        days > 0 && hours > 0 -> faNum(days) + " روز و " + faNum(hours) + " ساعت"
        days > 0 -> faNum(days) + " روز"
        hours > 0 -> faNum(hours) + " ساعت"
        else -> faNum(rem / 60) + " دقیقه"
    }
}
