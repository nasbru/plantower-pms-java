package com.github.nasbru;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.BME680Reader;

public class MainLauncher {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);
	
	public static void main(String[] args) {
		
		Config config = new Config();
		String mqttBroker = config.getMqttBroker();
		String sensorName = "bme680";
		String mqttClientId = config.getClientId(sensorName);
		String mqttBaseTopic = config.getBaseTopic(sensorName);
		
		BME680Reader reader = new BME680Reader(30, config);
		
		SensorDataHandler sdh = null;
		int maxAttempts = 5;
		int attempt = 0;
		long retryDelayMs = 5000;

		while (attempt < maxAttempts) {
		    attempt++;
		    try {
		        sdh = new SensorDataHandler(mqttBroker, mqttClientId, mqttBaseTopic, reader);
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
		    String discoveryPrefix = config.getMqttDiscoveryPrefix();
		    String nodeId = config.getNodeId(sensorName);
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
