package cn.orzmc.realtime.core.device

import java.time.LocalTime

object BcdHhmmEncoder {
    fun encode(time: LocalTime): BcdHhmmFrame = encode(time.hour, time.minute)

    fun encode(
        hour: Int,
        minute: Int,
    ): BcdHhmmFrame {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        require(minute in 0..59) { "minute must be between 0 and 59" }

        return BcdHhmmFrame(
            hourTens = hour / 10,
            hourOnes = hour % 10,
            minuteTens = minute / 10,
            minuteOnes = minute % 10,
        )
    }
}
