package online.pcguys.pockettally

import org.junit.Assert.assertEquals
import org.junit.Test

class TallyMathTest {
    @Test
    fun safeAddSaturatesAtLongLimits() {
        assertEquals(Long.MAX_VALUE, TallyMath.safeAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MIN_VALUE, TallyMath.safeAdd(Long.MIN_VALUE, -1L))
    }

    @Test
    fun safeAddPreservesOrdinaryCounts() {
        assertEquals(42L, TallyMath.safeAdd(40L, 2L))
        assertEquals(38L, TallyMath.safeAdd(40L, -2L))
    }

    @Test
    fun progressIsClampedAndHandlesMissingGoals() {
        assertEquals(0f, TallyMath.progress(20L, null))
        assertEquals(0f, TallyMath.progress(-5L, 10L))
        assertEquals(0.5f, TallyMath.progress(5L, 10L))
        assertEquals(1f, TallyMath.progress(15L, 10L))
    }
}
