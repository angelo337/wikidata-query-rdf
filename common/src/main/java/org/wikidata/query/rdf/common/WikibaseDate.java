package org.wikidata.query.rdf.common;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles wikidata dates. Note that this ignores leap seconds. This isn't ok
 * but its what joda time does so it where we're starting.
 */
public class WikibaseDate {
    private static final Pattern FORMAT_PATTERN = Pattern
            .compile("(?<year>[+-]?0+)-(?<month>0?0)-(?<day>0?0)(?:T(?<hour>0?0):(?<minute>0?0)(?::(?<second>0?0))?)?Z?"
                    .replace("0", "\\d"));

    /**
     * Build a WikibaseDate from the string representation. Supported:
     * <ul>
     * <li>+YYYYYYYYYYYY-MM-DDThh:mm:ssZ (Wikidata's default)
     * <li>YYYY-MM-DDThh:mm:ssZ (xsd:dateTime)
     * <li>YYYY-MM-DD (xsd:date with time assumed to be 00:00:00)
     * <li>
     * </ul>
     */
    public static WikibaseDate fromString(String string) {
        // TODO timezones
        Matcher m = FORMAT_PATTERN.matcher(string);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid date format:  " + string);
        }
        long year = parseLong(m.group("year"));
        // TODO two digit years without leading zeros might mean something other
        // than the year 20.
        int month = parseInt(m.group("month"));
        int day = parseInt(m.group("day"));
        int hour = parseOr0(m, "hour");
        int minute = parseOr0(m, "minute");
        int second = parseOr0(m, "second");
        return new WikibaseDate(year, month, day, hour, minute, second);
    }

    private static int parseOr0(Matcher m, String group) {
        String matched = m.group(group);
        if (matched == null) {
            return 0;
        }
        return parseInt(matched);
    }

    /**
     * Build a WikibaseDAte from seconds since epoch.
     */
    public static WikibaseDate fromSecondsSinceEpoch(long secondsSinceEpoch) {
        long year = yearFromSecondsSinceEpoch(secondsSinceEpoch);
        int second = (int) (secondsSinceEpoch - calculateFirstDayOfYear(year) * SECONDS_PER_DAY);
        int month = 1;
        long[] secondsPerMonthCumulative = secondsPerMonthCumulative(year);
        while (month < 12 && second >= secondsPerMonthCumulative[month]) {
            month++;
        }
        second -= secondsPerMonthCumulative[month - 1];
        int day = second / SECONDS_PER_DAY + 1;
        second %= SECONDS_PER_DAY;
        int hour = second / SECONDS_PER_HOUR;
        second %= SECONDS_PER_HOUR;
        int minute = second / SECONDS_PER_MINUTE;
        second %= SECONDS_PER_MINUTE;
        return new WikibaseDate(year, month, day, hour, minute, second);
    }

    private static final int DAYS_0000_TO_1970 = 719527;
    private static final int SECONDS_PER_MINUTE = (int) MINUTES.toSeconds(1);
    private static final int SECONDS_PER_HOUR = (int) HOURS.toSeconds(1);
    private static final int SECONDS_PER_DAY = (int) DAYS.toSeconds(1);
    private static final long AVERAGE_SECONDS_PER_YEAR = (SECONDS_PER_DAY * 365 * 3 + SECONDS_PER_DAY * 366) / 4;
    private static final long SECONDS_AT_EPOCH = 1970 * AVERAGE_SECONDS_PER_YEAR;
    /**
     * Days per month in non-leap-years.
     */
    static final int[] DAYS_PER_MONTH = new int[] { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
    private static final long[] SECONDS_PER_MONTH = new long[12];
    private static final long[] SECONDS_PER_MONTH_CUMULATIVE = new long[12];
    private static final long[] SECONDS_PER_MONTH_CUMULATIVE_LEAP_YEAR;
    static {
        long total = 0;
        for (int i = 0; i < DAYS_PER_MONTH.length; i++) {
            SECONDS_PER_MONTH[i] = DAYS.toSeconds(DAYS_PER_MONTH[i]);
            SECONDS_PER_MONTH_CUMULATIVE[i] = total;
            total += SECONDS_PER_MONTH[i];
        }
        SECONDS_PER_MONTH_CUMULATIVE_LEAP_YEAR = Arrays.copyOf(SECONDS_PER_MONTH_CUMULATIVE,
                SECONDS_PER_MONTH_CUMULATIVE.length);
        for (int i = 2; i < SECONDS_PER_MONTH_CUMULATIVE_LEAP_YEAR.length; i++) {
            SECONDS_PER_MONTH_CUMULATIVE_LEAP_YEAR[i] += SECONDS_PER_DAY;
        }
    }

    // TODO it'll be faster to keep it in seconds since epoch form
    private final long year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;
    private final int second;

    public WikibaseDate(long year, int month, int day, int hour, int minute, int second) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
    }

    /**
     * Wikidata contains some odd dates like -13798000000-00-00T00:00:00Z and
     * February 30th. We simply guess what they mean here.
     *
     * @return his if the date is fine, a new date if we modified it
     */
    public WikibaseDate cleanWeirdStuff() {
        long newYear = year;
        int newMonth = month;
        int newDay = day;
        int newHour = hour;
        int newMinute = minute;
        int newSecond = second;
        if (month == 0) {
            newMonth = 1;
        }
        if (day == 0) {
            newDay = 1;
        } else {
            int maxDaysInMonth = DAYS_PER_MONTH[newMonth - 1];
            if (isLeapYear(newYear) && newMonth == 2) {
                maxDaysInMonth++;
            }
            if (newDay > maxDaysInMonth) {
                newMonth++;
                newDay = newDay - maxDaysInMonth + 1;
                if (newMonth > 12) {
                    newMonth = newMonth - 12;
                    newYear++;
                }
            }
        }
        if (newYear == year && newMonth == month && newDay == day && newHour == hour && newMinute == minute
                && newSecond == second) {
            return this;
        }
        return new WikibaseDate(newYear, newMonth, newDay, newHour, newMinute, newSecond);
    }

    public long secondsSinceEpoch() {
        long seconds = calculateFirstDayOfYear(year) * SECONDS_PER_DAY;
        seconds += SECONDS_PER_MONTH_CUMULATIVE[month - 1];
        seconds += (day - 1) * SECONDS_PER_DAY;
        seconds += hour * SECONDS_PER_HOUR;
        seconds += minute * SECONDS_PER_MINUTE;
        seconds += second;
        if (month > 2 && isLeapYear(year)) {
            seconds += SECONDS_PER_DAY;
        }
        return seconds;
    }

    /**
     * Build a WikibaseDate from the string representation. See ToStringFormat
     * for more.
     */
    public String toString(ToStringFormat format) {
        return format.format(this);
    }

    @Override
    public String toString() {
        return toString(ToStringFormat.WIKIDATA);
    }

    /**
     * Year component of the date.
     */
    public long year() {
        return year;
    }

    /**
     * Month component of the date.
     */
    public int month() {
        return month;
    }

    /**
     * Day component of the date.
     */
    public int day() {
        return day;
    }

    /**
     * Hour component of the date.
     */
    public int hour() {
        return hour;
    }

    /**
     * Minute component of the date.
     */
    public int minute() {
        return minute;
    }

    /**
     * Second component of the date.
     */
    public int second() {
        return second;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + day;
        result = prime * result + hour;
        result = prime * result + minute;
        result = prime * result + month;
        result = prime * result + second;
        result = prime * result + (int) (year ^ (year >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        WikibaseDate other = (WikibaseDate) obj;
        if (day != other.day) {
            return false;
        }
        if (hour != other.hour) {
            return false;
        }
        if (minute != other.minute) {
            return false;
        }
        if (month != other.month) {
            return false;
        }
        if (second != other.second) {
            return false;
        }
        if (year != other.year) {
            return false;
        }
        return true;
    }

    /**
     * Format for toString.
     */
    public static enum ToStringFormat {
        /**
         * Wikidata style (+YYYYYYYYYYYY-MM-DDThh:mm:ssZ).
         */
        WIKIDATA {
            @Override
            public String format(WikibaseDate date) {
                return String.format(Locale.ROOT, "%+012d-%02d-%02dT%02d:%02d:%02dZ", date.year, date.month, date.day,
                        date.hour, date.minute, date.second);
            }
        },
        /**
         * xsd:dateTime style (YYYY-MM-DDThh:mm:ssZ).
         */
        DATE_TIME {
            @Override
            public String format(WikibaseDate date) {
                return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02dZ", date.year, date.month, date.day,
                        date.hour, date.minute, date.second);
            }
        },
        /**
         * xsd:date style (YYYY-MM-DD).
         */
        DATE {
            @Override
            public String format(WikibaseDate date) {
                return String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.month, date.day);
            }
        };

        public abstract String format(WikibaseDate date);
    }

    static boolean isLeapYear(long year) {
        // Borrowed from joda-time's GregorianChronology
        return ((year & 3) == 0) && ((year % 100) != 0 || (year % 400) == 0);
    }

    static long calculateFirstDayOfYear(long year) {
        /*
         * This is a clever hack for getting the number of leap years that works
         * properly for negative years borrowed from JodaTime's
         * GregorianChronology.
         */
        long leapYears = year / 100;
        if (year < 0) {
            leapYears = ((year + 3) >> 2) - leapYears + ((leapYears + 3) >> 2) - 1;
        } else {
            leapYears = (year >> 2) - leapYears + (leapYears >> 2);
            if (isLeapYear(year)) {
                leapYears--;
            }
        }
        return year * 365L + leapYears - DAYS_0000_TO_1970;
    }

    static long yearFromSecondsSinceEpoch(long secondsSinceEpoch) {
        /*
         * Similar to Joda-Time's way of getting year from date - estimate and
         * then fix the estimate. Except our estimates can be really off.
         */
        long unitSeconds = AVERAGE_SECONDS_PER_YEAR / 2;
        long i2 = secondsSinceEpoch / 2 + SECONDS_AT_EPOCH / 2;
        if (i2 < 0) {
            i2 = i2 - unitSeconds + 1;
        }
        long year = i2 / unitSeconds;
        while (true) {
            // Rerunning calculateFirstDayOfYear isn't going to be efficient
            // here.
            long yearStart = calculateFirstDayOfYear(year) * SECONDS_PER_DAY;
            long diff = secondsSinceEpoch - yearStart;
            if (diff < 0) {
                year--;
                continue;
            }
            if (diff >= SECONDS_PER_DAY * 365) {
                yearStart += SECONDS_PER_DAY * 365;
                if (isLeapYear(year)) {
                    yearStart += SECONDS_PER_DAY;
                }
                if (yearStart <= secondsSinceEpoch) {
                    year++;
                    continue;
                }
            }
            return year;
        }
    }

    static long[] secondsPerMonthCumulative(long year) {
        if (isLeapYear(year)) {
            return SECONDS_PER_MONTH_CUMULATIVE_LEAP_YEAR;
        }
        return SECONDS_PER_MONTH_CUMULATIVE;
    }
}