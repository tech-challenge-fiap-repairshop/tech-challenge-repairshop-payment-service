package com.cao.repairshop.payment.infrastructure.web

import com.cao.repairshop.payment.infrastructure.mercadopago.MercadoPagoClient
import com.cao.repairshop.payment.infrastructure.mercadopago.MercadoPagoPaymentRequest
import com.cao.repairshop.payment.infrastructure.messaging.PaymentSqsMessenger
import com.cao.repairshop.payment.infrastructure.persistence.DynamoPaymentRepository
import com.cao.repairshop.payment.infrastructure.persistence.models.InvoiceDynamoItem
import com.cao.repairshop.payment.infrastructure.persistence.models.PaymentDynamoItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class CreatePaymentRequest(
    val orderId: UUID? = null,
    val customerId: UUID? = null,
    val amount: Double,
    val paymentMethod: String? = "MERCADO_PAGO_QR"
)

data class WebhookNotification(
    val id: String,
    val status: String,
    val orderId: UUID? = null
)

data class RefundPaymentRequest(
    val reason: String? = "Inviabilidade técnica na execução da OS"
)

@RestController
@Tag(name = "Payments", description = "Endpoints de Pagamentos e Integração Mercado Pago")
class PaymentController(
    private val paymentRepository: DynamoPaymentRepository,
    private val mercadoPagoClient: MercadoPagoClient,
    private val sqsMessenger: PaymentSqsMessenger
) {

    /**
     * Endpoint oficial do desenho da arquitetura da Fase 4:
     * POST /payment/{service_order_id}
     */
    @PostMapping("/payment/{serviceOrderId}")
    @Operation(summary = "Gera uma nova ordem de pagamento / cobrança via Mercado Pago para a OS")
    fun createPaymentForOrder(
        @PathVariable serviceOrderId: UUID,
        @RequestBody request: CreatePaymentRequest
    ): ResponseEntity<PaymentDynamoItem> {
        val custId = request.customerId ?: UUID.randomUUID()

        // 1. Chama Mercado Pago protegido por Circuit Breaker
        val mpResponse = mercadoPagoClient.createPayment(
            MercadoPagoPaymentRequest(
                orderId = serviceOrderId,
                customerId = custId,
                amount = request.amount
            )
        )

        val paymentId = mpResponse.paymentId
        val ts = Instant.now().toEpochMilli()

        // 2. Grava item_payment no DynamoDB tb-payment
        val payment = PaymentDynamoItem(
            pk = "BILLING#$serviceOrderId",
            sk = "PAY#$ts#$paymentId",
            entityType = "PAYMENT",
            paymentId = paymentId,
            serviceOrderId = serviceOrderId.toString(),
            customerId = custId.toString(),
            priceValue = request.amount,
            paymentDate = Instant.now().toString(),
            status = mpResponse.status,
            paymentMethod = if (mpResponse.isFallback) "MERCADO_PAGO_CONTINGENCY" else (request.paymentMethod ?: "MERCADO_PAGO_QR"),
            osUpdateStatus = "PENDING",
            paymentInfo = mpResponse.qrCode,
            dispatchNotificateStatus = "PENDING"
        )
        paymentRepository.savePayment(payment)

        // 3. Grava item_invoice no DynamoDB tb-payment
        val invoiceId = UUID.randomUUID().toString()
        val invoice = InvoiceDynamoItem(
            pk = "BILLING#$serviceOrderId",
            sk = "INV#$ts#$invoiceId",
            entityType = "INVOICE",
            invoiceId = invoiceId,
            serviceOrderId = serviceOrderId.toString(),
            customerId = custId.toString(),
            invoiceNumber = "INV-${Instant.now().epochSecond.toString().takeLast(6)}",
            priceValue = request.amount,
            emissionDate = Instant.now().toString(),
            invoiceInfo = "Fatura emitida para OS $serviceOrderId",
            clientDispatch = "PENDING"
        )
        paymentRepository.saveInvoice(invoice)

        // 4. Publica evento na fila de notificação
        sqsMessenger.notifyPaymentStatus(
            eventType = "ready_for_payment",
            orderId = serviceOrderId,
            customerId = custId,
            paymentId = paymentId,
            amount = request.amount,
            status = "PENDING",
            qrCode = mpResponse.qrCode,
            ticketUrl = mpResponse.ticketUrl
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(payment)
    }

    @PostMapping("/payments")
    @Operation(summary = "Alias para criação de pagamento")
    fun createPayment(@RequestBody request: CreatePaymentRequest): ResponseEntity<PaymentDynamoItem> {
        val orderId = request.orderId ?: UUID.randomUUID()
        return createPaymentForOrder(orderId, request)
    }

    @GetMapping("/payment/{serviceOrderId}")
    @Operation(summary = "Consulta o status dos pagamentos de uma OS pelo serviceOrderId")
    fun getPaymentsByOrder(@PathVariable serviceOrderId: UUID): ResponseEntity<List<PaymentDynamoItem>> {
        val payments = paymentRepository.findByServiceOrderId(serviceOrderId)
        return ResponseEntity.ok(payments)
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Consulta o status de um pagamento pelo ID")
    fun getPaymentById(@PathVariable id: String): ResponseEntity<PaymentDynamoItem> {
        val payment = paymentRepository.findById(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(payment)
    }

    @PostMapping("/payment/{id}/confirm", "/payments/{id}/confirm")
    @Operation(summary = "Confirmação de pagamento manual/balcão ou aprovação Pix")
    fun confirmPayment(@PathVariable id: String): ResponseEntity<PaymentDynamoItem> {
        val payment = paymentRepository.findById(id) ?: return ResponseEntity.notFound().build()
        val updatedPayment = payment.copy(
            status = "APPROVED",
            dispatchNotificateStatus = "DISPATCHED",
            osUpdateStatus = "UPDATED"
        )
        paymentRepository.updateStatus(payment.pk, payment.sk, "APPROVED", "DISPATCHED", "UPDATED")

        val orderId = runCatching { UUID.fromString(payment.serviceOrderId) }.getOrNull()
        if (orderId != null) {
            sqsMessenger.sendPaymentSuccessEvent(orderId, payment.paymentId, payment.priceValue)
            val custId = runCatching { UUID.fromString(payment.customerId) }.getOrDefault(UUID.randomUUID())
            sqsMessenger.notifyPaymentStatus(
                eventType = "payment_approved",
                orderId = orderId,
                customerId = custId,
                paymentId = payment.paymentId,
                amount = payment.priceValue,
                status = "APPROVED"
            )
        }

        return ResponseEntity.ok(updatedPayment)
    }

    @PostMapping("/payment/{id}/refund", "/payments/{id}/refund")
    @Operation(summary = "Realiza estorno/reembolso no Mercado Pago para compensação da Saga Pattern")
    fun refundPayment(
        @PathVariable id: String,
        @RequestBody(required = false) request: RefundPaymentRequest?
    ): ResponseEntity<PaymentDynamoItem> {
        val payment = paymentRepository.findById(id) ?: return ResponseEntity.notFound().build()
        val reason = request?.reason ?: "Inviabilidade técnica na execução da OS"

        // 1. Aciona estorno no Mercado Pago via Circuit Breaker
        val refundResponse = mercadoPagoClient.refundPayment(payment.paymentId, payment.priceValue, reason)

        // 2. Registra estorno no DynamoDB
        val updatedPayment = paymentRepository.refundPaymentById(payment.paymentId, reason) ?: payment.copy(
            status = "REFUNDED",
            refundReason = reason,
            refundDate = Instant.now().toString()
        )

        // 3. Notifica clientes / mensageria sobre o estorno compensatório
        val orderId = runCatching { UUID.fromString(payment.serviceOrderId) }.getOrNull()
        if (orderId != null) {
            val custId = runCatching { UUID.fromString(payment.customerId) }.getOrDefault(UUID.randomUUID())
            sqsMessenger.notifyPaymentStatus(
                eventType = "payment_refunded",
                orderId = orderId,
                customerId = custId,
                paymentId = payment.paymentId,
                amount = payment.priceValue,
                status = "REFUNDED",
                reason = reason
            )
        }

        return ResponseEntity.ok(updatedPayment)
    }

    @PostMapping("/payment/order/{serviceOrderId}/refund")
    @Operation(summary = "Realiza estorno/reembolso de todos os pagamentos da OS para cancelamento compensatório")
    fun refundPaymentByOrder(
        @PathVariable serviceOrderId: UUID,
        @RequestBody(required = false) request: RefundPaymentRequest?
    ): ResponseEntity<List<PaymentDynamoItem>> {
        val payments = paymentRepository.findByServiceOrderId(serviceOrderId)
        if (payments.isEmpty()) return ResponseEntity.notFound().build()

        val refundedList = mutableListOf<PaymentDynamoItem>()
        for (payment in payments) {
            val response = refundPayment(payment.paymentId, request)
            if (response.statusCode.is2xxSuccessful && response.body != null) {
                refundedList.add(response.body!!)
            }
        }
        return ResponseEntity.ok(refundedList)
    }

    @PostMapping("/payment/webhook", "/payments/webhook")
    @Operation(summary = "Webhook de callback do Mercado Pago")
    fun webhook(@RequestBody notification: WebhookNotification): ResponseEntity<Map<String, String>> {
        val payment = paymentRepository.findById(notification.id)
        if (payment != null) {
            val isApproved = notification.status.equals("approved", true)
            val newStatus = if (isApproved) "APPROVED" else "REJECTED"
            val osStatus = if (isApproved) "UPDATED" else "PENDING"
            paymentRepository.updateStatus(payment.pk, payment.sk, newStatus, "DISPATCHED", osStatus)

            val orderId = runCatching { UUID.fromString(payment.serviceOrderId) }.getOrNull()
            if (orderId != null) {
                if (isApproved) {
                    sqsMessenger.sendPaymentSuccessEvent(orderId, payment.paymentId, payment.priceValue)
                }
                val custId = runCatching { UUID.fromString(payment.customerId) }.getOrDefault(UUID.randomUUID())
                sqsMessenger.notifyPaymentStatus(
                    eventType = if (isApproved) "payment_approved" else "payment_refused",
                    orderId = orderId,
                    customerId = custId,
                    paymentId = payment.paymentId,
                    amount = payment.priceValue,
                    status = newStatus
                )
            }
        }
        return ResponseEntity.ok(mapOf("status" to "processed"))
    }
}
