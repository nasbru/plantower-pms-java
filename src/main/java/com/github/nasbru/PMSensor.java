package com.github.nasbru;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fazecast.jSerialComm.SerialPort;

public class PMSensor implements AutoCloseable {

	private static final int MEASUREMENT_FRAME_LENGTH = 32;
	private static final int DEFAULT_RETRIES = 10;
	private static final int DEFAULT_MEASUREMENT_RETRIES = 50;
	private static final int MODE_CMD_TIMEOUT_MS = 2000;
	private static final int MEASUREMENT_TIMEOUT_MS = 3000;

	private final String serialAddress;
	private SerialPort serialPort;

	public PMSensor(String serialAddress) {
		if (serialAddress == null || serialAddress.isBlank()) {
			throw new IllegalArgumentException("serialAddress must not be null or empty");
		}

		if (!deviceFileExists(serialAddress)) {
			throw new IllegalArgumentException("Device file not found or not readable: " + serialAddress);
		}

		if (!canOpenPort(serialAddress)) {
			throw new IllegalStateException("Cannot open serial port: " + serialAddress);
		}

		this.serialAddress = serialAddress;
	}

	public void init() {
		serialPort = SerialPort.getCommPort(serialAddress);

		serialPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);

		// We use our own timeout in readFully(), so the read is non-blocking.
		serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

		if (!serialPort.openPort()) {
			throw new IllegalStateException("Cannot open serial port: " + serialAddress);
		}

		wakeUp();

