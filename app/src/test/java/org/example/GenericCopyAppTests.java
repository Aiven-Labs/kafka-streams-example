package org.example;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import data.gen.avro.logistics;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SystemStubsExtension.class)
class GenericCopyAppTests {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    static TopologyTestDriver testDriver;

    @Nested
    @DisplayName("Copying logistics data")
    class InputIsLogisticsData {

        static TestInputTopic<String, logistics> inputTopic;
        static TestOutputTopic<String, logistics> outputTopic;

        static final String inputTopicName = "logistics_data_gen";
        static final String outputTopicName = "logistics_data_copied";

        @BeforeAll
        static void setup() {
            SetupProperties.setProperties(inputTopicName, outputTopicName);

            Properties config = Config.getConfig("logistics_data_gen", "logistics_data_copied");
            Map<String, String> serdeConfig = Config.getSerdeConfig(config);

            SetupMockSchemaRepository.registerSchemas();

            Topology topology = GenericCopyApp.buildTopology(config, serdeConfig);
            testDriver = new TopologyTestDriver(topology, config);

            SpecificAvroSerde<logistics> valueSerde = new SpecificAvroSerde<>();
            valueSerde.configure(serdeConfig, false);  // Just for the value, not the key

            inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), valueSerde.serializer());
            outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), valueSerde.deserializer());
        }

        @AfterAll
        static void tearDown() {
            if (testDriver != null) {
                testDriver.close();
            }
        }
        @Test
        @DisplayName("Test a Delivered message is copied")
        void testDeliveredMessageIsCopied() {
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

            inputTopic.pipeInput("key", inputValue);

            KeyValue<String, logistics> outputRecord = outputTopic.readKeyValue();

            assertNotNull(outputRecord);
            assertEquals("key", outputRecord.key);
            assertEquals("Delivered", outputRecord.value.getState());
            assertEquals(now, outputRecord.value.getTimeUtc());
            assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
            assertEquals("UPS", outputRecord.value.getCarrier());
            assertEquals(List.of("Cosy jumper", "Wooly hat"), outputRecord.value.getManifest());
            assertEquals("Hidden somewhere outside your house", outputRecord.value.getMessage());
            assertEquals("None", outputRecord.value.getNextHopLocation());
        }

        @Test
        @DisplayName("Test a Processing message is copied")
        void testProcessingMessageIsCopied() {
            var now = System.currentTimeMillis();
            logistics inputValue = logistics.newBuilder()
                    .setState("Processing")
                    .setTimeUtc(now)
                    .setTrackingId("TRACK-ABC123")
                    .setCarrier("UPS")
                    .setMessage("Hidden somewhere outside your house")
                    .setManifest(List.of("Cosy jumper", "Wooly hat"))
                    .setNextHopLocation("None")
                    .build();

            inputTopic.pipeInput("key", inputValue);

            KeyValue<String, logistics> outputRecord = outputTopic.readKeyValue();

            assertNotNull(outputRecord);
            assertEquals("key", outputRecord.key);
            assertEquals(inputValue, outputRecord.value);
            assertEquals("Processing", outputRecord.value.getState());
            assertEquals(now, outputRecord.value.getTimeUtc());
            assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
            assertEquals("UPS", outputRecord.value.getCarrier());
            assertEquals(List.of("Cosy jumper", "Wooly hat"), outputRecord.value.getManifest());
            assertEquals("Hidden somewhere outside your house", outputRecord.value.getMessage());
            assertEquals("None", outputRecord.value.getNextHopLocation());
        }
    }

    @Nested
    @DisplayName("Copying simpler data")
    class InputIsSimplerData {

        static TestInputTopic<String, GenericRecord> inputTopic;
        static TestOutputTopic<String, GenericRecord> outputTopic;

        static final String inputTopicName = "logistics_data_gen";
        static final String outputTopicName = "logistics_data_copied";

        static AvroSchema valueSchema;

        static void registerSchemas() {
            Schema.Parser parser = new Schema.Parser();
            valueSchema = new AvroSchema("""
                    {
                      "namespace": "data.gen.avro",
                      "name": "partialLogistics",
                      "type": "record",
                      "fields": [ { "name": "state", "type": { "type": "string" } } ]
                    }
                    """);

            SchemaRegistryClient client = MockSchemaRegistry.getClientForScope("test_schema_registry");
            try {
                client.register("test_schema_registry", valueSchema);
            } catch (IOException | RestClientException e) {
                System.out.println("Unable to register input schema " + e);
            }
        }

        @BeforeAll
        static void setup() {
            SetupProperties.setProperties(inputTopicName, outputTopicName);

            Properties config = Config.getConfig("logistics_data_gen", "logistics_data_copied");
            Map<String, String> serdeConfig = Config.getSerdeConfig(config);

            registerSchemas();

            Topology topology = GenericCopyApp.buildTopology(config, serdeConfig);
            testDriver = new TopologyTestDriver(topology, config);

            GenericAvroSerde valueSerde = new GenericAvroSerde();
            valueSerde.configure(serdeConfig, false);  // Just for the value, not the key

            inputTopic = testDriver.createInputTopic(inputTopicName, Serdes.String().serializer(), valueSerde.serializer());
            outputTopic = testDriver.createOutputTopic(outputTopicName, Serdes.String().deserializer(), valueSerde.deserializer());
        }

        @AfterAll
        static void tearDown() {
            if (testDriver != null) {
                testDriver.close();
            }
        }
        @Test
        @DisplayName("Test a message is copied")
        void testMessageIsCopied() {
            var now = System.currentTimeMillis();
            GenericRecord inputValue = new GenericData.Record(valueSchema.rawSchema());
            inputValue.put("state", "Delaware");

            inputTopic.pipeInput("key", inputValue);

            KeyValue outputRecord = outputTopic.readKeyValue();

            System.out.println("outputRecord " + outputRecord);
            System.out.println("outputRecord.value " + outputRecord.value);

            assertNotNull(outputRecord);
            assertEquals("key", outputRecord.key);
            assertEquals("{\"state\": \"Delaware\"}", outputRecord.value.toString());
        }
    }
}