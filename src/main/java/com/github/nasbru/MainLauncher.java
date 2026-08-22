package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.PMS7003;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class MainLauncher {
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);

	public static void main(String[] args) {
		String  serialAddress = args[0];
		Context pi4j = Pi4J.newAutoContext();
		LOGGER.info("After context");
		
		PMS7003 pms7003 = new PMS7003(pi4j, serialAddress);
		LOGGER.info("After sensor.");

		LOGGER.info("Initializing PMS7003 sensor.");
		pms7003.init();

		pms7003.passiveMode();
		LOGGER.info("Sensor set to passive mode.");
		
		/*
		LOGGER.info("Measuring...");
		int[] m = pms7003.tryGetMeasurements();
		LOGGER.info("Measurements: PM1.0: {}, PM2.5: {}, PM10: {}", m[0], m[1], m[2]);

		pms7003.sleep();
		LOGGER.info("Sensor set to sleep mode.");
		
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		LOGGER.info("Waking up sensor.");
		pms7003.wakeUp();
		
		
		m = pms7003.tryGetMeasurements();
		LOGGER.info("Measurements: PM1.0: {}, PM2.5: {}, PM10: {}", m[0], m[1], m[2]);
		*/
		while(true) {
			int[] m = pms7003.tryGetMeasurements();
			LOGGER.info("PM1.0: {}, PM2.5: {}, PM10: {}", m[0], m[1], m[2]);
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
