package net.hardcoretogether.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTextTest {
	private static JsonObject defaults() {
		JsonObject json = new JsonObject();
		HtConfig.addMissingDefaults(json);
		return json;
	}

	@Test
	void everyDefaultOptionIsDocumented() {
		JsonObject json = defaults();
		assertEquals(ConfigText.OPTIONS.keySet(), json.keySet());
		String text = ConfigText.render(json);
		for (String key : json.keySet()) {
			assertTrue(text.contains("\"" + key + "\""), key);
		}
		assertTrue(text.startsWith("// "));
	}

	@Test
	void renderedFileReadsBackUnchanged() {
		JsonObject json = defaults();
		json.addProperty("old_option", 5);
		JsonObject read = JsonParser.parseString(ConfigText.render(json)).getAsJsonObject();
		assertEquals(json, read);
	}

	@Test
	void readingTheRenderedFileAddsNothing() {
		JsonObject read = JsonParser.parseString(ConfigText.render(defaults())).getAsJsonObject();
		assertTrue(HtConfig.addMissingDefaults(read).isEmpty());
	}

	@Test
	void defaultsAreHardTenSecondsAndNoTestDeaths() {
		JsonObject json = defaults();
		assertEquals("hard", json.get("difficulty").getAsString());
		assertEquals(10, json.get("countdown_seconds").getAsInt());
		assertFalse(json.get("allow_test_deaths").getAsBoolean());
	}
}
