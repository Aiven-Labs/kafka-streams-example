package org.example;

import static org.junit.jupiter.api.Assertions.*;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;

import data.gen.avro.logistics;            // The class generated for our input messages
import data.gen.avro.logistics_delivered;  // The class generated for our output messages

class SetupMockSchemaRepository {
    static Path inputSchemaPath = Paths.get("src/main/avro/logistics_gen.avsc");
    static Path outputSchemaPath = Paths.get("src/main/avro/logistics_delivered.avsc");

    static void registerSchemas() {
        String inputSchema = "";
        String outputSchema = "";
        try {
            inputSchema = Files.readString(inputSchemaPath);
        } catch (IOException e) {
            System.out.println("Unable to read input schema " + e);
        }

        try {
            outputSchema = Files.readString(outputSchemaPath);
        } catch (IOException e) {
            System.out.println("Unable to read output schema " + e);
        }

        // AvroSchema implements ParsedSchema

        SchemaRegistryClient client = MockSchemaRegistry.getClientForScope("test_schema_registry");
        try {
            client.register("test_schema_registry", new AvroSchema(inputSchema));
        } catch (IOException | RestClientException e) {
            System.out.println("Unable to register input schema " + e);
        }
        try {
            client.register("test_schema_registry", new AvroSchema(outputSchema));
        } catch (IOException | RestClientException e) {
            System.out.println("Unable to register output schema " + e);
        }
    }
}

@ExtendWith(SystemStubsExtension.class)
class GenericFilterAppTests {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    static final String inputTopicName = "logistics_data_gen";
    static final String outputTopicName = "logistics_data_delivered";

    static TopologyTestDriver testDriver;

    static TestInputTopic<String, logistics> inputTopic;
    static TestOutputTopic<String, logistics_delivered> outputTopic;


    /** Set some standard test values as system properties */
    static void setProperties()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "dummy:1234");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "not used");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "not used");
        System.setProperty("PASSWORD_FOR_STORE",        "not used");
        System.setProperty("SCHEMA_REGISTRY_URL",       "mock://test_schema_registry");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "not used");
        System.setProperty("SCHEMA_REGISTRY_USERNAME",  "not used");
        System.setProperty("INPUT_TOPIC",  inputTopicName);        // the default
        System.setProperty("OUTPUT_TOPIC", outputTopicName);       // the default
    }

    @BeforeAll
    static void setup() {
        setProperties();

        Properties config = Config.getConfig();
        Map<String, String> serdeConfig = Config.getSerdeConfig(config);

        SetupMockSchemaRepository.registerSchemas();

        Topology topology = GenericFilterApp.buildTopology(config, serdeConfig);
        testDriver = new TopologyTestDriver(topology, config);

        SpecificAvroSerde<logistics> inputSerde = new SpecificAvroSerde<>();
        inputSerde.configure(serdeConfig, false);  // Just for the value, not the key

        SpecificAvroSerde<logistics_delivered> outputSerde = new SpecificAvroSerde<>();
        outputSerde.configure(serdeConfig, false); // just for the value

        inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), inputSerde.serializer());
        outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), outputSerde.deserializer());
    }

    @AfterAll
    static void tearDown() {
        testDriver.close();
    }

    @Test
    @DisplayName("Test a Delivered message goes through")
    void testDeliveredMessagePropagares()
    {
        var now = System.currentTimeMillis();
        logistics inputValue = logistics.newBuilder()
                .setState("Delivered")
                .setTimeUtc(now)
                .setTrackingId("TRACK-ABC123")
                .setCarrier("UPS")
                .setMessage("Hidden somewhere outside your house")
                .setManifest(List.of("Cosy jumper", "Wooly hat"))
                .setNextHopLocation("None")
                .build();

        System.out.println("Input value " + inputValue);

        inputTopic.pipeInput("key", inputValue);

        KeyValue<String, logistics_delivered> outputRecord = outputTopic.readKeyValue();

        assertNotNull(outputRecord);
        assertEquals("key", outputRecord.key);
        assertEquals(now, outputRecord.value.getTimeUtc());
        assertEquals("UPS", outputRecord.value.getCarrier());
        assertEquals(List.of("Cosy jumper", "Wooly hat"), outputRecord.value.getManifest());
        assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
        assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
        assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
    }
}
