package uk.gov.pay.connector.util;

import org.junit.Test;
import uk.gov.pay.connector.service.BaseAuthoriseResponse.AuthoriseStatus;
import uk.gov.pay.connector.service.BaseCancelResponse;
import uk.gov.pay.connector.service.worldpay.*;

import java.time.LocalDate;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.*;

public class WorldpayXMLUnmarshallerTest {

    @Test
    public void shouldUnmarshallACancelSuccessResponse() throws Exception {
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_CANCEL_SUCCESS_RESPONSE);
        WorldpayCancelResponse response = XMLUnmarshaller.unmarshall(successPayload, WorldpayCancelResponse.class);
        assertThat(response.getTransactionId(), is("transaction-id"));
        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }


    @Test
    public void shouldUnmarshallACancelErrorResponse() throws Exception {
        String errorPayload = TestTemplateResourceLoader.load(WORLDPAY_CANCEL_ERROR_RESPONSE);

        WorldpayCancelResponse response = XMLUnmarshaller.unmarshall(errorPayload, WorldpayCancelResponse.class);
        assertThat(response.getTransactionId(), is(nullValue()));
        assertThat(response.getErrorCode(), is("2"));
        assertThat(response.getErrorMessage(), is("Something went wrong."));
    }

    @Test
    public void shouldUnmarshallANotification() throws Exception {
        String transactionId = "MyUniqueTransactionId!";
        String status = "CAPTURED";
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_NOTIFICATION)
                .replace("{{transactionId}}", transactionId)
                .replace("{{status}}", status)
                .replace("{{refund-ref}}", "REFUND-REF")
                .replace("{{bookingDateDay}}", "10")
                .replace("{{bookingDateMonth}}", "01")
                .replace("{{bookingDateYear}}", "2017");
        WorldpayNotification response = XMLUnmarshaller.unmarshall(successPayload, WorldpayNotification.class);
        assertThat(response.getStatus(), is(status));
        assertThat(response.getTransactionId(), is(transactionId));
        assertThat(response.getMerchantCode(), is("MERCHANTCODE"));
        assertThat(response.getReference(), is("REFUND-REF"));
        assertThat(response.getBookingDate(), is(LocalDate.parse("2017-01-10")));
    }

    @Test
    public void shouldUnmarshallACaptureSuccessResponse() throws Exception {
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_CAPTURE_SUCCESS_RESPONSE);
        WorldpayCaptureResponse response = XMLUnmarshaller.unmarshall(successPayload, WorldpayCaptureResponse.class);
        assertThat(response.getTransactionId(), is("transaction-id"));
        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }

    @Test
    public void shouldUnmarshallACaptureErrorResponse() throws Exception {
        String errorPayload = TestTemplateResourceLoader.load(WORLDPAY_CAPTURE_ERROR_RESPONSE);
        WorldpayCaptureResponse response = XMLUnmarshaller.unmarshall(errorPayload, WorldpayCaptureResponse.class);
        assertThat(response.getTransactionId(), is(nullValue()));
        assertThat(response.getErrorCode(), is("2"));
        assertThat(response.getErrorMessage(), is("Something went wrong."));
    }

    @Test
    public void shouldUnmarshallAAuthorisationSuccessResponse() throws Exception {
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE);
        WorldpayOrderStatusResponse response = XMLUnmarshaller.unmarshall(successPayload, WorldpayOrderStatusResponse.class);
        assertThat(response.getLastEvent(), is("AUTHORISED"));
        assertNull(response.getRefusedReturnCode());
        assertNull(response.getRefusedReturnCodeDescription());

        assertThat(response.authoriseStatus(), is(AuthoriseStatus.AUTHORISED));
        assertThat(response.getTransactionId(), is("transaction-id"));

        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }

    @Test
    public void shouldUnmarshall3dsResponse() throws Exception {
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_3DS_RESPONSE);
        WorldpayOrderStatusResponse response = XMLUnmarshaller.unmarshall(successPayload, WorldpayOrderStatusResponse.class);

        assertNull(response.getLastEvent());
        assertNull(response.getRefusedReturnCode());
        assertNull(response.getRefusedReturnCodeDescription());
        assertNull(response.getErrorCode());
        assertNull(response.getErrorMessage());

        assertThat(response.get3dsPaRequest(), is("eJxVUsFuwjAM/ZWK80aSUgpFJogNpHEo2hjTzl"));
        assertThat(response.get3dsIssuerUrl(), is("https://secure-test.worldpay.com/jsp/test/shopper/ThreeDResponseSimulator.jsp"));

        assertThat(response.authoriseStatus(), is(AuthoriseStatus.REQUIRES_3DS));
    }

    @Test
    public void shouldUnmarshallAAuthorisationFailedResponse() throws Exception {
        String failedPayload = TestTemplateResourceLoader.load(WORLDPAY_AUTHORISATION_FAILED_RESPONSE);
        WorldpayOrderStatusResponse response = XMLUnmarshaller.unmarshall(failedPayload, WorldpayOrderStatusResponse.class);
        assertThat(response.getLastEvent(), is("REFUSED"));
        assertThat(response.getRefusedReturnCode(), is("5"));
        assertThat(response.getRefusedReturnCodeDescription(), is("REFUSED"));

        assertThat(response.authoriseStatus(), is(AuthoriseStatus.REJECTED));
        assertThat(response.getTransactionId(), is("MyUniqueTransactionId!12"));

        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }

    @Test
    public void shouldUnmarshallCanceledAuthorisations() throws Exception {
        String failedPayload = TestTemplateResourceLoader.load(WORLDPAY_AUTHORISATION_CANCELLED_RESPONSE);
        WorldpayOrderStatusResponse response = XMLUnmarshaller.unmarshall(failedPayload, WorldpayOrderStatusResponse.class);
        assertThat(response.cancelStatus(), is(BaseCancelResponse.CancelStatus.CANCELLED));
        assertThat(response.getLastEvent(), is("CANCELLED"));
        assertThat(response.getRefusedReturnCode(), is("5"));
        assertThat(response.getRefusedReturnCodeDescription(), is("CANCELLED"));

        assertThat(response.authoriseStatus(), is(AuthoriseStatus.CANCELLED));
        assertThat(response.getTransactionId(), is("MyUniqueTransactionId!12"));

        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }

    @Test
    public void shouldUnmarshallAAuthorisationErrorResponse() throws Exception {
        String errorPayload = TestTemplateResourceLoader.load(WORLDPAY_AUTHORISATION_ERROR_RESPONSE);
        WorldpayOrderStatusResponse response = XMLUnmarshaller.unmarshall(errorPayload, WorldpayOrderStatusResponse.class);

        assertThat(response.authoriseStatus(), is(AuthoriseStatus.ERROR));
        assertThat(response.getTransactionId(), is(nullValue()));

        assertThat(response.getErrorCode(), is("2"));
        assertThat(response.getErrorMessage(), is("Something went wrong."));
    }

    @Test
    public void shouldUnmarshallARefundSuccessResponse() throws Exception {
        String successPayload = TestTemplateResourceLoader.load(WORLDPAY_REFUND_SUCCESS_RESPONSE);
        WorldpayRefundResponse response = XMLUnmarshaller.unmarshall(successPayload, WorldpayRefundResponse.class);

        assertThat(response.getReference(), is(notNullValue()));
        assertThat(response.getReference().isPresent(), is(false));
        assertThat(response.getErrorCode(), is(nullValue()));
        assertThat(response.getErrorMessage(), is(nullValue()));
    }

    @Test
    public void shouldUnmarshallARefundErrorResponse() throws Exception {
        String errorPayload = TestTemplateResourceLoader.load(WORLDPAY_REFUND_ERROR_RESPONSE);
        WorldpayRefundResponse response = XMLUnmarshaller.unmarshall(errorPayload, WorldpayRefundResponse.class);

        assertThat(response.getReference(), is(notNullValue()));
        assertThat(response.getReference().isPresent(), is(false));
        assertThat(response.getErrorCode(), is("2"));
        assertThat(response.getErrorMessage(), is("Something went wrong."));
    }
}
