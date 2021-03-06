package com.expediagroup.graphql.client

import com.expediagroup.graphql.types.GraphQLError
import com.expediagroup.graphql.types.GraphQLResponse
import com.expediagroup.graphql.types.SourceLocation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GraphQLWebClientTest {

    private val jacksonObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        WireMock.reset()
    }

    @Test
    fun `verifies spring web client can retrieve data`() {
        val expectedResponse = GraphQLResponse<HelloWorldResult>(
            data = HelloWorldResult("Hello World!"),
            errors = listOf(
                GraphQLError(
                    message = "helloWorld is also throwing an exception",
                    locations = listOf(SourceLocation(1, 1)),
                    path = listOf("helloWorld"),
                    extensions = mapOf("exceptionExtensionKey" to "JunitCustomValue")
                )
            ),
            extensions = mapOf("extensionKey" to "JUnitValue")
        )
        WireMock.stubFor(stubResponse(expectedResponse))

        val client = GraphQLWebClient(url = "${wireMockServer.baseUrl()}/graphql")
        runBlocking {
            val result: GraphQLResponse<HelloWorldResult> = client.execute(
                query = "query HelloWorldQuery { helloWorld }",
                operationName = "HelloWorldQuery"
            )

            assertNotNull(result)
            assertNotNull(result.data)
            assertEquals(expectedResponse.data?.helloWorld, result.data?.helloWorld)
            assertNotNull(result.errors)
            assertEquals(1, result.errors?.size)
            val expectedError = expectedResponse.errors?.first()!!
            val actualError = result.errors?.firstOrNull()
            assertNotNull(actualError)
            assertEquals(expectedError.message, actualError.message)
            assertEquals(1, actualError.locations?.size)
            assertEquals(expectedError.locations?.firstOrNull()?.column, actualError.locations?.firstOrNull()?.column)
            assertEquals(expectedError.locations?.firstOrNull()?.line, actualError.locations?.firstOrNull()?.line)
            assertEquals(1, actualError.path?.size)
            assertEquals("helloWorld", actualError.path?.firstOrNull())
            assertEquals("JunitCustomValue", actualError.extensions?.get("exceptionExtensionKey"))
            assertNotNull(result.extensions)
            assertEquals("JUnitValue", result.extensions?.get("extensionKey"))
        }
    }

    @Test
    fun `verifies spring web client instance can be customized`() {
        val expectedResponse = GraphQLResponse(data = HelloWorldResult("Hello World!"))
        WireMock.stubFor(stubResponse(response = expectedResponse, delayMillis = 50))

        val httpClient: HttpClient = HttpClient.create()
            .tcpConfiguration { client ->
                client.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10)
                    .doOnConnected { conn ->
                        conn.addHandlerLast(ReadTimeoutHandler(10, TimeUnit.MILLISECONDS))
                        conn.addHandlerLast(WriteTimeoutHandler(10, TimeUnit.MILLISECONDS))
                    }
            }
        val connector: ClientHttpConnector = ReactorClientHttpConnector(httpClient.wiretap(true))
        val webClientBuilder = WebClient.builder()
            .clientConnector(connector)

        val client = GraphQLWebClient(
            url = "${wireMockServer.baseUrl()}/graphql",
            builder = webClientBuilder
        )
        runBlocking {
            assertFailsWith(ReadTimeoutException::class) {
                client.execute<GraphQLResponse<HelloWorldResult>>(
                    query = "query HelloWorldQuery { helloWorld }",
                    operationName = "HelloWorldQuery"
                )
            }
        }
    }

    @Test
    fun `verifies individual spring web client requests can be customized`() {
        val expectedResponse = GraphQLResponse(data = HelloWorldResult("Hello World!"))
        val customHeaderName = "X-Custom-Header"
        val customHeaderValue = "My-Custom-Header-Value"
        WireMock.stubFor(stubResponse(expectedResponse).withHeader(customHeaderName, EqualToPattern(customHeaderValue)))

        val client = GraphQLWebClient(url = "${wireMockServer.baseUrl()}/graphql")
        runBlocking {
            val result: GraphQLResponse<HelloWorldResult> = client.execute(
                query = "query HelloWorldQuery { helloWorld }",
                operationName = "HelloWorldQuery"
            ) {
                header(customHeaderName, customHeaderValue)
            }

            assertNotNull(result)
            assertNotNull(result.data)
            assertEquals(expectedResponse.data?.helloWorld, result.data?.helloWorld)
            assertNull(result.errors)
            assertNull(result.extensions)
        }
    }

    private fun stubResponse(response: GraphQLResponse<*>, delayMillis: Int = 0): MappingBuilder =
        WireMock.post("/graphql")
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jacksonObjectMapper.writeValueAsString(response))
                    .withFixedDelay(delayMillis)
            )

    data class HelloWorldResult(val helloWorld: String)

    companion object {
        internal val wireMockServer: WireMockServer = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())

        @BeforeAll
        @JvmStatic
        fun oneTimeSetup() {
            wireMockServer.start()
            WireMock.configureFor(wireMockServer.port())
        }

        @AfterAll
        @JvmStatic
        fun oneTimeTearDown() {
            wireMockServer.stop()
        }
    }
}
