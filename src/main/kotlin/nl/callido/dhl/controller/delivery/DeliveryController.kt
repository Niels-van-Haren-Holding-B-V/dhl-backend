package nl.callido.dhl.controller.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.service.delivery.DeliveryService
import nl.callido.dhl.dto.delivery.RegisterDeliveryRequest
import nl.callido.dhl.dto.delivery.RegisterDeliveryResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The pre-existing registration endpoint. The locker flow calls the same
 * service internally on confirm — kept as a real endpoint to demonstrate
 * path reuse.
 */
@RestController
@RequestMapping("/api/deliveries")
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class DeliveryController(private val deliveryService: DeliveryService) {

    @PostMapping("/register")
    suspend fun register(@RequestBody req: RegisterDeliveryRequest): RegisterDeliveryResponse =
        withContext(Dispatchers.IO) { deliveryService.register(req.barcode, req.status, req.sessionId) }
}
