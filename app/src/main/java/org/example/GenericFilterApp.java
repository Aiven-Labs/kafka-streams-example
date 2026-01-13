package org.example;

import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;

// The input Avro messages follow a schema called `logistics`, which doesn't
// follow Java's capitalisation rules for classes. Regardless, we'll name
// the output schema `logistics_delivered` for consistency.
import data.gen.avro.logistics_delivered;  // The class generated for our output messages


/**
 * This app is an example of using Kafka Streams to filter a Logistics stream
 *
 * - Messages are Avro, as produced by the Aiven for Apache Kafka sample stream generator for Logistics.
 * - We ignore any messages where `state` is not `Delivered`.
 * - We only pass on some values, and we rename a couple.
 * - Since we use the GenericAvroSerde, we only an explicit schema for the target   messages.
 */
public class GenericFilterApp {
    private static final Logger log = LoggerFactory.getLogger(GenericFilterApp.class);

    // Define the state we are filtering on
    private static final String KEEP_STATE = "Delivered";

    // We want to expose the topology for use in our unit tests
    Topology topology = null;

    public Topology getTopology()
    {
        return topology;
    }

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

        // We're using a generic Serde for the input message values, and the library code
        // will download the schema from the schema repository at runtime, using the schema ID
        // at the start of each (Confluent-style) Avro message.
        // NOTE that I'm assuming that this is cached, and it doesn't retrieve the schema for
        // each and every message.
        final Serde<GenericRecord> inputMessageSerde = new GenericAvroSerde();
        inputMessageSerde.configure(serdeConfig, false); // `false` means it's a value Serde

        // Since the output messages have a different schema, we need a specific Serde for them
        // We let that get built from the .avsc file in the normal manner, so we've got a Java class to hand
        final SpecificAvroSerde<logistics_delivered> outputMessageSerde = new SpecificAvroSerde<>();
        outputMessageSerde.configure(serdeConfig, false); // `false` means it's a value Serde

        final StreamsBuilder builder = new StreamsBuilder();

        // Our source stream is read from the input topic using the (input) generic Avro serde
        KStream<String, GenericRecord> sourceStream = builder.stream(
                config.get("input.topic.name").toString(),
                Consumed.with(Serdes.String(), inputMessageSerde)
        );

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
                .peek( (String key, GenericRecord inputValue) -> log.info("LOOKING AT: Value='{}'", inputValue) )
                .filter( (String key, GenericRecord inputValue) -> inputValue.get("state").toString().equals(KEEP_STATE) )
                .peek( ( String key, GenericRecord inputValue) -> log.info("KEEPING: Value='{}'", inputValue) )
                .mapValues( (GenericRecord inputValue) -> {
                            // Only propagate some values.
                            // We don't bother with "state", since we already know it's "Delivered".
                            // We change the names "time_utc" to "timeUtc" and "tracking_id" to "trackingId"
                            // (although you can't tell that from the Java code, only from the output schema).
                            // If values are null or not of a type we expect, ignore them.
                            // For strings, beware that they're actually a Utf8 class.
                            logistics_delivered outputValue = new logistics_delivered();
                            var timeUtc = inputValue.get("time_utc");
                            log.info("Read timeUtc '{}'", timeUtc);
                            if (timeUtc instanceof Number) {
                                outputValue.setTimeUtc((Long) timeUtc);
                            }
                            var trackingId = inputValue.get("tracking_id");
                            log.info("Read trackingId {} '{}'", trackingId.getClass().toString(), trackingId);
                            if (trackingId instanceof org.apache.avro.util.Utf8) {
                                outputValue.setTrackingId(trackingId.toString());
                            }
                            var carrier = inputValue.get("carrier");
                            log.info("Read carrier '{}'", carrier);
                            if (carrier instanceof org.apache.avro.util.Utf8) {
                                outputValue.setCarrier(carrier.toString());
                            }
                            var manifest = inputValue.get("manifest");
                            log.info("Read manifest '{}'", manifest);
                            if (manifest instanceof List) {
                                List manifestList = (List) manifest;
                                /*
                                If I just call `outputValue.setManifest((List<String>) manifestList)` then it
                                reasonably enough warns me that its an "[unchecked] unchecked cast" because it
                                only knows that the value is a List, not a List<String>.

                                I'd hoped to be able to do:

                                List<String> strings = manifestList.stream()
                                       .map(String::valueOf)
                                       .toList();
                                 but that just gives me a scarier warning

                                   warning: [unchecked] unchecked call to <R>map(Function<? super T,? extends R>) as a member of the raw type Stream
                                            .map(String::valueOf)
                                                ^
                                   where R,T are type-variables:
                                     R extends Object declared in method <R>map(Function<? super T,? extends R>)
                                     T extends Object declared in interface Stream

                                 Doing the conversion by hand as follows works and keeps the compiler happy
                                 */
                                List<String> strings = new ArrayList<>();
                                for (Object obj : manifestList)
                                {
                                    try {
                                        strings.add(obj.toString());
                                    } catch (ClassCastException e) {
                                        ; // ignore the element
                                    }
                                }
                                outputValue.setManifest(strings);
                            }
                            return outputValue;
                        }
                )
                .peek( (String key, logistics_delivered outputValue) -> log.info("SENDING: Value='{}'", outputValue) )
                .to(
                        config.get("output.topic.name").toString(),
                        Produced.with(Serdes.String(), outputMessageSerde)
                );

        topology = builder.build();
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
