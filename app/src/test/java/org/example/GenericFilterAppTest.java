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
import org.example.GenericFilterApp;

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
class GenericFilterAppTest {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    @Test
    @DisplayName("Playing with GenericFilterApp giving an exception")
    void testGenericFilterAppException()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "mock:1234");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "not used");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "not used");
        System.setProperty("PASSWORD_FOR_STORE",        "not used");
        System.setProperty("SCHEMA_REGISTRY_URL",       "mock:test_schema_registry");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "not used");
        System.setProperty("SCHEMA_REGISTRY_USERNAME",  "not used");
        System.setProperty("INPUT_TOPIC",  "logistics_data_gen");        // the default
        System.setProperty("OUTPUT_TOPIC", "logistics_data_delivered");  // the default

        String[] arguments = new String[] {};

        Exception exception = assertThrows(RuntimeException.class, () ->{
            GenericFilterApp.main(arguments);
        });

    }

    static final String inputTopicName = "logistics_data_gen";
    static final String outputTopicName = "logistics_data_delivered";

    /** Set some standard test values as system properties */
    static void setProperties()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "dummy:1234");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "not used");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "not used");
        System.setProperty("PASSWORD_FOR_STORE",        "not used");
        System.setProperty("SCHEMA_REGISTRY_URL",       "mock:test_schema_registry");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "not used");
        System.setProperty("SCHEMA_REGISTRY_USERNAME",  "not used");
        System.setProperty("INPUT_TOPIC",  inputTopicName);        // the default
        System.setProperty("OUTPUT_TOPIC", outputTopicName);       // the default
    }

    @Test
    @DisplayName("Playing with GenericFilterApp")
    void testGenericFilterApp()
    {
        setProperties();

        Properties config = Config.getConfig();
        Map<String, String> serdeConfig = Config.getSerdeConfig(config);

        SetupMockSchemaRepository.registerSchemas();

        //String[] arguments = new String[] {};
        //GenericFilterApp.main(arguments);

        Topology topology = GenericFilterApp.buildTopology(config, serdeConfig);
        var testDriver = new TopologyTestDriver(topology, config);

        SpecificAvroSerde<logistics> inputSerde = new SpecificAvroSerde<>();
        inputSerde.configure(serdeConfig, false);  // Just for the value, not the key

        SpecificAvroSerde<logistics_delivered> outputSerde = new SpecificAvroSerde<>();
        outputSerde.configure(serdeConfig, false); // just for the value

        TestInputTopic<String, logistics> inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), inputSerde.serializer());
        TestOutputTopic<String, logistics_delivered> outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), outputSerde.deserializer());

        logistics inputValue = logistics.newBuilder()
                .setState("Delivered")
                .setTimeUtc(System.currentTimeMillis())
                .setTrackingId("TRACK-ABC123")
                .setCarrier("UPS")
                .setMessage("Hidden somewhere outside your house")
                .setManifest(List.of("Cosy jumper", "Wooly hat"))
                .setNextHopLocation("None")
                .build();

        inputTopic.pipeInput("key", inputValue);

        KeyValue<String, logistics_delivered> outputRecord = outputTopic.readKeyValue();

        assertNotNull(outputRecord);
        assertEquals("order-123", outputRecord.key);
        assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());


        testDriver.close();
    }





    @Test
    @DisplayName("Playing with the Config")
    void testBrokenConfigInMain()
    {
        System.setProperty("KAFKA_SERVICE_URI",         "ignore me");
        System.setProperty("SSL_TRUSTSTORE_LOCATION",   "ignore me");
        System.setProperty("SSL_KEYSTORE_LOCATION",     "ignore me");
        System.setProperty("PASSWORD_FOR_STORE",        "ignore me");
        System.setProperty("SCHEMA_REGISTRY_URL",       "ignore me");
        System.setProperty("SCHEMA_REGISTRY_PASSWORD",  "ignore me");
        System.setProperty("SCHEMA_REGISTRY_USERNAME", "");     // use the default
        System.setProperty("INPUT_TOPIC", "");                  // use the default
        System.setProperty("OUTPUT_TOPIC", "");                 // use the default

        String[] arguments = new String[] {};

        Exception exception = assertThrows(RuntimeException.class, () ->{
            GenericFilterApp.main(arguments);
        });


        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains("Failed to create new KafkaAdminClient"));
        //assertEquals(actualMessage, expectedMessage);
    }
}
