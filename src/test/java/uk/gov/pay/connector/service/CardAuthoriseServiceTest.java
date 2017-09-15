package uk.gov.pay.connector.service;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.setup.Environment;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.internal.hamcrest.HamcrestArgumentMatcher;
import org.mockito.runners.MockitoJUnitRunner;
import uk.gov.pay.connector.exception.ChargeNotFoundRuntimeException;
import uk.gov.pay.connector.exception.ConflictRuntimeException;
import uk.gov.pay.connector.exception.IllegalStateRuntimeException;
import uk.gov.pay.connector.exception.OperationAlreadyInProgressRuntimeException;
import uk.gov.pay.connector.model.GatewayError;
import uk.gov.pay.connector.model.domain.*;
import uk.gov.pay.connector.model.gateway.GatewayResponse;
import uk.gov.pay.connector.model.gateway.GatewayResponse.GatewayResponseBuilder;
import uk.gov.pay.connector.service.BaseAuthoriseResponse.AuthoriseStatus;
import uk.gov.pay.connector.service.worldpay.WorldpayOrderStatusResponse;
import uk.gov.pay.connector.util.AuthUtils;

import javax.persistence.OptimisticLockException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static uk.gov.pay.connector.model.GatewayError.gatewayConnectionTimeoutException;
import static uk.gov.pay.connector.model.GatewayError.malformedResponseReceivedFromGateway;
import static uk.gov.pay.connector.model.domain.ChargeStatus.*;
import static uk.gov.pay.connector.model.gateway.GatewayResponse.GatewayResponseBuilder.responseBuilder;
import static uk.gov.pay.connector.service.CardExecutorService.ExecutionStatus.COMPLETED;
import static uk.gov.pay.connector.service.CardExecutorService.ExecutionStatus.IN_PROGRESS;

@RunWith(MockitoJUnitRunner.class)
public class CardAuthoriseServiceTest extends CardServiceTest {

    private static final String PA_REQ_VALUE_FROM_PROVIDER = "pa-req-value-from-provider";
    private static final String ISSUER_URL_FROM_PROVIDER = "issuer-url-from-provider";
    private static final String SESSION_IDENTIFIER = "session-identifier";
    private static final String TRANSACTION_ID = "transaction-id";

    private final Auth3dsDetailsFactory auth3dsDetailsFactory = new Auth3dsDetailsFactory();
    private final ChargeEntity charge = createNewChargeWith(1L, ENTERING_CARD_DETAILS);

    @Mock
    private  ChargeEntity mockChargeReloadedInPostOperation;

    @Mock
    private CardExecutorService mockExecutorService;

    private CardAuthoriseService cardAuthorisationService;

    @Before
    public void setUpCardAuthorisationService() {
        Environment mockEnvironment = mock(Environment.class);
        mockMetricRegistry = mock(MetricRegistry.class);
        Counter mockCounter = mock(Counter.class);
        when(mockMetricRegistry.counter(anyString())).thenReturn(mockCounter);
        when(mockEnvironment.metrics()).thenReturn(mockMetricRegistry);
        cardAuthorisationService = new CardAuthoriseService(mockedChargeDao, mockedCardTypeDao, mockedProviders, mockExecutorService,
                auth3dsDetailsFactory, mockEnvironment);
    }

