package uk.gov.pay.connector.dao;

import org.junit.Test;
import uk.gov.pay.connector.model.api.ExternalChargeState;

import java.time.ZonedDateTime;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static uk.gov.pay.connector.model.TransactionType.PAYMENT;
import static uk.gov.pay.connector.model.api.ExternalChargeState.EXTERNAL_CREATED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_ABORTED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_CANCELLED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_REJECTED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_APPROVED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_APPROVED_RETRY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_READY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_SUBMITTED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.EXPIRED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.EXPIRE_CANCEL_FAILED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.EXPIRE_CANCEL_READY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.EXPIRE_CANCEL_SUBMITTED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.USER_CANCELLED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.USER_CANCEL_ERROR;
import static uk.gov.pay.connector.model.domain.ChargeStatus.USER_CANCEL_READY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.USER_CANCEL_SUBMITTED;

public class ChargeSearchParamsTest {

    @Test
    public void buildQueryParams_chargeSearch_withAllParameters() throws Exception {

        String expectedQueryString =
                "reference=ref" +
                        "&email=user" +
                        "&from_date=2012-06-30T12:30:40Z[UTC]" +
                        "&to_date=2012-07-30T12:30:40Z[UTC]" +
                        "&page=2" +
                        "&display_size=5" +
                        "&state=created" +
                        "&card_brand=visa";

        ChargeSearchParams params = new ChargeSearchParams()
                .withDisplaySize(5L)
                .withExternalState(EXTERNAL_CREATED.getStatus())
                .withCardBrand("visa")
                .withGatewayAccountId(111L)
                .withPage(2L)
                .withReferenceLike("ref")
                .withEmailLike("user")
                .withFromDate(ZonedDateTime.parse("2012-06-30T12:30:40Z[UTC]"))
                .withToDate(ZonedDateTime.parse("2012-07-30T12:30:40Z[UTC]"));

        assertThat(params.buildQueryParams(), is(expectedQueryString));
        assertThat(params.getGatewayAccountId(), is(111L));
        assertThat(params.getDisplaySize(), is(5L));
        assertThat(params.getPage(), is(2L));
    }

    @Test
    public void getInternalStates_chargeSearch_shouldPopulateAllInternalChargeStates_FromExternalFailedState() {

        ChargeSearchParams params = new ChargeSearchParams()
                .withExternalState(ExternalChargeState.EXTERNAL_FAILED_CANCELLED.getStatus());

        assertThat(params.buildQueryParams(), is("state=failed"));
        assertThat(params.getInternalStates(),  hasSize(11));
        assertThat(params.getInternalStates(), containsInAnyOrder(
                USER_CANCEL_READY, USER_CANCEL_SUBMITTED, USER_CANCEL_ERROR, USER_CANCELLED, EXPIRE_CANCEL_READY, EXPIRE_CANCEL_SUBMITTED, EXPIRED, EXPIRE_CANCEL_FAILED,
                AUTHORISATION_REJECTED, AUTHORISATION_CANCELLED, AUTHORISATION_ABORTED));
    }

    @Test
    public void getInternalStates_shouldSetInternalStatesDirectlyToSearchParams() {

        // So internal methods can call findAll with specific states of a charge
        ChargeSearchParams params = new ChargeSearchParams()
                .withInternalStates(asList(CAPTURED, USER_CANCELLED));

        assertThat(params.getInternalStates(), hasSize(2));
        assertThat(params.getInternalStates(), containsInAnyOrder(CAPTURED, USER_CANCELLED));
    }

    @Test
    public void buildQueryParams_transactionsSearch_withAllParameters() throws Exception {

        String expectedQueryString =
                "transaction_type=payment" +
                        "&reference=ref" +
                        "&email=user@example.com" +
                        "&from_date=2012-06-30T12:30:40Z[UTC]" +
                        "&to_date=2012-07-30T12:30:40Z[UTC]" +
                        "&page=2" +
                        "&display_size=5" +
                        "&payment_states=created" +
                        "&refund_states=submitted" +
                        "&card_brand=visa" +
                        "&card_brand=master-card";

        ChargeSearchParams params = new ChargeSearchParams()
                .withDisplaySize(5L)
                .withTransactionType(PAYMENT)
                .addExternalChargeStates(singletonList("created"))
                .addExternalRefundStates(singletonList("submitted"))
                .withCardBrands(asList("visa", "master-card"))
                .withGatewayAccountId(111L)
                .withPage(2L)
                .withReferenceLike("ref")
                .withEmailLike("user@example.com")
                .withFromDate(ZonedDateTime.parse("2012-06-30T12:30:40Z[UTC]"))
                .withToDate(ZonedDateTime.parse("2012-07-30T12:30:40Z[UTC]"));

        assertThat(params.buildQueryParams(), is(expectedQueryString));
    }

    @Test
    public void buildQueryParams_transactionsSearch_withNonTransactionType_andStateForChargeAndRefund() throws Exception {

        String expectedQueryString =
                "reference=ref" +
                        "&email=user@example.com" +
                        "&page=2" +
                        "&payment_states=success" +
                        "&refund_states=success";

        ChargeSearchParams params = new ChargeSearchParams()
                .addExternalChargeStates(singletonList("success"))
                .addExternalRefundStates(singletonList("success"))
                .withGatewayAccountId(111L)
                .withPage(2L)
                .withReferenceLike("ref")
                .withEmailLike("user@example.com");

        assertThat(params.buildQueryParams(), is(expectedQueryString));
    }

    @Test
    public void getInternalChargeStatuses_transactionsSearch_MultipleChargeStates() {

        String expectedQueryString = "payment_states=success,failed";

        ChargeSearchParams params = new ChargeSearchParams()
                .addExternalChargeStates(singletonList("success"))
                .addExternalChargeStates(singletonList("failed"))
                .withGatewayAccountId(111L);

        assertThat(params.buildQueryParams(), is(expectedQueryString));
        assertThat(params.getInternalChargeStatuses(),  hasSize(16));
        assertThat(params.getInternalChargeStatuses(), containsInAnyOrder(
                USER_CANCEL_READY, USER_CANCEL_SUBMITTED, USER_CANCEL_ERROR, USER_CANCELLED, EXPIRE_CANCEL_READY, EXPIRE_CANCEL_SUBMITTED, EXPIRED, EXPIRE_CANCEL_FAILED,
                AUTHORISATION_REJECTED, AUTHORISATION_CANCELLED, AUTHORISATION_ABORTED, CAPTURE_APPROVED, CAPTURE_APPROVED_RETRY, CAPTURE_READY, CAPTURED, CAPTURE_SUBMITTED));

    }

    @Test
    public void getInternalChargeStatuses_transactionsSearch_shouldPopulateAllInternalChargeStates_FromExternalFailedState() {

        ChargeSearchParams params = new ChargeSearchParams()
                .addExternalChargeStates(singletonList(ExternalChargeState.EXTERNAL_FAILED_CANCELLED.getStatus()));

        assertThat(params.buildQueryParams(), is("payment_states=failed"));
        assertThat(params.getInternalChargeStatuses(),  hasSize(11));
        assertThat(params.getInternalChargeStatuses(), containsInAnyOrder(
                USER_CANCEL_READY, USER_CANCEL_SUBMITTED, USER_CANCEL_ERROR, USER_CANCELLED, EXPIRE_CANCEL_READY, EXPIRE_CANCEL_SUBMITTED, EXPIRED, EXPIRE_CANCEL_FAILED,
                AUTHORISATION_REJECTED, AUTHORISATION_CANCELLED, AUTHORISATION_ABORTED));
    }
}
