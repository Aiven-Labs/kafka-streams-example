package org.example;

import org.apache.kafka.clients.CommonClientConfigs;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class FilterApp {

    private static final Logger log = LoggerFactory.getLogger(FilterApp.class);

    // Define topic names
    private static final String INPUT_TOPIC = "input-topic";
    private static final String OUTPUT_TOPIC = "output-topic";

    // Define the key and value we are filtering on
    private static final String TARGET_FIELD = "state";
    private static final String TARGET_VALUE = "Delivered";

    public static void main(String[] args) {
        // Gather our `-D` arguments
        // -DKAFKA_SERVICE_URI=tibs-kafka-for-streams-251120-dev-sandbox.c.aivencloud.com:12693
        //-DSSL_TRUSTSTORE_LOCATION=/Users/tony.ibbs/sw/aiven/project-celtic/kafka-streams-in-celtic/kafka-streams-example/certs/client.truststore.jks
        //-DSSL_KEYSTORE_LOCATION=/Users/tony.ibbs/sw/aiven/project-celtic/kafka-streams-in-celtic/kafka-streams-example/certs/client.keystore.p12
        //PASSWORD_FOR_STORE=not-a-good-password
        String kafkaServiceUri = System.getProperty("KAFKA_SERVICE_URI"); //, "tibs-kafka-for-streams-251120-dev-sandbox.c.aivencloud.com:12693");
        String sslTruststoreLocation = System.getProperty("SSL_TRUSTSTORE_LOCATION"); //, "/Users/tony.ibbs/sw/aiven/project-celtic/kafka-streams-in-celtic/kafka-streams-example/certs/client.truststore.jks");
        String sslKeystoreLocation = System.getProperty("SSL_KEYSTORE_LOCATION"); //, "/Users/tony.ibbs/sw/aiven/project-celtic/kafka-streams-in-celtic/kafka-streams-example/certs/client.keystore.p12");
        String passwordForStore = System.getProperty("PASSWORD_FOR_STORE"); //, "not-a-good-password");

        if (kafkaServiceUri == null || sslTruststoreLocation == null || sslKeystoreLocation == null || passwordForStore == null) {
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

        // 2. Define the Streams Topology
        final StreamsBuilder builder = new StreamsBuilder();

        // Start reading from the input topic
        final KStream<String, String> sourceStream = builder.stream(INPUT_TOPIC);

        // Define the filtering logic using Gson
        final KStream<String, String> filteredStream = sourceStream.filter((key, jsonValue) -> {
            try {
                log.info("INSPECT: Key='{}', Value='{}'", key, jsonValue);
                // Use Gson's JsonParser to safely parse the JSON string
                JsonElement element = JsonParser.parseString(jsonValue);

                if (element.isJsonObject()) {
                    JsonObject jsonObject = element.getAsJsonObject();

                    // Check if the object contains the target field
                    if (jsonObject.has(TARGET_FIELD)) {
                        JsonElement statusElement = jsonObject.get(TARGET_FIELD);

                        // Check if the field is a String and matches the target value
                        if (statusElement.isJsonPrimitive() && statusElement.getAsString().equals(TARGET_VALUE)) {
                            log.info("ACCEPT: Key='{}', Status='{}'", key, TARGET_VALUE);
                            return true; // Keep this message
                        }
                    }
                }

                // If parsing failed, the field was missing, or the value didn't match, drop the message.
                log.debug("DROP: Key='{}', Message did not meet criteria.", key);
                return false;

            } catch (Exception e) {
                // Handle cases where the message is not valid JSON
                log.error("Error parsing JSON with Gson for key: {}. Dropping message.", key, e);
                return false;
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
            // Clean up local state stores (useful for development/testing)
            streams.cleanUp();
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
