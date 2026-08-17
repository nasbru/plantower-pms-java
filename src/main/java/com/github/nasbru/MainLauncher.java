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
		int interval = Integer.parseInt(config.getAppInterval());
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
	    
	    pms7003.start();
	    
	    
	 // Keep application running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down...");
            bme680.stopReading();
            pms7003.stop();
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
