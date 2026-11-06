// BEE-VPN for Windows — Jalali (Persian, شمسی) dates.
// Backed by System.Globalization.PersianCalendar, verified date-for-date
// against the ICU calendar that the Telegram bot's Intl-based faJalali uses
// (Nowruz 1404→1405 boundaries, month lengths) — so the buyer sees the same
// dates in the bot and in the app. Tehran is UTC+3:30 year-round (no DST).
using System;
using System.Globalization;

namespace BeeVpn;

public static class Jalali
{
    static readonly PersianCalendar Pc = new();
    static readonly TimeSpan Tehran = new(3, 30, 0);
    static readonly string[] FaDigits = { "۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹" };

    /// <summary>Persian digits with 3-digit grouping, like the bot's faNum.</summary>
    public static string Fa(long n)
    {
        bool neg = n < 0;
        string s = Math.Abs(n).ToString();
        var sb = new System.Text.StringBuilder();
        if (neg) sb.Append('−');
        for (int i = 0; i < s.Length; i++)
        {
            if (i > 0 && (s.Length - i) % 3 == 0) sb.Append('٬');
            sb.Append(FaDigits[s[i] - '0']);
        }
        return sb.ToString();
    }

    /// <summary>Persian digits, no grouping — for years and date parts.</summary>
    public static string FaPlain(long n)
    {
        bool neg = n < 0;
        string s = Math.Abs(n).ToString();
        var sb = new System.Text.StringBuilder();
        if (neg) sb.Append('−');
        foreach (char c in s) sb.Append(FaDigits[c - '0']);
        return sb.ToString();
    }

    static string Two(int v) => v < 10 ? "۰" + FaPlain(v) : FaPlain(v);

    public static (int jy, int jm, int jd) ToJalali(DateTime gregorian)
        => (Pc.GetYear(gregorian), Pc.GetMonth(gregorian), Pc.GetDayOfMonth(gregorian));

    /// <summary>«۱۴۰۵/۰۸/۱۵» from a Tehran-local date.</summary>
    public static string FaDate(DateTime tehranLocal)
    {
        var (jy, jm, jd) = ToJalali(tehranLocal);
        return $"{FaPlain(jy)}/{Two(jm)}/{Two(jd)}";
    }

    /// <summary>«۱۴۰۵/۰۸/۱۵ ۱۸:۳۰» from a Tehran-local datetime.</summary>
    public static string FaDateTime(DateTime tehranLocal)
        => $"{FaDate(tehranLocal)} {Two(tehranLocal.Hour)}:{Two(tehranLocal.Minute)}";

    static DateTime TehranLocal(long epochSec)
        => DateTimeOffset.FromUnixTimeSeconds(epochSec).ToOffset(Tehran).DateTime;

    /// <summary>Epoch seconds → Jalali date in Tehran time.</summary>
    public static string FaDateEpoch(long epochSec) => FaDate(TehranLocal(epochSec));

    /// <summary>Epoch seconds → Jalali date + HH:mm in Tehran time.</summary>
    public static string FaDateTimeEpoch(long epochSec) => FaDateTime(TehranLocal(epochSec));
}
