package uk.gov.pay.connector.service;

import com.google.common.collect.ImmutableList;
import com.google.inject.persist.Transactional;
import io.dropwizard.setup.Environment;
import org.apache.commons.lang3.StringUtils;
import uk.gov.pay.connector.dao.CardTypeDao;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.dao.ChargeEventDao;
import uk.gov.pay.connector.exception.ChargeNotFoundRuntimeException;
import uk.gov.pay.connector.model.GatewayError;
import uk.gov.pay.connector.model.domain.AddressEntity;
import uk.gov.pay.connector.model.domain.AuthCardDetails;
import uk.gov.pay.connector.model.domain.CardDetailsEntity;
import uk.gov.pay.connector.model.domain.CardTypeEntity;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.GatewayAccountEntity;
import uk.gov.pay.connector.model.gateway.AuthorisationGatewayRequest;
import uk.gov.pay.connector.model.gateway.GatewayResponse;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_READY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_TIMEOUT;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_UNEXPECTED_ERROR;
import static uk.gov.pay.connector.model.domain.ChargeStatus.ENTERING_CARD_DETAILS;
import static uk.gov.pay.connector.model.domain.NumbersInStringsSanitizer.sanitize;

public class CardAuthoriseService extends CardAuthoriseBaseService<AuthCardDetails> {

    private CardTypeDao cardTypeDao;
    private final Auth3dsDetailsFactory auth3dsDetailsFactory;

    @Inject
    public CardAuthoriseService(ChargeDao chargeDao,
                                ChargeEventDao chargeEventDao,
                                CardTypeDao cardTypeDao,
                                PaymentProviders providers,
                                CardExecutorService cardExecutorService,
                                Auth3dsDetailsFactory auth3dsDetailsFactory,
                                Environment environment) {
        super(chargeDao, chargeEventDao, providers, cardExecutorService, environment);
        this.cardTypeDao = cardTypeDao;
        this.auth3dsDetailsFactory = auth3dsDetailsFactory;
    }

    @Transactional
    public ChargeEntity preOperation(String chargeId, AuthCardDetails authCardDetails) {

        return chargeDao.findByExternalId(chargeId).map(chargeEntity -> {

            String cardBrand = authCardDetails.getCardBrand();

            if (!chargeEntity.getGatewayAccount().isRequires3ds() && cardBrandRequires3ds(cardBrand)) {

                chargeEntity.setStatus(ChargeStatus.AUTHORISATION_ABORTED);

                logger.error("AuthCardDetails authorisation failed pre operation. Card brand requires 3ds but Gateway account has 3ds disabled - charge_external_id={}, operation_type={}, card_brand={}",
                        chargeEntity.getExternalId(), OperationType.AUTHORISATION.getValue(), cardBrand);

                chargeEventDao.persistChargeEventOf(chargeEntity, Optional.empty());

            } else {
                chargeEntity = preOperation(chargeEntity, OperationType.AUTHORISATION, getLegalStates(), AUTHORISATION_READY);
                getPaymentProviderFor(chargeEntity).generateTransactionId().ifPresent(chargeEntity::setGatewayTransactionId);
            }

            return chargeEntity;

        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    private boolean cardBrandRequires3ds(String cardBrand) {
        List<CardTypeEntity> cardTypes = cardTypeDao.findByBrand(cardBrand).stream()
                .filter(cardTypeEntity -> cardTypeEntity.getBrand().equals(cardBrand))
                .collect(Collectors.toList());
        return cardTypes.stream().anyMatch(CardTypeEntity::isRequires3ds);
    }

    public GatewayResponse<BaseAuthoriseResponse> operation(ChargeEntity chargeEntity, AuthCardDetails authCardDetails) {
        return getPaymentProviderFor(chargeEntity)
                .authorise(AuthorisationGatewayRequest.valueOf(chargeEntity, authCardDetails));
    }

    @Override
    protected List<ChargeStatus> getLegalStates() {
        return ImmutableList.of(
                ENTERING_CARD_DETAILS
        );
    }

    @Transactional
    public GatewayResponse<BaseAuthoriseResponse> postOperation(String chargeId, AuthCardDetails authCardDetails, GatewayResponse<BaseAuthoriseResponse> operationResponse) {

        return chargeDao.findByExternalId(chargeId).map(chargeEntity -> {

            ChargeStatus status = operationResponse.getBaseResponse()
                    .map(BaseAuthoriseResponse::authoriseStatus)
                    .map(BaseAuthoriseResponse.AuthoriseStatus::getMappedChargeStatus)
                    .orElseGet(() -> operationResponse.getGatewayError()
                            .map(gatewayError -> mapError(gatewayError))
                            .orElse(ChargeStatus.AUTHORISATION_ERROR));

            String transactionId = operationResponse.getBaseResponse()
                    .map(BaseAuthoriseResponse::getTransactionId).orElse("");

            operationResponse.getSessionIdentifier().ifPresent(chargeEntity::setProviderSessionId);

            logger.info("Authorisation for {} ({} {}) for {} ({}) - {} .'. {} -> {}",
                    chargeEntity.getExternalId(), chargeEntity.getPaymentGatewayName().getName(),
                    StringUtils.isNotBlank(transactionId) ? transactionId : "missing transaction ID",
                    chargeEntity.getGatewayAccount().getAnalyticsId(), chargeEntity.getGatewayAccount().getId(),
                    operationResponse, chargeEntity.getStatus(), status);

            GatewayAccountEntity account = chargeEntity.getGatewayAccount();

            metricRegistry.counter(String.format("gateway-operations.%s.%s.%s.authorise.result.%s", account.getGatewayName(), account.getType(), account.getId(), status.toString())).inc();

            chargeEntity.setStatus(status);
            operationResponse.getBaseResponse().ifPresent(response -> auth3dsDetailsFactory.create(response).ifPresent(chargeEntity::set3dsDetails));

            if (StringUtils.isBlank(transactionId)) {
                logger.warn("AuthCardDetails authorisation response received with no transaction id. -  charge_external_id={}", chargeEntity.getExternalId());
            } else {
                chargeEntity.setGatewayTransactionId(transactionId);
            }

            appendCardDetails(chargeEntity, authCardDetails);
            chargeEventDao.persistChargeEventOf(chargeEntity, Optional.empty());
            return operationResponse;

        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    private ChargeStatus mapError(GatewayError gatewayError) {
        switch (gatewayError.getErrorType()) {
            case GENERIC_GATEWAY_ERROR:
                return AUTHORISATION_ERROR;
            case GATEWAY_CONNECTION_TIMEOUT_ERROR:
                return AUTHORISATION_TIMEOUT;
            default:
                return AUTHORISATION_UNEXPECTED_ERROR;
        }
    }

    private void appendCardDetails(ChargeEntity chargeEntity, AuthCardDetails authCardDetails) {
        CardDetailsEntity detailsEntity = new CardDetailsEntity();
        detailsEntity.setCardBrand(sanitize(authCardDetails.getCardBrand()));
        detailsEntity.setBillingAddress(new AddressEntity(authCardDetails.getAddress()));
        detailsEntity.setCardHolderName(sanitize(authCardDetails.getCardHolder()));
        detailsEntity.setExpiryDate(authCardDetails.getEndDate());
        detailsEntity.setLastDigitsCardNumber(StringUtils.right(authCardDetails.getCardNo(), 4));
        chargeEntity.setCardDetails(detailsEntity);
        logger.info("Stored confirmation details for charge - charge_external_id={}", chargeEntity.getExternalId());
    }
}
