package com.foodtracker.model

import kotlinx.serialization.Serializable

@Serializable
data class ProductInfo(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val quantity: String? = null
)
