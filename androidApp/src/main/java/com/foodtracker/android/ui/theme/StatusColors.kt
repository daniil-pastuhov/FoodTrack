package com.foodtracker.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Foreground (text/icon) + background pair for an expiry status badge or metric card. */
data class ExpiryStatusColors(val foreground: Color, val background: Color)

@Composable
fun freshStatusColors(): ExpiryStatusColors =
    if (LocalIsDarkTheme.current) {
        ExpiryStatusColors(FreshGreenOnDark, FreshGreenBgDark)
    } else {
        ExpiryStatusColors(FreshGreenOnLight, FreshGreenBg)
    }

@Composable
fun expiringStatusColors(): ExpiryStatusColors =
    if (LocalIsDarkTheme.current) {
        ExpiryStatusColors(ExpiringAmberOnDark, ExpiringAmberBgDark)
    } else {
        ExpiryStatusColors(ExpiringAmberOnLight, ExpiringAmberBg)
    }

@Composable
fun expiredStatusColors(): ExpiryStatusColors =
    if (LocalIsDarkTheme.current) {
        ExpiryStatusColors(ExpiredRedOnDark, ExpiredRedBgDark)
    } else {
        ExpiryStatusColors(ExpiredRedOnLight, ExpiredRedBg)
    }
