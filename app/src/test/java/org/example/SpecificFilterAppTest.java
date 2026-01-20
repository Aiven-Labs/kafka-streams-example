package org.example;

import data.gen.avro.logistics;
import data.gen.avro.logistics_delivered;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SystemStubsExtension.class)
class SpecificFilterAppTests {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    static TopologyTestDriver testDriver;

    static TestInputTopic<String, logistics> inputTopic;
    static TestOutputTopic<String, logistics_delivered> outputTopic;

    static final String inputTopicName = "logistics_data_gen";
    static final String outputTopicName = "logistics_data_delivered";

    @BeforeAll
    static void setup() {
        SetupProperties.setProperties(inputTopicName, outputTopicName);

        Properties config = Config.getConfig("logistics_data_gen", "logistics_data_delivered");
        Map<String, String> serdeConfig = Config.getSerdeConfig(config);

        SetupMockSchemaRepository.registerSchemas();

        Topology topology = SpecificFilterApp.buildTopology(config, serdeConfig);
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
        if (testDriver != null) {
            testDriver.close();
        }
    }

    @Test
    @DisplayName("Test a Delivered message goes through")
    void testDeliveredMessagePropagates() {
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

        KeyValue<String, logistics_delivered> outputRecord = outputTopic.readKeyValue();

        assertNotNull(outputRecord);
        assertEquals("key", outputRecord.key);
        assertEquals(now, outputRecord.value.getTimeUtc());
        assertEquals("UPS", outputRecord.value.getCarrier());
        assertEquals(List.of("Cosy jumper", "Wooly hat"), outputRecord.value.getManifest());
        assertEquals("TRACK-ABC123", outputRecord.value.getTrackingId());
    }

    @Test
    @DisplayName("Test a Processing message does not go through")
    void testProcessingMessageDoesNotPropagate() {
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

        assertTrue(outputTopic.isEmpty(), "Processing message does not come through");
    }
}