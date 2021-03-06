package uk.gov.pay.connector.service.worldpay;

import org.joda.time.DateTime;
import org.junit.Test;
import uk.gov.pay.connector.model.OrderRequestType;
import uk.gov.pay.connector.model.domain.Address;
import uk.gov.pay.connector.model.domain.AuthCardDetails;
import uk.gov.pay.connector.service.GatewayOrder;
import uk.gov.pay.connector.util.AuthUtils;
import uk.gov.pay.connector.util.TestTemplateResourceLoader;

import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static uk.gov.pay.connector.model.domain.Address.anAddress;
import static uk.gov.pay.connector.service.worldpay.WorldpayOrderRequestBuilder.*;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.*;

public class WorldpayOrderRequestBuilderTest {

    @Test
    public void shouldGenerateValidAuthoriseOrderRequestForAddressWithMinimumFields() throws Exception {

        Address minAddress = anAddress();
        minAddress.setLine1("123 My Street");
        minAddress.setPostcode("SW8URR");
        minAddress.setCity("London");
        minAddress.setCountry("GB");

        AuthCardDetails authCardDetails = getValidTestCard(minAddress);

        GatewayOrder actualRequest = aWorldpayAuthoriseOrderRequestBuilder()
                .withSessionId("uniqueSessionId")
                .withAcceptHeader("text/html")
                .withUserAgentHeader("Mozilla/5.0")
                .withTransactionId("MyUniqueTransactionId!")
                .withMerchantCode("MERCHANTCODE")
                .withDescription("This is the description")
                .withAmount("500")
                .withAuthorisationDetails(authCardDetails)
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_AUTHORISE_WORLDPAY_REQUEST_MIN_ADDRESS), actualRequest.getPayload());
        assertEquals(OrderRequestType.AUTHORISE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidAuthoriseOrderRequestForAddressWithMinimumFieldsWhen3dsEnabled() throws Exception {

        Address minAddress = anAddress();
        minAddress.setLine1("123 My Street");
        minAddress.setPostcode("SW8URR");
        minAddress.setCity("London");
        minAddress.setCountry("GB");

        AuthCardDetails authCardDetails = getValidTestCard(minAddress);

        GatewayOrder actualRequest = aWorldpayAuthoriseOrderRequestBuilder()
                .withSessionId("uniqueSessionId")
                .with3dsRequired(true)
                .withAcceptHeader("text/html")
                .withUserAgentHeader("Mozilla/5.0")
                .withTransactionId("MyUniqueTransactionId!")
                .withMerchantCode("MERCHANTCODE")
                .withDescription("This is the description")
                .withAmount("500")
                .withAuthorisationDetails(authCardDetails)
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_AUTHORISE_WORLDPAY_3DS_REQUEST_MIN_ADDRESS), actualRequest.getPayload());
        assertEquals(OrderRequestType.AUTHORISE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidAuthoriseOrderRequestForAddressWithAllFields() throws Exception {

        Address fullAddress = anAddress();
        fullAddress.setLine1("123 My Street");
        fullAddress.setLine2("This road");
        fullAddress.setPostcode("SW8URR");
        fullAddress.setCity("London");
        fullAddress.setCounty("London county");
        fullAddress.setCountry("GB");

        AuthCardDetails authCardDetails = getValidTestCard(fullAddress);

        GatewayOrder actualRequest = aWorldpayAuthoriseOrderRequestBuilder()
                .withSessionId("uniqueSessionId")
                .withAcceptHeader("text/html")
                .withUserAgentHeader("Mozilla/5.0")
                .withTransactionId("MyUniqueTransactionId!")
                .withMerchantCode("MERCHANTCODE")
                .withDescription("This is the description")
                .withAmount("500")
                .withAuthorisationDetails(authCardDetails)
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_AUTHORISE_WORLDPAY_REQUEST_FULL_ADDRESS), actualRequest.getPayload());
        assertEquals(OrderRequestType.AUTHORISE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidAuthoriseOrderRequestWhenSpecialCharactersInUserInput() throws Exception {

        Address address = anAddress();
        address.setLine1("123 & My Street");
        address.setLine2("This road -->");
        address.setPostcode("SW8 > URR");
        address.setCity("London !>");
        address.setCountry("GB");

        AuthCardDetails authCardDetails = getValidTestCard(address);

        GatewayOrder actualRequest = aWorldpayAuthoriseOrderRequestBuilder()
                .withSessionId("uniqueSessionId")
                .withAcceptHeader("text/html")
                .withUserAgentHeader("Mozilla/5.0")
                .withTransactionId("MyUniqueTransactionId!")
                .withMerchantCode("MERCHANTCODE")
                .withDescription("This is the description with <!-- ")
                .withAmount("500")
                .withAuthorisationDetails(authCardDetails)
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_SPECIAL_CHAR_VALID_AUTHORISE_WORLDPAY_REQUEST_ADDRESS), actualRequest.getPayload());
        assertEquals(OrderRequestType.AUTHORISE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidAuth3dsResponseOrderRequest() throws Exception {

        String providerSessionId = "provider-session-id";
        GatewayOrder actualRequest = aWorldpay3dsResponseAuthOrderRequestBuilder()
                .withPaResponse3ds("I am an opaque 3D Secure PA response from the card issuer")
                .withSessionId("uniqueSessionId")
                .withTransactionId("MyUniqueTransactionId!")
                .withMerchantCode("MERCHANTCODE")
                .withProviderSessionId(providerSessionId)
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_3DS_RESPONSE_AUTH_WORLDPAY_REQUEST), actualRequest.getPayload());
        assertEquals(OrderRequestType.AUTHORISE_3DS, actualRequest.getOrderRequestType());
        assertThat(actualRequest.getProviderSessionId().get(), is(providerSessionId));
    }

    @Test
    public void shouldGenerateValidCaptureOrderRequest() throws Exception {

        DateTime date = new DateTime(2013, 2, 23, 0, 0);

        GatewayOrder actualRequest = aWorldpayCaptureOrderRequestBuilder()
                .withDate(date)
                .withMerchantCode("MERCHANTCODE")
                .withAmount("500")
                .withTransactionId("MyUniqueTransactionId!")
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_CAPTURE_WORLDPAY_REQUEST), actualRequest.getPayload());
        assertEquals(OrderRequestType.CAPTURE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidCaptureOrderRequestWithSpecialCharactersInStrings() throws Exception {

        DateTime date = new DateTime(2013, 2, 23, 0, 0);

        GatewayOrder actualRequest = aWorldpayCaptureOrderRequestBuilder()
                .withDate(date)
                .withMerchantCode("MERCHANTCODE")
                .withAmount("500")
                .withTransactionId("MyUniqueTransactionId <!-- & > ")
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_SPECIAL_CHAR_VALID_CAPTURE_WORLDPAY_REQUEST), actualRequest.getPayload());
        assertEquals(OrderRequestType.CAPTURE, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidCancelOrderRequest() throws Exception {

        GatewayOrder actualRequest = aWorldpayCancelOrderRequestBuilder()
                .withMerchantCode("MERCHANTCODE")
                .withTransactionId("MyUniqueTransactionId!")
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_CANCEL_WORLDPAY_REQUEST), actualRequest.getPayload());
        assertEquals(OrderRequestType.CANCEL, actualRequest.getOrderRequestType());
    }

    @Test
    public void shouldGenerateValidRefundOrderRequest() throws Exception {

        GatewayOrder actualRequest = aWorldpayRefundOrderRequestBuilder()
                .withReference("reference")
                .withAmount("200")
                .withMerchantCode("MERCHANTCODE")
                .withTransactionId("MyUniqueTransactionId!")
                .build();

        assertXMLEqual(TestTemplateResourceLoader.load(WORLDPAY_VALID_REFUND_WORLDPAY_REQUEST), actualRequest.getPayload());
        assertEquals(OrderRequestType.REFUND, actualRequest.getOrderRequestType());
    }

    private AuthCardDetails getValidTestCard(Address address) {
        return AuthUtils.buildAuthCardDetails("Mr. Payment", "4111111111111111", "123", "12/15", "visa", address);
    }
}
