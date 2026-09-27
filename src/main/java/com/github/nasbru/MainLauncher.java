package com.github.nasbru;

public class MainLauncher {

	private static final long READ_INTERVAL_MS = 2000;

	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("MainLauncher: usage: java ... <device address> <read interval ms>");
			System.out.println("MainLauncher: example: java ... /dev/ttyUSB0 2000");
			return;
		}

		String serialAddress = args[0];

		long readIntervalMs;

		try {
			readIntervalMs = Long.parseLong(args[1]);
		} catch (NumberFormatException e) {
			System.out.println("MainLauncher: invalid read interval: " + args[1]);
			return;
		}

		if (readIntervalMs <= 0) {
			System.out.println("MainLauncher: read interval must be positive, got: " + readIntervalMs);
			return;
		}

		try (PMSensor sensor = new PMSensor(serialAddress)) {
			System.out.println("MainLauncher: starting sensor on " + serialAddress
					+ ", read interval=" + readIntervalMs + " ms");

			sensor.init();

			if (!sensor.passiveMode()) {
				// A failed ACK does not mean the mode switch failed — the command is
				// idempotent and the ACK frame itself may have been corrupted on a noisy
				// line (especially on long wires). A valid measurement frame will prove
				// the mode anyway, so warn and continue instead of exiting.
				System.out.println("MainLauncher: WARNING - no valid passive-mode ACK after retries,"
						+ " continuing anyway (mode may already be passive)");
			}

			System.out.println("MainLauncher: entering measurement loop (Ctrl+C to stop)");

			while (true) {
				try {
					int[] measurements = sensor.getMeasurements();

					System.out.println("MainLauncher: pm1.0=" + measurements[0] + " µg/m³, pm2.5=" + measurements[1]
							+ " µg/m³, pm10=" + measurements[2] + " µg/m³");

				} catch (PMSensor.MeasurementReadException e) {
					System.out.println("MainLauncher: measurement read failed: " + e.getMessage());
				}

				try {
					Thread.sleep(readIntervalMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.out.println("MainLauncher: interrupted, exiting loop");
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			System.out.println("MainLauncher: invalid sensor configuration: " + e.getMessage());
		} catch (Exception e) {
			System.out.println("MainLauncher: sensor error: " + e.getMessage());
		} finally {
			System.out.println("MainLauncher: finished");
		}
	}
}
