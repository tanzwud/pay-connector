package uk.gov.pay.connector.service;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Stopwatch;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.CaptureProcessConfig;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.model.domain.ChargeEntity;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CardCaptureProcess {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ChargeDao chargeDao;
    private final CardCaptureService captureService;
    private final MetricRegistry metricRegistry;
    private final CaptureProcessConfig captureConfig;
    private volatile long queueSize;
    private final Counter queueSizeMetric;

    @Inject
    public CardCaptureProcess(Environment environment, ChargeDao chargeDao, CardCaptureService cardCaptureService, ConnectorConfiguration connectorConfiguration) {
        this.chargeDao = chargeDao;
        this.captureService = cardCaptureService;
        this.captureConfig = connectorConfiguration.getCaptureProcessConfig();
        metricRegistry = environment.metrics();

        queueSizeMetric = metricRegistry.counter("gateway-operations.capture-process.queue-size");
    }

    public void runCapture() {
        Stopwatch responseTimeStopwatch = Stopwatch.createStarted();
        try {
            queueSize = chargeDao.countChargesForCapture();

            updateQueueSizeMetric(queueSize);

            List<ChargeEntity> chargesToCapture = chargeDao.findChargesForCapture(captureConfig.getBatchSize(), captureConfig.getRetryFailuresEveryAsJavaDuration());

            if (chargesToCapture.size() > 0) {
                logger.info("Capturing : " + chargesToCapture.size() + " of " + queueSize + " charges");
            }

            chargesToCapture.forEach((charge) -> {
                if (shouldRetry(charge)) {
                    try {
                        captureService.doCapture(charge.getExternalId());
                    } catch (Exception e) {
                        logger.error("Exception when running capture for [" + charge.getExternalId() + "]", e);
                        throw e;
                    }
                } else {
                    captureService.markChargeAsCaptureError(charge.getExternalId());
                }
            });
        } catch (Exception e) {
            logger.error("Exception when running capture", e);
        } finally {
            responseTimeStopwatch.stop();
            metricRegistry.histogram("gateway-operations.capture-process.running_time").update(responseTimeStopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    private boolean shouldRetry(ChargeEntity charge) {
        return chargeDao.countCaptureRetriesForCharge(charge.getId()) < captureConfig.getMaximumRetries();
    }

    private void updateQueueSizeMetric(long newQueueSize) {
        // Counters do not provide a set method to record a spot value, thus we need this workaround.
        long currentQueueSizeCounter = queueSizeMetric.getCount();
        queueSizeMetric.inc(newQueueSize - currentQueueSizeCounter); // if input<0, we get decrease
    }

    public long getQueueSize() {
        return queueSize;
    }
}
