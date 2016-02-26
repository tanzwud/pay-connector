package uk.gov.pay.connector.unit.service;

import fj.data.Either;
import org.apache.commons.lang.math.RandomUtils;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.pay.connector.model.*;
import uk.gov.pay.connector.model.domain.Card;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.GatewayAccount;
import uk.gov.pay.connector.service.CardService;
import uk.gov.pay.connector.service.PaymentProvider;
import uk.gov.pay.connector.service.PaymentProviders;
import uk.gov.pay.connector.util.CardUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.util.Maps.newHashMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static uk.gov.pay.connector.model.domain.ChargeStatus.*;

public class CardServiceTest {

    private final String providerName = "theProvider";
    private final PaymentProvider theMockProvider = mock(PaymentProvider.class);

    private GatewayAccountJpaDao accountDao = mock(GatewayAccountJpaDao.class);
    private ChargeJpaDao chargeDao = mock(ChargeJpaDao.class);
    private PaymentProviders providers = mock(PaymentProviders.class);
    private final CardService cardService = new CardService(accountDao, chargeDao, providers);

    @Test
    public void doAuthorise_shouldAuthoriseACharge() throws Exception {

        Long chargeId = 1234L;
        String gatewayTxId = "theTxId";

        ChargeEntity chargeEntity = newCharge(ENTERING_CARD_DETAILS);

        when(chargeDao.findById(chargeId)).thenReturn(Optional.of(chargeEntity));
        when(accountDao.findById(chargeEntity.getGatewayAccount().getId())).thenReturn(Optional.of(chargeEntity.getGatewayAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        AuthorisationResponse resp = new AuthorisationResponse(true, null, AUTHORISATION_SUCCESS, gatewayTxId);
        when(theMockProvider.authorise(any())).thenReturn(resp);

        Card cardDetails = CardUtils.aValidCard();
        Either<GatewayError, GatewayResponse> response = cardService.doAuthorise(String.valueOf(chargeId), cardDetails);

        assertTrue(response.isRight());
        assertThat(response.right().value(), is(aSuccessfulResponse()));
        verify(chargeDao, times(1)).updateStatus(chargeId, AUTHORISATION_READY);
        verify(chargeDao, times(1)).updateStatus(chargeId, AUTHORISATION_SUCCESS);
        verify(chargeDao, times(1)).updateGatewayTransactionId(eq(chargeId), any(String.class));
    }

    @Test
    public void shouldNotAuthoriseAChargeIfInternalStateIsIncorrect() throws Exception {

        String chargeId = "theChargeId";
        String gatewayTxId = "theTxId";

        mockSuccessfulAuthorisationWithIncorrectInternalState(chargeId, gatewayTxId);
        Card cardDetails = CardUtils.aValidCard();
        Either<GatewayError, GatewayResponse> response = cardService.doAuthorise(chargeId, cardDetails);

        assertTrue(response.isLeft());
        assertThat(response.left().value(),
                is(anIllegalStateErrorResponse("Charge not in correct state to be processed, theChargeId")));
        verify(chargeDao, times(1)).updateStatus(chargeId, AUTHORISATION_READY);
        verify(chargeDao, times(0)).updateGatewayTransactionId(eq(chargeId), any(String.class));
    }

    @Test
    public void doAuthorise_shouldCaptureACharge() throws Exception {

        Long chargeId = 45678L;

        when(chargeDao.findById(chargeId)).thenReturn(Optional.empty());

        Either<GatewayError, GatewayResponse> response = cardService.doAuthorise(String.valueOf(chargeId), CardUtils.aValidCard());

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(ChargeNotFound));
        assertThat(gatewayError.getMessage(), is("Charge with id [45678] not found."));
    }

    @Test
    public void doAuthorise_shouldGetAnGatewayErrorWhenInvalidStatus() throws Exception {

        Long chargeId = 1234L;

        ChargeEntity chargeEntity = newCharge(ChargeStatus.CREATED);
        chargeEntity.setId(chargeId);

        when(chargeDao.findById(chargeId)).thenReturn(Optional.of(chargeEntity));

        Card cardDetails = CardUtils.aValidCard();
        Either<GatewayError, GatewayResponse> response = cardService.doAuthorise(String.valueOf(chargeId), cardDetails);

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(GenericGatewayError));
        assertThat(gatewayError.getMessage(), is("Charge not in correct state to be processed, 1234"));
    }

