// Vita3K emulator project
// Copyright (C) 2026 Vita3K team
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

#include <rtc/rtc.h>

#include <util/log.h>

#include <atomic>

namespace {
// While an emulator pause is active (guest threads suspended, audio stream
// paused) the guest-visible clock must stay frozen as well. Without this, the
// game resumes after a long pause with a system/RTC-time based timeline that
// is seconds to minutes in the future, while the frozen audio playback only
// continues from the pause point - the sound lags the picture by exactly the
// pause duration.
std::atomic<std::uint64_t> rtc_accumulated_paused_ticks{ 0 };
std::atomic<bool> rtc_clock_paused{ false };
std::atomic<std::uint64_t> rtc_pause_start_ticks{ 0 };

std::uint64_t rtc_host_now_ticks() {
    const auto now = std::chrono::high_resolution_clock::now();
    return std::chrono::time_point_cast<VitaClocks>(now).time_since_epoch().count();
}
} // namespace

void rtc_set_clock_paused(const bool paused) {
    if (paused) {
        if (!rtc_clock_paused.exchange(true, std::memory_order_relaxed))
            rtc_pause_start_ticks.store(rtc_host_now_ticks(), std::memory_order_relaxed);
    } else if (rtc_clock_paused.exchange(false, std::memory_order_relaxed)) {
        const std::uint64_t delta = rtc_host_now_ticks() - rtc_pause_start_ticks.load(std::memory_order_relaxed);
        rtc_accumulated_paused_ticks.fetch_add(delta, std::memory_order_relaxed);
        LOG_INFO("[RTC] guest clock unfrozen after a {:.2f} s pause", static_cast<double>(delta) / VITA_CLOCKS_PER_SEC);
    }
}

void rtc_reset_clock_state() {
    // The clock-pause bookkeeping lives at process scope while a game can be
    // launched, closed and relaunched several times inside the same emulator
    // process. If a game is closed while paused, the frozen state would leak
    // into the next game: its guest clock never advances, time-based logic
    // stalls and the game can hang with no more frames presented. Start every
    // game with a clean, running clock.
    rtc_accumulated_paused_ticks.store(0, std::memory_order_relaxed);
    rtc_pause_start_ticks.store(0, std::memory_order_relaxed);
    rtc_clock_paused.store(false, std::memory_order_relaxed);
}

std::uint64_t rtc_guest_clock_ticks() {
    const std::uint64_t host_now = rtc_host_now_ticks();
    const std::uint64_t effective = rtc_clock_paused.load(std::memory_order_relaxed)
        ? rtc_pause_start_ticks.load(std::memory_order_relaxed)
        : host_now;
    return effective - rtc_accumulated_paused_ticks.load(std::memory_order_relaxed);
}

bool rtc_clock_is_paused() {
    return rtc_clock_paused.load(std::memory_order_relaxed);
}

static std::uint64_t rtc_ticks_since_epoch() {
    return rtc_guest_clock_ticks();
}

std::uint64_t rtc_base_ticks() {
    return RTC_OFFSET + std::time(nullptr) * VITA_CLOCKS_PER_SEC - rtc_ticks_since_epoch();
}

std::uint64_t rtc_get_ticks(uint64_t base_ticks) {
    return base_ticks + rtc_ticks_since_epoch();
}

// The following functions are from PPSSPP
// Copyright (c) 2012- PPSSPP Project.

void __RtcPspTimeToTm(tm *val, const SceDateTime *pt) {
    val->tm_year = pt->year - 1900;
    val->tm_mon = pt->month - 1;
    val->tm_mday = pt->day;
    val->tm_wday = -1;
    val->tm_yday = -1;
    val->tm_hour = pt->hour;
    val->tm_min = pt->minute;
    val->tm_sec = pt->second;
    val->tm_isdst = 0;
}

void __RtcTicksToPspTime(SceDateTime *t, std::uint64_t ticks) {
    int numYearAdd = 0;
    if (ticks < VITA_CLOCKS_PER_SEC) {
        t->year = 1;
        t->month = 1;
        t->day = 1;
        t->hour = 0;
        t->minute = 0;
        t->second = 0;
        t->microsecond = ticks;
        return;
    } else if (ticks < RTC_OFFSET) {
        // Need to get a year past 1970 for gmtime
        // Add enough 400 year to pass over 1970.
        numYearAdd = (int)((RTC_OFFSET - ticks) / RTC_400_YEAR_TICKS + 1);
        ticks += RTC_400_YEAR_TICKS * numYearAdd;
    }

    while (ticks >= RTC_OFFSET + RTC_400_YEAR_TICKS) {
        ticks -= RTC_400_YEAR_TICKS;
        --numYearAdd;
    }

    time_t time = (ticks - RTC_OFFSET) / VITA_CLOCKS_PER_SEC;
    t->microsecond = ticks % VITA_CLOCKS_PER_SEC;

    tm *local = gmtime(&time);
    if (!local) {
        LOG_ERROR("Date is too high/low to handle, pretending to work.");
        return;
    }

    t->year = local->tm_year + 1900 - numYearAdd * 400;
    t->month = local->tm_mon + 1;
    t->day = local->tm_mday;
    t->hour = local->tm_hour;
    t->minute = local->tm_min;
    t->second = local->tm_sec;
}

std::uint64_t __RtcPspTimeToTicks(const SceDateTime *pt) {
    tm local;
    __RtcPspTimeToTm(&local, pt);

    std::int64_t tickOffset = 0;
    while (local.tm_year < 70) {
        tickOffset -= RTC_400_YEAR_TICKS;
        local.tm_year += 400;
    }
    while (local.tm_year >= 470) {
        tickOffset += RTC_400_YEAR_TICKS;
        local.tm_year -= 400;
    }

    time_t seconds = rtc_timegm(&local);
    std::uint64_t result = RTC_OFFSET + (std::uint64_t)seconds * VITA_CLOCKS_PER_SEC;
    result += pt->microsecond;
    return result + tickOffset;
}
