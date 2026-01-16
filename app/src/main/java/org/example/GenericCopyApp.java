package org.example;

import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;


/**
 * This app is an example of using Kafka Streams to copy a stream
 *
 * - Messages are Avro
 * - We copy all values
 * - Since we use the GenericAvroSerde, we don't need an explicit schema
 */
public class GenericCopyApp {
    private static final Logger log = LoggerFactory.getLogger(GenericCopyApp.class);

    public static Topology buildTopology(Properties config, Map<String, String> serdeConfig)
    {
        // We're using a generic Serde for the input message values, and the library code
        // will download the schema from the schema repository at runtime, using the schema ID
        // at the start of each (Confluent-style) Avro message.
        // NOTE that I'm assuming that this is cached, and it doesn't retrieve the schema for
        // each and every message.
        // Since we're copying the messages, we can use the same Serde to write to the output topic.
        // (If we were *just* copying messages, then we could just copy the bytes, but then we
        // wouldn't be able to log what we're seeing.)
        final Serde<GenericRecord> inputMessageSerde = new GenericAvroSerde();
        inputMessageSerde.configure(serdeConfig, false); // `false` means it's a value Serde

        final StreamsBuilder builder = new StreamsBuilder();

        // Our source stream is read from the input topic using the (input) generic Avro serde
        KStream<String, GenericRecord> sourceStream = builder.stream(
                config.get("input.topic.name").toString(),
                Consumed.with(Serdes.String(), inputMessageSerde)
        );

        sourceStream
                .peek( (String key, GenericRecord inputValue) -> log.info("COPYING: Value='{}'", inputValue) )
                .to(
                        config.get("output.topic.name").toString(),
                        Produced.with(Serdes.String(), inputMessageSerde)
                );

        // Create and return the Topology for that transformation
        return builder.build();
    }

    public static void main(String[] args) {
        Properties config = Config.getConfig();
        final Map<String, String> serdeConfig = Config.getSerdeConfig(config);

        Topology topology = buildTopology(config, serdeConfig);
        System.out.println(topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, config);

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
