package uk.gov.pay.connector.dao;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.DefaultMapper;
import org.skife.jdbi.v2.util.StringMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.model.api.ExternalChargeStatus;
import uk.gov.pay.connector.model.domain.ChargeEvent;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.util.ChargeEventListener;
import uk.gov.pay.connector.util.DateTimeUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.newHashMap;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CREATED;
import static uk.gov.pay.connector.util.ChargesSearch.createQueryHandle;

public class ChargeDao {
    private static final Logger logger = LoggerFactory.getLogger(ChargeDao.class);

    private DBI jdbi;
    private ChargeEventListener eventListener;

    public ChargeDao(DBI jdbi, ChargeEventListener eventListener) {
        this.jdbi = jdbi;
        this.eventListener = eventListener;
    }

    public String saveNewCharge(String gatewayAccountId, Map<String, Object> charge) {
        LocalDateTime createdDate = LocalDateTime.now();
        String newChargeId = jdbi.withHandle(handle ->
                handle
                        .createStatement("INSERT INTO charges(amount, gateway_account_id, status, return_url, description, reference, created_date) " +
                                "VALUES (:amount, :gateway_account_id, :status, :return_url, :description, :reference, :created_date)")
                        .bindFromMap(charge)
                        .bind("gateway_account_id", Long.valueOf(gatewayAccountId))
                        .bind("status", CREATED.getValue())
                        .bind("created_date", Timestamp.valueOf(createdDate))
                        .executeAndReturnGeneratedKeys(StringMapper.FIRST)
                        .first()
        );
        eventListener.notify(ChargeEvent.from(Long.parseLong(newChargeId), CREATED, createdDate));
        return newChargeId;
    }

    public Optional<Map<String, Object>> findChargeForAccount(String chargeId, String accountId) {
        Map<String, Object> data = jdbi.withHandle(handle ->
                handle
                        .createQuery("SELECT c.charge_id, c.amount, c.gateway_account_id, c.status, c.return_url, " +
                                "c.gateway_transaction_id, c.description, c.reference, to_char(c.created_date, 'YYYY-MM-DD HH24:MI:SS') as created_date, ga.payment_provider " +
                                "FROM charges c, gateway_accounts ga " +
                                "WHERE c.gateway_account_id = ga.gateway_account_id " +
                                "AND c.charge_id=:charge_id " +
                                "AND c.gateway_account_id=:account_id")
                        .bind("charge_id", Long.valueOf(chargeId))
                        .bind("account_id", Long.valueOf(accountId))
                        .map(new DefaultMapper())
                        .first()
        );

        if (data != null) {
            data = copyAndConvertFieldsToString(data, "charge_id", "gateway_account_id");
        }
        return Optional.ofNullable(data);
    }

    public Optional<Map<String, Object>> findById(String chargeId) {
        Map<String, Object> data = jdbi.withHandle(handle ->
                handle
                        .createQuery("SELECT c.charge_id, c.amount, c.gateway_account_id, c.status, c.return_url, " +
                                "c.gateway_transaction_id, c.description, c.reference, to_char(c.created_date, 'YYYY-MM-DD HH24:MI:SS') as created_date, ga.payment_provider " +
                                "FROM charges c, gateway_accounts ga " +
                                "WHERE c.gateway_account_id = ga.gateway_account_id " +
                                "AND c.charge_id=:charge_id")
                        .bind("charge_id", Long.valueOf(chargeId))
                        .map(new DefaultMapper())
                        .first()
        );

        if (data != null) {
            data = copyAndConvertFieldsToString(data, "charge_id", "gateway_account_id");
        }
        return Optional.ofNullable(data);
    }

    public void updateGatewayTransactionId(String chargeId, String transactionId) {
        Integer numberOfUpdates = jdbi.withHandle(handle ->
                handle
                        .createStatement("UPDATE charges SET gateway_transaction_id=:transactionId WHERE charge_id=:charge_id")
                        .bind("charge_id", Long.valueOf(chargeId))
                        .bind("transactionId", transactionId)
                        .execute()
        );

        if (numberOfUpdates != 1) {
            throw new PayDBIException(format("Could not update charge '%s' with gateway transaction id %s, updated %d rows.", chargeId, transactionId, numberOfUpdates));
        }
    }

    public void updateStatusWithGatewayInfo(String provider, String gatewayTransactionId, ChargeStatus newStatus) {
        Integer numberOfUpdates = jdbi.withHandle(handle ->
                handle
                        .createStatement("UPDATE charges AS ch SET status=:status " +
                                "FROM gateway_accounts AS ga WHERE " +
                                "ch.gateway_transaction_id=:gateway_transaction_id AND " +
                                "ga.gateway_account_id=ch.gateway_account_id AND " +
                                "ga.payment_provider=:provider")
                        .bind("gateway_transaction_id", gatewayTransactionId)
                        .bind("provider", provider)
                        .bind("status", newStatus.getValue())
                        .execute()

        );

        if (numberOfUpdates != 1) {
            throw new PayDBIException(format("Could not update charge (gateway_transaction_id: %s) with status %s, updated %d rows.", gatewayTransactionId, newStatus, numberOfUpdates));
        }

        Optional<String> chargeIdMaybe = findChargeByTransactionId(provider, gatewayTransactionId);
        if (chargeIdMaybe.isPresent()) {
            eventListener.notify(ChargeEvent.from(Long.parseLong(chargeIdMaybe.get()), newStatus, LocalDateTime.now()));
        } else {
            logger.error(String.format("Cannot find charge_id for gateway_transaction_id [%s] and provider [%s]", gatewayTransactionId, provider));
        }
    }

