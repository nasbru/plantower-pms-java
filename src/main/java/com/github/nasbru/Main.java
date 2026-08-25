package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class Main {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		String serialAddress = args.length > 0 ? args[0] : "/dev/ttyS0";
		int interval = Integer.parseInt(args[1]);

		Context pi4j = Pi4J.newAutoContext();

		PMSensor PmSensor = new PMSensor(pi4j, serialAddress);

		PmSensor.init();
		PmSensor.passiveMode();

		while (true) {
			int[] m = PmSensor.getMeasurements();
			LOGGER.info("PM1.0: {}, PM2.5: {}, PM10: {}", m[0], m[1], m[2]);

			try {
				Thread.sleep(interval * 1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}
}
