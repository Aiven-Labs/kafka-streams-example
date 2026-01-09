package org.example;

import static org.junit.jupiter.api.Assertions.*;

import org.example.GenericFilterApp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;


@ExtendWith(SystemStubsExtension.class)
class GenericFilterAppTest {

    // After we've specified this, each test should unset any System property changes when it ends
    @SystemStub
    private SystemProperties systemProperties;

    @Test
    @DisplayName("Playing with GenericFilterApp")
    void testGenericFilterApp()
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


        String expectedMessage = "I don't know what to expect"; // It's actually going to be "Failed to create new KafkaAdminClient"
        String actualMessage = exception.getMessage();

        //assertTrue(actualMessage.contains(expectedMessage));
        assertEquals(actualMessage, expectedMessage);
    }

	@Test
	@DisplayName("1 + 1 = 2 (good)")
	void additionGood() {
		assertEquals(2, 1 + 1);
	}

	@Test
	@DisplayName("1 + 2 = 2 (bad)")
	void additionBad() {
		assertEquals(2, 1 + 2);
	}

}
