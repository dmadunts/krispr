package dev.krispr.sample.lib

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Compose adds `$composer`, `$changed` and default-mask logic here; only the user's comparisons get mutants. */
@Composable
fun cartBadge(count: Int, max: Int = 99): String {
    val label = remember(count, max) { if (count > max) "$max+" else count.toString() }
    return label
}
