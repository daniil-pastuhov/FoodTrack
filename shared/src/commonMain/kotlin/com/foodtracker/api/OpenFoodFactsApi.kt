package com.foodtracker.api

import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ProductNutrition
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
    val quantity: String? = null,
    @SerialName("nutriscore_grade")
    val nutriScoreGrade: String? = null,
    @SerialName("nova_group")
    val novaGroup: Int? = null,
    @SerialName("allergens_tags")
    val allergensTags: List<String>? = null,
    @SerialName("ingredients_text")
    val ingredientsText: String? = null
)

// Open Food Facts sometimes returns tag-style categories with a language prefix, e.g. "en:Milk".
internal fun stripLanguagePrefix(raw: String): String {
    val colonIndex = raw.indexOf(':')
    val prefix = raw.substring(0, colonIndex.coerceAtLeast(0))
    return if (colonIndex in 1..3 && prefix.all { it.isLetter() }) {
        raw.substring(colonIndex + 1).trim()
    } else {
        raw
    }
}

/** OFF reports "not-applicable"/"unknown" for products it simply hasn't rated; those are absent
 *  ratings rather than grades, and showing them as a badge would imply a judgement OFF never
 *  made. */
private val UNRATED_GRADES = setOf("not-applicable", "unknown", "")

internal fun OffProduct.toNutrition(): ProductNutrition = ProductNutrition(
    nutriScore = nutriScoreGrade?.trim()?.lowercase()?.takeIf { it !in UNRATED_GRADES },
    novaGroup = novaGroup?.takeIf { it in 1..4 },
    // Tags arrive language-prefixed ("en:milk"), the same shape categories do.
    allergens = allergensTags.orEmpty()
        .map { stripLanguagePrefix(it).trim() }
        .filter { it.isNotEmpty() }
        .distinct(),
    ingredients = ingredientsText?.trim()?.takeIf { it.isNotEmpty() }
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
                    category = prod.categories?.split(",")?.firstOrNull()?.trim()?.let(::stripLanguagePrefix),
                    quantity = prod.quantity?.takeIf { it.isNotBlank() },
                    nutrition = prod.toNutrition()
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
