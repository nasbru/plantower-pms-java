package com.github.nasbru.sensors;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.pi4j.context.Context;
import com.pi4j.io.serial.Serial;
import com.pi4j.io.serial.Parity;
import com.pi4j.io.serial.StopBits;
import com.pi4j.io.serial.FlowControl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PMSensor {
	private static final Logger LOGGER = LoggerFactory.getLogger(PMSensor.class);
	private static final int MEASUREMENT_FRAME_LENGTH = 32;
	private static final int DEFAULT_RETRIES = 10;
	private static final int MODE_CMD_TIMEOUT_MS = 2000;
	private static final int MEASUREMENT_TIMEOUT_MS = 3000;

	private final Context pi4j;
	private final String serialAddress;
	private Serial serial;

	public PMSensor(Context pi4j, String serialAddress) {

		if (pi4j == null) {
			throw new IllegalArgumentException("pi4j Context must not be null");
		}
		if (serialAddress == null || serialAddress.isEmpty()) {
			throw new IllegalArgumentException("serialAddress must not be null or empty");
		}
		if (!deviceFileExists(serialAddress)) {
			LOGGER.warn("Device file {} does not exist or is not readable", serialAddress);
			throw new IllegalArgumentException("Device file not found or not readable: " + serialAddress);
		}
		if (!canOpenPort(pi4j, serialAddress)) {
			LOGGER.warn("Cannot open port {} during probe", serialAddress);
			throw new IllegalStateException("Cannot open serial port (probe failed): " + serialAddress);
		}

		this.pi4j = pi4j;
		this.serialAddress = serialAddress;
	}

	public void init() {
		serial = pi4j.create(Serial.newConfigBuilder(pi4j).use_9600_N81().dataBits_8().parity(Parity.NONE)
				.stopBits(StopBits._1).flowControl(FlowControl.NONE).id("PMSensor").device(serialAddress)
				.provider("pigpio-serial").build());

		serial.open();

		wakeUp(); // In case the sensor was left in sleep mode
	}

	private byte[] sendCommandAndReadFrame(byte[] request, int frameLength, int timeoutMillis)
			throws InterruptedException {
		serial.drain();
		serial.write(request);

		byte[] frame = new byte[frameLength];
		int read = readFully(frame, 0, frameLength, timeoutMillis);
		if (read < frameLength) {
			LOGGER.debug("Partial frame read ({} / {}) for request {}", read, frameLength, Arrays.toString(request));
			return Arrays.copyOf(frame, read);
		}
		return frame;
	}

	private boolean executeWithRetries(String actionName, byte[] request, byte[] expectedResponse, int retries,
			int timeoutMillis) {
		int expectedLen = (expectedResponse == null) ? 0 : expectedResponse.length;

		for (int i = 1; i <= retries; i++) {
			LOGGER.debug("Setting sensor to {}, attempt {}/{}", actionName, i, retries);
			try {
				byte[] response = sendCommandAndReadFrame(request, expectedLen, timeoutMillis);

				if (expectedResponse == null) {
					if (response != null && response.length > 0) {
						LOGGER.debug("{}: unexpected bytes received and ignored: {}", actionName,
								Arrays.toString(response));
					} else {
						LOGGER.debug("{}: command sent, no response expected", actionName);
					}
					return true;
				}

				if (response != null && response.length == expectedResponse.length
						&& Arrays.equals(response, expectedResponse)) {
					LOGGER.debug("{} response: {}", actionName, Arrays.toString(response));
					return true;
				} else {
					LOGGER.debug("{} attempt {} failed: response={}", actionName, i, Arrays.toString(response));
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.debug("{} interrupted", actionName);
				return false;
			}
		}
		LOGGER.debug("Failed to set {} after {} attempts", actionName, retries);
		return false;
	}

	public boolean passiveMode(int retries) {
		return executeWithRetries("passive mode", Command.PASSIVE_MODE.getRequest(), Command.PASSIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
	}

	public boolean activeMode(int retries) {
		return executeWithRetries("active mode", Command.ACTIVE_MODE.getRequest(), Command.ACTIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
	}

	public boolean sleep(int retries) {
		return executeWithRetries("sleep", Command.SLEEP.getRequest(), Command.SLEEP.getResponse(), retries,
				MODE_CMD_TIMEOUT_MS);
	}

	public void wakeUp(int retries) {
		for (int i = 0; i < DEFAULT_RETRIES; i++) {
			serial.drain();
			serial.write(Command.WAKE_UP.getRequest());
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				LOGGER.warn("Wake up delay interrupted: {}", e.toString());
			}
		}
	}

	public boolean passiveMode() {
		return passiveMode(DEFAULT_RETRIES);
	}

	public boolean activeMode() {
		return activeMode(DEFAULT_RETRIES);
	}

	public boolean sleep() {
		return sleep(DEFAULT_RETRIES);
	}

	public void wakeUp() {
		wakeUp(DEFAULT_RETRIES);
	}

	private byte[] passiveMeasurement() throws InterruptedException {
		byte[] frame = sendCommandAndReadFrame(Command.PASSIVE_MEASUREMENT.getRequest(), MEASUREMENT_FRAME_LENGTH,
				MEASUREMENT_TIMEOUT_MS);

		LOGGER.debug("Received measurement frame: {}", Arrays.toString(frame));
		return frame;
	}

	/*
	 * public synchronized void sleep() throws InterruptedException {
	 * serial.drain(); serial.write(Command.SLEEP.getRequest()); byte[] response =
	 * new byte[8]; readFully(response, 0, response.length, 2000);
	 * LOGGER.debug("Sending sensor to sleep..."); if (Arrays.equals(response,
	 * Command.SLEEP.getResponse())) { LOGGER.debug("Success"); } else {
	 * LOGGER.debug("Failure"); } }
	 * 
	 * 
	 * 
	 * /* public synchronized void wakeUp() throws InterruptedException {
	 * LOGGER.debug("Waking up the sensor...");
	 * serial.write(Command.WAKE_UP.getRequest()); // Wake up may not return an
	 * 8-byte response reliably; don't block forever if (serial.available() >= 8) {
	 * byte[] response = new byte[8]; readFully(response, 0, response.length, 2000);
	 * LOGGER.debug("Wake response: {}", Arrays.toString(response)); } }
	 * 
	 * public synchronized void reset() throws InterruptedException { sleep();
	 * Thread.sleep(1000); wakeUp(); }
	 */

	// Check whether the given serial device file exists and is readable.
	public static boolean deviceFileExists(String devicePath) {
		if (devicePath == null)
			return false;
		try {
			Path p = Paths.get(devicePath);
			return Files.exists(p) && Files.isReadable(p) && !Files.isDirectory(p);
		} catch (Exception e) {
			LOGGER.debug("deviceFileExists check failed for {}: {}", devicePath, e.toString());
			return false;
		}
	}

	// Try to open the serial port with Pi4J and immediately close it. Returns true
	// if open succeeded.
	public static boolean canOpenPort(Context pi4j, String serialAddress) {
		if (pi4j == null || serialAddress == null)
			return false;
		Serial probe = null;
		try {
			probe = pi4j.create(Serial.newConfigBuilder(pi4j).use_9600_N81().dataBits_8().parity(Parity.NONE)
					.stopBits(StopBits._1).flowControl(FlowControl.NONE).id("PMS7003Probe").device(serialAddress)
					.provider("pigpio-serial").build());
			probe.open();
			boolean opened = probe.isOpen();
			try {
				probe.close();
			} catch (Exception ex) {
				// ignore close failures
			}
			return opened;
		} catch (Exception e) {
			LOGGER.debug("Port probe failed for {}: {}", serialAddress, e.toString());
			if (probe != null) {
				try {
					if (probe.isOpen())
						probe.close();
				} catch (Exception ex) {
				}
			}
			return false;
		}
	}

	public int[] getMeasurements() {
		final int maxAttempts = 50;

		try {
			for (int attempts = 1; attempts <= maxAttempts; attempts++) {
				byte[] frame = passiveMeasurement();
				if (isFrameValid(frame)) {
					if (attempts > 1) {
						LOGGER.debug("Measurement frame received after {} attempts", attempts);
					}
					return processFrame(frame);
				}
			}
			throw new MeasurementReadException(
					"Failed to receive a valid measurement frame after " + maxAttempts + " attempts");

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MeasurementReadException("Interrupted while getting measurements", e);
		}
	}

	/**
	 * Read exactly `len` bytes into buffer[offset..offset+len) or until
	 * timeoutMillis elapses. Returns number of bytes actually read.
	 */
	private int readFully(byte[] buffer, int offset, int len, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		int totalRead = 0;
		while (totalRead < len && System.currentTimeMillis() < deadline) {
			int avail = serial.available();
			if (avail > 0) {
				int toRead = Math.min(avail, len - totalRead);
				int r = serial.read(buffer, offset + totalRead, toRead);
				if (r > 0) {
					totalRead += r;
				}
			} else {
				Thread.sleep(10);
			}
		}
		return totalRead;
	}

	private boolean isFrameValid(byte[] frame) {
		if (frame.length != MEASUREMENT_FRAME_LENGTH) {
			return false;
		}

		if (frame[0] != 0x42 || frame[1] != 0x4d) {
			return false;
		}

		int length = (((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF)) + 4; // length field + 4 bytes header/length = total
		if (length != MEASUREMENT_FRAME_LENGTH) {
			return false;
		}

		int sum = 0;
		for (int i = 0; i < frame.length - 2; i++) {
			sum += frame[i] & 0xFF;
		}

		int checksum = ((frame[frame.length - 2] & 0xFF) << 8) | (frame[frame.length - 1] & 0xFF);
		boolean isChecksumValid = checksum == sum;

		return isChecksumValid;
	}

	private int[] processFrame(byte[] frame) {
		int pm1_0 = (frame[10] << 8) | (frame[11] & 0xFF);
		int pm2_5 = (frame[12] << 8) | (frame[13] & 0xFF);
		int pm10 = (frame[14] << 8) | (frame[15] & 0xFF);

		int[] measurements = { pm1_0, pm2_5, pm10 };

		return measurements;
	}

	private enum Command {

		SLEEP(new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0xE4, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x73 },
				new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0x00, (byte) 0x04, (byte) 0xE4, (byte) 0x00, (byte) 0x01,
						(byte) 0x77 }),

		WAKE_UP(new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0xE4, (byte) 0x00, (byte) 0x01, (byte) 0x01,
				(byte) 0x74 }),

		PASSIVE_MEASUREMENT(new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0xE2, (byte) 0x00, (byte) 0x00, (byte) 0x01,
				(byte) 0x71 }),

		ACTIVE_MODE(
				new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0xE1, (byte) 0x00, (byte) 0x00, (byte) 0x01,
						(byte) 0x70 },
				new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0x00, (byte) 0x04, (byte) 0xE1, (byte) 0x00, (byte) 0x01,
						(byte) 0x74 }),

		PASSIVE_MODE(
				new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0xE1, (byte) 0x00, (byte) 0x01, (byte) 0x01,
						(byte) 0x71 },
				new byte[] { (byte) 0x42, (byte) 0x4D, (byte) 0x00, (byte) 0x04, (byte) 0xE1, (byte) 0x01, (byte) 0x01,
						(byte) 0x75 });

		byte[] request;
		byte[] response;

		private Command(byte[] request, byte[] response) {
			this.request = request;
			this.response = response;
		}

		private Command(byte[] request) {
			this.request = request;
		}

		public byte[] getRequest() {
			return request;
		}

		public byte[] getResponse() {
			return response;
		}
	}

	public class MeasurementReadException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public MeasurementReadException(String message) {
			super(message);
		}

		public MeasurementReadException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
