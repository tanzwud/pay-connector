package uk.gov.pay.connector.util;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.util.StringMapper;
import uk.gov.pay.connector.dao.TokenDao;
import uk.gov.pay.connector.model.domain.ChargeStatus;

public class DatabaseTestHelper {
    private DBI jdbi;
    private TokenDao tokenDao;

    public DatabaseTestHelper(DBI jdbi) {
        this.jdbi = jdbi;
        this.tokenDao = new TokenDao(jdbi);
    }

    public void addGatewayAccount(String accountId, String paymentProvider) {
        jdbi.withHandle(h ->
                        h.update("INSERT INTO gateway_accounts(gateway_account_id, payment_provider) VALUES(?, ?)",
                                Long.valueOf(accountId), paymentProvider)
        );
    }

    public void addCharge(String chargeId, String gatewayAccountId, long amount, ChargeStatus status, String returnUrl, String gatewayTransactionId) {
        jdbi.withHandle(h ->
                        h.update("INSERT INTO charges(charge_id, amount, status, gateway_account_id, return_url, gateway_transaction_id) VALUES(?, ?, ?, ?, ?, ?)",
                                Long.valueOf(chargeId), amount, status.getValue(), Long.valueOf(gatewayAccountId), returnUrl, gatewayTransactionId)
        );
    }

    public String getChargeTokenId(String chargeId) {
        return jdbi.withHandle(h ->
                        h.createQuery("SELECT secure_redirect_token from tokens WHERE charge_id = :charge_id")
                        .bind("charge_id", Long.valueOf(chargeId))
                        .map(StringMapper.FIRST)
                        .first()
        );
    }

    public String getChargeGatewayTransactionId(String chargeId) {
        return jdbi.withHandle(h ->
                        h.createQuery("SELECT gateway_transaction_id from charges WHERE charge_id = :charge_id")
                                .bind("charge_id", Long.valueOf(chargeId))
                                .map(StringMapper.FIRST)
                                .first()
        );
    }

    public void addToken(String chargeId, String tokenId) {
        tokenDao.insertNewToken(chargeId, tokenId);
    }
}
