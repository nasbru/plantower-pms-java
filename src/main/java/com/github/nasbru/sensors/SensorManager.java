package com.github.nasbru.sensors;

import com.github.nasbru.measurements.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages sensors in passive mode by polling their measurements.
 *
 * Collects measurements from all sensors using getMeasurements(), merges them
 * into a single array, and forwards to database and server.
 */
public class SensorManager {
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Logger LOGGER = LoggerFactory.getLogger(SensorManager.class);
	
	private final List<Sensor> sensors;
	private final ScheduledExecutorService scheduler;
	private final int periodSeconds;

	public SensorManager(List<Sensor> sensors, int periodSeconds) {
		this.sensors = sensors != null ? new ArrayList<>(sensors) : new ArrayList<>();
		this.periodSeconds = periodSeconds;
		this.scheduler = Executors.newScheduledThreadPool(1);
	}

	/**
	 * Starts periodic collection of sensor data.
	 */
	public void start() {
		LOGGER.info("Starting sensor data collection with period {} seconds", periodSeconds);
		scheduler.scheduleAtFixedRate(this::collectAndProcess, 0, periodSeconds, TimeUnit.SECONDS);
	}

	/**
	 * Stops periodic collection of sensor data.
	 */
	public void stop() {
		LOGGER.info("Stopping sensor data collection");
		scheduler.shutdown();
		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Collects measurements from all sensors, merges them, and forwards to database
	 * and server.
	 */
	private synchronized void collectAndProcess() {
		try {
			StringBuilder lineToLog = new StringBuilder();

			List<Measurement> allMeasurements = new ArrayList<>();

			// Collect measurements from each sensor
			for (Sensor sensor : sensors) {
				if (sensor == null) {
					LOGGER.warn("Encountered null sensor, skipping.");
					continue;
				}

				try {
					Measurement[] measurements = sensor.getMeasurements();
					if (measurements != null) {
						for (Measurement m : measurements) {
							allMeasurements.add(m);
						}
					} else {
						LOGGER.warn("Sensor '{}' returned null measurements.", sensor.getName());
					}
				} catch (Exception e) {
					LOGGER.error("Error reading measurements from sensor '{}'", sensor.getName(), e);
				}
			}

			if (allMeasurements.isEmpty()) {
				LOGGER.warn("No measurements collected from any sensor.");
				return;
			}

			// Convert to array and forward

			Measurement[] mergedData = allMeasurements.toArray(new Measurement[0]);
		} catch (Exception e) {
			LOGGER.error("Unexpected error during data collection cycle", e);
		}
	}

	public synchronized void addSensor(Sensor sensor) {
		if (sensor == null) {
			LOGGER.warn("Cannot add null sensor.");
			return;
		}
		sensors.add(sensor);
		LOGGER.info("Added sensor '{}'", sensor.getName());
	}

	public synchronized boolean removeSensor(Sensor sensor) {
		if (sensors.remove(sensor)) {
			LOGGER.info("Removed sensor '{}'", sensor.getName());
			return true;
		} else {
			LOGGER.warn("Sensor '{}' not found.", sensor.getName());
			return false;
		}
	}
}