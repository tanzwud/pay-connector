package uk.gov.pay.connector.it.contract;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.hamcrest.CoreMatchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import uk.gov.pay.connector.model.CancelGatewayRequest;
import uk.gov.pay.connector.model.CaptureGatewayRequest;
import uk.gov.pay.connector.model.RefundGatewayRequest;
import uk.gov.pay.connector.model.domain.*;
import uk.gov.pay.connector.model.gateway.AuthorisationGatewayRequest;
import uk.gov.pay.connector.model.gateway.GatewayResponse;
import uk.gov.pay.connector.service.GatewayClient;
import uk.gov.pay.connector.service.PaymentProvider;
import uk.gov.pay.connector.service.smartpay.SmartpayAuthorisationResponse;
import uk.gov.pay.connector.service.smartpay.SmartpayPaymentProvider;
import uk.gov.pay.connector.service.worldpay.WorldpayCaptureResponse;
import uk.gov.pay.connector.util.TestClientFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Consumer;

import static com.google.common.io.Resources.getResource;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.model.domain.GatewayAccountEntity.Type.TEST;
import static uk.gov.pay.connector.service.GatewayClient.createGatewayClient;
import static uk.gov.pay.connector.util.AuthUtils.buildAuthCardDetails;
import static uk.gov.pay.connector.util.SystemUtils.envOrThrow;

@RunWith(MockitoJUnitRunner.class)
public class SmartpayPaymentProviderTest {

    private String url = "https://pal-test.barclaycardsmartpay.com/pal/servlet/soap/Payment";
    private String username = envOrThrow("GDS_CONNECTOR_SMARTPAY_USER");
    private String password = envOrThrow("GDS_CONNECTOR_SMARTPAY_PASSWORD");
    private ChargeEntity chargeEntity;
    private MetricRegistry mockMetricRegistry;
    private Histogram mockHistogram;
    private Counter mockCounter;

    @Before
    public void setUpAndCheckThatSmartpayIsUp() {
        try {
            new URL(url).openConnection().connect();
            Map<String, String> validSmartPayCredentials = ImmutableMap.of(
                    "merchant_id", "DCOTest",
                    "username", username,
                    "password", password);
            GatewayAccountEntity validGatewayAccount = new GatewayAccountEntity();
            validGatewayAccount.setId(123L);
            validGatewayAccount.setGatewayName("smartpay");
            validGatewayAccount.setCredentials(validSmartPayCredentials);
            validGatewayAccount.setType(TEST);

            chargeEntity = aValidChargeEntity()
                    .withGatewayAccountEntity(validGatewayAccount).build();

            mockMetricRegistry = mock(MetricRegistry.class);
            mockHistogram = mock(Histogram.class);
            mockCounter = mock(Counter.class);
            when(mockMetricRegistry.histogram(anyString())).thenReturn(mockHistogram);
            when(mockMetricRegistry.counter(anyString())).thenReturn(mockCounter);
        } catch (IOException ex) {
            Assume.assumeTrue(false);
        }
    }

    @Test
    public void shouldSendSuccessfullyAnOrderForMerchant() throws Exception {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        testCardAuthorisation(paymentProvider, chargeEntity);
    }

    @Test
    public void shouldFailRequestAuthorisationIfCredentialsAreNotCorrect() throws Exception {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        GatewayAccountEntity accountWithInvalidCredentials = new GatewayAccountEntity();
        accountWithInvalidCredentials.setId(11L);
        accountWithInvalidCredentials.setGatewayName("smartpay");
        accountWithInvalidCredentials.setCredentials(ImmutableMap.of(
                "merchant_id", "MerchantAccount",
                "username", "wrong-username",
                "password", "wrong-password"
        ));
        accountWithInvalidCredentials.setType(TEST);

        chargeEntity.setGatewayAccount(accountWithInvalidCredentials);
        AuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);

