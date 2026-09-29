package dev.krispr.playground.mocks

import android.content.Context

enum class Tier(val monthlyCents: Long) {
    FREE(0), PLUS(499), PRO(1299);

    fun allows(feature: String): Boolean = when (this) {
        FREE -> feature == "read"
        PLUS -> feature != "export"
        PRO -> true
    }
}

interface Analytics {
    fun track(event: String, props: Map<String, Any?> = emptyMap())
    fun userId(): String?
}

/** Sends messages through a lambda callback, the Compose-style `(T) -> Unit` a test mocks. */
class Notifier(
    private val context: Context,
    private val analytics: Analytics,
    private val onMessage: (String) -> Unit,
    private val onCount: (Int) -> Unit = {},
) {
    private var sent = 0

    fun notifyUpgrade(tier: Tier, feature: String): Boolean {
        if (tier.allows(feature)) return false
        val price = Tier.entries.firstOrNull { it.allows(feature) }?.monthlyCents ?: return false
        onMessage("Upgrade to use $feature from ${price / 100}.${(price % 100).toString().padStart(2, '0')}")
        sent++
        onCount(sent)
        analytics.track("upgrade_prompt", mapOf("feature" to feature, "user" to analytics.userId()))
        return true
    }

    fun welcome(name: String?) {
        val label = context.applicationInfo.packageName.substringAfterLast('.')
        val who = if (name.isNullOrBlank()) "there" else name.trim()
        onMessage("Welcome to $label, $who")
        analytics.track("welcome")
    }
}
