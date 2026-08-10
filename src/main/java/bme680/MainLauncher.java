package bme680;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.eclipse.paho.client.mqttv3.MqttException;

public class MainLauncher {
	
	private static final Path CONFIG_FILE = Paths.get("data", "config.properties");
	public static void main(String[] args) {
		System.out.println("Start");
		
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
			props.clear();
			props.load(in);
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}
		String mqttBroker = props.getProperty("mqtt.broker", "tcp://localhost:1883");
		String mqttClientId = props.getProperty("mqtt.clientId", "bme680_publisher_1");
		String mqttBaseTopic = props.getProperty("mqtt.baseTopic", "home/bme680_1");
		
		BME680Reader reader = new BME680Reader(30);
		
		SensorDataHandler sdh = null;
		int maxAttempts = 5;
		int attempt = 0;
		long retryDelayMs = 5000;

		while (attempt < maxAttempts) {
		    attempt++;
		    try {
		        sdh = new SensorDataHandler(mqttBroker, mqttClientId, mqttBaseTopic);
		        break; // udało się
		    } catch (MqttException e) {
		        System.err.println("MQTT connect failed (attempt " + attempt + "): " + e.getMessage());
		        if (attempt >= maxAttempts) {
		            System.err.println("Max attempts reached. Exiting.");
		            System.exit(1); // lub continue bez mqtt
		        }
		        try {
		            Thread.sleep(retryDelayMs);
		        } catch (InterruptedException ie) {
		            Thread.currentThread().interrupt();
		            break;
		        }
		    }
		}

		if (sdh != null) {
		    reader.addListener(sdh);
		    Thread thread = new Thread(reader);
		    thread.start();
		}
		
		while(true) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
