package com.foodtracker.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The lookup's one promise: [ProductLookupResult.NotFound] only ever means Open Food Facts said
 * so. Everything short of a readable answer is [ProductLookupResult.Offline], or the scanner
 * would tell someone with no signal that their product doesn't exist.
 */
class OpenFoodFactsLookupTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        OpenFoodFactsApi(
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

    private fun apiAnswering(status: HttpStatusCode, body: String) =
        api { respond(body, status, jsonHeaders) }

    @Test
    fun aKnownProductIsFound() = runTest {
        val result = apiAnswering(
            HttpStatusCode.OK,
            """{"status":1,"product":{"product_name":"Greek yoghurt","brands":"Fage"}}"""
        ).lookupProduct(" 5012345678900 ")

        val found = assertIs<ProductLookupResult.Found>(result)
        assertEquals("5012345678900", found.product.barcode)
        assertEquals("Greek yoghurt", found.product.name)
        assertEquals("Fage", found.product.brand)
    }

    @Test
    fun aBlankImageIsNoImage() = runTest {
        val result = apiAnswering(
            HttpStatusCode.OK,
            """{"status":1,"product":{"product_name":"Oat milk","image_front_url":"  "}}"""
        ).lookupProduct("123")

        assertEquals(null, assertIs<ProductLookupResult.Found>(result).product.imageUrl)
    }

    @Test
    fun aProductWithNoNameIsStillFound() = runTest {
        val result = apiAnswering(HttpStatusCode.OK, """{"status":1,"product":{"product_name":" "}}""")
            .lookupProduct("123")

        assertEquals("Item 123", assertIs<ProductLookupResult.Found>(result).product.name)
    }

    @Test
    fun aNotFoundStatusInASuccessfulReplyIsNotFound() = runTest {
        val result = apiAnswering(HttpStatusCode.OK, """{"status":0,"status_verbose":"product not found"}""")
            .lookupProduct("123")

        assertEquals(ProductLookupResult.NotFound, result)
    }

    @Test
    fun aStatusOfOneWithoutAProductIsNotFound() = runTest {
        val result = apiAnswering(HttpStatusCode.OK, """{"status":1}""").lookupProduct("123")

        assertEquals(ProductLookupResult.NotFound, result)
    }

    @Test
    fun http404IsNotFoundWhateverTheBodySays() = runTest {
        assertEquals(
            ProductLookupResult.NotFound,
            apiAnswering(HttpStatusCode.NotFound, """{"status":0}""").lookupProduct("123")
        )
        assertEquals(
            ProductLookupResult.NotFound,
            apiAnswering(HttpStatusCode.NotFound, "<html>not json</html>").lookupProduct("123")
        )
    }

    @Test
    fun aBlankBarcodeIsNotFoundWithoutAskingTheNetwork() = runTest {
        val result = api { error("should not be called") }.lookupProduct("   ")

        assertEquals(ProductLookupResult.NotFound, result)
    }

    @Test
    fun noConnectionIsOffline() = runTest {
        val result = api { throw IOException("Unable to resolve host") }.lookupProduct("123")

        assertEquals(ProductLookupResult.Offline, result)
    }

    @Test
    fun serverErrorsAndRateLimitsAreOffline() = runTest {
        listOf(
            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.TooManyRequests
        ).forEach { status ->
            assertEquals(
                ProductLookupResult.Offline,
                apiAnswering(status, """{"status":0}""").lookupProduct("123"),
                "for $status"
            )
        }
    }

    @Test
    fun anUnreadableSuccessfulReplyIsOffline() = runTest {
        val result = apiAnswering(HttpStatusCode.OK, "<html>captive portal</html>").lookupProduct("123")

        assertEquals(ProductLookupResult.Offline, result)
    }

    @Test
    fun cancellationIsNotReportedAsOffline() = runTest {
        assertFailsWith<CancellationException> {
            api { throw CancellationException("left the scanner") }.lookupProduct("123")
        }
    }

    @Test
    fun productOrNullOnlyGivesAProductForFound() = runTest {
        val found = apiAnswering(HttpStatusCode.OK, """{"status":1,"product":{"product_name":"Milk"}}""")
            .lookupProduct("123")

        assertEquals("Milk", found.productOrNull?.name)
        assertEquals(null, ProductLookupResult.NotFound.productOrNull)
        assertEquals(null, ProductLookupResult.Offline.productOrNull)
    }
}
