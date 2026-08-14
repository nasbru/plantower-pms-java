package com.github.nasbru;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.BME680Reader;

public class MainLauncher {
	
	private static final Path CONFIG_FILE = Paths.get("data", "config.properties");
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);
	
	public static void main(String[] args) {
		
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
			props.clear();
			props.load(in);
		} catch (IOException e) {
			LOGGER.error("Error during reading config file. ", e.getMessage());
		}
		String mqttBroker = props.getProperty("mqtt.broker", "tcp://localhost:1883");
		String mqttClientId = props.getProperty("mqtt.clientId", "bme680_publisher_1");
		String mqttBaseTopic = props.getProperty("mqtt.baseTopic", "home/bme680_1");
		
		float tempOffset = Float.parseFloat(props.getProperty("bme680.temperature.offset", "0.0"));
		float humidOffset = Float.parseFloat(props.getProperty("bme680.humidity.offset", "0.0"));
		float pressOffset = Float.parseFloat(props.getProperty("bme680.pressure.offset", "0.0"));
		
		BME680Reader reader = new BME680Reader(30, tempOffset, humidOffset, pressOffset);
		
		SensorDataHandler sdh = null;
		int maxAttempts = 5;
		int attempt = 0;
		long retryDelayMs = 5000;

		while (attempt < maxAttempts) {
		    attempt++;
		    try {
		        sdh = new SensorDataHandler(mqttBroker, mqttClientId, mqttBaseTopic);
		        break; // attempt successful
		    } catch (MqttException e) {
		        LOGGER.error("MQTT connect failed (attempt " + attempt + "): ", e.getMessage());
		        if (attempt >= maxAttempts) {
		            LOGGER.error("Max attempts reached. Exiting.");
		            System.exit(1);
		        }
		        try {
		            Thread.sleep(retryDelayMs);
		        } catch (InterruptedException ie) {
		            Thread.currentThread().interrupt();
		            break;
		        }
		    }
		}

		if (sdh != null) {
		    String discoveryPrefix = props.getProperty("mqtt.discovery_prefix", "homeassistant");
		    String nodeId = props.getProperty("mqtt.node_id", "bme680_1");
		    sdh.setDiscoveryConfig(discoveryPrefix, nodeId);
		    
		    try {
				sdh.publishDiscovery(discoveryPrefix, nodeId);
			} catch (MqttException e) {
				LOGGER.warn("Failed to publish discovery on startup", e);
			}
			
		    reader.addListener(sdh);
		    Thread thread = new Thread(reader);
		    thread.start();
		}
		
		while(true) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
