package com.github.nasbru;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.BME680Reader;
import com.github.nasbru.sensors.PMS7003;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class MainLauncher {
	private static BME680Reader bme680 = null;
	private static PMS7003 pms7003 = null;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);
	
	public static void main(String[] args) {
		
		
		Context pi4j = Pi4J.newAutoContext();
		Config config = new Config();
		int interval = Integer.parseInt(config.getAppInterval());
		boolean bme680Enabled = config.isBme680Enabled();
		boolean pms7003Enabled = config.isPms7003Enabled();
		
		if(!bme680Enabled && !pms7003Enabled) {
			LOGGER.error("No sensors enabled in config file. Exiting.");
			return;
		}
		
		String broker = config.getMqttBroker();
		String prefix = config.getMqttDiscoveryPrefix();
		
		if(bme680Enabled) {
			bme680 = new BME680Reader(interval, config);
			String bme680Name = bme680.getName();
			SensorDataHandler bme680DataHandler = new SensorDataHandler(bme680Name);
			bme680DataHandler.setMqttConfig(broker, prefix);
			try {
				bme680DataHandler.initMqtt();
			} catch (MqttException e) {
				LOGGER.error("Error initializing MQTT for BME680: " + e.getMessage(), e);
				return;
			}
			bme680.addListener(bme680DataHandler);
			Thread bme680Thread = new Thread(bme680);
		    bme680Thread.start();
		}
		if(pms7003Enabled) {
			String pms7003Device = config.getPms7003Device();
			pms7003 = new PMS7003(interval, pi4j, pms7003Device);
			String pms7003Name = pms7003.getName();
			SensorDataHandler pms7003DataHandler = new SensorDataHandler(pms7003Name);
			pms7003DataHandler.setMqttConfig(broker, prefix);
			try {
				pms7003DataHandler.initMqtt();
			} catch (MqttException e) {
				LOGGER.error("Error initializing MQTT for PMS7003: " + e.getMessage(), e);
				return;
			}
			pms7003.addListener(pms7003DataHandler);
			pms7003.start();
		}
	    
	 // Keep application running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down...");
            if(bme680 != null) bme680.stopReading();
            if(pms7003 != null) pms7003.stop();
            pi4j.shutdown();
        }));

        // Wait indefinitely
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOGGER.error("Main thread interrupted", e);
            Thread.currentThread().interrupt();
        }
	}
}
