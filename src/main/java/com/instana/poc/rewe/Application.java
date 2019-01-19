package com.instana.poc.rewe;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.instana.sdk.annotation.Span;
import com.instana.sdk.support.SpanSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.instana.sdk.annotation.Span.Type.INTERMEDIATE;
import static java.lang.Long.toHexString;
import static java.util.Collections.singletonMap;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@SpringBootApplication
public class Application {

    private static final org.slf4j.Logger LOGGER = getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Configuration
    static class RestTemplateConfiguration {

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

    }

    @RestController
    static class ApiController {

        private static final String INTERMEDIATE_SPAN_NAME = "additionalPosData";

        private final RestTemplate restTemplate;

        private final Random random = new Random();

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        private final URI apiUri;

        @Autowired
        ApiController(RestTemplate restTemplate,
                      @Value("${instana_agent_host}") String agentHost,
                      @Value("${instana_agent_port:42699}") int agentPort) {
            this.restTemplate = restTemplate;

            apiUri = URI.create(String.format("http://%s:%d/com.instana.plugin.generic.trace", agentHost, agentPort));
        }


        @GetMapping(path = "/api/google", produces = TEXT_PLAIN_VALUE)
        public String sayHello() {
            ResponseEntity<String> response = restTemplate.getForEntity("https://www.google.com", String.class);

            try {
                return response.getBody();
            } finally {
                sendDelayedSpan(Duration.ofMinutes(1));
            }
        }

        /*
         * The intermediate span makes it easy to find out the trace and parent id in this case.
         * If the trace and parent id is captured in a filter, this span is not necessary.
         */
        @Span(type = INTERMEDIATE, value = INTERMEDIATE_SPAN_NAME)
        private void sendDelayedSpan(Duration delay) {
            /*
             * This can be very much optimized by batching multiple spans per HTTP request wrapping the JSON of the spans in a JSON array
             */
            final SpanInfo spanInfo = new SpanInfo(
                    /*
                     * These SpanSupport methods return {code null} until the agent has instrumented the Java
                     * application. Also, for them to work, the SDK functionality must be opted-in via the
                     * configuration.yaml file
                     */
                    SpanSupport.traceId(INTERMEDIATE, INTERMEDIATE_SPAN_NAME),
                    SpanSupport.spanId(INTERMEDIATE, INTERMEDIATE_SPAN_NAME),
                    toHexString(random.nextLong()),
                    /*
                     * Pre-date creation of this span. It has no implications in terms of functionality
                     * except than it yields a more intuitive sorting in the trace view, in which the remote shop
                     * sends the trace earlier. This should ideally match the actual time the shop sends the request,
                     * and it can occur before the root call.
                     */
                    System.currentTimeMillis() - 3000L,
                    /*
                     * Set this to the actual duration of the shop's call to make the the trace view more informative.
                     */
                    500L,
                    /*
                     * The prefix `tags` is important. Without it, the search for these tags may not work in the Analyze view.
                     */
                    singletonMap("tags.rewe.custom_tag", "value!"));

            if (Objects.nonNull(spanInfo.traceId)) {
                LOGGER.info("Will submit tagged exit span {} for trace {}", spanInfo.spanId, spanInfo.traceId);

                executor.schedule(() -> {
                    ResponseEntity<Void> responseEntity = restTemplate.postForEntity(apiUri, spanInfo, Void.class);

                    if (responseEntity.getStatusCode().is2xxSuccessful()) {
                        LOGGER.info("Tagged span {} for trace {} was delivered successfully", spanInfo.spanId, spanInfo.traceId);
                    } else {
                        LOGGER.error("An error occurred while submitting tagged span {} for trace {}", spanInfo.spanId, spanInfo.traceId);
                    }
                }, delay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                LOGGER.warn("Java instrumentation not initialised yet, no tagged span will be sent to the agent");
            }
        }
    }

    /*
     * Convenience class to compose JSON via Jackson and its integration in RestTemplate. Not very efficient.
     */
    static class SpanInfo {

        private final String traceId;

        private final String parentId;

        private final String spanId;

        private final long startTime;

        private final long duration;

        private final Map<String, String> data;

        SpanInfo(String traceId, String parentId, String spanId, long startTime, long duration, Map<String, String> data) {
            this.traceId = traceId;
            this.parentId = parentId;
            this.spanId = spanId;
            this.startTime = startTime;
            this.duration = duration;
            this.data = data;
        }

        @JsonProperty("name")
        public String getName() {
            /*
             * This is going to be the endpoint to which the call will be associated in the service called "SDK"
             */
            return "MarketXYZ";
        }

        @JsonProperty("type")
        public String getType() {
            return "EXIT";
        }

        @JsonProperty("traceId")
        public String getTraceId() {
            return traceId;
        }

        @JsonProperty("parentId")
        public String getParentId() {
            return parentId;
        }

        @JsonProperty("spanId")
        public String getSpanId() {
            return spanId;
        }

        @JsonProperty("timestamp")
        public long getStartTime() {
            return startTime;
        }

        @JsonProperty("duration")
        public long getDuration() {
            return duration;
        }

        @JsonProperty("data")
        public Map<String, String> getData() {
            return data;
        }

    }

}

