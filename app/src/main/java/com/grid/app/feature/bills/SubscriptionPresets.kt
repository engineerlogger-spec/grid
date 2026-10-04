package com.grid.app.feature.bills

import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit

/** Popular recurring payments, for one-tap setup. Names only — no third-party logos are bundled. */
data class SubscriptionPreset(
    val name: String,
    val colorKey: String,
    /** Icon key of the seeded category to use (Subscriptions, Bills & Utilities, Housing, Health…). */
    val categoryIcon: String = "subscriptions",
    val cycle: Cycle = Cycle.Monthly,
)

object SubscriptionPresets {
    val all = listOf(
        SubscriptionPreset("Netflix", "red"),
        SubscriptionPreset("Spotify", "green"),
        SubscriptionPreset("YouTube Premium", "red"),
        SubscriptionPreset("Disney+", "indigo"),
        SubscriptionPreset("Prime Video", "sky"),
        SubscriptionPreset("Apple One", "slate"),
        SubscriptionPreset("iCloud+", "sky"),
        SubscriptionPreset("Google One", "blue"),
        SubscriptionPreset("ChatGPT Plus", "teal"),
        SubscriptionPreset("Claude Pro", "peach"),
        SubscriptionPreset("Microsoft 365", "orange", cycle = Cycle(CycleUnit.YEAR, 1)),
        SubscriptionPreset("Adobe Creative Cloud", "red"),
        SubscriptionPreset("Xbox Game Pass", "green"),
        SubscriptionPreset("PlayStation Plus", "blue"),
        SubscriptionPreset("Deezer", "violet"),
        SubscriptionPreset("Audible", "orange"),
        SubscriptionPreset("Dropbox", "blue"),
        SubscriptionPreset("Duolingo", "lime"),
        SubscriptionPreset("Gym", "lime", categoryIcon = "health"),
        SubscriptionPreset("Phone plan", "amber", categoryIcon = "bills"),
        SubscriptionPreset("Internet", "sky", categoryIcon = "bills"),
        SubscriptionPreset("Electricity", "yellow", categoryIcon = "bills"),
        SubscriptionPreset("Insurance", "slate", categoryIcon = "bills"),
        SubscriptionPreset("Rent", "sand", categoryIcon = "housing"),
    )
}
