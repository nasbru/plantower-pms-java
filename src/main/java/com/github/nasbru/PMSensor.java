package com.github.nasbru;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PMSensor implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(PMSensor.class);

	private static final int MEASUREMENT_FRAME_LENGTH = 32;
	private static final int DEFAULT_RETRIES = 10;
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
		LOG.debug("init: port configured, baud=9600, 8N1, timeouts=NONBLOCKING");

		if (!serialPort.openPort()) {
			LOG.error("init: openPort() FAILED for {}", serialAddress);
			throw new IllegalStateException("Cannot open serial port: " + serialAddress);
		}

		LOG.info("init: openPort() OK");
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
		LOG.debug("passiveMode({}): start", retries);
		boolean result = executeWithRetries(Command.PASSIVE_MODE.getRequest(), Command.PASSIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
		LOG.debug("passiveMode: result={}", result);
		return result;
	}

	public boolean passiveMode() {
		return passiveMode(DEFAULT_RETRIES);
	}

	public boolean activeMode(int retries) {
		LOG.debug("activeMode({}): start", retries);
		boolean result = executeWithRetries(Command.ACTIVE_MODE.getRequest(), Command.ACTIVE_MODE.getResponse(),
				retries, MODE_CMD_TIMEOUT_MS);
		LOG.debug("activeMode: result={}", result);
		return result;
	}

	public boolean activeMode() {
		return activeMode(DEFAULT_RETRIES);
	}

	public boolean sleep(int retries) {
		LOG.debug("sleep({}): start", retries);
		boolean result = executeWithRetries(Command.SLEEP.getRequest(), Command.SLEEP.getResponse(), retries,
				MODE_CMD_TIMEOUT_MS);
		LOG.debug("sleep: result={}", result);
		return result;
	}

	public boolean sleep() {
		return sleep(DEFAULT_RETRIES);
	}

	public void wakeUp(int retries) {
		LOG.debug("wakeUp({}): start", retries);
		ensureOpen();

		for (int i = 0; i < retries; i++) {
			flush();
			write(Command.WAKE_UP.getRequest());

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOG.debug("wakeUp: interrupted");
				return;
			}
		}

		LOG.debug("wakeUp: done, sent {} wake-up commands", retries);
	}

	public void wakeUp() {
		wakeUp(DEFAULT_RETRIES);
	}

	public int[] getMeasurements(int retries) {
		long startTime = System.currentTimeMillis();
		int invalidFrames = 0;

		try {
			// Discard stale data accumulated in the RX buffer since the previous
			// measurement cycle. Done ONCE per call — the retry loop does not need
			// to clear anything: readMeasurementFrame() scans the stream for the
			// frame header (0x42 0x4D), so garbage and phase shifts are harmless.
			int bufferedBeforeFlush = serialPort.bytesAvailable();
			LOG.debug("getMeasurements({}): bytesAvailable before flush={}", retries,
					bufferedBeforeFlush > 0 ? bufferedBeforeFlush + " <-- sensor is streaming (active mode?)"
							: "0 (silent - passive mode)");

			flush();
			LOG.debug("getMeasurements({}): start, RX buffer flushed", retries);

			for (int attempts = 1; attempts <= retries; attempts++) {
				byte[] frame = passiveMeasurement();

				if (isFrameValid(frame)) {
					int[] values = processFrame(frame);
					LOG.debug("getMeasurements: OK on attempt {}/{} (invalid frames so far: {}, total {} ms), "
							+ "pm1.0={} pm2.5={} pm10={}", attempts, retries, invalidFrames,
							System.currentTimeMillis() - startTime, values[0], values[1], values[2]);
					return values;
				}

				invalidFrames++;
				logInvalidFrame(attempts, frame);
			}

			throw new MeasurementReadException(
					"Failed to receive a valid measurement frame after " + retries + " attempts");

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MeasurementReadException("Interrupted while getting measurements", e);
		}
	}

	public int[] getMeasurements() {
		return getMeasurements(DEFAULT_RETRIES);
	}

	private boolean executeWithRetries(byte[] request, byte[] expectedResponse, int retries, int timeoutMillis) {

		if (retries <= 0) {
			return false;
		}

		for (int i = 1; i <= retries; i++) {
			try {
				byte[] response = sendCommandAndReadAnswerFrame(request, expectedResponse.length, timeoutMillis);

				if (Arrays.equals(response, expectedResponse)) {
					LOG.debug("executeWithRetries: success on attempt {}/{}", i, retries);
					return true;
				}

				if (LOG.isDebugEnabled()) {
					LOG.debug("executeWithRetries: attempt {}/{} mismatch, expected={}, got={}",
							i, retries, toHex(expectedResponse), toHex(response));
				}

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOG.debug("executeWithRetries: interrupted on attempt {}/{}", i, retries);
				return false;
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("executeWithRetries: FAILED after {} attempts, request={}", retries, toHex(request));
		}
		return false;
	}

	private byte[] passiveMeasurement() throws InterruptedException {
		long start = System.currentTimeMillis();

		// No flush() here — the measurement read does not depend on the buffer
		// being clean or phase-aligned: readMeasurementFrame() scans the stream
		// for the frame header (0x42 0x4D) and discards any garbage it finds.
		write(Command.PASSIVE_MEASUREMENT.getRequest());

		byte[] frame = readMeasurementFrame(MEASUREMENT_TIMEOUT_MS);
		long elapsed = System.currentTimeMillis() - start;

		if (LOG.isTraceEnabled()) {
			LOG.trace("passiveMeasurement: elapsed={} ms, frame={}", elapsed, toHex(frame));
		}
		return frame;
	}

	/**
	 * Reads one complete measurement frame (32 bytes) directly from the byte
	 * stream. Phase 1 scans byte-by-byte for the frame header (0x42 0x4D),
	 * discarding garbage (noise bytes, stale data, misaligned leftovers) — so the
	 * read window can never get stuck at a wrong phase. Phase 2 reads the remaining
	 * 30 bytes of the frame. A fake header inside frame data is caught later by the
	 * checksum in isFrameValid() and only costs one retry. Returns the complete
	 * frame, a partial frame (timeout after the header), or an empty array (timeout
	 * before any header).
	 */
	private byte[] readMeasurementFrame(long timeoutMillis) throws InterruptedException {

		long deadline = System.currentTimeMillis() + timeoutMillis;
		int state = 0; // number of header bytes matched so far (0..1)
		int garbage = 0; // bytes skipped before the header

		while (System.currentTimeMillis() < deadline) {
			int b = readSingleByte();

			if (b < 0) {
				Thread.sleep(10);
				continue;
			}

			if (state == 0) {
				if (b == 0x42) {
					state = 1;
				} else {
					garbage++;
				}
			} else if (b == 0x4D) {
				// Header found — read the rest of the frame.
				if (garbage > 0 && LOG.isDebugEnabled()) {
					LOG.debug("readMeasurementFrame: skipped {} garbage byte(s) before header", garbage);
				}

				byte[] frame = new byte[MEASUREMENT_FRAME_LENGTH];
				frame[0] = 0x42;
				frame[1] = 0x4D;

				long remaining = Math.max(1, deadline - System.currentTimeMillis());
				int read = readFully(frame, 2, MEASUREMENT_FRAME_LENGTH - 2, remaining);

				if (read < MEASUREMENT_FRAME_LENGTH - 2) {
					LOG.warn("readMeasurementFrame: incomplete frame (read {} of {} bytes after header)",
							read, MEASUREMENT_FRAME_LENGTH - 2);
					return Arrays.copyOf(frame, 2 + read);
				}

				return frame;

			} else {
				// The matched 0x42 was garbage after all. If this byte is itself
				// 0x42, it becomes the new candidate (handles 42 42 4D streams).
				garbage++;
				state = (b == 0x42) ? 1 : 0;

				if (state == 0) {
					garbage++;
				}
			}
		}

		LOG.warn("readMeasurementFrame: header not found within timeout (skipped {} garbage byte(s))", garbage);
		return new byte[0];
	}

	private int readSingleByte() throws InterruptedException {
		if (serialPort.bytesAvailable() <= 0) {
			return -1;
		}

		byte[] one = new byte[1];
		int read = read(one, 0, 1);

		return read == 1 ? (one[0] & 0xFF) : -1;
	}

	private byte[] sendCommandAndReadAnswerFrame(byte[] request, int frameLength, int timeoutMillis)
			throws InterruptedException {

		long start = System.currentTimeMillis();

		// ACK path (passiveMode/activeMode/sleep): the RX buffer may hold stale
		// bytes (active-mode frames, noise, wake-up leftovers). Reading them as
		// the command response causes guaranteed mismatch, so clear the buffer
		// before sending. Measurement frames must NOT use this method — they
		// use readMeasurementFrame(), which is immune to phase misalignment.
		flush();
		LOG.debug("sendCommandAndReadAnswerFrame: RX buffer flushed before ACK command");

		write(request);

		byte[] frame = new byte[frameLength];
		int read = readFully(frame, 0, frameLength, timeoutMillis);
		long elapsed = System.currentTimeMillis() - start;

		if (LOG.isTraceEnabled()) {
			LOG.trace("sendCommandAndReadAnswerFrame: request={}, expectedLen={}, read={}, elapsed={} ms, data={}",
					toHex(request), frameLength, read, elapsed, toHex(frame, read));
		}

		if (read < frameLength) {
			LOG.warn("sendCommandAndReadAnswerFrame: incomplete frame (read {} of {} bytes)", read, frameLength);
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
		if (LOG.isTraceEnabled()) {
			LOG.trace("write: {}/{} bytes written: {}", written, data.length, toHex(data));
		}
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
			LOG.debug("isFrameValid: REJECTED - wrong length {}, expected {}",
					frame == null ? "(null)" : frame.length, MEASUREMENT_FRAME_LENGTH);
			return false;
		}

		if ((frame[0] & 0xFF) != 0x42 || (frame[1] & 0xFF) != 0x4D) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("isFrameValid: REJECTED - bad header {}, expected 42 4D", toHex(frame, 2));
			}
			return false;
		}

		int length = (((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF)) + 4;

		if (length != MEASUREMENT_FRAME_LENGTH) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("isFrameValid: REJECTED - bad length field {}, expected {} (frame bytes 2-3: {})",
						length, MEASUREMENT_FRAME_LENGTH, toHex(frame, 4));
			}
			return false;
		}

		int sum = 0;

		for (int i = 0; i < frame.length - 2; i++) {
			sum += frame[i] & 0xFF;
		}

		int checksum = ((frame[frame.length - 2] & 0xFF) << 8) | (frame[frame.length - 1] & 0xFF);

		if (checksum != sum) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("isFrameValid: REJECTED - bad checksum, calculated=0x{}, frame=0x{} (diff={})",
						String.format("%04X", sum), String.format("%04X", checksum), checksum - sum);
			}
			return false;
		}

		return true;
	}

	private void logInvalidFrame(int attempt, byte[] frame) {
		if (!LOG.isDebugEnabled()) {
			return;
		}

		if (frame == null) {
			LOG.debug("getMeasurements: attempt {} - invalid frame: null", attempt);
			return;
		}

		LOG.debug("getMeasurements: attempt {} - invalid frame (len={})={} (bytesAvailable={})",
				attempt, frame.length, toHex(frame), serialPort.bytesAvailable());
	}

	private static String toHex(byte[] bytes) {
		return toHex(bytes, bytes == null ? 0 : bytes.length);
	}

	private static String toHex(byte[] bytes, int len) {
		if (bytes == null) {
			return "null";
		}

		StringBuilder sb = new StringBuilder();
		int actualLen = Math.min(len, bytes.length);

		for (int i = 0; i < actualLen; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(String.format("%02X", bytes[i]));
		}

		return sb.toString();
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
