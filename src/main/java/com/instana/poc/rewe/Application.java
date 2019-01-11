package com.instana.poc.rewe;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.instana.sdk.support.SpanSupport.*;
import static java.lang.Long.toHexString;
import static java.util.Collections.singletonMap;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@SpringBootApplication
public class Application {

	private static final org.slf4j.Logger LOGGER = getLogger(Application.class);

	private static final String INSTANA_VALVE_NAME = "InstanaValve";

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@RestController
	static class ApiController {

		final RestTemplate restTemplate;

		@Autowired
		ApiController(RestTemplate restTemplate) {
			this.restTemplate = restTemplate;
		}


		@GetMapping(path = "/api/google", produces = TEXT_PLAIN_VALUE)
		public String sayHello() {
			ResponseEntity<String> response = restTemplate.getForEntity("https://www.google.com", String.class);

			return response.getBody();
		}

	}

	@Configuration
	static class RestTemplateConfiguration {

		@Bean
		RestTemplate restTemplate() {
			return new RestTemplate();
		}

	}

	@Configuration
	static class InstanaHeaderInjectionConfiguration {

		@Bean
		@Qualifier(INSTANA_VALVE_NAME)
		Valve instanaValve(@Value("${instana_agent_host}") String agentHost,
						   @Value("${instana_agent_port:42699}") int agentPort,
						   RestTemplate restTemplate) {

			final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

			final URI apiUri = URI.create(String.format("http://%s:%d/com.instana.plugin.generic.trace", agentHost, agentPort));

			final Random random = new Random();

			return new ValveBase() {
				@Override
				public void invoke(Request request, Response response) throws IOException, ServletException {
					final String traceId = toHexString(random.nextLong());
					final String spanId = toHexString(random.nextLong());

					MimeHeaders mimeHeaders = request.getCoyoteRequest().getMimeHeaders();

					MessageBytes traceIdHeaderValue = mimeHeaders.addValue(TRACE_ID);
					traceIdHeaderValue.setString(traceId);

					MessageBytes spanIdHeaderValue = mimeHeaders.addValue(SPAN_ID);
					spanIdHeaderValue.setString(spanId);

					LOGGER.info("Will send root span {} for trace {}", request.getHeader(SPAN_ID), request.getHeader(TRACE_ID));

					final long startTime = System.currentTimeMillis();

					getNext().invoke(request, response);

					final long duration = System.currentTimeMillis() - startTime;

					executor.schedule(() -> {
						/*
						 * This can be very much optimized by batching multiple spans per HTTP request wrapping the JSON of the spans in a JSON array
						 */
						SpanInfo spanInfo = new SpanInfo(traceId, spanId, startTime, duration, singletonMap("rewe.custom_tag.key", "value!"));
						ResponseEntity<Void> responseEntity = restTemplate.postForEntity(apiUri, spanInfo, Void.class);

						if (responseEntity.getStatusCode().is2xxSuccessful()) {
							LOGGER.info("Root span {} for trace {} was delivered successfully", spanId, traceId);
						} else {
							LOGGER.error("An error occurred while submitting root span {} for trace {}", spanId, traceId);
						}
					}, 1, TimeUnit.MINUTES);
				}
			};
		}

	}

	@ConditionalOnProperty(name = "instana_skip_injection", havingValue = "false", matchIfMissing = true)
	@Component
	public class CustomContainer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

		private final Valve valve;

		@Autowired
		public CustomContainer(@Qualifier("InstanaValve") Valve valve) {
			this.valve = valve;
		}

		@Override
		public void customize(TomcatServletWebServerFactory factory) {
			factory.addEngineValves(valve);

			LOGGER.info("Instana synthetic root span valve installed");
		}
	}

	static class SpanInfo {

		private final String traceId;

		private final String spanId;

		private final long startTime;

		private final long duration;

		private final Map<String, String> data;

		SpanInfo(String traceId, String spanId, long startTime, long duration, Map<String, String> data) {
			this.traceId = traceId;
			this.spanId = spanId;
			this.startTime = startTime;
			this.duration = duration;
			this.data = data;
		}

		@JsonProperty("name")
		public String getName() {
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

