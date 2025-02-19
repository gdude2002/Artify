package com.artify.route

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.artify.entity.Illustrations
import com.artify.entity.Illustrations.Response.Companion.asResponse
import com.artify.entity.Users
import com.artify.entity.defaultSnowflakeGenerator
import com.artify.image.ImageProcessorMessage
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Connection
import com.rabbitmq.client.MessageProperties
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.codec.binary.Hex
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.*
import javax.imageio.ImageIO

fun Route.illustrationsRoute(
    s3client: AmazonS3,
    amqpConnection: Connection
) {
    route("/illustrations") {
        authenticate(optional = true) {
            get {
                val user = getUser()

                val illustrations = if (user == null) {
                    transaction {
                        Illustrations.Entity
                            .all()
                            .limit(50)
                            .orderBy(Illustrations.Table.id to SortOrder.DESC)
                            .map { it.asResponse() }
                    }
                } else {
                    // TODO: Apply some kind of recommendation algorithm
                    emptyList()
                }

                call.respond(HttpStatusCode.OK, illustrations)
            }
        }

        authenticate {
            post<Illustrations.Post> { request ->
                val user = getUser() ?: throw BadRequestException("Unknown user")

                // Process illustrations, put into bucket and dispatch RabbitMQ message for thumbnails
                val images = request.illustrations.map { illustration ->
                    async {
                        val mimeTypeEnd = illustration.indexOf(";base64,")
                        val mimeType = try {
                            ContentType.parse(illustration.substring(5, mimeTypeEnd))
                        } catch (e: BadContentTypeFormatException) {
                            throw BadRequestException("Failed to parse content type")
                        }

                        val data = Base64.getDecoder().decode(illustration.substring(mimeTypeEnd + 8))
                        val image = try {
                            ImageIO.read(data.inputStream())
                        } catch (e: IOException) {
                            throw BadRequestException("Failed to read one or multiple illustrations")
                        }

                        val hash = Hex.encodeHexString(MessageDigest.getInstance("MD5").digest(data))
                        s3client.putObject(
                            "artify-com",
                            "$hash/original",
                            ByteArrayInputStream(data),
                            ObjectMetadata().apply {
                                contentType = mimeType.toString()
                                contentLength = data.size.toLong()
                            }
                        )

                        return@async hash to image
                    }
                }.awaitAll().toMap()

                val entity = transaction {
                    Illustrations.Entity.new(defaultSnowflakeGenerator.nextId().id) {
                        author = user
                        title = request.title
                        body = request.body
                        commentsEnabled = request.commentsEnabled
                        isPrivate = request.isPrivate
                        isAi = request.isAi
                        hashes = images.keys.toTypedArray()
                    }.asResponse()
                }

                call.respond(HttpStatusCode.Created, entity)

                for (image in images)
                    dispatchImageProcessingMessage(amqpConnection, image.key, image.value)
            }
        }

        route("/{id}") {
            get {
                val result = transaction {
                    Illustrations.Entity
                        .find { Illustrations.Table.id.eq(call.parameters["id"]?.toLong()) }
                        .singleOrNull()
                        ?.asResponse()
                }

                if (result != null)
                    call.respond(HttpStatusCode.OK, result)
                else
                    call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private suspend fun dispatchImageProcessingMessage(amqpConnection: Connection, hash: String, image: BufferedImage) {
    withContext(Dispatchers.IO) {
        val channel = amqpConnection.createChannel()
        channel.queueDeclare("scaling_queue", true, false, false, null)

        val cropWidth = if (image.width < image.height) image.width else image.height
        val cropHeight = if (image.height < image.width) image.height else image.width
        val cropPosition = ImageProcessorMessage.Dimension(
            (image.width / 2) - (cropWidth / 2),
            (image.height / 2) - (cropHeight / 2)
        )
        val cropSize = ImageProcessorMessage.Dimension(cropWidth, cropHeight)
        val scales = listOf(
            ImageProcessorMessage.Dimension(512, 512),
            ImageProcessorMessage.Dimension(256, 256),
            ImageProcessorMessage.Dimension(128, 128)
        ).filter { it.x < cropWidth && it.y < cropHeight }.toSet()

        channel.basicPublish(
            "",
            "scaling_queue",
            AMQP.BasicProperties.Builder()
                .deliveryMode(MessageProperties.PERSISTENT_BASIC.deliveryMode).build(),
            Json.encodeToString(
                ImageProcessorMessage(
                    hash,
                    cropPosition,
                    cropSize,
                    scales
                )
            ).toByteArray()
        )
    }
}
