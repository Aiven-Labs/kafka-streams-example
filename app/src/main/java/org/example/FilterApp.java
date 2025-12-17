package org.example;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

// It would be nice if the Avro class names followed Java capitalisation conventions,
// but unfortunately that is not the case - we'll just have to live with it
import data.gen.avro.logistics;           // The class generated for our input messages
import data.gen.avro.logistics_delivered;  // And the class generated for our output messages

public class FilterApp {
    private static final Logger log = LoggerFactory.getLogger(FilterApp.class);

    // Define the state we are filtering on
    private static final String KEEP_STATE = "Delivered";

    private static Properties setConfig() {
        // Gather our `-D` arguments
        // We put them all into one configuration, even though they really fall into three groups
        String kafkaServiceUri = System.getProperty("KAFKA_SERVICE_URI");
        String sslTruststoreLocation = System.getProperty("SSL_TRUSTSTORE_LOCATION");
        String sslKeystoreLocation = System.getProperty("SSL_KEYSTORE_LOCATION");
        String passwordForStore = System.getProperty("PASSWORD_FOR_STORE");
        String schemaRegistryUrl = System.getProperty("SCHEMA_REGISTRY_URL");
        // We have a sensible default for the schema registry user, so provide it
        String schemaRegistryUserName = System.getProperty("SCHEMA_REGISTRY_USERNAME", "avnadmin");
        String schemaRegistryPassword = System.getProperty("SCHEMA_REGISTRY_PASSWORD");
        // We have defaults for our topic names as well
        String inputTopic = System.getProperty("INPUT_TOPIC", "logistics_data_gen");
        String outputTopic = System.getProperty("OUTPUT_TOPIC", "logistics_data_delivered");

        if (kafkaServiceUri == null
                || sslTruststoreLocation == null
                || sslKeystoreLocation == null
                || passwordForStore == null
                || schemaRegistryUrl == null
                || schemaRegistryUserName == null
                || schemaRegistryPassword == null
                || inputTopic == null
                || outputTopic == null) {
            if (kafkaServiceUri == null) log.error("Missing value for -DKAFKA_SERVICE_URI");
            if (sslTruststoreLocation == null) log.error("Missing value for -DSSL_TRUSTSTORE_LOCATION");
            if (sslKeystoreLocation == null) log.error("Missing value for -DSSL_KEYSTORE_LOCATION");
            if (passwordForStore == null) log.error("Missing value for -DPASSWORD_FOR_STORE");
            if (schemaRegistryUrl == null) log.error("Missing value for -DSCHEMA_REGISTRY_URL");
            if (schemaRegistryUserName == null) log.error("Missing value for -DSCHEMA_REGISTRY_USERNAME");
            if (schemaRegistryPassword == null) log.error("Missing value for -DSCHEMA_REGISTRY_PASSWORD");
            if (inputTopic == null) log.error("Missing value for -DINPUT_TOPIC");
            if (outputTopic == null) log.error("Missing value for -DOUTPUT_TOPIC");
            System.exit(1);
        }

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "json-filter-application");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServiceUri);

        // We're not particularly interested in the message key, so leave it as a string
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

        // Security settings.
        // a. These settings must match the security settings of the secure Kafka cluster.
        // b. The SSL trust store and key store files must be locally accessible to the application.
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, sslKeystoreLocation);
        config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, passwordForStore);
        config.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, passwordForStore);

        // Schema registry
        config.put("schema.registry.url", schemaRegistryUrl);
        config.put("schema.registry.basic.auth.credentials.source", "USER_INFO");
        config.put("schema.registry.basic.auth.user.info", schemaRegistryUserName + ":" + schemaRegistryPassword);

        // Topic names
        config.put("input.topic.name", inputTopic);
        config.put("output.topic.name", outputTopic);

        return config;
    }

    public static void main(String[] args) {
        Properties config = setConfig();

        // Set up the schema registry
        // The values we want are in `config`, because it was convenient to gather
        // them along with all the other command line values
        final Map<String, String> serdeConfig = new HashMap<String, String>();
        serdeConfig.put(
                "schema.registry.url", config.get("schema.registry.url").toString()
        );
        serdeConfig.put(
                "schema.registry.basic.auth.credentials.source",
                config.get("schema.registry.basic.auth.credentials.source").toString()
        );
        serdeConfig.put(
                "schema.registry.basic.auth.user.info",
                config.get("schema.registry.basic.auth.user.info").toString()
        );

        // Configure the Serde to be used for the input Avro messages
        // Keys remain strings (the default key type), values are Avro objects.
        final SpecificAvroSerde<logistics> inputMessageSerde = new SpecificAvroSerde<>();
        inputMessageSerde.configure(serdeConfig, false);  // `false` means it's a value Serde

        // And similarly for the output
        final SpecificAvroSerde<logistics_delivered> outputMessageSerde = new SpecificAvroSerde<>();
        outputMessageSerde.configure(serdeConfig, false); // `false` means it's a value Serde

        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, logistics> sourceStream = builder.stream(config.get("input.topic.name").toString());

        // 1. I don't really need to put the type information into each lambda (except the final `peek` where it does
        //    help), but I feel it makes it more obvious what the control flow is.
        //    For instance, I could do `.filter( (key, inputValue) -> inputValue.getState().equals(KEEP_STATE) )`
        // 2. In a real production app, we don't need all three `peek` calls - but in an example and during development
        //    they're quite nice for explicitly logging what is going on
        sourceStream
                .peek( (String key, logistics inputValue) -> log.info("LOOKING AT: Value='{}'", inputValue) )
                .filter( (String key, logistics inputValue) -> inputValue.getState().equals(KEEP_STATE) )
                .peek( ( String key, logistics inputValue) -> log.info("KEEPING: Value='{}'", inputValue) )
                .mapValues( (logistics inputValue) -> {
                            // Only propagate some values.
                            // We don't bother with "state", since we already know it's "Delivered"
                            // We change the names "time_utc" to "timeUtc" and "tracking_id" to "trackingId"
                            // (although you can't tell that from the Java code, only from the schemas)
                            logistics_delivered outputValue = new logistics_delivered();
                            outputValue.setTimeUtc(inputValue.getTimeUtc());
                            outputValue.setTrackingId(inputValue.getTrackingId());
                            outputValue.setCarrier(inputValue.getCarrier());
                            outputValue.setManifest(inputValue.getManifest());
                            return outputValue;
                        }
                )
                .peek( (String key, logistics_delivered outputValue) -> log.info("SENDING: Value='{}'", outputValue) )
                .to(
                        config.get("output.topic.name").toString(),
                        Produced.with(Serdes.String(), outputMessageSerde)
                );

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
