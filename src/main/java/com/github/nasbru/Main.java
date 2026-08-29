package com.github.nasbru;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		String serialAddress = args.length > 0 ? args[0] : "/dev/ttyS0";
		int intervalSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

		try (PMSensor sensor = new PMSensor(serialAddress)) {

			sensor.init();
			sensor.passiveMode();

			ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
			CountDownLatch stopLatch = new CountDownLatch(1);

			Runnable pollTask = () -> {
				try {
					int[] m = sensor.getMeasurements();
					LOGGER.info("PM1.0: {}, PM2.5: {}, PM10: {}", m[0], m[1], m[2]);
				} catch (Exception e) {
					LOGGER.error("Error reading PM sensor", e);
				}
			};

			scheduler.scheduleAtFixedRate(pollTask, 0, intervalSeconds, TimeUnit.SECONDS);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				LOGGER.info("Shutdown requested, stopping scheduler and Pi4J...");
				scheduler.shutdown();
				try {
					if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
						scheduler.shutdownNow();
					}
				} catch (InterruptedException e) {
					scheduler.shutdownNow();
					Thread.currentThread().interrupt();
				}

				stopLatch.countDown();
			}));

			try {
				stopLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.info("Main thread interrupted, exiting");
			}

		}
	}
}
