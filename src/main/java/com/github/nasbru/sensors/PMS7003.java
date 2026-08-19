package com.github.nasbru.sensors;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

public class PMS7003 {
	private final Serial serial;
	private static final Logger LOGGER = LoggerFactory.getLogger(PMS7003.class);
	private static final int MEASUREMENT_FRAME_LENGTH = 32; // standard PMS7003 frame length

	public PMS7003(Context pi4j, String serialAddress, Set<String> allowedMeasurements) {

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

		serial = pi4j.create(Serial.newConfigBuilder(pi4j).use_9600_N81().dataBits_8().parity(Parity.NONE)
				.stopBits(StopBits._1).flowControl(FlowControl.NONE).id("PMS7003Device").device(serialAddress)
				.provider("pigpio-serial").build());

		serial.open();

		try {
			passiveMode();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public boolean isOpen() {
		return serial.isOpen();
	}

	public int available() {
		return serial.available();
	}

	public int read(byte[] buffer, int offset, int amount) {
		return serial.read(buffer, offset, amount);
	}

	private boolean sendModeCommand(String modeName, byte[] request, byte[] expectedResponse)
			throws InterruptedException {
		LOGGER.debug("Setting sensor to {} mode...", modeName);

		serial.drain();
		serial.write(request);

		byte[] response = new byte[8];
		readFully(response, 0, response.length, 2000);

		if (Arrays.equals(response, expectedResponse)) {
			LOGGER.debug("Success");
			return true;
		} else {
			LOGGER.debug("Failure - response={}", Arrays.toString(response));
			return false;
		}
	}

	private boolean activeModeOnce() throws InterruptedException {
		return sendModeCommand("active", Command.ACTIVE_MODE.getRequest(), Command.ACTIVE_MODE.getResponse());
	}

	private boolean sleepOnce() throws InterruptedException {
		return sendModeCommand("sleep", Command.SLEEP.getRequest(), Command.SLEEP.getResponse());
	}

	public boolean passiveMode(int retries) {
		for (int i = 1; i <= retries; i++) {
			LOGGER.debug("Setting sensor to passive mode, attempt {}/{}", i, retries);
			try {
				if (sendModeCommand("passive", Command.PASSIVE_MODE.getRequest(), Command.PASSIVE_MODE.getResponse())) {
					LOGGER.debug("Passive mode enabled");
					return true;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.debug("Passive mode interrupted");
				return false;
			}
		}
		LOGGER.debug("Failed to enable passive mode after {} attempts", retries);
		return false;
	}

	public boolean activeMode(int retries) {
		for (int i = 1; i <= retries; i++) {
			LOGGER.debug("Setting sensor to active mode, attempt {}/{}", i, retries);
			try {
				if (sendModeCommand("active", Command.ACTIVE_MODE.getRequest(), Command.ACTIVE_MODE.getResponse())) {
					LOGGER.debug("Active mode enabled");
					return true;
				}
				LOGGER.debug("Active mode failed on attempt {}", i);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.debug("Active mode interrupted");
				return false;
			}
		}

		LOGGER.debug("Failed to enable active mode after {} attempts", retries);
		return false;
	}
	
	public boolean sleep(int retries) {
		
		for (int i = 1; i <= retries; i++) {
			LOGGER.debug("Sending sensor to sleep, attempt {}/{}", i, retries);
			try {
				if (sendModeCommand("sleep", Command.SLEEP.getRequest(), Command.SLEEP.getResponse())) {
					LOGGER.debug("Sensor is now in sleep mode");
					return true;
				}
				LOGGER.debug("Sleep command failed on attempt {}", i);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.debug("Sleep command interrupted");
				return false;
			}
		}

		LOGGER.debug("Failed to send sensor to sleep after {} attempts", retries);
		return false;
	}

	private byte[] passiveMeasurement() throws InterruptedException {
		serial.drain();
		serial.write(Command.PASSIVE_MEASUREMENT.getRequest());
		// Wait for a full measurement frame (32 bytes) with timeout
		byte[] frame = new byte[MEASUREMENT_FRAME_LENGTH];
		readFully(frame, 0, MEASUREMENT_FRAME_LENGTH, 3000);

		LOGGER.debug("Received measurement frame: {}", Arrays.toString(frame));
		return frame;
	}
	
	/*
	public synchronized void sleep() throws InterruptedException {
		serial.drain();
		serial.write(Command.SLEEP.getRequest());
		byte[] response = new byte[8];
		readFully(response, 0, response.length, 2000);
		LOGGER.debug("Sending sensor to sleep...");
		if (Arrays.equals(response, Command.SLEEP.getResponse())) {
			LOGGER.debug("Success");
		} else {
			LOGGER.debug("Failure");
		} 
	} */

	public synchronized void wakeUp() throws InterruptedException {
		LOGGER.debug("Waking up the sensor...");
		serial.write(Command.WAKE_UP.getRequest());
		// Wake up may not return an 8-byte response reliably; don't block forever
		if (serial.available() >= 8) {
			byte[] response = new byte[8];
			readFully(response, 0, response.length, 2000);
			LOGGER.debug("Wake response: {}", Arrays.toString(response));
		}
	}

	public synchronized void reset() throws InterruptedException {
		sleep();
		Thread.sleep(1000);
		wakeUp();
	}

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
		int[] invalidMeasurements = new int[3];
		final int maxAttempts = 50;
		try {
			byte[] frame = null;
			int attempts = 0;
			do {
				frame = passiveMeasurement();
				attempts++;
				if (isFrameValid(frame)) {
					break;
				} else {
					LOGGER.debug("Invalid measurement frame received (attempt {}/{}) - {}", attempts, maxAttempts,
							Arrays.toString(frame));
				}
			} while (attempts < maxAttempts);

			if (frame == null || !isFrameValid(frame)) {
				LOGGER.warn("Failed to receive a valid measurement frame after {} attempts", maxAttempts);
				return invalidMeasurements;
			}

			return processFrame(frame);
		} catch (InterruptedException e) {
			LOGGER.error("Interrupted while getting data: {}", e.toString());
			Thread.currentThread().interrupt();
			return invalidMeasurements;
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
}
