package uk.gov.pay.connector.resources;

import com.google.common.collect.ImmutableMap;
import fj.F;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.LinksConfig;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.dao.EventDao;
import uk.gov.pay.connector.dao.GatewayAccountDao;
import uk.gov.pay.connector.dao.TokenDao;
import uk.gov.pay.connector.model.api.ExternalChargeStatus;
import uk.gov.pay.connector.model.domain.ChargeEvent;
import uk.gov.pay.connector.util.ResponseBuilder;
import uk.gov.pay.connector.util.ResponseUtil;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.newHashMap;
import static fj.data.Either.reduce;
import static java.lang.String.format;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static uk.gov.pay.connector.model.api.ExternalChargeStatus.mapFromStatus;
import static uk.gov.pay.connector.model.api.ExternalChargeStatus.valueOfExternalStatus;
import static uk.gov.pay.connector.model.domain.ChargeStatus.STATUS_KEY;
import static uk.gov.pay.connector.resources.ApiPaths.*;
import static uk.gov.pay.connector.util.ResponseUtil.*;

@Path("/")
public class ChargesApiResource {
    private static final String AMOUNT_KEY = "amount";
    private static final String DESCRIPTION_KEY = "description";
    private static final String RETURN_URL_KEY = "return_url";
    private static final String REFERENCE_KEY = "reference";
    private static final String[] REQUIRED_FIELDS = {AMOUNT_KEY, DESCRIPTION_KEY, REFERENCE_KEY, RETURN_URL_KEY};
    private static final Map<String, Integer> MAXIMUM_FIELDS_SIZE = ImmutableMap.of(
            DESCRIPTION_KEY, 255,
            REFERENCE_KEY, 255
    );

    private static final String STATUS_KEY = "status";

    private ChargeDao chargeDao;
    private TokenDao tokenDao;
    private GatewayAccountDao gatewayAccountDao;
    private EventDao eventDao;
    private LinksConfig linksConfig;
    private Logger logger = LoggerFactory.getLogger(ChargesApiResource.class);

    public ChargesApiResource(ChargeDao chargeDao, TokenDao tokenDao, GatewayAccountDao gatewayAccountDao, EventDao eventDao, LinksConfig linksConfig) {
        this.chargeDao = chargeDao;
        this.tokenDao = tokenDao;
        this.gatewayAccountDao = gatewayAccountDao;
        this.eventDao = eventDao;
        this.linksConfig = linksConfig;
    }

    @GET
    @Path(CHARGE_API_PATH)
    @Produces(APPLICATION_JSON)
    public Response getCharge(@PathParam("accountId") String accountId, @PathParam("chargeId") String chargeId, @Context UriInfo uriInfo) {
        Optional<Map<String, Object>> maybeCharge = chargeDao.findChargeForAccount(chargeId, accountId);

        return maybeCharge
                .map(charge -> {
                    URI selfUri = selfUriFor(uriInfo, accountId, chargeId);
                    String tokenId = tokenDao.findByChargeId(chargeId);
                    Map<String, Object> responseData = getResponseData(chargeId, tokenId, charge, selfUri);

                    return ResponseUtil.entityResponse(responseData);
                })
                .orElseGet(() -> responseWithChargeNotFound(logger, chargeId));

    }

    @POST
    @Path(CHARGES_API_PATH)
    @Produces(APPLICATION_JSON)
    public Response createNewCharge(@PathParam("accountId") String accountId, Map<String, Object> chargeRequest, @Context UriInfo uriInfo) {
        Optional<List<String>> missingFields = checkMissingFields(chargeRequest);
        if (missingFields.isPresent()) {
            return fieldsMissingResponse(logger, missingFields.get());
        }

        Optional<List<String>> invalidSizeFields = checkInvalidSizeFields(chargeRequest);
        if (invalidSizeFields.isPresent()) {
            return fieldsInvalidSizeResponse(logger, invalidSizeFields.get());
        }

        if (gatewayAccountDao.idIsMissing(accountId)) {
            return notFoundResponse(logger, "Unknown gateway account: " + accountId);
        }

        logger.info("Creating new charge of {}.", chargeRequest);
        String chargeId = chargeDao.saveNewCharge(accountId, chargeRequest);
        String tokenId = UUID.randomUUID().toString();
        tokenDao.insertNewToken(chargeId, tokenId);

        Optional<Map<String, Object>> maybeCharge = chargeDao.findById(chargeId);

        return maybeCharge
                .map(charge -> {
                    URI selfUri = selfUriFor(uriInfo, accountId, chargeId);
                    Map<String, Object> responseData = getResponseData(chargeId, tokenId, charge, selfUri);

                    logger.info("charge = {}", charge);
                    logger.info("responseData = {}", responseData);

                    return entityCreatedResponse(selfUri, responseData);
                })
                .orElseGet(() -> responseWithChargeNotFound(logger, chargeId));
    }

