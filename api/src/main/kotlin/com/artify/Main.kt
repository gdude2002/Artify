package com.artify

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.artify.entity.Illustrations
import com.artify.route.authRoute
import com.artify.route.illustrationsRoute
import com.artify.route.usersRoute
import com.auth0.jwk.JwkProviderBuilder
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.TimeUnit

fun main(args: Array<String>): Unit = EngineMain.main(args)

@Suppress("unused")
fun Application.api() {
    val s3client: AmazonS3 = AmazonS3ClientBuilder
        .standard()
        .withCredentials(
            AWSStaticCredentialsProvider(
                BasicAWSCredentials(
                    System.getenv("AWS_ACCESS_KEY"),
                    System.getenv("AWS_SECRET_KEY")
                )
            )
        )
        .withRegion(Regions.EU_CENTRAL_1)
        .build()

    val cognitoProvider: AWSCognitoIdentityProvider = AWSCognitoIdentityProviderClientBuilder
        .standard()
        .withCredentials(
            AWSStaticCredentialsProvider(
                BasicAWSCredentials(
                    System.getenv("AWS_ACCESS_KEY"),
                    System.getenv("AWS_SECRET_KEY")
                )
            )
        )
        .withRegion(Regions.EU_CENTRAL_1)
        .build()

    val factory = ConnectionFactory()
    val connection: Connection = factory.newConnection(System.getenv("RABBITMQ_HOST"))

    Database.connect(HikariDataSource {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = environment.config.property("postgres.host").getString()
        username = environment.config.property("postgres.username").getString()
        password = environment.config.property("postgres.password").getString()
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    })

    install(AutoHeadResponse)
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = false
        })
    }
    install(RequestValidation) {
        validate<Illustrations.Post> {
            it.validate()
        }
    }
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, cause.reasons.joinToString())
        }
    }
    install(Authentication) {
        jwt {
            this@api.environment.config.property("aws.cognito.client.id").getString()
            val issuer = this@api.environment.config.property("aws.cognito.issuer").getString()
            val provider = JwkProviderBuilder(issuer)
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()

            verifier(
                provider,
                issuer
            )
            validate { credentials ->
                // TODO: A lot of people seem to reference the audience but its not present in the tokens for this?
                //if (credentials.payload.audience.contains(audience))
                    JWTPrincipal(credentials.payload)
                //else
                //    null
            }
        }
    }

    routing {
        trace {
            application.log.trace(it.buildText())
        }

        route("/api") {
            authRoute(cognitoProvider)
            usersRoute()
            illustrationsRoute(s3client, connection)
        }
    }
}

fun HikariDataSource(builder: HikariConfig.() -> Unit): HikariDataSource {
    val config = HikariConfig()
    config.apply(builder)
    config.validate()
    return HikariDataSource(config)
}
