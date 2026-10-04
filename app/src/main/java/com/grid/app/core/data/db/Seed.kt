package com.grid.app.core.data.db

import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentKind

/** Default data inserted when the database is first created. */
object Seed {
    data class SeedCategory(val name: String, val iconKey: String, val colorKey: String, val kind: CategoryKind)
    data class SeedMethod(val name: String, val kind: PaymentKind)

    val categories = listOf(
        SeedCategory("Restaurants", "restaurant", "orange", CategoryKind.EXPENSE),
        SeedCategory("Groceries", "groceries", "mint", CategoryKind.EXPENSE),
        SeedCategory("Transport", "transport", "sky", CategoryKind.EXPENSE),
        SeedCategory("Shopping", "shopping", "pink", CategoryKind.EXPENSE),
        SeedCategory("Clothing", "clothing", "magenta", CategoryKind.EXPENSE),
        SeedCategory("Bills & Utilities", "bills", "amber", CategoryKind.EXPENSE),
        SeedCategory("Subscriptions", "subscriptions", "violet", CategoryKind.EXPENSE),
        SeedCategory("Housing", "housing", "sand", CategoryKind.EXPENSE),
        SeedCategory("Health", "health", "red", CategoryKind.EXPENSE),
        SeedCategory("Entertainment", "entertainment", "indigo", CategoryKind.EXPENSE),
        SeedCategory("Travel", "travel", "teal", CategoryKind.EXPENSE),
        SeedCategory("Education", "education", "blue", CategoryKind.EXPENSE),
        SeedCategory("Gifts", "gifts", "yellow", CategoryKind.EXPENSE),
        SeedCategory("Personal care", "personal_care", "peach", CategoryKind.EXPENSE),
        SeedCategory("Services", "services", "slate", CategoryKind.EXPENSE),
        SeedCategory("Cash", "cash", "green", CategoryKind.EXPENSE),
        SeedCategory("Insurance", "insurance", "blue", CategoryKind.EXPENSE),
        SeedCategory("Other", "other", "gray", CategoryKind.EXPENSE),
        SeedCategory("Salary", "salary", "lime", CategoryKind.INCOME),
        SeedCategory("Freelance", "freelance", "green", CategoryKind.INCOME),
        SeedCategory("Refunds", "refund", "teal", CategoryKind.INCOME),
        SeedCategory("Other income", "other_income", "sky", CategoryKind.INCOME),
    )

    val paymentMethods = listOf(
        SeedMethod("Card", PaymentKind.CARD),
        SeedMethod("Cash", PaymentKind.CASH),
        SeedMethod("Google Wallet", PaymentKind.GOOGLE_WALLET),
        SeedMethod("PayPal", PaymentKind.PAYPAL),
        SeedMethod("Revolut", PaymentKind.REVOLUT),
    )

    /** Icon keys with special meaning in code. */
    const val ICON_SALARY = "salary"
    const val ICON_SUBSCRIPTIONS = "subscriptions"
    const val ICON_OTHER = "other"
    const val ICON_OTHER_INCOME = "other_income"
    const val ICON_REFUND = "refund"
}
