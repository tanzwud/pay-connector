package uk.gov.pay.connector.service.smartpay;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.persistence.oxm.annotations.XmlPath;
import uk.gov.pay.connector.service.BaseAuthoriseResponse;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.StringJoiner;

@XmlRootElement(name = "Envelope", namespace = "http://schemas.xmlsoap.org/soap/envelope/")
public class SmartpayAuthorisationResponse extends SmartpayBaseResponse implements BaseAuthoriseResponse {

    private static final String AUTHORISED = "Authorised";

    @XmlPath("soap:Body/ns1:authoriseResponse/ns1:paymentResult/ns1:resultCode/text()")
    private String result;

    @XmlPath("soap:Body/ns1:authoriseResponse/ns1:paymentResult/ns1:pspReference/text()")
    private String pspReference;

    public String getPspReference() {
        return pspReference;
    }

    @Override
    public AuthoriseStatus authoriseStatus() {
        return AUTHORISED.equals(result) ? AuthoriseStatus.AUTHORISED : AuthoriseStatus.REJECTED;
    }

    @Override
    public String getTransactionId() {
        return pspReference;
    }

    @Override
    public String get3dsPaRequest() {
        return null;
    }

    @Override
    public String get3dsIssuerUrl() {
        return null;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "SmartPay authorisation response (", ")");
        if (StringUtils.isNotBlank(getTransactionId())) {
            joiner.add("pspReference: " + getTransactionId());
        }
        if (StringUtils.isNotBlank(result)) {
            joiner.add("resultCode: " + result);
        }
        if (StringUtils.isNotBlank(getErrorCode())) {
            joiner.add("faultcode: " + getErrorCode());
        }
        if (StringUtils.isNotBlank(getErrorMessage())) {
            joiner.add("faultstring: " + getErrorMessage());
        }
        return joiner.toString();
    }

}
