package com.foodtracker.api

import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ProductNutrition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
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

/**
 * What looking a barcode up came to. [Offline] and [NotFound] lead to different messages: the
 * first means try again later or carry on by hand, the second means the product really isn't
 * in the database.
 */
sealed interface ProductLookupResult {
    data class Found(val product: ProductInfo) : ProductLookupResult

    /** Open Food Facts answered, and it doesn't know this barcode. */
    data object NotFound : ProductLookupResult

    /** No answer worth trusting: no network, a timeout, a server error or an unreadable reply. */
    data object Offline : ProductLookupResult
}

/** The product, when there is one. */
val ProductLookupResult.productOrNull: ProductInfo?
    get() = (this as? ProductLookupResult.Found)?.product

internal fun OffResponse.toLookupResult(barcode: String): ProductLookupResult {
    val prod = product
    if (status != 1 || prod == null) return ProductLookupResult.NotFound
    return ProductLookupResult.Found(
        ProductInfo(
            barcode = barcode,
            name = prod.productName?.takeIf { it.isNotBlank() } ?: "Item $barcode",
            brand = prod.brands?.takeIf { it.isNotBlank() },
            imageUrl = prod.imageFrontUrl?.takeIf { it.isNotBlank() },
            category = prod.categories?.split(",")?.firstOrNull()?.trim()?.let(::stripLanguagePrefix),
            quantity = prod.quantity?.takeIf { it.isNotBlank() },
            nutrition = prod.toNutrition()
        )
    )
}

class OpenFoodFactsApi(
    private val httpClient: HttpClient = createDefaultClient()
) {
    suspend fun lookupProduct(barcode: String): ProductLookupResult {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isEmpty()) return ProductLookupResult.NotFound

        return try {
            val url = "https://world.openfoodfacts.org/api/v2/product/$cleanBarcode.json"
            val response = httpClient.get(url) {
                header("User-Agent", "FoodTracker-Android/1.0 (https://github.com/daniil-pastuhov/FoodTrack)")
            }
            when {
                // How the v2 API reports an unknown barcode, usually with a JSON body saying the
                // same. Read from the status code so a differently shaped body can't turn a real
                // not-found into a failure.
                response.status == HttpStatusCode.NotFound -> ProductLookupResult.NotFound
                !response.status.isSuccess() -> ProductLookupResult.Offline
                else -> response.body<OffResponse>().toLookupResult(cleanBarcode)
            }
        } catch (e: CancellationException) {
            // Leaving the scanner cancels the lookup, which is not a network failure.
            throw e
        } catch (_: Exception) {
            ProductLookupResult.Offline
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
