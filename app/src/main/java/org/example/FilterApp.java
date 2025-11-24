package org.example;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class FilterApp {

    private static final Logger log = LoggerFactory.getLogger(FilterApp.class);

    // Define topic names
    private static final String INPUT_TOPIC = "input-topic";
    private static final String OUTPUT_TOPIC = "output-topic";

    // Define the key and value we are filtering on
    private static final String FILTER_ON_FIELD = "state";
    private static final String FILTER_ON_VALUE = "Delivered";

    private static Properties setConfig() {
        // Gather our `-D` arguments
        String kafkaServiceUri = System.getProperty("KAFKA_SERVICE_URI");
        String sslTruststoreLocation = System.getProperty("SSL_TRUSTSTORE_LOCATION");
        String sslKeystoreLocation = System.getProperty("SSL_KEYSTORE_LOCATION");
        String passwordForStore = System.getProperty("PASSWORD_FOR_STORE");

        if (kafkaServiceUri == null||
                sslTruststoreLocation == null ||
                sslKeystoreLocation == null ||
                passwordForStore == null) {
            if (kafkaServiceUri == null) log.error("Missing value for -DKAFKA_SERVICE_URI");
            if (sslTruststoreLocation == null) log.error("Missing value for -DSSL_TRUSTSTORE_LOCATION");
            if (sslKeystoreLocation == null) log.error("Missing value for -DSSL_KEYSTORE_LOCATION");
            if (passwordForStore == null) log.error("Missing value for -DPASSWORD_FOR_STORE");
            System.exit(1);
        }

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "json-filter-application");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServiceUri);

        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        // Security settings.
        // a. These settings must match the security settings of the secure Kafka cluster.
        // b. The SSL trust store and key store files must be locally accessible to the application.
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, sslKeystoreLocation);
        config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, passwordForStore);

        // For this demo app, let's start at the beginning of the input topic
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return config;
    }

    public static void main(String[] args) {
        Properties config = setConfig();

        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, String> sourceStream = builder.stream(INPUT_TOPIC);

        final KStream<String, String> filteredStream = sourceStream.flatMapValues(new ValueMapper<String, Iterable<String>>() {
            @Override
            public Iterable<String> apply(String value){
                log.info("FILTER JSON OBJECT: Value='{}'", value);
                try {
                    // Use Gson's JsonParser to safely parse the JSON string
                    JsonElement element = JsonParser.parseString(value);

                    if (!element.isJsonObject()) {
                        log.info("IGNORE: NOT JSON OBJECT: Value='{}'", FILTER_ON_VALUE);
                        return Collections.emptyList();
                    }
                } catch (Exception e) {
                    // If parsing it as JSON (or anything else) failed, then drop it
                    log.error("IGNORE: JSON PARSE FAILED: Value {}. Dropping message.", value, e);
                    return Collections.emptyList();
                }

                // Remove any messages where FILTER_ON_FIELD does not contain FILTER_ON_VALUE
                log.info("FILTER: Value='{}'", value);
                JsonElement element = JsonParser.parseString(value);
                JsonObject inputObject = element.getAsJsonObject();
                // Is the field we gate on present in this message?
                if (inputObject.has(FILTER_ON_FIELD)) {
                    JsonElement statusElement = inputObject.get(FILTER_ON_FIELD);
                    if (!statusElement.isJsonPrimitive() || !statusElement.getAsString().equals(FILTER_ON_VALUE)) {
                        log.info("IGNORE: Value='{}'", FILTER_ON_VALUE);
                        return Collections.emptyList();
                    }
                }

                // Only propagate some values: name, address, tracking_id and timestamp
                // We don't bother with "state", since we already know it's "Delivered"
                // We change the name of "tracking_id" to "trackingId"
                // We'll accept that missing values get "sent on" as `null`
                log.info("MAP VALUES: Value='{}'", value);
                JsonObject outputObject = new JsonObject();
                outputObject.add("name", inputObject.get("name"));
                outputObject.add("address", inputObject.get("address"));
                outputObject.add("trackingId", inputObject.get("tracking_id")); // Note the name change
                outputObject.add("timestamp", inputObject.get("timestamp"));
                return Collections.singleton(outputObject.toString());
            }
        });

        // Write the filtered stream (Key=String, Value=JSON String) to the output topic
        filteredStream.to(OUTPUT_TOPIC, Produced.with(Serdes.String(), Serdes.String()));

        final Topology topology = builder.build();
        System.out.println(topology.describe());

        final KafkaStreams streams = new KafkaStreams(builder.build(), config);

        // Add a shutdown hook to close the Streams application gracefully
        final CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.cleanUp();  // Clean up local state stores (useful for development/testing)
            streams.start();
            latch.await();
        } catch (Throwable e) {
            log.error("Error starting or running Kafka Streams application", e);
            System.exit(1);
        }
        log.info("Kafka Streams Application Shut Down.");
        System.exit(0);
    }
}
