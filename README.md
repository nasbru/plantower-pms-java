# Plantower PM Sensor — Java Library

A lightweight Java library for communicating with Plantower **PMS7003** and **PMS5003**
particulate-matter sensors over a serial port.

It configures the sensor to operate in passive mode and provides access to PM1.0, PM2.5, and PM10 measurements.

---

## Features

- Support for Plantower PMS7003 and PMS5003 sensors
- Passive-mode sensor communication
- Reading PM1.0, PM2.5, and PM10 measurements
- Serial communication through UART or a USB–serial adapter
- Single dependency: [jSerialComm](https://fazecast.github.io/jSerialComm/) 2.11.2
- Java 17 compatibility

---

## Requirements

- Java 17
- Maven
- Plantower PMS7003 or PMS5003 sensor
- UART interface or USB–serial adapter

---

## Build

```bash
mvn clean package
```

The build produces a JAR in the `target/` directory.

---

## Using the Library

### API overview

The public API is provided by `com.github.nasbru.PMSensor`:

| Method | Description |
| `PMSensor(String serialAddress)` | Constructor — validates device path and checks that the port can be opened |
| `init()` | Opens the serial port and wakes the sensor up |
| `passiveMode()` | Switches the sensor to passive (poll-on-demand) mode |
| `activeMode()` | Switches the sensor to active (continuous) mode |
| `sleep()` | Puts the sensor to sleep |
| `wakeUp()` | Wakes the sensor up |
| `getMeasurements()` | Returns `int[] { pm1_0, pm2_5, pm10 }` in µg/m³ |
| `close()` | Closes the serial port (`AutoCloseable`) |

Each mode-switching method has an overload accepting a `retries` parameter. The default is 10 retries.

Errors are reported exclusively through exceptions:
- `IllegalArgumentException` — invalid serial device path
- `IllegalStateException` — port cannot be opened or written to
- `PMSensor.MeasurementReadException` — failed to receive a valid frame

### Usage example

```java
import com.github.nasbru.PMSensor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        String serialAddress = "/dev/ttyUSB0";
        int intervalSeconds = 10;

        try (PMSensor sensor = new PMSensor(serialAddress)) {

            sensor.init();
            sensor.passiveMode();

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            CountDownLatch stopLatch = new CountDownLatch(1);

            Runnable pollTask = () -> {
                try {
                    int[] m = sensor.getMeasurements();
                    System.out.printf("PM1.0: %d, PM2.5: %d, PM10: %d%n", m[0], m[1], m[2]);
                } catch (Exception e) {
                    System.err.println("Error reading sensor: " + e.getMessage());
                }
            };

            scheduler.scheduleAtFixedRate(pollTask, 0, intervalSeconds, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down...");
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
            }
        }
    }
}
```

### Example output

```
PM1.0: 3, PM2.5: 5, PM10: 6
```

---

## Hardware and Wiring

Connect the sensor to the host as follows:

| Sensor pin | Host pin |
|------------|----------|
| TX         | RX       |
| RX         | TX       |
| GND        | GND      |
| VCC        | 5 V      |

> **Important:** Make sure that the sensor and host share a common ground.

The default PMS sensor baud rate is **9600**.

### Raspberry Pi UART

When using the built-in Raspberry Pi UART:

1. Enable the serial interface (e.g. via `raspi-config`).
2. Use the correct device path: `/dev/serial0`, `/dev/ttyAMA0`, or `/dev/ttyS0`.

Alternatively, connect the sensor through a USB–serial adapter, which is typically
available as `/dev/ttyUSB0`.

---


## Troubleshooting

### Serial device is missing or inaccessible

Check whether the device exists:

```bash
ls -l /dev/ttyUSB0
```

If necessary, add your user to the `dialout` group:

```bash
sudo usermod -aG dialout "$USER"
```

Log out and back in for the change to take effect.

### The serial port cannot be opened

Make sure that no other application is using the port, for example:

- `screen`
- `minicom`
- another sensor-reading process

### No valid frames are received

Check the following:

- Sensor power supply (5 V)
- TX/RX wiring
- Common ground
- Serial device path

---

## Notes

- After power-up, the sensor starts in active mode by default.
- After waking the sensor from sleep mode, allow the fan to run for at least
  30 seconds before taking a measurement. For reading intervals of 30 seconds
  or less, it is recommended not to put the sensor to sleep. Instead, keep it
  awake.
- Once the sensor has been switched from active mode to passive mode, switching
  it back to active mode does not appear to work.
- The library is intended to be used in passive mode, with measurements
  triggered on demand.
- The library has been tested with the Plantower PMS7003 and PMS5003. Other Plantower PM sensor models may also be compatible, but they have not been explicitly tested.

  
---

## License

See the [LICENSE](LICENSE) file for license information.
