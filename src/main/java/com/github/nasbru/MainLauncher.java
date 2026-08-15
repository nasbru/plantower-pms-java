package com.github.nasbru;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.BME680Reader;
import com.github.nasbru.sensors.PMS7003;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class MainLauncher {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);
	
	public static void main(String[] args) {
		
		Context pi4j = Pi4J.newAutoContext();
		
		Config config = new Config();
		int interval = 30;
		String pms7003Device = config.getPms7003Device();
		
		BME680Reader bme680 = new BME680Reader(interval, config);
		PMS7003 pms7003 = new PMS7003(interval, pi4j, pms7003Device);
		
		String bme680Name = bme680.getName();
		String pms7003Name = pms7003.getName();
		String broker = config.getMqttBroker();
		String prefix = config.getMqttDiscoveryPrefix();
		
		SensorDataHandler bme680DataHandler = new SensorDataHandler(bme680Name);
		SensorDataHandler pms7003DataHandler = new SensorDataHandler(pms7003Name);
		
		bme680DataHandler.setMqttConfig(broker, prefix);
		pms7003DataHandler.setMqttConfig(broker, prefix);
		try {
			bme680DataHandler.initMqtt();
			pms7003DataHandler.initMqtt();
		} catch (MqttException e) {
			LOGGER.error("MQTT initialization error");
		}
		
		bme680.addListener(bme680DataHandler);
		pms7003.addListener(pms7003DataHandler);
		
	    Thread bme680Thread = new Thread(bme680);
	    bme680Thread.start();
	    Thread pms7003Thread = new Thread(pms7003);
	    pms7003Thread.start();
	    
	    
	    while(true) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		/*
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
		} */
	}
}
