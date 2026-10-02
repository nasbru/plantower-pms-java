package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLauncher {

	private static final Logger LOG = LoggerFactory.getLogger(TestLauncher.class);

	public static void main(String[] args) {
		if (args.length < 2) {
			LOG.error("Usage: java ... <device address> <read interval ms>");
			LOG.error("Example: java ... /dev/ttyUSB0 2000");
			return;
		}

		String serialAddress = args[0];

		long readIntervalMs;

		try {
			readIntervalMs = Long.parseLong(args[1]);
		} catch (NumberFormatException e) {
			LOG.error("Invalid read interval: {}", args[1]);
			return;
		}

		if (readIntervalMs <= 0) {
			LOG.error("Read interval must be positive, got: {}", readIntervalMs);
			return;
		}

		try (PMSensor sensor = new PMSensor(serialAddress)) {
			LOG.info("Starting sensor on {}, read interval={} ms", serialAddress, readIntervalMs);

			sensor.init();

			if (!sensor.passiveMode()) {
				// A failed ACK does not mean the mode switch failed — the command is
				// idempotent and the ACK frame itself may have been corrupted on a noisy
				// line (especially on long wires). A valid measurement frame will prove
				// the mode anyway, so warn and continue instead of exiting.
				LOG.warn("No valid passive-mode ACK after retries, continuing anyway"
						+ " (mode may already be passive)");
			}

			LOG.info("Entering measurement loop (Ctrl+C to stop)");

			while (true) {
				try {
					int[] measurements = sensor.getMeasurements();

					LOG.info("pm1.0={} µg/m³, pm2.5={} µg/m³, pm10={} µg/m³",
							measurements[0], measurements[1], measurements[2]);

				} catch (PMSensor.MeasurementReadException e) {
					LOG.warn("Measurement read failed: {}", e.getMessage());
				}

				try {
					Thread.sleep(readIntervalMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					LOG.info("Interrupted, exiting loop");
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			LOG.error("Invalid sensor configuration: {}", e.getMessage());
		} catch (Exception e) {
			LOG.error("Sensor error: {}", e.getMessage());
		} finally {
			LOG.info("Finished");
		}
	}
}