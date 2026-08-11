package online.pcguys.pockettally

object TallyMath {
    fun safeAdd(value: Long, delta: Long): Long = try {
        Math.addExact(value, delta)
    } catch (_: ArithmeticException) {
        if (delta >= 0L) Long.MAX_VALUE else Long.MIN_VALUE
    }

    fun appliedDelta(before: Long, after: Long): Long = try {
        Math.subtractExact(after, before)
    } catch (_: ArithmeticException) {
        if (after >= before) Long.MAX_VALUE else Long.MIN_VALUE
    }

    fun progress(value: Long, goal: Long?): Float {
        if (goal == null || goal <= 0L) return 0f
        return (value.toDouble() / goal.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
