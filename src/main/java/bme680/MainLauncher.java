package bme680;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

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
		String haUrl = props.getProperty("ha.url", "http://localhost:8123/api/states/sensor.bme680");
		String haToken = props.getProperty("ha.token");
		
		BME680Reader reader = new BME680Reader(30);
		SensorDataHandler sdh = new SensorDataHandler(haUrl, haToken);
		reader.addListener(sdh);
		
		Thread thread = new Thread(reader);
		thread.start();
		
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
