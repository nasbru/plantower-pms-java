package com.github.nasbru;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainLauncher {

	private static final Logger LOG = LoggerFactory.getLogger(MainLauncher.class);

	public static void main(String[] args) {
		if (args.length < 2) {
			LOG.error("MainLauncher: usage: java ... <device address> <read interval ms>");
			LOG.error("MainLauncher: example: java ... /dev/ttyUSB0 2000");
			return;
		}

		String serialAddress = args[0];

		long readIntervalMs;

		try {
			readIntervalMs = Long.parseLong(args[1]);
		} catch (NumberFormatException e) {
			LOG.error("MainLauncher: invalid read interval: {}", args[1]);
			return;
		}

		if (readIntervalMs <= 0) {
			LOG.error("MainLauncher: read interval must be positive, got: {}", readIntervalMs);
			return;
		}

		try (PMSensor sensor = new PMSensor(serialAddress)) {
			LOG.info("MainLauncher: starting sensor on {}, read interval={} ms", serialAddress, readIntervalMs);

			sensor.init();

			if (!sensor.passiveMode()) {
				// A failed ACK does not mean the mode switch failed — the command is
				// idempotent and the ACK frame itself may have been corrupted on a noisy
				// line (especially on long wires). A valid measurement frame will prove
				// the mode anyway, so warn and continue instead of exiting.
				LOG.warn("MainLauncher: no valid passive-mode ACK after retries, continuing anyway"
						+ " (mode may already be passive)");
			}

			LOG.info("MainLauncher: entering measurement loop (Ctrl+C to stop)");

			while (true) {
				try {
					int[] measurements = sensor.getMeasurements();

					LOG.info("MainLauncher: pm1.0={} µg/m³, pm2.5={} µg/m³, pm10={} µg/m³",
							measurements[0], measurements[1], measurements[2]);

				} catch (PMSensor.MeasurementReadException e) {
					LOG.warn("MainLauncher: measurement read failed: {}", e.getMessage());
				}

				try {
					Thread.sleep(readIntervalMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					LOG.info("MainLauncher: interrupted, exiting loop");
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			LOG.error("MainLauncher: invalid sensor configuration: {}", e.getMessage());
		} catch (Exception e) {
			LOG.error("MainLauncher: sensor error: {}", e.getMessage());
		} finally {
			LOG.info("MainLauncher: finished");
		}
	}
}