package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.sensors.PMSensor;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class MainLauncher {
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);

	public static void main(String[] args) {
		String  serialAddress = args.length > 0 ? args[0] : "/dev/ttyS0";
		Context pi4j = Pi4J.newAutoContext();
		
		PMSensor pms7003 = new PMSensor(pi4j, serialAddress);

		LOGGER.info("Initializing PMS7003 sensor.");
		pms7003.init();

		pms7003.passiveMode();
		LOGGER.info("Sensor set to passive mode.");
		
		while(true) {
			int[] m = pms7003.getMeasurements();
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
