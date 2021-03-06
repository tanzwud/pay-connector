package uk.gov.pay.connector.matcher;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Map;

public class RefundsMatcher extends TypeSafeMatcher<Map<String, Object>> {
    private final String externalId;
    private final Matcher reference;
    private final long chargeId;
    private final long amount;
    private final String status;

    public static RefundsMatcher aRefundMatching(String externalId, Matcher reference, long chargeId, long amount, String status) {
        return new RefundsMatcher(externalId, reference, chargeId, amount, status);
    }

    private <T> RefundsMatcher(String externalId, Matcher<T> reference, long chargeId, long amount, String status) {
        this.externalId = externalId;
        this.reference = reference;
        this.chargeId = chargeId;
        this.amount = amount;
        this.status = status;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("{amount=").appendValue(amount).appendText(", ");
        description.appendText("charge_id=").appendValue(chargeId).appendText(", ");
        description.appendText("reference=").appendValue(reference).appendText(", ");
        description.appendText("external_id=").appendValue(externalId).appendText(", ");
        description.appendText("status=").appendValue(status).appendText("}");
    }

    @Override
    protected boolean matchesSafely(Map<String, Object> record) {
        String external_id = record.get("external_id").toString().trim();
        return external_id.equals(externalId) &&
                reference.matches(record.get("reference")) &&
                record.get("amount").equals(amount) &&
                record.get("status").equals(status) &&
                record.get("charge_id").equals(chargeId);
    }
}
