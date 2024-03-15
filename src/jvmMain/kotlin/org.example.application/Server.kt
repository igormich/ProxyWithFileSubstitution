package org.example.application

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

@Serializable
data class ProxyConfig(
    val targetServer: String,
    val substitutionDir: String = "substitution",
    val port: Int = 8080,
    val host: String = "127.0.0.1"
)

class Proxy(private val proxyConfig: ProxyConfig) {
    @OptIn(InternalAPI::class)
    fun start() {
        embeddedServer(Netty, environment = applicationEngineEnvironment {
            connector {
                port = proxyConfig.port
                host = proxyConfig.host
                module {
                    val client = HttpClient(CIO) {
                        followRedirects = true
                    }
                    intercept(ApplicationCallPipeline.Call) {
                        val channel: ByteReadChannel = call.request.receiveChannel()
                        val size = channel.availableForRead
                        val byteArray = ByteArray(size)
                        channel.readFully(byteArray)
                        try {
                            val path = call.request.uri.substringBefore('?')

                            val file = File("./${proxyConfig.substitutionDir}$path")
                            if (path != "/" && file.exists()) {
                                println("substitution for $path")
                                call.respondFile(file)
                                return@intercept
                            }
                            println("Proxy for $path")
                            val response: HttpResponse =
                                client.request("https://${proxyConfig.targetServer}${call.request.uri}") {
                                    method = call.request.httpMethod
                                    headers {
                                        appendAll(call.request.headers.filter { key, _ ->
                                            !key.equals(
                                                HttpHeaders.ContentType,
                                                ignoreCase = true
                                            ) && !key.equals(
                                                HttpHeaders.ContentLength, ignoreCase = true
                                            ) && !key.equals(
                                                HttpHeaders.Host, ignoreCase = true
                                            ) && !key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)
                                        })
                                    }
                                    if (call.request.httpMethod == HttpMethod.Post) {
                                        body = ByteArrayContent(byteArray, call.request.contentType())
                                    }
                                }
                            val proxiedHeaders = response.headers
                            val location = proxiedHeaders[HttpHeaders.Location]
                            val contentType = proxiedHeaders[HttpHeaders.ContentType]
                            val contentLength = proxiedHeaders[HttpHeaders.ContentLength]
                            call.respond(object : OutgoingContent.WriteChannelContent() {
                                override val contentLength: Long? = contentLength?.toLong()
                                override val contentType: ContentType? =
                                    contentType?.let { ContentType.parse(it) }
                                override val headers: Headers = Headers.build {
                                    appendAll(proxiedHeaders.filter { key, _ ->
                                        !key.equals(
                                            HttpHeaders.ContentType,
                                            ignoreCase = true
                                        ) && !key.equals(
                                            HttpHeaders.ContentLength, ignoreCase = true
                                        ) && !key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)
                                    })
                                }
                                override val status: HttpStatusCode = response.status
                                override suspend fun writeTo(channel: ByteWriteChannel) {
                                    response.content.copyAndClose(channel)
                                }
                            })
                        } catch (e: Exception) {
                            e.printStack()
                        }

                    }
                }
            }
        }).start(wait = true)
    }
}

fun main() {
    val data = Files.readAllLines(File("config.json").toPath()).joinToString("")
    val config = Json.decodeFromString<ProxyConfig>(data)
    println("Proxy start on ${config.host}:${config.port}")
    Proxy(config).start()

}