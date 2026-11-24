package cn.orzmc.realtime.core.device

import java.time.LocalTime

object BcdTimeEncoder {
    fun encode(time: LocalTime): BcdTimeFrame = encode(time.hour, time.minute, time.second)

    fun encode(
        hour: Int,
        minute: Int,
        second: Int,
    ): BcdTimeFrame {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        require(minute in 0..59) { "minute must be between 0 and 59" }
        require(second in 0..59) { "second must be between 0 and 59" }

        return BcdTimeFrame(
            hourTens = hour / 10,
            hourOnes = hour % 10,
            minuteTens = minute / 10,
            minuteOnes = minute % 10,
            secondTens = second / 10,
            secondOnes = second % 10,
        )
    }
}