    @Test
    public void doCapture_shouldCaptureACharge() throws Exception {

        String chargeId = "12345";
        String gatewayTxId = "theTxId";
        ChargeEntity charge = newCharge(AUTHORISATION_SUCCESS);
        charge.setGatewayTransactionId(gatewayTxId);

        when(chargeDao.findById(Long.valueOf(chargeId))).thenReturn(Optional.of(charge));
        when(accountDao.findById(charge.getGatewayAccount().getId())).thenReturn(Optional.of(charge.getGatewayAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        CaptureResponse response1 = new CaptureResponse(true, null);
        when(theMockProvider.capture(any())).thenReturn(response1);

        Either<GatewayError, GatewayResponse> response = cardService.doCapture(chargeId);

        assertTrue(response.isRight());
        assertThat(response.right().value(), is(aSuccessfulResponse()));
        ArgumentCaptor<ChargeEntity> argumentCaptor = ArgumentCaptor.forClass(ChargeEntity.class);
        verify(chargeDao).mergeChargeEntityWithChangedStatus(argumentCaptor.capture());

        assertThat(argumentCaptor.getValue().getStatus(), is(CAPTURE_SUBMITTED.getValue()));

        ArgumentCaptor<CaptureRequest> request = ArgumentCaptor.forClass(CaptureRequest.class);
        verify(theMockProvider, times(1)).capture(request.capture());
        assertThat(request.getValue().getTransactionId(), is(gatewayTxId));
    }

    @Test
    public void doCapture_shouldGetChargeNotFoundWhenChargeDoesNotExist() {

        Long chargeId = 45678L;

        when(chargeDao.findById(chargeId)).thenReturn(Optional.empty());

        Either<GatewayError, GatewayResponse> response = cardService.doCapture(String.valueOf(chargeId));

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(ChargeNotFound));
        assertThat(gatewayError.getMessage(), is("Charge with id [45678] not found."));
    }

    @Test
    public void doCapture_ShouldGetAnErrorWhenStatusIsNotAuthorisationSuccess() {

        String chargeId = "12345";
        String gatewayTxId = "theTxId";
        ChargeEntity charge = newCharge(CREATED);
        charge.setGatewayTransactionId(gatewayTxId);

        when(chargeDao.findById(Long.valueOf(chargeId))).thenReturn(Optional.of(charge));

        Either<GatewayError, GatewayResponse> response = cardService.doCapture(chargeId);

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(GenericGatewayError));
        assertThat(gatewayError.getMessage(), is("Cannot capture a charge with status CREATED."));
    }