    @Before
    public void configureChargeDaoMock() {

        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));

       /* ChargeEntity mergedAuthReadyEntity = ChargeEntityFixture
                .aValidChargeEntity()
                .withId(charge.getId())
                .withExternalId(charge.getExternalId())
                .withStatus(AUTHORISATION_READY)
                .build();
        mergedAuthReadyEntity.setCardDetails(new CardDetailsEntity());

        ChargeEntity mergedAuthSuccessEntity = ChargeEntityFixture
                .aValidChargeEntity()
                .withId(charge.getId())
                .withExternalId(charge.getExternalId())
                .withStatus(AUTHORISATION_READY)
                .build();
        mergedAuthReadyEntity.setCardDetails(new CardDetailsEntity());*/

        //mockChargeReloadedInPostOperation = spy(mergedAuthReadyEntity);

        when(mockedChargeDao.merge(argThat(chargeWithStatus(charge.getExternalId(), AUTHORISATION_READY))))
                .thenReturn(mockChargeReloadedInPostOperation);

        //when(mockedChargeDao.merge(argThat(chargeWithStatus(charge.getExternalId(), AUTHORISATION_SUCCESS))))
        //        .thenReturn(mergedAuthSuccessEntity);

        //when(mockedChargeDao.merge(mockChargeReloadedInPostOperation)).thenReturn(mockChargeReloadedInPostOperation);
    }

    private HamcrestArgumentMatcher<ChargeEntity> chargeWithStatus(String externalId, ChargeStatus status) {
        return new HamcrestArgumentMatcher<>(new TypeSafeMatcher<ChargeEntity>() {
            @Override
            protected boolean matchesSafely(ChargeEntity chargeEntity) {
                return chargeEntity.getExternalId().equals(externalId) && ChargeStatus.fromString(chargeEntity.getStatus()) == status;
            }

            @Override
            public void describeTo(Description description) {
            }
        });
    }

    public void mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue() {
        doAnswer(invocation -> Pair.of(COMPLETED, ((Supplier) invocation.getArguments()[0]).get()))
                .when(mockExecutorService).execute(any(Supplier.class));
    }

    private GatewayResponse mockAuthResponse(String TRANSACTION_ID, AuthoriseStatus authoriseStatus, String errorCode) {
        WorldpayOrderStatusResponse worldpayResponse = mock(WorldpayOrderStatusResponse.class);
        when(worldpayResponse.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(worldpayResponse.authoriseStatus()).thenReturn(authoriseStatus);
        when(worldpayResponse.getErrorCode()).thenReturn(errorCode);
        GatewayResponseBuilder<WorldpayOrderStatusResponse> gatewayResponseBuilder = responseBuilder();
        return gatewayResponseBuilder
                .withResponse(worldpayResponse)
                .withSessionIdentifier(SESSION_IDENTIFIER)
                .build();
    }

    private void setupPaymentProviderMock(GatewayError gatewayError) {
        GatewayResponseBuilder<WorldpayOrderStatusResponse> gatewayResponseBuilder = responseBuilder();
        GatewayResponse authorisationResponse = gatewayResponseBuilder
                .withGatewayError(gatewayError)
                .build();
        when(mockedPaymentProvider.authorise(any())).thenReturn(authorisationResponse);
    }

    @Test
    public void doAuthorise_shouldRespondAuthorisationSuccess() throws Exception {

        providerWillAuthorise();

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isSuccessful(), is(true));
        assertThat(response.getSessionIdentifier().get(), is(SESSION_IDENTIFIER));

        verify(mockChargeReloadedInPostOperation).setProviderSessionId(SESSION_IDENTIFIER);
        verify(mockChargeReloadedInPostOperation).setStatus(AUTHORISATION_SUCCESS);
        verify(mockChargeReloadedInPostOperation).setGatewayTransactionId(TRANSACTION_ID);
        verify(mockedChargeDao).notifyStatusHasChanged(mockChargeReloadedInPostOperation, Optional.empty());
        verify(mockChargeReloadedInPostOperation, never()).set3dsDetails(any(Auth3dsDetailsEntity.class));
    }

    @Test
    public void doAuthorise_shouldRespondAuthorisationSuccess_overridingGeneratedTransactionId() throws Exception {

        providerWillAuthorise();

        String generatedTransactionId = "this-will-be-override-to-TRANSACTION-ID-from-provider";

        when(mockedProviders.byName(charge.getPaymentGatewayName())).thenReturn(mockedPaymentProvider);
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.of(generatedTransactionId));

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isSuccessful(), is(true));
        assertThat(response.getSessionIdentifier().get(), is(SESSION_IDENTIFIER));

        verify(mockChargeReloadedInPostOperation).setProviderSessionId(SESSION_IDENTIFIER);
        verify(mockChargeReloadedInPostOperation).setStatus(AUTHORISATION_SUCCESS);
        verify(mockChargeReloadedInPostOperation).setGatewayTransactionId(TRANSACTION_ID);
        verify(mockedChargeDao).notifyStatusHasChanged(mockChargeReloadedInPostOperation, Optional.empty());
        verify(mockChargeReloadedInPostOperation, never()).set3dsDetails(any(Auth3dsDetailsEntity.class));
    }


    @Test
    public void doAuthorise_shouldRespondWith3dsResponseFor3dsOrders() {

        providerWillRequire3ds();

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isSuccessful(), is(true));
        assertThat(response.getSessionIdentifier().get(), is(SESSION_IDENTIFIER));


        verify(mockChargeReloadedInPostOperation).setProviderSessionId(SESSION_IDENTIFIER);
        verify(mockChargeReloadedInPostOperation).setStatus(AUTHORISATION_3DS_REQUIRED);
        verify(mockChargeReloadedInPostOperation).setGatewayTransactionId(TRANSACTION_ID);
        verify(mockedChargeDao).notifyStatusHasChanged(mockChargeReloadedInPostOperation, Optional.empty());
        verify(mockChargeReloadedInPostOperation, never()).set3dsDetails(any(Auth3dsDetailsEntity.class));


        //assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_3DS_REQUIRED.toString()));
        //assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(TRANSACTION_ID));
        //assertThat(mockChargeReloadedInPostOperation.get3dsDetails().getPaRequest(), is(PA_REQ_VALUE_FROM_PROVIDER));
        //assertThat(mockChargeReloadedInPostOperation.get3dsDetails().getIssuerUrl(), is(ISSUER_URL_FROM_PROVIDER));
    }

    @Test(expected = ConflictRuntimeException.class)
    public void shouldRespondAuthorisationFailedWhen3dsRequiredConflictingConfigurationOfCardTypeWithGatewayAccount() throws Exception {

        AuthCardDetails authCardDetails = AuthUtils.aValidAuthorisationDetails();

        GatewayAccountEntity gatewayAccountEntity = new GatewayAccountEntity();
        CardTypeEntity cardTypeEntity = new CardTypeEntity();
        cardTypeEntity.setRequires3ds(true);
        cardTypeEntity.setBrand(authCardDetails.getCardBrand());
        gatewayAccountEntity.setType(GatewayAccountEntity.Type.LIVE);
        gatewayAccountEntity.setGatewayName("worldpay");
        gatewayAccountEntity.setRequires3ds(false);

        ChargeEntity charge = ChargeEntityFixture
                .aValidChargeEntity()
                .withGatewayAccountEntity(gatewayAccountEntity)
                .withStatus(ENTERING_CARD_DETAILS)
                .build();
        ChargeEntity reloadedCharge = spy(charge);

        when(mockedCardTypeDao.findByBrand(authCardDetails.getCardBrand())).thenReturn(newArrayList(cardTypeEntity));
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        when(mockedChargeDao.merge(charge)).thenReturn(reloadedCharge);
        when(mockedChargeDao.mergeAndNotifyStatusHasChanged(reloadedCharge, Optional.empty())).thenReturn(reloadedCharge);

        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), authCardDetails);

        assertThat(response.isSuccessful(), is(false));
        assertThat(reloadedCharge.getStatus(), is(AUTHORISATION_ABORTED.toString()));
    }




    @Test
    public void shouldRetainGeneratedTransactionIdIfAuthorisationAborted() throws Exception {
        String generatedTransactionId = "generated-transaction-id";
        when(mockedProviders.byName(charge.getPaymentGatewayName())).thenReturn(mockedPaymentProvider);
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.of(generatedTransactionId));
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        when(mockedPaymentProvider.authorise(any())).thenThrow(RuntimeException.class);

        try {
            cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());
            fail("Won’t get this far");
        } catch (RuntimeException e) {
            assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(generatedTransactionId));
        }
    }

    @Test
    public void shouldRespondAuthorisationRejected() throws Exception {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.REJECTED, null);
        providerWillRespondToAuthoriseWith(authResponse);

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isSuccessful(), is(true));
        assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_REJECTED.toString()));
        assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(TRANSACTION_ID));
    }

    @Test
    public void shouldRespondAuthorisationCancelled() throws Exception {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.CANCELLED, null);
        providerWillRespondToAuthoriseWith(authResponse);

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isSuccessful(), is(true));
        assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_CANCELLED.toString()));
        assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(TRANSACTION_ID));
    }

    @Test
    public void shouldRespondAuthorisationError() throws Exception {
        providerWillReject();

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isFailed(), is(true));
        assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_ERROR.toString()));
        assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(nullValue()));
    }

    @Test
    public void shouldStoreCardDetailsIfAuthorisationSuccess() {
        AuthCardDetails authCardDetails = AuthUtils.aValidAuthorisationDetails();
        providerWillAuthorise();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), authCardDetails);

        assertThat(mockChargeReloadedInPostOperation.getCardDetails(), is(notNullValue()));
    }

    @Test
    public void shouldStoreCardDetailsEvenIfAuthorisationRejected() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.REJECTED, null);
        providerWillRespondToAuthoriseWith(authResponse);

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(mockChargeReloadedInPostOperation.getCardDetails(), is(notNullValue()));
    }

    @Test
    public void shouldStoreCardDetailsEvenIfInAuthorisationError() {
        providerWillReject();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(mockChargeReloadedInPostOperation.getCardDetails(), is(notNullValue()));
    }

    @Test
    public void shouldStoreProviderSessionIdIfAuthorisationSuccess() {
        AuthCardDetails authCardDetails = AuthUtils.aValidAuthorisationDetails();
        providerWillAuthorise();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), authCardDetails);

        assertThat(mockChargeReloadedInPostOperation.getProviderSessionId(), is(SESSION_IDENTIFIER));
    }

    @Test
    public void shouldStoreProviderSessionIdIfAuthorisationRejected() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.REJECTED, null);
        providerWillRespondToAuthoriseWith(authResponse);

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(mockChargeReloadedInPostOperation.getCardDetails(), is(notNullValue()));
        assertThat(mockChargeReloadedInPostOperation.getProviderSessionId(), is(SESSION_IDENTIFIER));
    }

    @Test
    public void shouldNotProviderSessionIdEvenIfInAuthorisationError() {
        providerWillReject();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(mockChargeReloadedInPostOperation.getCardDetails(), is(notNullValue()));
        assertNull(mockChargeReloadedInPostOperation.getProviderSessionId());
    }

    @Test
    public void authoriseShouldThrowAnOperationAlreadyInProgressRuntimeExceptionWhenTimeout() throws Exception {
        when(mockExecutorService.execute(any())).thenReturn(Pair.of(IN_PROGRESS, null));

        try {
            cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());
            fail("Exception not thrown.");
        } catch (OperationAlreadyInProgressRuntimeException e) {
            Map<String, String> expectedMessage = ImmutableMap.of("message", format("Authorisation for charge already in progress, %s", charge.getExternalId()));
            assertThat(e.getResponse().getEntity(), is(expectedMessage));
        }
    }

    @Test(expected = ChargeNotFoundRuntimeException.class)
    public void shouldThrowAChargeNotFoundRuntimeExceptionWhenChargeDoesNotExist() {
        String chargeId = "jgk3erq5sv2i4cds6qqa9f1a8a";

        when(mockedChargeDao.findByExternalId(chargeId))
                .thenReturn(Optional.empty());

        cardAuthorisationService.doAuthorise(chargeId, AuthUtils.aValidAuthorisationDetails());
    }

    @Test(expected = OperationAlreadyInProgressRuntimeException.class)
    public void shouldThrowAnOperationAlreadyInProgressRuntimeExceptionWhenStatusIsAuthorisationReady() {
        ChargeEntity charge = createNewChargeWith(1L, ChargeStatus.AUTHORISATION_READY);
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        when(mockedChargeDao.merge(any())).thenReturn(charge);
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        verifyNoMoreInteractions(mockedChargeDao, mockedProviders);
    }

    @Test(expected = IllegalStateRuntimeException.class)
    public void shouldThrowAnIllegalStateRuntimeExceptionWhenInvalidStatus() throws Exception {
        ChargeEntity charge = createNewChargeWith(1L, ChargeStatus.CREATED);
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        when(mockedChargeDao.merge(any())).thenReturn(charge);
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        verifyNoMoreInteractions(mockedChargeDao, mockedProviders);
    }

    @Test(expected = ConflictRuntimeException.class)
    public void shouldThrowAConflictRuntimeException() throws Exception {
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        when(mockedChargeDao.merge(any())).thenThrow(new OptimisticLockException());
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        verifyNoMoreInteractions(mockedChargeDao, mockedProviders);
    }

    @Test
    public void shouldReportAuthorisationTimeout_whenProviderTimeout() {
        GatewayError gatewayError = gatewayConnectionTimeoutException("Connection timed out");
        providerWillRespondWithError(gatewayError);

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isFailed(), is(true));
        assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_TIMEOUT.toString()));
        assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(nullValue()));
    }

    @Test
    public void shouldReportUnexpectedError_whenProviderError() {
        GatewayError gatewayError = malformedResponseReceivedFromGateway("Malformed response received");
        providerWillRespondWithError(gatewayError);

        GatewayResponse response = cardAuthorisationService.doAuthorise(charge.getExternalId(), AuthUtils.aValidAuthorisationDetails());

        assertThat(response.isFailed(), is(true));
        assertThat(mockChargeReloadedInPostOperation.getStatus(), is(AUTHORISATION_UNEXPECTED_ERROR.toString()));
        assertThat(mockChargeReloadedInPostOperation.getGatewayTransactionId(), is(nullValue()));
    }

    private void providerWillRespondToAuthoriseWith(GatewayResponse value) {
        when(mockedPaymentProvider.authorise(any())).thenReturn(value);

        when(mockedProviders.byName(charge.getPaymentGatewayName())).thenReturn(mockedPaymentProvider);
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.empty());
    }

    private void providerWillAuthorise() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.AUTHORISED, null);
        providerWillRespondToAuthoriseWith(authResponse);
    }

    private void providerWillRequire3ds() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        WorldpayOrderStatusResponse worldpayResponse = new WorldpayOrderStatusResponse();
        worldpayResponse.set3dsPaRequest(PA_REQ_VALUE_FROM_PROVIDER);
        worldpayResponse.set3dsIssuerUrl(ISSUER_URL_FROM_PROVIDER);
        GatewayResponseBuilder<WorldpayOrderStatusResponse> gatewayResponseBuilder = responseBuilder();
        GatewayResponse worldpay3dsResponse = gatewayResponseBuilder
                .withResponse(worldpayResponse)
                .build();
        when(mockedPaymentProvider.authorise(any())).thenReturn(worldpay3dsResponse);

        when(mockedProviders.byName(charge.getPaymentGatewayName())).thenReturn(mockedPaymentProvider);
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.of(TRANSACTION_ID));
    }

    private void providerWillReject() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(null, AuthoriseStatus.REJECTED, "error-code");
        providerWillRespondToAuthoriseWith(authResponse);
    }

    private void providerWillRespondWithError(GatewayError gatewayError) {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        setupPaymentProviderMock(gatewayError);

        when(mockedProviders.byName(charge.getPaymentGatewayName())).thenReturn(mockedPaymentProvider);
        //TODO ?
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.empty());
    }
}
