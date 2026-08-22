package com.github.nasbru;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PMSensorReader implements Runnable {
	private static final Logger LOGGER = LoggerFactory.getLogger(PMSensorReader.class);
	private final PMSensor sensor;
	private ScheduledFuture<?> future;
	private ScheduledExecutorService scheduler;
	private int period;
	private int[] lastMeasurement;
	private long measuringTime = 60000;

	public PMSensorReader(PMSensor sensor, int period) {
		this.sensor = sensor;
		this.period = period;
	}

	@Override
	public void run() {
		try {
			byte[] buffer = new byte[64];

			long startTime = System.currentTimeMillis();
			int maxWaitTime = 5000;

			while ((System.currentTimeMillis() - startTime) < measuringTime) {
				long readTime = System.currentTimeMillis();
				if (Thread.currentThread().isInterrupted()) {
					LOGGER.info("Reader interrupted, exiting loop");
					break;
				}
				try {
					// Check that at least 64 bytes of data are available
					LOGGER.debug("Waiting for at least 64 bytes of data. Available data: ");
					while (sensor.available() < 64) {
						if (Thread.currentThread().isInterrupted()) {
							LOGGER.info("Reader interrupted while waiting for data");
							throw new InterruptedException();
						}
						// If there are less than 64 bytes of data available, wait a while
						LOGGER.debug(sensor.available() + " ");
						if ((System.currentTimeMillis() - readTime > maxWaitTime)) {
							LOGGER.warn("Reading time exceeded. Trying to wake up the device");
							sensor.wakeUp();
							readTime = System.currentTimeMillis();
						}
						Thread.sleep(150);
					}
					// Read the available data into the buffer
					int bytesRead = sensor.read(buffer, 0, 64);
					LOGGER.debug("Bytes read: " + bytesRead);

					// Search the buffer for the frame header
					for (int i = 0; i <= 33; i++) { // <-- if the frame is within this range then the whole will fit
						if (buffer[i] == 0x42 && buffer[i + 1] == 0x4d) {
							// Header found, read 32 bytes of frame
							LOGGER.debug("Header found.");
							byte[] frame = Arrays.copyOfRange(buffer, i, i + 32);
							if (sensor.isFrameValid(frame)) {
								LOGGER.debug("Frame is valid. Proccessing frame.");
								lastMeasurement = sensor.processFrame(frame);
							}

							Arrays.fill(buffer, (byte) 0); // Clear buffer
							break; // Go to the next iteration of the outer loop
						}
					}

				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break; // wyjście z pętli i zakończenie run()
				} catch (Exception e) {
					LOGGER.error("Error during data processing: " + e.getMessage(), e);
					Arrays.fill(buffer, (byte) 0); // Clear buffer in case of error
				}
			}
			if (lastMeasurement != null) {
				LOGGER.info("PM1.0 = {} PM2.5 = {} PM10 = {}", lastMeasurement[0], lastMeasurement[1],
						lastMeasurement[2]);
			}
		} catch (Exception e) {
			LOGGER.error("Error while reading data from serial port: " + e.getMessage(), e);
		}
	}

	public synchronized void start() {
		if (future != null && !future.isCancelled() && !future.isDone()) {
			LOGGER.debug("PMSensorReader already started");
			return;
		}
		if (scheduler == null) {
			scheduler = Executors.newSingleThreadScheduledExecutor();
		}
		future = scheduler.scheduleAtFixedRate(this, 0, period, TimeUnit.SECONDS);
	}

	public synchronized void stop() {
		if (future != null) {
			// cancel(true) aby natychmiast przerwać sleep() itp. — tylko jeśli run()
			// obsługuje przerwania
			future.cancel(true);
			future = null;
		}

		if (scheduler != null && !scheduler.isShutdown()) {
			scheduler.shutdown();
			try {
				if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
					scheduler.shutdownNow();
					if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
						LOGGER.warn("Scheduler did not terminate");
					}
				}
			} catch (InterruptedException e) {
				scheduler.shutdownNow();
				Thread.currentThread().interrupt();
			} finally {
				scheduler = null; // pozwala na późniejsze ponowne uruchomienie
			}
		}
	}
}
