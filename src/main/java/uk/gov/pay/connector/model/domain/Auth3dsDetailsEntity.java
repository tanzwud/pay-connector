package uk.gov.pay.connector.model.domain;

import javax.persistence.Column;
import javax.persistence.Embeddable;

@Embeddable
public class Auth3dsDetailsEntity {

    @Column(name = "pa_request_3ds")
    private String paRequest;

    @Column(name = "issuer_url_3ds")
    private String issuerUrl;

    public String getPaRequest() {
        return paRequest;
    }

    public void setPaRequest(String paRequest) {
        this.paRequest = paRequest;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public void setIssuerUrl(String issuerUrl) {
        this.issuerUrl = issuerUrl;
    }
}
