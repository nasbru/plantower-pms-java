package bme680;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.ConnectException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import bme680.measurement.Measurement;

public class SensorDataHandler implements SensorListener {
	private static final String HA_URL = "http://192.168.1.28:8123/api/states/sensor.bme680";
	private static final String HA_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJlYzYxNmU4NjgzZDk0MjEwYjI3ODZjNWIwYTA3MGEwZSIsImlhdCI6MTc4NjE2NDU1MSwiZXhwIjoyMTAxNTI0NTUxfQ.EEltWCkW3pTkSNRK6uuqjd5Pd06JYT14K7FWraEaLRY";
	private Logger logger = LoggerFactory.getLogger(SensorDataHandler.class);
	private ObjectMapper objectMapper = new ObjectMapper();
	private HttpClient client = HttpClient.newHttpClient();

	@Override
	public void onDataReceived(Measurement[] m) {
		MeasurementData data = new MeasurementData(m[0].getValue(), m[1].getValue(), m[2].getValue(), m[3].getValue(),
				m[4].getValue(), m[5].getValue());
		try {
			String jsonData = objectMapper.writeValueAsString(data);

			// Wysyłanie danych do Home Assistant
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(HA_URL))
					.header("Authorization", "Bearer " + HA_TOKEN).header("Content-Type", "application/json")
					.POST(BodyPublishers.ofString(jsonData)).build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			System.out.println("Response Code: " + response.statusCode());
			System.out.println("Response Body: " + response.body());
		} catch(ConnectException e) {
			logger.warn("No connection with HA host.");
		}
		catch (Exception e) {
			logger.error("Exception during data processing.", e);
		}
	}

}
