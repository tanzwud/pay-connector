package uk.gov.pay.connector.util.templates;

import org.apache.http.client.utils.URLEncodedUtils;
import uk.gov.pay.connector.service.OrderRequestBuilder.TemplateData;

import java.nio.charset.Charset;

public class FormUrlEncodedStringBuilder implements PayloadBuilder {

    private final PayloadDefinition payloadDefinition;
    private final Charset charset;

    public FormUrlEncodedStringBuilder(PayloadDefinition payloadDefinition, Charset charset) {
        this.payloadDefinition = payloadDefinition;
        this.charset = charset;
    }

    public String buildWith(TemplateData templateData) {
        return URLEncodedUtils.format(payloadDefinition.extract(templateData), charset);
    }

}
