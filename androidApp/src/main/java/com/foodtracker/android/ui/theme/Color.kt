package com.foodtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

val FreshGreen = Color(0xFF10B981)
val FreshGreenBg = Color(0xFFD1FAE5)

val ExpiringAmber = Color(0xFFF59E0B)
val ExpiringAmberBg = Color(0xFFFEF3C7)

val ExpiredRed = Color(0xFFEF4444)
val ExpiredRedBg = Color(0xFFFEE2E2)

// Status badge / metric-card foreground+background pairs, tuned per theme so the
// text/icon color always has enough contrast against its paired background
// (the saturated *On* colors above are for solid fills with white text/icon, e.g.
// the scanner's "date found" chip — they aren't used as text-on-pastel colors).
val FreshGreenOnLight = Color(0xFF047857)
val FreshGreenOnDark = Color(0xFF6EE7B7)
val FreshGreenBgDark = Color(0xFF064E3B)

val ExpiringAmberOnLight = Color(0xFF92400E)
val ExpiringAmberOnDark = Color(0xFFFCD34D)
val ExpiringAmberBgDark = Color(0xFF78350F)

val ExpiredRedOnLight = Color(0xFFB91C1C)
val ExpiredRedOnDark = Color(0xFFFCA5A5)
val ExpiredRedBgDark = Color(0xFF7F1D1D)

val BrandPrimary = Color(0xFF0D9488)
val BrandSecondary = Color(0xFF14B8A6)
val BrandDark = Color(0xFF115E59)

val SurfaceLight = Color(0xFFF8FAFC)
val CardLight = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF64748B)
val DividerColor = Color(0xFFE2E8F0)
