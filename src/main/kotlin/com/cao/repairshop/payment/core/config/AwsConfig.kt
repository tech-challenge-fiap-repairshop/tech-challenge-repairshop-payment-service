package com.cao.repairshop.payment.core.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

@Configuration
class AwsConfig(
    @Value("\${aws.region:\${AWS_REGION:us-east-1}}")
    private val region: String,
    @Value("\${aws.endpoint-url:\${AWS_ENDPOINT_URL:#{null}}}")
    private val endpointUrl: String?,
    @Value("\${aws.access-key-id:\${AWS_ACCESS_KEY_ID:#{null}}}")
    private val accessKeyId: String?,
    @Value("\${aws.secret-access-key:\${AWS_SECRET_ACCESS_KEY:#{null}}}")
    private val secretAccessKey: String?,
    @Value("\${aws.session-token:\${AWS_SESSION_TOKEN:#{null}}}")
    private val sessionToken: String?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun credentialsProvider(): AwsCredentialsProvider {
        return if (!accessKeyId.isNullOrBlank() && !secretAccessKey.isNullOrBlank()) {
            val maskedKey = if (accessKeyId.length > 8) "${accessKeyId.take(4)}...${accessKeyId.takeLast(4)}" else "***"
            val hasSessionToken = !sessionToken.isNullOrBlank()
            logger.info("AWS Client: Autenticando com credenciais estáticas/temporárias (AccessKey: {}, SessionToken: {})",
                maskedKey, if (hasSessionToken) "PRESENTE" else "AUSENTE")

            val credentials = if (hasSessionToken) {
                AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
            } else {
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            }
            StaticCredentialsProvider.create(credentials)
        } else {
            logger.info("AWS Client: Autenticando via DefaultCredentialsProvider (IAM Role EKS/IRSA, Variáveis de Ambiente ou ~/.aws/credentials)")
            DefaultCredentialsProvider.create()
        }
    }

    @Bean
    fun dynamoDbClient(): DynamoDbClient {
        val builder = DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider())

        if (!endpointUrl.isNullOrBlank()) {
            logger.info("Payment Service - Conectando DynamoDB em endpoint customizado: {}", endpointUrl)
            builder.endpointOverride(URI.create(endpointUrl))
        }

        return builder.build()
    }

    @Bean
    fun dynamoDbEnhancedClient(dynamoDbClient: DynamoDbClient): DynamoDbEnhancedClient {
        return DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build()
    }

    @Bean
    fun sqsClient(): SqsClient {
        val builder = SqsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider())

        if (!endpointUrl.isNullOrBlank()) {
            logger.info("Payment Service - Conectando SQS em endpoint customizado: {}", endpointUrl)
            builder.endpointOverride(URI.create(endpointUrl))
        }

        return builder.build()
    }
}
