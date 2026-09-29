package dev.krispr.sample.lib

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CartTextTest {
    private val text = CartText(RuntimeEnvironment.getApplication())

    @Test
    fun summarisesItemCountFromResources() {
        val cart = Moshi.Builder().build().adapter(Cart::class.java)
            .fromJson("""{"lines":[{"sku":"a","quantity":2,"unitCents":100},{"sku":"b","quantity":1,"unitCents":5}]}""")!!
        assertEquals("3 items in your cart", text.summary(cart))
    }

    // Deliberately weak: never checks the empty-cart text, so the `count == 0` mutant survives.
    @Test
    fun debugBuildConfig() {
        assertTrue(text.isDebugBuild())
    }
}
