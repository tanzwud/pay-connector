package uk.gov.pay.connector.dao;

import uk.gov.pay.connector.model.TransactionType;
import uk.gov.pay.connector.model.api.ExternalChargeState;
import uk.gov.pay.connector.model.api.ExternalRefundStatus;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.RefundStatus;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

public class ChargeSearchParams {

    private TransactionType transactionType;
    private Long gatewayAccountId;
    private String reference;
    private String email;
    private ZonedDateTime fromDate;
    private ZonedDateTime toDate;
    private Long page;
    private Long displaySize;
    private List<String> cardBrands = new ArrayList<>();
    private Set<ChargeStatus> internalStates = new HashSet<>();
    private Set<ChargeStatus> internalChargeStatuses = new HashSet<>();
    private Set<RefundStatus> internalRefundStatuses = new HashSet<>();

    public Long getGatewayAccountId() {
        return gatewayAccountId;
    }

    public ChargeSearchParams withTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
        return this;
    }

    public ChargeSearchParams withGatewayAccountId(Long gatewayAccountId) {
        this.gatewayAccountId = gatewayAccountId;
        return this;
    }

    public ChargeSearchParams withEmailLike(String email) {
        this.email = email;
        return this;
    }

    public Set<String> getExternalChargeStates() {
        return this.internalChargeStatuses.stream()
                .map(s -> s.toExternal().getStatus())
                .sorted()
                .collect(Collectors.toSet());
    }

    public Set<String> getExternalStates() {
        return this.internalStates.stream()
                .map(s -> s.toExternal().getStatus())
                .collect(Collectors.toSet());
    }

    public Set<String> getExternalRefundStates() {
        return this.internalRefundStatuses.stream()
                .map(s -> s.toExternal().getStatus())
                .sorted()
                .collect(Collectors.toSet());
    }

    public Set<ChargeStatus> getInternalChargeStatuses() {
        return this.internalChargeStatuses;
    }

    public Set<ChargeStatus> getInternalStates() {
        return this.internalStates;
    }

    public Set<RefundStatus> getInternalRefundStatuses() {
        return this.internalRefundStatuses;
    }

    public ChargeSearchParams withExternalState(String state) {
        if (state != null) {
            this.internalStates.addAll(
                    parseChargeState(state).stream()
                            .map(ChargeStatus::fromExternal)
                            .flatMap(Collection::stream)
                            .collect(Collectors.toSet()));
        }
        return this;
    }

    public ChargeSearchParams addExternalChargeStates(List<String> states) {
        if (states != null) {
            this.internalChargeStatuses.addAll(states.stream()
                    .map(this::parseChargeState)
                    .map(externalChargeStates -> externalChargeStates.stream().map(ChargeStatus::fromExternal)
                            .flatMap(Collection::stream)
                            .collect(Collectors.toSet()))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet()));
        }
        return this;
    }

    public ChargeSearchParams addExternalRefundStates(List<String> states) {
        if (states != null) {
            this.internalRefundStatuses.addAll(states.stream()
                    .map(ExternalRefundStatus::fromPublicStatusLabel)
                    .flatMap(externalRefundStatus -> RefundStatus.fromExternal(externalRefundStatus).stream())
                    .collect(Collectors.toSet()));
        }
        return this;
    }

    public ChargeSearchParams withInternalStates(List<ChargeStatus> statuses) {
        this.internalStates.addAll(statuses);
        return this;
    }

    public ChargeSearchParams withCardBrand(String cardBrand) {
        this.cardBrands = Collections.singletonList(cardBrand);
        return this;
    }

    public ChargeSearchParams withCardBrands(List<String> cardBrands) {
        this.cardBrands = cardBrands;
        return this;
    }

    public String getReference() {
        return reference;
    }

    public String getEmail() {
        return email;
    }

    public ChargeSearchParams withReferenceLike(String reference) {
        this.reference = reference;
        return this;
    }

    public ZonedDateTime getFromDate() {
        return fromDate;
    }

    public ChargeSearchParams withFromDate(ZonedDateTime fromDate) {
        this.fromDate = fromDate;
        return this;
    }

    public ZonedDateTime getToDate() {
        return toDate;
    }

    public ChargeSearchParams withToDate(ZonedDateTime toDate) {
        this.toDate = toDate;
        return this;
    }

    public Long getPage() {
        return page;
    }

    public ChargeSearchParams withPage(Long page) {
        this.page = page;
        return this;
    }

    public Long getDisplaySize() {
        return displaySize;
    }

    public ChargeSearchParams withDisplaySize(Long displaySize) {
        if (displaySize <= 0) {
            throw new IllegalArgumentException("displaySize must be a positive integer");
        }

        this.displaySize = displaySize;
        return this;
    }

    public List<String> getCardBrands() {
        return cardBrands;
    }

    public String buildQueryParams() {
        StringBuilder builder = new StringBuilder();
        if (transactionType != null) {
            builder.append("&transaction_type=").append(transactionType.getValue());
        }
        if (isNotBlank(reference))
            builder.append("&reference=").append(reference);
        if (email != null)
            builder.append("&email=").append(email);
        if (fromDate != null)
            builder.append("&from_date=").append(fromDate);
        if (toDate != null)
            builder.append("&to_date=").append(toDate);
        if (page != null)
            builder.append("&page=").append(page);
        if (displaySize != null)
            builder.append("&display_size=").append(displaySize);

        getExternalStates().stream()
                .findFirst()
                .ifPresent(state -> builder.append("&state=").append(state));

        String paymentStates = getExternalChargeStates().stream()
                .collect(Collectors.joining(","));

        String refundStates = getExternalRefundStates().stream()
                .collect(Collectors.joining(","));

        if (isNotEmpty(paymentStates)) {
            builder.append("&payment_states=").append(paymentStates);
        }

        if (isNotEmpty(refundStates)) {
            builder.append("&refund_states=").append(refundStates);
        }

        if (!cardBrands.isEmpty()) {
            cardBrands.forEach(cardBrand -> builder.append("&card_brand=").append(cardBrand));
        }
        return builder.toString().replaceFirst("&", "");
    }

    private List<ExternalChargeState> parseChargeState(String state) {
        if (isBlank(state)) {
            return new ArrayList<>();
        }
        return ExternalChargeState.fromStatusString(state);
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }
}