    @GET
    @Path(CHARGE_EVENTS_API_PATH)
    @Produces(APPLICATION_JSON)
    public Response getEvents(@PathParam("accountId") Long accountId, @PathParam("chargeId") Long chargeId){
        List<ChargeEvent> events = eventDao.findEvents(accountId,chargeId);
        ImmutableMap<String, Object> responsePayload = ImmutableMap.of("charge_id", chargeId, "events", events);
        return Response.ok().entity(responsePayload).build();
    }

    private Map<String, Object> getResponseData(String chargeId, String tokenId, Map<String, Object> charge, URI selfUri) {
        ResponseBuilder responseBuilder = new ResponseBuilder()
                .withCharge(convertStatusToExternalStatus(newHashMap(charge)))
                .withLink("self", GET, selfUri);

        if (!isEmpty(tokenId)) {
            URI nextUrl = secureRedirectUriFor(chargeId, tokenId);
            responseBuilder.withLink("next_url", GET, nextUrl);
        }

        return responseBuilder.build();
    }

    @GET
    @Path(CHARGES_API_PATH)
    @Produces(APPLICATION_JSON)
    public Response getCharges(@PathParam("accountId") String accountId,
                               @QueryParam("reference") String reference,
                               @QueryParam("status") String status,
                               @QueryParam("from_date") String fromDate,
                               @QueryParam("to_date") String toDate,
                               @Context UriInfo uriInfo) {
        ExternalChargeStatus chargeStatus = null;
        if (StringUtils.isNotBlank(status)) {
            chargeStatus = valueOfExternalStatus(status);
        }

        List<Map<String, Object>> charges = chargeDao.findAllBy(accountId, reference, chargeStatus, fromDate, toDate);
        charges.forEach(charge -> charge.put(STATUS_KEY, mapFromStatus(charge.get(STATUS_KEY).toString()).getValue()));
        ImmutableMap<String, Object> responsePayload = ImmutableMap.of("results", charges);
        return Response.ok().entity(responsePayload).build();
    }

    private Map<String, Object> convertStatusToExternalStatus(Map<String, Object> data) {
        ExternalChargeStatus externalState = mapFromStatus(data.get(STATUS_KEY).toString());
        data.put(STATUS_KEY, externalState.getValue());
        return data;
    }

    private URI selfUriFor(UriInfo uriInfo, String accountId ,String chargeId) {
        return uriInfo.getBaseUriBuilder()
                .path(CHARGE_API_PATH)
                .build(accountId, chargeId);
    }

    private URI secureRedirectUriFor(String chargeId, String tokenId) {
        String secureRedirectLocation = linksConfig.getCardDetailsUrl()
                .replace("{chargeId}", chargeId)
                .replace("{chargeTokenId}", tokenId);
        try {
            return new URI(secureRedirectLocation);
        } catch (URISyntaxException e) {
            logger.error(format("Invalid secure redirect url: %s", secureRedirectLocation), e);
            throw new RuntimeException(e);
        }
    }

    private Optional<List<String>> checkMissingFields(Map<String, Object> inputData) {
        List<String> missing = Arrays.stream(REQUIRED_FIELDS)
                .filter(field -> !inputData.containsKey(field))
                .collect(Collectors.toList());

        return missing.isEmpty()
                ? Optional.<List<String>>empty()
                : Optional.of(missing);
    }

    private Optional<List<String>> checkInvalidSizeFields(Map<String, Object> inputData) {
        List<String> invalidSize = MAXIMUM_FIELDS_SIZE.entrySet().stream()
                .filter(entry -> !isFieldSizeValid(inputData, entry.getKey(), entry.getValue()))
                .map(entry -> entry.getKey())
                .collect(Collectors.toList());

        return invalidSize.isEmpty()
                ? Optional.<List<String>>empty()
                : Optional.of(invalidSize);
    }

    private boolean isFieldSizeValid(Map<String, Object> chargeRequest, String fieldName, int fieldSize) {
        String value = chargeRequest.get(fieldName).toString();
        return value.length() <= fieldSize;
    }
}
