package uk.gov.pay.connector.resources;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.model.GatewayError;
import uk.gov.pay.connector.model.domain.Auth3dsDetails;
import uk.gov.pay.connector.model.domain.AuthCardDetails;
import uk.gov.pay.connector.model.gateway.GatewayResponse;
import uk.gov.pay.connector.service.*;
import uk.gov.pay.connector.service.BaseAuthoriseResponse.AuthoriseStatus;
import uk.gov.pay.connector.util.ResponseUtil;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static uk.gov.pay.connector.resources.AuthCardDetailsValidator.isWellFormatted;
import static uk.gov.pay.connector.util.ResponseUtil.badRequestResponse;
import static uk.gov.pay.connector.util.ResponseUtil.serviceErrorResponse;

@Path("/")
public class CardResource {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final CardAuthoriseService cardAuthoriseService;
    private final Card3dsResponseAuthService card3dsResponseAuthService;
    private final CardCaptureService cardCaptureService;
    private final ChargeCancelService chargeCancelService;

    @Inject
    public CardResource(CardAuthoriseService cardAuthoriseService, Card3dsResponseAuthService card3dsResponseAuthService,
                        CardCaptureService cardCaptureService, ChargeCancelService chargeCancelService) {
        this.cardAuthoriseService = cardAuthoriseService;
        this.card3dsResponseAuthService = card3dsResponseAuthService;
        this.cardCaptureService = cardCaptureService;
        this.chargeCancelService = chargeCancelService;
    }

    @POST
    @Path("/v1/frontend/charges/{chargeId}/cards")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response authoriseCharge(@PathParam("chargeId") String chargeId, AuthCardDetails authCardDetails) {

        if (!isWellFormatted(authCardDetails)) {
            return badRequestResponse("Values do not match expected format/length.");
        }
        GatewayResponse<BaseAuthoriseResponse> response = cardAuthoriseService.doAuthorise(chargeId, authCardDetails);

        if (isAuthorisationSubmitted(response)) {
            return badRequestResponse("This transaction was deferred.");
        }

        return isAuthorisationDeclined(response) ? badRequestResponse("This transaction was declined.") : handleGatewayAuthoriseResponse(response);
    }

    @POST
    @Path("/v1/frontend/charges/{chargeId}/3ds")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response authorise3dsCharge(@PathParam("chargeId") String chargeId, Auth3dsDetails auth3DsDetails) {
        GatewayResponse<BaseAuthoriseResponse> response = card3dsResponseAuthService.doAuthorise(chargeId, auth3DsDetails);
        return isAuthorisationDeclined(response) ? badRequestResponse("This transaction was declined.") : handleGatewayAuthoriseResponse(response);
    }

    @POST
    @Path("/v1/frontend/charges/{chargeId}/capture")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response captureCharge(@PathParam("chargeId") String chargeId) {
        logger.info("Capture of charge asynchronously [charge_external_id={}]", chargeId);
        cardCaptureService.markChargeAsCaptureApproved(chargeId);
        return ResponseUtil.noContentResponse();
    }

    @POST
    @Path("/v1/api/accounts/{accountId}/charges/{chargeId}/cancel")
    @Produces(APPLICATION_JSON)
    public Response cancelCharge(@PathParam("accountId") Long accountId, @PathParam("chargeId") String chargeId) {
        return handleGatewayCancelResponse(chargeCancelService.doSystemCancel(chargeId, accountId), chargeId);
    }

    @POST
    @Path("/v1/frontend/charges/{chargeId}/cancel")
    @Produces(APPLICATION_JSON)
    public Response userCancelCharge(@PathParam("chargeId") String chargeId) {
        return handleGatewayCancelResponse(chargeCancelService.doUserCancel(chargeId), chargeId);
    }

    private Response handleError(GatewayError error) {
        switch (error.getErrorType()) {
            case UNEXPECTED_HTTP_STATUS_CODE_FROM_GATEWAY:
            case MALFORMED_RESPONSE_RECEIVED_FROM_GATEWAY:
            case GATEWAY_URL_DNS_ERROR:
            case GATEWAY_CONNECTION_TIMEOUT_ERROR:
            case GATEWAY_CONNECTION_SOCKET_ERROR:
                return serviceErrorResponse(error.getMessage());
        }

        return badRequestResponse(error.getMessage());
    }

    private Response handleGatewayCancelResponse(Optional<GatewayResponse<BaseCancelResponse>> responseMaybe, String chargeId) {
        if (responseMaybe.isPresent()) {
            Optional<GatewayError> error = responseMaybe.get().getGatewayError();
            error.ifPresent(gatewayError -> logger.error(gatewayError.getMessage()));
        } else {
            logger.error("Error during cancellation of charge {} - CancelService did not return a GatewayResponse", chargeId);
        }

        return ResponseUtil.noContentResponse();
    }

    private Response handleGatewayAuthoriseResponse(GatewayResponse<? extends BaseAuthoriseResponse> response) {
        return response.getGatewayError()
                .map(this::handleError)
                .orElseGet(() -> response.getBaseResponse()
                        .map(r -> ResponseUtil.successResponseWithEntity(ImmutableMap.of("status", r.authoriseStatus().getMappedChargeStatus().toString())))
                        .orElseGet(() -> ResponseUtil.serviceErrorResponse("InterpretedStatus not found for Gateway response")));
    }

    private static boolean isAuthorisationSubmitted(GatewayResponse<BaseAuthoriseResponse> response) {
        return response.getBaseResponse()
                .filter(baseResponse -> baseResponse.authoriseStatus() == AuthoriseStatus.SUBMITTED)
                .isPresent();
    }

    private static boolean isAuthorisationDeclined(GatewayResponse<BaseAuthoriseResponse> response) {
        return response.getBaseResponse()
                .filter(baseResponse -> baseResponse.authoriseStatus() == AuthoriseStatus.REJECTED ||
                        baseResponse.authoriseStatus() == AuthoriseStatus.ERROR)
                .isPresent();
    }
}
