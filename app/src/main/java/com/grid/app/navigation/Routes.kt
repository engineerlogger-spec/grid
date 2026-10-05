package com.grid.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute
/** [dayEpoch] opens on that day (filtered to it); [monthEpoch] only opens on the month containing it. */
@Serializable data class ActivityRoute(val categoryId: Long? = null, val dayEpoch: Long? = null, val monthEpoch: Long? = null)
@Serializable data object DeletedRoute
@Serializable data object AiRoute
@Serializable data object AskRoute
@Serializable data object BillsRoute
@Serializable data object InsightsRoute
@Serializable data object CheckInRoute
@Serializable data object SettingsRoute
/** [fromTransactionId]: a new subscription made from that payment (prefilled; its earlier payments get linked). */
@Serializable data class SubscriptionEditRoute(val id: Long? = null, val fromTransactionId: Long? = null)
@Serializable data class PendingEditRoute(val id: Long? = null)
@Serializable data object DetectedRoute
@Serializable data object CaptureSetupRoute
@Serializable data object BackupRoute
@Serializable data object CategoriesRoute
@Serializable data object BankSetupRoute
@Serializable data object MovedRoute
