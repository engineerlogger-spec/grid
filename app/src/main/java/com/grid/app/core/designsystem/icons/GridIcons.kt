package com.grid.app.core.designsystem.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HomeRepairService
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.grid.app.core.model.PaymentKind

/** Category icon set. Keys are persisted (CategoryEntity.iconKey) — add freely, never rename. */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "restaurant" to Icons.Rounded.Restaurant,
        "groceries" to Icons.Rounded.LocalGroceryStore,
        "transport" to Icons.Rounded.DirectionsBus,
        "shopping" to Icons.Rounded.ShoppingBag,
        "clothing" to Icons.Rounded.Checkroom,
        "bills" to Icons.Rounded.Bolt,
        "subscriptions" to Icons.Rounded.Autorenew,
        "housing" to Icons.Rounded.Home,
        "health" to Icons.Rounded.Favorite,
        "entertainment" to Icons.Rounded.Movie,
        "travel" to Icons.Rounded.Flight,
        "education" to Icons.Rounded.School,
        "gifts" to Icons.Rounded.CardGiftcard,
        "personal_care" to Icons.Rounded.Spa,
        "services" to Icons.Rounded.HomeRepairService,
        "other" to Icons.Rounded.MoreHoriz,
        "salary" to Icons.Rounded.Payments,
        "freelance" to Icons.Rounded.Work,
        "refund" to Icons.Rounded.Replay,
        "other_income" to Icons.Rounded.Savings,
        "cash" to Icons.Rounded.LocalAtm,
        "insurance" to Icons.Rounded.Shield,
        // Extra choices for custom categories
        "cafe" to Icons.Rounded.LocalCafe,
        "fastfood" to Icons.Rounded.Fastfood,
        "bar" to Icons.Rounded.LocalBar,
        "car" to Icons.Rounded.DirectionsCar,
        "fuel" to Icons.Rounded.LocalGasStation,
        "parking" to Icons.Rounded.LocalParking,
        "taxi" to Icons.Rounded.LocalTaxi,
        "train" to Icons.Rounded.Train,
        "moto" to Icons.Rounded.TwoWheeler,
        "pets" to Icons.Rounded.Pets,
        "kids" to Icons.Rounded.ChildCare,
        "fitness" to Icons.Rounded.FitnessCenter,
        "sport" to Icons.Rounded.SportsSoccer,
        "games" to Icons.Rounded.SportsEsports,
        "music" to Icons.Rounded.MusicNote,
        "tv" to Icons.Rounded.Tv,
        "books" to Icons.AutoMirrored.Rounded.MenuBook,
        "art" to Icons.Rounded.Brush,
        "tech" to Icons.Rounded.Computer,
        "phone" to Icons.Rounded.PhoneAndroid,
        "internet" to Icons.Rounded.Wifi,
        "water" to Icons.Rounded.Water,
        "laundry" to Icons.Rounded.LocalLaundryService,
        "haircut" to Icons.Rounded.ContentCut,
        "pharmacy" to Icons.Rounded.Medication,
        "hotel" to Icons.Rounded.Hotel,
        "beach" to Icons.Rounded.BeachAccess,
        "party" to Icons.Rounded.Celebration,
        "charity" to Icons.Rounded.VolunteerActivism,
        "repairs" to Icons.Rounded.Construction,
        "receipt" to Icons.Rounded.Receipt,
        "bank" to Icons.Rounded.AccountBalance,
        "card" to Icons.Rounded.CreditCard,
        "investments" to Icons.AutoMirrored.Rounded.TrendingUp,
    )

    fun of(key: String): ImageVector = all[key] ?: Icons.Rounded.MoreHoriz
}

/** Payment methods get an icon, or a monogram for brands (no third-party logos are bundled). */
sealed interface MethodGlyph {
    data class Icon(val vector: ImageVector) : MethodGlyph
    data class Monogram(val letter: String) : MethodGlyph

    companion object {
        fun of(kind: PaymentKind): MethodGlyph = when (kind) {
            PaymentKind.CASH -> Icon(Icons.Rounded.Payments)
            PaymentKind.CARD -> Icon(Icons.Rounded.CreditCard)
            PaymentKind.GOOGLE_WALLET -> Icon(Icons.Rounded.AccountBalanceWallet)
            PaymentKind.PAYPAL -> Monogram("P")
            PaymentKind.REVOLUT -> Monogram("R")
            PaymentKind.BANK -> Icon(Icons.Rounded.AccountBalance)
            PaymentKind.OTHER -> Icon(Icons.Rounded.MoreHoriz)
        }
    }
}
