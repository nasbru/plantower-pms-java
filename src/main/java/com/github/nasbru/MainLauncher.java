package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.measurements.Measurement;
import com.github.nasbru.sensors.PMS7003;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class PMS7003Launcher {
	private static final Logger LOGGER = LoggerFactory.getLogger(MainLauncher.class);

	public static void main(String[] args) {

		Context pi4j = Pi4J.newAutoContext();

		PMS7003 pms7003 = new PMS7003(30, pi4j, "/dev/ttyUSB0");

		Measurement[] m = pms7003.getMeasurements();
		for (Measurement measurement : m) {
			LOGGER.info(measurement.toString());
		}
	}
}
