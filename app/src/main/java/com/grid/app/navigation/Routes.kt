package com.grid.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute
@Serializable data class ActivityRoute(val categoryId: Long? = null, val dayEpoch: Long? = null)
@Serializable data object BillsRoute
@Serializable data object InsightsRoute
@Serializable data object CheckInRoute
@Serializable data object SettingsRoute
