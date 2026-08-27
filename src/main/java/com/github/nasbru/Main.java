package com.github.nasbru;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;

public class Main {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		String serialAddress = args.length > 0 ? args[0] : "/dev/ttyS0";
		int intervalSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

		Context pi4j = Pi4J.newAutoContext();

		PMSensor PmSensor = new PMSensor(pi4j, serialAddress);

		PmSensor.init();
		PmSensor.passiveMode();

		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		CountDownLatch stopLatch = new CountDownLatch(1);

		Runnable pollTask = () -> {
		    try {
		        int[] m = PmSensor.getMeasurements();
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
		    try {
		        pi4j.shutdown();
		    } catch (Exception e) {
		        LOGGER.warn("Error while shutting down Pi4J", e);
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