		// The sensor needs ~1 s after wake-up before it reliably accepts commands
		// (otherwise passiveMode() right after init() may be silently ignored).
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public boolean passiveMode(int retries) {
		return executeWithRetries(Command.PASSIVE_MODE.getRequest(), Command.PASSIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
	}

	public boolean passiveMode() {
		return passiveMode(DEFAULT_RETRIES);
	}

	public boolean activeMode(int retries) {
		return executeWithRetries(Command.ACTIVE_MODE.getRequest(), Command.ACTIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
	}

	public boolean activeMode() {
		return activeMode(DEFAULT_RETRIES);
	}

	public boolean sleep(int retries) {
		return executeWithRetries(Command.SLEEP.getRequest(), Command.SLEEP.getResponse(), retries,
				MODE_CMD_TIMEOUT_MS);
	}

	public boolean sleep() {
		return sleep(DEFAULT_RETRIES);
	}

	public void wakeUp(int retries) {
		ensureOpen();

		for (int i = 0; i < retries; i++) {
			flush();
			write(Command.WAKE_UP.getRequest());

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	public void wakeUp() {
		wakeUp(DEFAULT_RETRIES);
	}

	public int[] getMeasurements(int retries) {

		try {
			// Discard stale data accumulated in the RX buffer since the previous
			// measurement cycle. This is done ONCE per call — the retry loop below
			// must NOT clear the RX buffer, so that a misaligned frame window can
			// slide across the stream until it hits a frame boundary (0x42 0x4D).
			flush();

			for (int attempts = 1; attempts <= retries; attempts++) {
				byte[] frame = passiveMeasurement();

				if (isFrameValid(frame)) {
					return processFrame(frame);
				}
			}

			throw new MeasurementReadException(
					"Failed to receive a valid measurement frame after " + retries + " attempts");

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MeasurementReadException("Interrupted while getting measurements", e);
		}
	}

	public int[] getMeasurements() {
		return getMeasurements(DEFAULT_MEASUREMENT_RETRIES);
	}

	private boolean executeWithRetries(byte[] request, byte[] expectedResponse, int retries, int timeoutMillis) {

		if (retries <= 0) {
			return false;
		}

		for (int i = 1; i <= retries; i++) {
			try {
				byte[] response = sendCommandAndReadFrame(request, expectedResponse.length, timeoutMillis);

				if (Arrays.equals(response, expectedResponse)) {
					return true;
				}

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		return false;
	}

	private byte[] passiveMeasurement() throws InterruptedException {
		return sendCommandAndReadFrame(Command.PASSIVE_MEASUREMENT.getRequest(), MEASUREMENT_FRAME_LENGTH,
				MEASUREMENT_TIMEOUT_MS);
	}

	private byte[] sendCommandAndReadFrame(byte[] request, int frameLength, int timeoutMillis)
			throws InterruptedException {

		// No flush() here — clearing the RX buffer before every attempt resets the
		// read position to a random phase of the stream and destroys frame-boundary
		// resynchronization. Stale data is discarded once in getMeasurements().
		write(request);

		byte[] frame = new byte[frameLength];
		int read = readFully(frame, 0, frameLength, timeoutMillis);

		if (read < frameLength) {
			return Arrays.copyOf(frame, read);
		}

		return frame;
	}

	private int readFully(byte[] buffer, int offset, int len, long timeoutMillis) throws InterruptedException {

		long deadline = System.currentTimeMillis() + timeoutMillis;
		int totalRead = 0;

		while (totalRead < len && System.currentTimeMillis() < deadline) {

			int available = serialPort.bytesAvailable();

			if (available > 0) {
				int toRead = Math.min(available, len - totalRead);
				int read = read(buffer, offset + totalRead, toRead);

				if (read > 0) {
					totalRead += read;
				}
			} else {
				Thread.sleep(10);
			}
		}

		return totalRead;
	}

	private int read(byte[] bytes, int offset, int len) {
		ensureOpen();

		if (offset < 0 || len < 0 || offset + len > bytes.length) {
			throw new IndexOutOfBoundsException();
		}

		if (len == 0) {
			return 0;
		}

		// readBytes() has a simpler overload without an offset.
		byte[] temp = new byte[len];
		int read = (int) serialPort.readBytes(temp, len);

		if (read > 0) {
			System.arraycopy(temp, 0, bytes, offset, read);
		}

		return read;
	}

	private void write(byte[] data) {
		ensureOpen();

		long written = serialPort.writeBytes(data, data.length);
		if (written != data.length) {
			throw new IllegalStateException("Only " + written + " of " + data.length + " bytes were written");
		}
	}

	private void flush() {
		ensureOpen();

		serialPort.flushIOBuffers();
	}

	private boolean isOpen() {
		return serialPort != null && serialPort.isOpen();
	}

	private boolean isFrameValid(byte[] frame) {
		if (frame == null || frame.length != MEASUREMENT_FRAME_LENGTH) {
			return false;
		}

		if ((frame[0] & 0xFF) != 0x42 || (frame[1] & 0xFF) != 0x4D) {
			return false;
		}

		int length = (((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF)) + 4;

		if (length != MEASUREMENT_FRAME_LENGTH) {
			return false;
		}

		int sum = 0;

		for (int i = 0; i < frame.length - 2; i++) {
			sum += frame[i] & 0xFF;
		}

		int checksum = ((frame[frame.length - 2] & 0xFF) << 8) | (frame[frame.length - 1] & 0xFF);

		return checksum == sum;
	}

	private int[] processFrame(byte[] frame) {
		int pm1_0 = unsignedShort(frame[10], frame[11]);
		int pm2_5 = unsignedShort(frame[12], frame[13]);
		int pm10 = unsignedShort(frame[14], frame[15]);

		return new int[] { pm1_0, pm2_5, pm10 };
	}

	private void ensureOpen() {
		if (!isOpen()) {
			throw new IllegalStateException("Serial port is not open: " + serialAddress);
		}
	}

	@Override
	public void close() {
		if (serialPort != null && serialPort.isOpen()) {
			serialPort.closePort();
		}
	}

	private static int unsignedShort(byte high, byte low) {
		return ((high & 0xFF) << 8) | (low & 0xFF);
	}

	private static boolean deviceFileExists(String devicePath) {
		if (devicePath == null) {
			return false;
		}

		try {
			Path path = Paths.get(devicePath);

			return Files.exists(path) && Files.isReadable(path) && !Files.isDirectory(path);

		} catch (Exception e) {
			return false;
		}
	}

	private static boolean canOpenPort(String serialAddress) {
		if (serialAddress == null) {
			return false;
		}

		SerialPort probe = SerialPort.getCommPort(serialAddress);

		probe.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);

		probe.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

		try {
			return probe.openPort();
		} catch (Exception e) {
			return false;
		} finally {
			if (probe.isOpen()) {
				probe.closePort();
			}
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

		private final byte[] request;
		private final byte[] response;

		Command(byte[] request, byte[] response) {
			this.request = request;
			this.response = response;
		}

		Command(byte[] request) {
			this(request, null);
		}

		byte[] getRequest() {
			return request;
		}

		byte[] getResponse() {
			return response;
		}
	}
}


