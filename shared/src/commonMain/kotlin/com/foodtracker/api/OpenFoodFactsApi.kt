package com.foodtracker.api

import com.foodtracker.model.ProductInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OffResponse(
    val code: String? = null,
    val status: Int = 0,
    val product: OffProduct? = null
)

@Serializable
internal data class OffProduct(
    @SerialName("product_name")
    val productName: String? = null,
    val brands: String? = null,
    @SerialName("image_front_url")
    val imageFrontUrl: String? = null,
    val categories: String? = null,
    val quantity: String? = null
)

class OpenFoodFactsApi(
    private val httpClient: HttpClient = createDefaultClient()
) {
    suspend fun fetchProduct(barcode: String): ProductInfo? {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isEmpty()) return null

        return try {
            val url = "https://world.openfoodfacts.org/api/v2/product/$cleanBarcode.json"
            val response: OffResponse = httpClient.get(url) {
                header("User-Agent", "FoodTracker-KMP/1.0 (https://github.com/foodtracker)")
            }.body()

            if (response.status == 1 && response.product != null) {
                val prod = response.product
                val name = prod.productName?.takeIf { it.isNotBlank() } ?: "Item $cleanBarcode"
                ProductInfo(
                    barcode = cleanBarcode,
                    name = name,
                    brand = prod.brands?.takeIf { it.isNotBlank() },
                    imageUrl = prod.imageFrontUrl?.takeIf { it.isNotBlank() },
                    category = prod.categories?.split(",")?.firstOrNull()?.trim(),
                    quantity = prod.quantity?.takeIf { it.isNotBlank() }
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun createDefaultClient(): HttpClient {
            return HttpClient {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                }
            }
        }
    }
}