    @Test
    public void doCapture_shouldUpdateChargeWithCaptureUnknownWhenProviderResponseIsNotSuccessful() {
        String chargeId = "12345";
        String gatewayTxId = "theTxId";
        ChargeEntity charge = newCharge(AUTHORISATION_SUCCESS);
        charge.setGatewayTransactionId(gatewayTxId);

        when(chargeDao.findById(Long.valueOf(chargeId))).thenReturn(Optional.of(charge));
        when(accountDao.findById(charge.getGatewayAccount().getId())).thenReturn(Optional.of(charge.getGatewayAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        CaptureResponse unsuccessfulResponse = new CaptureResponse(false, new GatewayError("error", GenericGatewayError));
        when(theMockProvider.capture(any())).thenReturn(unsuccessfulResponse);

        Either<GatewayError, GatewayResponse> response = cardService.doCapture(chargeId);

        assertTrue(response.isRight());
        assertThat(response.right().value(), is(anUnSuccessfulResponse()));
        ArgumentCaptor<ChargeEntity> argumentCaptor = ArgumentCaptor.forClass(ChargeEntity.class);
        verify(chargeDao).mergeChargeEntityWithChangedStatus(argumentCaptor.capture());

        assertThat(argumentCaptor.getValue().getStatus(), is(CAPTURE_UNKNOWN.getValue()));

        ArgumentCaptor<CaptureRequest> request = ArgumentCaptor.forClass(CaptureRequest.class);
        verify(theMockProvider, times(1)).capture(request.capture());
        assertThat(request.getValue().getTransactionId(), is(gatewayTxId));
    }

    @Test
    public void doCancel_shouldCancelACharge() throws Exception {

        Long chargeId = 1234L;
        String accountId = "theAccountId";

        ChargeEntity charge = newCharge(ENTERING_CARD_DETAILS);

        when(chargeDao.findChargeForAccount(chargeId, accountId)).thenReturn(Optional.of(charge));
        when(accountDao.findById(charge.getGatewayAccount().getId())).thenReturn(Optional.of(charge.getGatewayAccount()));

        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        CancelResponse cancelResponse = new CancelResponse(true, null);
        when(theMockProvider.cancel(any())).thenReturn(cancelResponse);

        Either<GatewayError, GatewayResponse> response = cardService.doCancel(String.valueOf(chargeId), accountId);

        assertTrue(response.isRight());
        assertThat(response.right().value(), is(aSuccessfulResponse()));

        ArgumentCaptor<ChargeEntity> argumentCaptor = ArgumentCaptor.forClass(ChargeEntity.class);

        verify(chargeDao).mergeChargeEntityWithChangedStatus(argumentCaptor.capture());
        ChargeEntity updatedCharge = argumentCaptor.getValue();
        assertThat(updatedCharge.getStatus(), is(SYSTEM_CANCELLED.getValue()));
    }

    @Test
    public void doCancel_shouldGetChargeNotFoundWhenChargeDoesNotExistForAccount() {
        Long chargeId = 1234L;
        String accountId = "theAccountId";

        when(chargeDao.findChargeForAccount(chargeId, accountId)).thenReturn(Optional.empty());

        Either<GatewayError, GatewayResponse> response = cardService.doCancel(String.valueOf(chargeId), accountId);

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(ChargeNotFound));
        assertThat(gatewayError.getMessage(), is("Charge with id [1234] not found."));
    }

    @Test
    public void doCancel_shouldFailForStatesThatAreNotCancellable() {
        Long chargeId = 1234L;
        String accountId = "theAccountId";

        ChargeEntity charge = newCharge(CAPTURE_SUBMITTED);
        charge.setId(chargeId);

        when(chargeDao.findChargeForAccount(chargeId, accountId)).thenReturn(Optional.of(charge));

        Either<GatewayError, GatewayResponse> response = cardService.doCancel(String.valueOf(chargeId), accountId);

        assertTrue(response.isLeft());
        GatewayError gatewayError = response.left().value();

        assertThat(gatewayError.getErrorType(), is(GenericGatewayError));
        assertThat(gatewayError.getMessage(), is("Cannot cancel a charge id [1234]: status is [CAPTURE SUBMITTED]."));
    }

    @Test
    public void doCancel_shouldNotUpdateStatusWhenProviderResponseIsNotSuccessful() {
        Long chargeId = 1234L;
        String accountId = "theAccountId";

        ChargeEntity charge = newCharge(ENTERING_CARD_DETAILS);

        when(chargeDao.findChargeForAccount(chargeId, accountId)).thenReturn(Optional.of(charge));
        when(accountDao.findById(charge.getGatewayAccount().getId())).thenReturn(Optional.of(charge.getGatewayAccount()));

        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        CancelResponse cancelResponse = new CancelResponse(false, new GatewayError("Error", GatewayErrorType.GenericGatewayError));
        when(theMockProvider.cancel(any())).thenReturn(cancelResponse);

        Either<GatewayError, GatewayResponse> response = cardService.doCancel(String.valueOf(chargeId), accountId);

        assertTrue(response.isRight());
        assertThat(response.right().value(), is(anUnSuccessfulResponse()));

        verify(chargeDao, never()).merge(any(ChargeEntity.class));

    }

    private void mockSuccessfulCapture(String chargeId, String gatewayTransactionId) {
        Map<String, Object> charge = theCharge(chargeId, AUTHORISATION_SUCCESS);
        charge.put("gateway_transaction_id", gatewayTransactionId);

        when(chargeDao.findById(chargeId)).thenReturn(Optional.of(charge));
        when(accountDao.findById(gatewayAccountId)).thenReturn(Optional.of(theAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        CaptureResponse response = new CaptureResponse(true, null);
        when(theMockProvider.capture(any())).thenReturn(response);
    }

    private void mockSuccessfulAuthorisation(String chargeId, String transactionId) {
        when(chargeDao.findById(chargeId))
                .thenReturn(Optional.of(theCharge(chargeId, ENTERING_CARD_DETAILS)))
                .thenReturn(Optional.of(theCharge(chargeId, AUTHORISATION_READY)));
        when(accountDao.findById(gatewayAccountId)).thenReturn(Optional.of(theAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        AuthorisationResponse resp = new AuthorisationResponse(true, null, AUTHORISATION_SUCCESS, transactionId);
        when(theMockProvider.authorise(any())).thenReturn(resp);
    }

    private void mockSuccessfulAuthorisationWithIncorrectInternalState(String chargeId, String transactionId) {
        when(chargeDao.findById(chargeId))
                .thenReturn(Optional.of(theCharge(chargeId, ENTERING_CARD_DETAILS)))
                .thenReturn(Optional.of(theCharge(chargeId, AUTHORISATION_SUCCESS)));
        when(accountDao.findById(gatewayAccountId)).thenReturn(Optional.of(theAccount()));
        when(providers.resolve(providerName)).thenReturn(theMockProvider);
        AuthorisationResponse resp = new AuthorisationResponse(true, null, AUTHORISATION_SUCCESS, transactionId);
        when(theMockProvider.authorise(any())).thenReturn(resp);
    }


    private GatewayAccount theAccount() {
        return new GatewayAccount(RandomUtils.nextLong(), providerName, newHashMap());
    }

    private Map<String, Object> theCharge(String chargeId, ChargeStatus status) {
        return new HashMap<String, Object>() {{
            put("charge_id", chargeId);
            put("status", status.getValue());
            put("amount", "500");
            put("gateway_account_id", gatewayAccountId);
        }};
    }


    private Matcher<GatewayResponse> aSuccessfulResponse() {
        return new TypeSafeMatcher<GatewayResponse>() {
            private GatewayResponse gatewayResponse;

            @Override
            protected boolean matchesSafely(GatewayResponse gatewayResponse) {
                this.gatewayResponse = gatewayResponse;
                return gatewayResponse.isSuccessful() && gatewayResponse.getError() == null;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Success, but response was not successful: " + gatewayResponse.getError().getMessage());
            }
        };
    }

    private Matcher<GatewayError> anIllegalStateErrorResponse(String message) {
        return new TypeSafeMatcher<GatewayError>() {
            private GatewayError gatewayError;

            @Override
            protected boolean matchesSafely(GatewayError gatewayError) {
                this.gatewayError = gatewayError;
                return (gatewayError.getErrorType() == IllegalStateError) && (gatewayError.getMessage().equals(message));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(format("failure of type: %s, with message: %s",
                        gatewayError.getErrorType(), gatewayError.getMessage()));
            }
        };
    }

    private Matcher<GatewayResponse> anUnSuccessfulResponse() {
        return new TypeSafeMatcher<GatewayResponse>() {
            private GatewayResponse gatewayResponse;

            @Override
            protected boolean matchesSafely(GatewayResponse gatewayResponse) {
                this.gatewayResponse = gatewayResponse;
                return !gatewayResponse.isSuccessful() && gatewayResponse.getError() != null;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Response Error : " + gatewayResponse.getError().getMessage());
            }
        };
    }

}