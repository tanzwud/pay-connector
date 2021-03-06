package uk.gov.pay.connector.healthcheck;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.pay.connector.service.CardExecutorService;

import javax.inject.Inject;
import java.util.concurrent.ThreadPoolExecutor;

public class CardExecutorServiceHealthCheck extends HealthCheck {

    private ThreadPoolExecutor threadPoolExecutor;

    @Inject
    public CardExecutorServiceHealthCheck(CardExecutorService cardExecutorService) {
        this.threadPoolExecutor = (ThreadPoolExecutor)cardExecutorService.getExecutor();
        initialiseMetrics(cardExecutorService.getMetricRegistry());
    }

    private void initialiseMetrics(MetricRegistry metricRegistry) {
        metricRegistry.<Gauge<Integer>>register("card-executor.active-threads", () -> threadPoolExecutor.getActiveCount());
        metricRegistry.<Gauge<Integer>>register("card-executor.pool-size", () -> threadPoolExecutor.getPoolSize());
        metricRegistry.<Gauge<Integer>>register("card-executor.core-pool-size", () -> threadPoolExecutor.getCorePoolSize());
        metricRegistry.<Gauge<Integer>>register("card-executor.queue-size", () ->
                threadPoolExecutor.getQueue() == null ? 0 : threadPoolExecutor.getQueue().size());
    }

    @Override
    protected Result check() throws Exception {
        if (threadPoolExecutor.getQueue().size() <= 10) {
            return Result.healthy();
        }

        return Result.unhealthy("CardExecutorService-Unhealthy - Check metrics");
    }
}
