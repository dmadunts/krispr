package dev.krispr.sample.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelTest {
    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    // Lazy: viewModelScope binds Dispatchers.Main when the ViewModel is built, so after setMain.
    private val viewModel by lazy { CartViewModel(CartRepository()) }

    @Test
    fun addingMergesLinesOfTheSameSku() {
        viewModel.add("tea", 250, quantity = 2)
        viewModel.add("tea", 250)
        viewModel.add("cake", 400)
        assertEquals(4, viewModel.state.value.items)
        assertEquals(250L * 3 + 400 + 499, viewModel.state.value.totalCents)
        assertTrue(viewModel.state.value.canCheckout)
    }

    @Test
    fun emptyCartCannotCheckOut() {
        viewModel.add("tea", 250, quantity = 0)
        assertFalse(viewModel.state.value.canCheckout)
    }

    // Weak on purpose: only checks that a valid code changes the total, never the codes that must be
    // rejected, and never the MAX_ITEMS checkout limit.
    @Test
    fun redeemingACodeLowersTheTotal() {
        viewModel.add("tea", 1000)
        val before = viewModel.state.value.totalCents
        viewModel.redeem("SAVE10")
        assertTrue(viewModel.state.value.totalCents < before)
    }
}
