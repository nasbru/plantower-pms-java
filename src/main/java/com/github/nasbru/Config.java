package com.github.nasbru;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Config {
	private static final Path DEFAULT_PATH = Paths.get("data/config.properties");
	private static final String SENSOR_PREFIX = "sensor.";
	private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
	private final Properties properties = new Properties();
	private final Path path;

	public Config(Path path) {
		this.path = path;
		load();
	}

	public Config() {
		this(DEFAULT_PATH);
	}

	private void load() {
		try (InputStream in = Files.newInputStream(path)) {
			properties.clear();
			properties.load(in);
		} catch (IOException e) {
			LOGGER.error("Error while reading config file.");
		}
	}

	public String getMqttBroker() {
		return properties.getProperty("mqtt.broker", "tcp://localhost:1883");
	}

	public String getMqttDiscoveryPrefix() {
		return properties.getProperty("mqtt.discovery_prefix");
	}
	/*
	 * public String getClientId(String sensorName) { return
	 * properties.getProperty("mqtt." + sensorName + ".clientId"); }
	 * 
	 * public String getBaseTopic(String sensorName) { return
	 * properties.getProperty("mqtt." + sensorName + ".baseTopic"); }
	 * 
	 * public String getNodeId(String sensorName) { return
	 * properties.getProperty("mqtt." + sensorName + ".node_id"); }
	 */

	public float getBme680TemperatureOffset() {
		return Float.parseFloat(properties.getProperty("bme680.temperature.offset", "0"));
	}

	public float getBme680HumidityOffset() {
		return Float.parseFloat(properties.getProperty("bme680.humidity.offset", "0"));
	}

	public float getBme680PressureOffset() {
		return Float.parseFloat(properties.getProperty("bme680.pressure.offset", "0"));
	}

	public String getPms7003Device() {
		return properties.getProperty("pms7003.device", "/dev/ttyS0");
	}
}
