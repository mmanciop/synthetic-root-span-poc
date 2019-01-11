# Synthetic Root Span PoC

This PoC shows how to 'trick' the Instana Java instrumentation into thinking
that a trace and parent span are propagated over an incoming HTTP request,
when it case it is not.

## How does it work

The trick is perfomed by injecting the X-INSTANA-T and X-INSTANA-S headers in
the request via a Tomcat valve.

The span with the ID specified in the 'fake' X-INSTANA-S header is then
sent to Instana via the [Trace SDK Web Service](https://docs.instana.io/core_concepts/tracing/trace_rest_sdk/).

## Build and run

Edit `docker-compose.yaml` to configure the agent key for your Instana tenant
by editing the value of the `INSTANA_AGENT_KEY` environment variable accordingly
(and, if on-prem or based in a different zone, the `INSTANA_AGENT_ENDPOINT`
environment variable as well).

Then, execute:
```
./mwnw clean package
docker-compose build
docker-compose up
```

The application will come up at your Docker's IP (e.g., `docker-machine ip`) at the URL:
`http://<docker-ip>:8080/api/google`

Spam a few requests, search for the `SDK` service and `MarketXYZ` endpoint in Instana,
and enjoy :-)
