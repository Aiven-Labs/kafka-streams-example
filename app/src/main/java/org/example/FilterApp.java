package org.example;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

// It would be nice if the Avro class names followed Java capitalisation conventions,
// but unfortunately that is not the case - we'll just have to live with it
// (the `logistics` name is set by the Aiven data generator, and it seems sensible
// to keep the `logistics_delivered` name similar)
import data.gen.avro.logistics;            // The class generated for our input messages
import data.gen.avro.logistics_delivered;  // And the class generated for our output messages

import org.example.Config;

public class FilterApp {
    private static final Logger log = LoggerFactory.getLogger(FilterApp.class);

    // Define the state we are filtering on
    private static final String KEEP_STATE = "Delivered";

    public static void main(String[] args) {
        Properties config = Config.getConfig();

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

        // Read from the input stream, filter out any messages where the "state" is not KEEP_STATE, and then
        // create new messages using a subset of the original message values. Write the results to the output topic.
        // Use `peek` to output log messages at various points.
        //
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