    public void updateStatus(String chargeId, ChargeStatus newStatus) {
        Integer numberOfUpdates = jdbi.withHandle(handle ->
                handle
                        .createStatement("UPDATE charges SET status=:status WHERE charge_id=:charge_id")
                        .bind("charge_id", Long.valueOf(chargeId))
                        .bind("status", newStatus.getValue())
                        .execute()
        );

        if (numberOfUpdates != 1) {
            throw new PayDBIException(format("Could not update charge '%s' with status %s, updated %d rows.", chargeId, newStatus, numberOfUpdates));
        }
        eventListener.notify(ChargeEvent.from(Long.parseLong(chargeId), newStatus, LocalDateTime.now()));
    }

    // updates the new status only if the charge is in one of the old statuses and returns num of rows affected
    // very specific transition happening here so check for a valid state before transitioning
    public int updateNewStatusWhereOldStatusIn(String chargeId, ChargeStatus newStatus, List<ChargeStatus> oldStatuses) {
        String sql = format("UPDATE charges SET status=:newStatus WHERE charge_id=:charge_id and status in (%s)", getStringFromStatusList(oldStatuses));

        Integer updateCount = jdbi.withHandle(handle ->
                handle.createStatement(sql)
                        .bind("charge_id", Long.valueOf(chargeId))
                        .bind("newStatus", newStatus.getValue())
                        .execute()
        );
        if (updateCount > 0) {
            eventListener.notify(ChargeEvent.from(Long.parseLong(chargeId), newStatus, LocalDateTime.now()));
        }
        return updateCount;
    }

    public List<Map<String, Object>> findAllBy(String gatewayAccountId, String reference, ExternalChargeStatus status,
                                               String fromDate, String toDate) {
        String query =
                "SELECT DISTINCT " +
                        "c.charge_id, " +
                        "c.gateway_transaction_id, " +
                        "c.status, " +
                        "c.amount, " +
                        "c.description, " +
                        "c.reference, " +
                        "to_char(c.created_date AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SSZ') as created_date " +
                        "FROM " +
                        "charges c " +
                        "WHERE " +
                        "c.gateway_account_id=:gid " +
                        "%s " +
                        "ORDER BY c.charge_id DESC";

        List<Map<String, Object>> rawData = jdbi.withHandle(handle ->
                createQueryHandle(handle, query, gatewayAccountId, reference, status, fromDate, toDate));

        logger.info("found " + rawData.size() + " charge records for the criteria");
        convertCreatedDate(rawData);
        return copyAndConvertFieldsToString(rawData, "charge_id");
    }

    public Optional<String> findAccountByTransactionId(String provider, String transactionId) {
        Map<String, Object> data = jdbi.withHandle(handle ->
                handle
                        .createQuery("SELECT ch.gateway_account_id FROM charges AS ch, gateway_accounts AS ga " +
                                "WHERE ga.gateway_account_id = ch.gateway_account_id " +
                                "AND ga.payment_provider=:provider " +
                                "AND ch.gateway_transaction_id=:transactionId")
                        .bind("provider", provider)
                        .bind("transactionId", transactionId)
                        .map(new DefaultMapper())
                        .first()
        );

        if(data != null && data.get("gateway_account_id") != null) {
            return Optional.ofNullable(data.get("gateway_account_id").toString());
        }

        return Optional.empty();
    }

    private void convertCreatedDate(List<Map<String, Object>> charges) {
        charges.stream().forEach(charge -> {
            String dateString = charge.get("created_date").toString();
            Optional<ZonedDateTime> createdDateOptional = DateTimeUtils.toUTCZonedDateTime(dateString);
            if (createdDateOptional.isPresent()) {
                charge.put("created_date", createdDateOptional.get());
            } else {
                //This should never be happening
                logger.error(String.format("Charge.created_date is not stored in correct format [%s]", dateString));
                charge.put("created_date", null);
            }
        });
    }

    private Optional<String> findChargeByTransactionId(String provider, String transactionId) {
        Map<String, Object> data = jdbi.withHandle(handle ->
                handle
                        .createQuery("SELECT ch.charge_id FROM charges AS ch, gateway_accounts AS ga " +
                                "WHERE ga.gateway_account_id = ch.gateway_account_id " +
                                "AND ga.payment_provider=:provider " +
                                "AND ch.gateway_transaction_id=:transactionId")
                        .bind("provider", provider)
                        .bind("transactionId", transactionId)
                        .map(new DefaultMapper())
                        .first()
        );

        if(data != null && data.get("charge_id") != null) {
            return Optional.ofNullable(data.get("charge_id").toString());
        }

        return Optional.empty();
    }

    private String getStringFromStatusList(List<ChargeStatus> oldStatuses) {
        return oldStatuses
                .stream()
                .map(t -> "'" + t.getValue() + "'")
                .collect(Collectors.joining(","));
    }

    private Map<String, Object> copyAndConvertFieldsToString(Map<String, Object> data, String... fields) {
        Map<String, Object> copy = newHashMap(data);
        for (String field : fields) {
            copy.put(field, String.valueOf(copy.remove(field)));
        }
        return copy;
    }

    private List<Map<String, Object>> copyAndConvertFieldsToString(List<Map<String, Object>> data, final String... fields) {
        return data.stream()
                .map(charge -> copyAndConvertFieldsToString(charge, fields))
                .collect(toList());
    }
}
