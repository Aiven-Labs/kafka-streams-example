package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.example.GenericFilterApp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class GenericFilterAppTest {

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
