package com.foodtracker.model

import kotlinx.serialization.Serializable

@Serializable
enum class ItemStatus {
    ACTIVE,
    DISCARDED
}