        assertFalse(response.isSuccessful());
        assertNotNull(response.getGatewayError());
    }

    @Test
    public void shouldSuccessfullySendACaptureRequest() throws Exception {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        GatewayResponse<SmartpayAuthorisationResponse> response = testCardAuthorisation(paymentProvider, chargeEntity);

        assertThat(response.getBaseResponse().isPresent(), CoreMatchers.is(true));
        String transactionId = response.getBaseResponse().get().getPspReference();
        assertThat(transactionId, CoreMatchers.is(not(nullValue())));

        chargeEntity.setGatewayTransactionId(transactionId);

        GatewayResponse<WorldpayCaptureResponse> captureGatewayResponse = paymentProvider.capture(CaptureGatewayRequest.valueOf(chargeEntity));
        assertTrue(captureGatewayResponse.isSuccessful());
    }

    @Test
    public void shouldSuccessfullySendACancelRequest() throws Exception {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        GatewayResponse<SmartpayAuthorisationResponse> response = testCardAuthorisation(paymentProvider, chargeEntity);

        assertThat(response.getBaseResponse().isPresent(), CoreMatchers.is(true));
        String transactionId = response.getBaseResponse().get().getPspReference();
        assertThat(transactionId, CoreMatchers.is(not(nullValue())));

        chargeEntity.setGatewayTransactionId(transactionId);

        GatewayResponse cancelResponse = paymentProvider.cancel(CancelGatewayRequest.valueOf(chargeEntity));
        assertThat(cancelResponse.isSuccessful(), is(true));

    }

    @Test
    public void shouldRefundToAnExistingPaymentSuccessfully() throws Exception {
        AuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        PaymentProvider smartpay = getSmartpayPaymentProvider();
        GatewayResponse<SmartpayAuthorisationResponse> authoriseResponse = smartpay.authorise(request);
        assertTrue(authoriseResponse.isSuccessful());

        chargeEntity.setGatewayTransactionId(authoriseResponse.getBaseResponse().get().getPspReference());

        GatewayResponse<WorldpayCaptureResponse> captureGatewayResponse = smartpay.capture(CaptureGatewayRequest.valueOf(chargeEntity));
        assertTrue(captureGatewayResponse.isSuccessful());

        RefundEntity refundEntity = new RefundEntity(chargeEntity, 1L);
        RefundGatewayRequest refundRequest = RefundGatewayRequest.valueOf(refundEntity);
        GatewayResponse refundResponse = smartpay.refund(refundRequest);

        assertThat(refundResponse.isSuccessful(), is(true));

    }

    private GatewayResponse testCardAuthorisation(PaymentProvider paymentProvider, ChargeEntity chargeEntity) {
        AuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());

        return response;
    }

    private PaymentProvider getSmartpayPaymentProvider() throws Exception {
        Client client = TestClientFactory.createJerseyClient();
        GatewayClient gatewayClient = createGatewayClient(client, ImmutableMap.of(TEST.toString(), url), MediaType.APPLICATION_XML_TYPE, mockMetricRegistry);
        return new SmartpayPaymentProvider(gatewayClient, new ObjectMapper());
    }

    private String notificationPayloadForTransaction(String transactionId) throws IOException {
        URL resource = getResource("templates/smartpay/notification-capture.json");
        return Resources.toString(resource, Charset.defaultCharset()).replace("{{transactionId}}", transactionId);
    }

    private String notificationPayloadForTransactionWithUnknownStatus(String transactionId) throws IOException {
        URL resource = getResource("templates/smartpay/notification-capture.-with-unknown-status.json");
        return Resources.toString(resource, Charset.defaultCharset()).replace("{{transactionId}}", transactionId);
    }

    private String multipleNotificationPayloadForTransactions(String transactionId, String transactionId2) throws IOException {
        URL resource = getResource("templates/smartpay/multiple-notifications-different-dates.json");
        return Resources.toString(resource, Charset.defaultCharset())
                .replace("{{transactionId}}", transactionId)
                .replace("{{transactionId2}}", transactionId2);
    }

    public static AuthorisationGatewayRequest getCardAuthorisationRequest(ChargeEntity chargeEntity) {
        Address address = Address.anAddress();
        address.setLine1("41");
        address.setLine2("Scala Street");
        address.setCity("London");
        address.setCounty("London");
        address.setPostcode("EC2A 1AE");
        address.setCountry("GB");

        AuthCardDetails authCardDetails = aValidSmartpayCard();
        authCardDetails.setAddress(address);

        return new AuthorisationGatewayRequest(chargeEntity, authCardDetails);
    }

    public static AuthCardDetails aValidSmartpayCard() {
        String validSandboxCard = "5555444433331111";
        return buildAuthCardDetails(validSandboxCard, "737", "08/18", "visa");
    }

    @SuppressWarnings("unchecked")
    private <T> Consumer<T> mockAccountUpdater() {
        return mock(Consumer.class);
    }
}
