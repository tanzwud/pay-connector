package uk.gov.pay.connector.service.smartpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.model.*;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.GatewayAccount;
import uk.gov.pay.connector.service.GatewayClient;
import uk.gov.pay.connector.service.PaymentProvider;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static javax.ws.rs.core.Response.Status.OK;
import static uk.gov.pay.connector.model.AuthorisationResponse.*;
import static uk.gov.pay.connector.model.CancelResponse.aSuccessfulCancelResponse;
import static uk.gov.pay.connector.model.CancelResponse.errorCancelResponse;
import static uk.gov.pay.connector.model.CaptureResponse.aSuccessfulCaptureResponse;
import static uk.gov.pay.connector.model.CaptureResponse.captureFailureResponse;
import static uk.gov.pay.connector.model.GatewayError.baseGatewayError;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.service.OrderCaptureRequestBuilder.aSmartpayOrderCaptureRequest;
import static uk.gov.pay.connector.service.OrderSubmitRequestBuilder.aSmartpayOrderSubmitRequest;
import static uk.gov.pay.connector.service.smartpay.SmartpayOrderCancelRequestBuilder.aSmartpayOrderCancelRequest;

public class SmartpayPaymentProvider implements PaymentProvider {

    private static final String MERCHANT_CODE = "MerchantAccount";
    public static final String ACCEPTED = "[accepted]";
    private final Logger logger = LoggerFactory.getLogger(SmartpayPaymentProvider.class);

    private final GatewayClient client;
    private final GatewayAccount gatewayAccount;
    private ObjectMapper objectMapper;

    public SmartpayPaymentProvider(GatewayClient client, GatewayAccount gatewayAccount, ObjectMapper objectMapper) {
        this.client = client;
        this.gatewayAccount = gatewayAccount;
        this.objectMapper = objectMapper;
    }

    @Override
    public AuthorisationResponse authorise(AuthorisationRequest request) {
        String requestString = buildOrderSubmitFor(request, request.getChargeId());
        Response response = client.postXMLRequestFor(gatewayAccount, requestString);
        return response.getStatus() == OK.getStatusCode() ?
                mapToCardAuthorisationResponse(response) :
                errorResponse(logger, response);
    }

    @Override
    public CaptureResponse capture(CaptureRequest request) {
        String captureRequestString = buildOrderCaptureFor(request);
        logger.debug("captureRequestString = " + captureRequestString);
        Response response = client.postXMLRequestFor(gatewayAccount, captureRequestString);
        return response.getStatus() == OK.getStatusCode() ?
                mapToCaptureResponse(response) :
                handleCaptureError(response);
    }

    @Override
    public StatusUpdates newStatusFromNotification(String notification) {
        try {
            List<SmartpayNotification> notifications = objectMapper.readValue(notification, SmartpayNotificationList.class).getNotifications();
            Collections.sort(notifications);

            List<Pair<String, ChargeStatus>> updates = notifications.stream()
                    .filter(this::definedStatuses)
                    .map(this::toInternalStatus)
                    .collect(Collectors.toList());

            return StatusUpdates.withUpdate(ACCEPTED, updates);
        } catch (IllegalArgumentException | IOException e) {
            logger.error(String.format("Could not deserialise smartpay notification:\n %s", notification), e);
        }
        return StatusUpdates.noUpdate(ACCEPTED);
    }

    private boolean definedStatuses(SmartpayNotification notification) {
        String smartpayStatus = notification.getEventCode();
        Optional<ChargeStatus> newChargeStatus = SmartpayStatusMapper.mapToChargeStatus(smartpayStatus, notification.isSuccessFull());
        if (!newChargeStatus.isPresent()) {
            logger.error(format("Could not map Smartpay status %s to our internal status.", notification.getEventCode()));
        }
        return newChargeStatus.isPresent();
    }

    private Pair<String, ChargeStatus> toInternalStatus(SmartpayNotification notification) {
        String smartpayStatus = notification.getEventCode();
        Optional<ChargeStatus> newChargeStatus = SmartpayStatusMapper.mapToChargeStatus(smartpayStatus, notification.isSuccessFull());
        return new Pair<>(notification.getTransactionId(), newChargeStatus.get());
    }

    @Override
    public CancelResponse cancel(CancelRequest request) {
        Response response = client.postXMLRequestFor(gatewayAccount, buildCancelOrderFor(request));
        return response.getStatus() == OK.getStatusCode() ?
                mapToCancelResponse(response) :
                errorCancelResponse(logger, response);
    }

    private AuthorisationResponse mapToCardAuthorisationResponse(Response response) {
        SmartpayAuthorisationResponse sResponse = client.unmarshallResponse(response, SmartpayAuthorisationResponse.class);

        return sResponse.isAuthorised() ?
                successfulAuthorisation(AUTHORISATION_SUCCESS, sResponse.getPspReference()) :
                authorisationFailureResponse(logger, sResponse.getPspReference(), sResponse.getErrorMessage());
    }

    private CancelResponse mapToCancelResponse(Response response) {
        SmartpayCancelResponse spResponse = client.unmarshallResponse(response, SmartpayCancelResponse.class);
        return spResponse.isCancelled() ? aSuccessfulCancelResponse() : new CancelResponse(false, baseGatewayError(spResponse.getErrorMessage()));
    }

    private String buildOrderSubmitFor(AuthorisationRequest request, String transactionId) {
        return aSmartpayOrderSubmitRequest()
                .withMerchantCode(MERCHANT_CODE)
                .withTransactionId(transactionId)
                .withDescription(request.getDescription())
                .withAmount(request.getAmount())
                .withCard(request.getCard())
                .build();
    }

    private String buildCancelOrderFor(CancelRequest request) {
        return aSmartpayOrderCancelRequest()
                .withMerchantCode(MERCHANT_CODE)
                .withTransactionId(request.getTransactionId())
                .build();
    }

    private String buildOrderCaptureFor(CaptureRequest request) {
        return aSmartpayOrderCaptureRequest()
                .withMerchantCode(MERCHANT_CODE)
                .withTransactionId(request.getTransactionId())
                .withAmount(request.getAmount())
                .build();
    }

    private CaptureResponse mapToCaptureResponse(Response response) {
        SmartpayCaptureResponse sResponse = client.unmarshallResponse(response, SmartpayCaptureResponse.class);
        return sResponse.isCaptured() ?
                aSuccessfulCaptureResponse() :
                captureFailureResponse(logger, sResponse.getErrorMessage(), sResponse.getPspReference());
    }

    private CaptureResponse handleCaptureError(Response response) {
        logger.error(format("Error code received from provider: response status = %s, body = %s.", response.getStatus(), response.readEntity(String.class)));
        return new CaptureResponse(false, baseGatewayError("Error processing capture request"));
    }
}
