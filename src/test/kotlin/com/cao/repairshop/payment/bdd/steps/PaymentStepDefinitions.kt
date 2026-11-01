package com.cao.repairshop.payment.bdd.steps

import com.cao.repairshop.payment.bdd.config.CucumberTestcontainersConfig
import com.cao.repairshop.payment.infrastructure.mercadopago.MercadoPagoClient
import com.cao.repairshop.payment.infrastructure.persistence.DynamoPaymentRepository
import com.cao.repairshop.payment.infrastructure.persistence.models.PaymentDynamoItem
import com.cao.repairshop.payment.infrastructure.web.CreatePaymentRequest
import com.cao.repairshop.payment.infrastructure.web.PaymentController
import com.cao.repairshop.payment.infrastructure.web.RefundPaymentRequest
import com.cao.repairshop.payment.infrastructure.web.WebhookNotification
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.pt.Dado
import io.cucumber.java.pt.E
import io.cucumber.java.pt.Então
import io.cucumber.java.pt.Quando
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Assertions.*
import org.springframework.http.ResponseEntity
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.sqs.SqsClient
import java.util.UUID

class PaymentStepDefinitions(
    private val paymentController: PaymentController,
    private val paymentRepository: DynamoPaymentRepository,
    private val dynamoDbClient: DynamoDbClient,
    private val sqsClient: SqsClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) {

    private var currentOrderId: UUID = UUID.randomUUID()
    private var currentCustomerId: UUID = UUID.randomUUID()
    private var currentAmount: Double = 0.0
    private var lastCreatedPayment: PaymentDynamoItem? = null
    private var lastPaymentResponse: ResponseEntity<PaymentDynamoItem>? = null
    private var lastRefundReason: String? = null

    @Before
    fun setup() {
        CucumberTestcontainersConfig.resetWireMock()
        circuitBreakerRegistry.circuitBreaker("mercadoPagoCircuitBreaker").transitionToClosedState()
    }

    @After
    fun tearDown() {
        CucumberTestcontainersConfig.resetWireMock()
        circuitBreakerRegistry.circuitBreaker("mercadoPagoCircuitBreaker").transitionToClosedState()
    }

    @Dado("que o serviço de pagamentos está operacional e conectado ao DynamoDB e LocalStack SQS")
    fun servicoOperacional() {
        assertNotNull(paymentController)
        assertNotNull(paymentRepository)
        assertNotNull(dynamoDbClient)
        assertNotNull(sqsClient)
    }

    @E("o gateway de pagamentos do Mercado Pago está disponível para transações")
    fun mercadoPagoDisponivel() {
        CucumberTestcontainersConfig.resetWireMock()
        val cb = circuitBreakerRegistry.circuitBreaker("mercadoPagoCircuitBreaker")
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, cb.state)
    }

    @Dado("que uma Ordem de Serviço foi concluída com valor de {double}")
    fun osConcluidaComValor(valor: Double) {
        currentOrderId = UUID.randomUUID()
        currentCustomerId = UUID.randomUUID()
        currentAmount = valor
    }

    @Quando("o atendente solicita a geração do pagamento via PIX para a Ordem de Serviço")
    fun solicitaGeracaoPagamentoPix() {
        val request = CreatePaymentRequest(
            orderId = currentOrderId,
            customerId = currentCustomerId,
            amount = currentAmount,
            paymentMethod = "MERCADO_PAGO_QR"
        )
        val response = paymentController.createPaymentForOrder(currentOrderId, request)
        lastPaymentResponse = response
        lastCreatedPayment = response.body
    }

    @Então("uma chamada HTTP deve ser realizada com sucesso ao endpoint do Mercado Pago")
    fun chamadaHttpMercadoPagoSucesso() {
        assertNotNull(lastPaymentResponse)
        assertTrue(lastPaymentResponse!!.statusCode.is2xxSuccessful)
        val payment = lastPaymentResponse!!.body
        assertNotNull(payment)
        assertFalse(payment!!.paymentId.isBlank())
    }

    @E("a cobrança deve ser registrada no DynamoDB com status {string}")
    fun cobrancaRegistradaNoDynamoDb(statusEsperado: String) {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento criado")
        val saved = paymentRepository.findById(payment.paymentId)
        assertNotNull(saved, "Pagamento não encontrado no DynamoDB")
        assertEquals(statusEsperado, saved!!.status)
        assertEquals(currentAmount, saved.priceValue)
    }

    @E("uma fatura deve ser gerada no DynamoDB vinculada à Ordem de Serviço")
    fun faturaGeradaNoDynamoDb() {
        val queryRequest = QueryRequest.builder()
            .tableName(CucumberTestcontainersConfig.TABLE_NAME)
            .keyConditionExpression("PK = :pk AND begins_with(SK, :skPrefix)")
            .expressionAttributeValues(
                mapOf(
                    ":pk" to AttributeValue.builder().s("BILLING#$currentOrderId").build(),
                    ":skPrefix" to AttributeValue.builder().s("INV#").build()
                )
            )
            .build()

        val response = dynamoDbClient.query(queryRequest)
        assertTrue(response.hasItems() && response.items().isNotEmpty(), "Fatura (INV#) não encontrada no DynamoDB para OS $currentOrderId")
        val invoiceItem = response.items().first()
        val invoiceAmount = (invoiceItem["price_value"]?.n() ?: invoiceItem["price_value"]?.s())?.toDouble()
        assertEquals(currentAmount, invoiceAmount)
    }

    @E("um QR Code Pix válido e link de ticket devem ser retornados para o cliente")
    fun qrCodePixERetornado() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento criado")
        assertNotNull(payment.paymentInfo)
        assertTrue(payment.paymentInfo.contains("PIX") || payment.paymentInfo.contains("00020126"), "QR Code Pix inválido: ${payment.paymentInfo}")
    }

    @Dado("que existe uma cobrança pendente para uma Ordem de Serviço no valor de {double}")
    fun existeCobrancaPendente(valor: Double) {
        currentOrderId = UUID.randomUUID()
        currentCustomerId = UUID.randomUUID()
        currentAmount = valor

        val request = CreatePaymentRequest(
            orderId = currentOrderId,
            customerId = currentCustomerId,
            amount = currentAmount,
            paymentMethod = "MERCADO_PAGO_QR"
        )
        val response = paymentController.createPaymentForOrder(currentOrderId, request)
        lastCreatedPayment = response.body
        assertNotNull(lastCreatedPayment)
        assertEquals("PENDING", lastCreatedPayment!!.status)
    }

    @Quando("a notificação de confirmação de pagamento é recebida pelo sistema")
    fun confirmacaoPagamentoRecebida() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento pendente")
        val response = paymentController.confirmPayment(payment.paymentId)
        assertTrue(response.statusCode.is2xxSuccessful)
        lastCreatedPayment = response.body
    }

    @Então("o status do pagamento no DynamoDB deve ser atualizado para {string}")
    fun statusAtualizadoNoDynamoDb(statusEsperado: String) {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento encontrado")
        val saved = paymentRepository.findById(payment.paymentId)
        assertNotNull(saved)
        assertEquals(statusEsperado, saved!!.status)
    }

    @E("uma mensagem de sucesso deve ser publicada na fila de sucesso de pagamentos")
    fun mensagemPublicadaNaFilaDeSucesso() {
        val queueUrl = sqsClient.getQueueUrl { it.queueName(CucumberTestcontainersConfig.QUEUE_SUCCESS) }.queueUrl()
        val receiveResponse = sqsClient.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1) }
        assertTrue(receiveResponse.hasMessages(), "Nenhuma mensagem encontrada na fila payment-success-queue-test")
        val msgBody = receiveResponse.messages().first().body()
        assertTrue(msgBody.contains(currentOrderId.toString()) || msgBody.contains("orderId"))
    }

    @E("uma notificação de pagamento aprovado deve ser enviada para a fila de clientes")
    fun notificacaoPagamentoAprovadoNaFilaClientes() {
        val queueUrl = sqsClient.getQueueUrl { it.queueName(CucumberTestcontainersConfig.QUEUE_NOTIFICATION) }.queueUrl()
        val receiveResponse = sqsClient.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1) }
        assertTrue(receiveResponse.hasMessages(), "Nenhuma mensagem encontrada na fila payment-notification-queue-test")
    }

    @Dado("que existe um pagamento aprovado para a Ordem de Serviço no valor de {double}")
    fun existePagamentoAprovado(valor: Double) {
        currentOrderId = UUID.randomUUID()
        currentCustomerId = UUID.randomUUID()
        currentAmount = valor

        val request = CreatePaymentRequest(
            orderId = currentOrderId,
            customerId = currentCustomerId,
            amount = currentAmount,
            paymentMethod = "MERCADO_PAGO_QR"
        )
        val created = paymentController.createPaymentForOrder(currentOrderId, request).body!!
        val confirmed = paymentController.confirmPayment(created.paymentId).body!!
        lastCreatedPayment = confirmed
        assertEquals("APPROVED", lastCreatedPayment!!.status)
    }

    @Quando("o sistema aciona o estorno do pagamento com o motivo {string}")
    fun acionaEstornoComMotivo(motivo: String) {
        lastRefundReason = motivo
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento aprovado disponível")
        val refundReq = RefundPaymentRequest(reason = motivo)
        val response = paymentController.refundPayment(payment.paymentId, refundReq)
        assertTrue(response.statusCode.is2xxSuccessful)
        lastCreatedPayment = response.body
    }

    @Então("o endpoint de estorno do Mercado Pago deve ser acionado com sucesso")
    fun endpointEstornoMercadoPagoAcionado() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento estornado")
        assertNotNull(payment)
        assertEquals("REFUNDED", payment.status)
    }

    @E("o status do pagamento no DynamoDB deve ser alterado para {string}")
    fun statusAlteradoPara(statusEsperado: String) {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento encontrado")
        val saved = paymentRepository.findById(payment.paymentId)
        assertNotNull(saved)
        assertEquals(statusEsperado, saved!!.status)
    }

    @E("o motivo e a data do estorno devem ser registrados no DynamoDB")
    fun motivoEDataEstornoRegistrados() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento estornado")
        val saved = paymentRepository.findById(payment.paymentId)
        assertNotNull(saved)
        assertEquals(lastRefundReason, saved!!.refundReason)
        assertNotNull(saved.refundDate)
    }

    @E("um evento de estorno deve ser publicado para notificação")
    fun eventoEstornoPublicado() {
        val queueUrl = sqsClient.getQueueUrl { it.queueName(CucumberTestcontainersConfig.QUEUE_NOTIFICATION) }.queueUrl()
        val receiveResponse = sqsClient.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1) }
        assertTrue(receiveResponse.hasMessages(), "Nenhuma mensagem encontrada na fila payment-notification-queue-test para o estorno")
    }

    @Quando("o webhook do Mercado Pago notifica que o pagamento foi rejeitado")
    fun webhookNotificaRejeicao() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento pendente")
        val webhookPayload = WebhookNotification(
            id = payment.paymentId,
            status = "rejected",
            orderId = currentOrderId
        )
        val response = paymentController.webhook(webhookPayload)
        assertTrue(response.statusCode.is2xxSuccessful)
        lastCreatedPayment = paymentRepository.findById(payment.paymentId)
    }

    @E("uma notificação de recusa de pagamento deve ser enviada para a fila de clientes")
    fun notificacaoRecusaEnviada() {
        val queueUrl = sqsClient.getQueueUrl { it.queueName(CucumberTestcontainersConfig.QUEUE_NOTIFICATION) }.queueUrl()
        val receiveResponse = sqsClient.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1) }
        assertTrue(receiveResponse.hasMessages(), "Nenhuma notificação encontrada na fila payment-notification-queue-test")
    }

    @Dado("que o gateway do Mercado Pago está instável ou indisponível")
    fun gatewayMercadoPagoInstavel() {
        CucumberTestcontainersConfig.simulateMercadoPagoFailure()
        val cb = circuitBreakerRegistry.circuitBreaker("mercadoPagoCircuitBreaker")
        cb.transitionToOpenState()
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, cb.state)
    }

    @Quando("o atendente solicita a geração de pagamento para uma Ordem de Serviço no valor de {double}")
    fun atendenteSolicitaGeracaoPagamentoComInstabilidade(valor: Double) {
        currentOrderId = UUID.randomUUID()
        currentCustomerId = UUID.randomUUID()
        currentAmount = valor

        val request = CreatePaymentRequest(
            orderId = currentOrderId,
            customerId = currentCustomerId,
            amount = currentAmount
        )
        val response = paymentController.createPaymentForOrder(currentOrderId, request)
        lastPaymentResponse = response
        lastCreatedPayment = response.body
    }

    @Então("o Circuit Breaker deve interceptar a falha e acionar o fallback de contingência")
    fun circuitBreakerAcionaFallback() {
        assertNotNull(lastPaymentResponse)
        assertTrue(lastPaymentResponse!!.statusCode.is2xxSuccessful)
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento retornado")
        assertTrue(payment.paymentId.startsWith("FALLBACK-MP-"), "ID do pagamento deveria começar com prefixo FALLBACK-MP- mas foi: ${payment.paymentId}")
    }

    @E("o pagamento deve ser registrado com método {string}")
    fun pagamentoRegistradoComMetodo(metodoEsperado: String) {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento retornado")
        assertEquals(metodoEsperado, payment.paymentMethod)
        val saved = paymentRepository.findById(payment.paymentId)
        assertNotNull(saved)
        assertEquals(metodoEsperado, saved!!.paymentMethod)
    }

    @E("o QR Code de contingência deve ser disponibilizado para o cliente")
    fun qrCodeContingenciaDisponibilizado() {
        val payment = lastCreatedPayment ?: fail("Nenhum pagamento retornado")
        assertNotNull(payment.paymentInfo)
        assertTrue(payment.paymentInfo.contains("contingencia-oficina-repairshop"), "QR Code deveria ser o de contingência: ${payment.paymentInfo}")
    }
}

