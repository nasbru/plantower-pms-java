package bme680;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bme680.measurement.Measurement;

public class SensorDataHandler implements SensorListener {
	private static final Logger logger = LoggerFactory.getLogger(SensorDataHandler.class);

	private final MqttClient client;
	private final String baseTopic;
	//private String discoveryPrefix;
	//private String nodeId;

	/**
	 * @param brokerUrl e.g. "tcp://localhost:1883"
	 * @param clientId  client id for MQTT
	 * @param baseTopic e.g. "home/bme680_1" (no trailing slash)
	 */
	public SensorDataHandler(String brokerUrl, String clientId, String baseTopic) throws MqttException {
		this.baseTopic = baseTopic.endsWith("/") ? baseTopic.substring(0, baseTopic.length() - 1) : baseTopic;
		this.client = new MqttClient(brokerUrl, clientId);
		MqttConnectOptions options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		// jeśli broker wymaga username/password, skonfiguruj options.setUserName(...) i
		// setPassword(...)
		client.connect(options);
		logger.info("Connected to MQTT broker {} as {}", brokerUrl, clientId);
	}
	/*
	public void setDiscoveryConfig(String discoveryPrefix, String nodeId) {
	    this.discoveryPrefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
	    this.nodeId = nodeId == null ? "bme680_1" : nodeId;
	} */

	@Override
	public void onDataReceived(Measurement[] m) {
		if (m == null || m.length < 6) {
			logger.warn("Received measurement array too short: {}", (m == null ? 0 : m.length));
			return;
		}
		try {
			publishValue("temperature", formatValue(m[0].getValue()));
			publishValue("humidity", formatValue(m[1].getValue()));
			publishValue("pressure", formatValue(m[2].getValue()));
			publishValue("iaq", formatValue(m[3].getValue()));
			publishValue("co2", formatValue(m[4].getValue()));
			publishValue("voc", formatValue(m[5].getValue()));
		} catch (MqttException ex) {
			logger.error("MQTT publish failed", ex);
			// Spróbuj ponownie połączenia jeśli klient jest rozłączony
			tryReconnect();
		} catch (Exception ex) {
			logger.error("Unexpected error while handling sensor data", ex);
		}
	}

	private void publishValue(String suffix, String valueStr) throws MqttException {
		String topic = baseTopic + "/" + suffix;
		MqttMessage msg = new MqttMessage(valueStr.getBytes(StandardCharsets.UTF_8));
		msg.setQos(1);
		msg.setRetained(true); // retained = true — dostosuj jeśli nie chcesz
		client.publish(topic, msg);
		logger.debug("Published {} -> {}", topic, valueStr);
	}
	
	/*
	public void publishDiscovery(String discoveryPrefix, String nodeId) throws MqttPersistenceException, MqttException {
		if (client == null || !client.isConnected()) {
			logger.warn("Cannot publish discovery - client disconnected");
			return;
		}
		String prefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
		// temperature
		String topic = prefix + "/sensor/" + nodeId + "_temperature/config";
		String payload = "{" + "\"name\":\"BME680 Temperature\"," + "\"state_topic\":\"" + baseTopic + "/temperature\","
				+ "\"unit_of_measurement\":\"°C\"," + "\"device_class\":\"temperature\"," + "\"unique_id\":\"" + nodeId
				+ "_temperature\"" + "}";
		MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);

		// humidity
		topic = prefix + "/sensor/" + nodeId + "_humidity/config";
		payload = "{" + "\"name\":\"BME680 Humidity\"," + "\"state_topic\":\"" + baseTopic + "/humidity\","
				+ "\"unit_of_measurement\":\"%\"," + "\"device_class\":\"humidity\"," + "\"unique_id\":\"" + nodeId
				+ "_humidity\"" + "}";
		msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);

		// pressure
		topic = prefix + "/sensor/" + nodeId + "_pressure/config";
		payload = "{" + "\"name\":\"BME680 Pressure\"," + "\"state_topic\":\"" + baseTopic + "/pressure\","
				+ "\"unit_of_measurement\":\"hPa\"," + "\"device_class\":\"pressure\"," + "\"unique_id\":\"" + nodeId
				+ "_pressure\"" + "}";
		msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);
		
		// iaq
		topic = prefix + "/sensor/" + nodeId + "_iaq/config";
		payload = "{" + "\"name\":\"BME680 IAQ\"," + "\"state"
				+ "topic\":\"" + baseTopic + "/iaq\"," + "\"unit_of_measurement\":\"IAQ\"," + "\"device_class\":\"aqi\","
				+ "\"unique_id\":\"" + nodeId + "_iaq\"" + "}";
		msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);
		
		// co2
		topic = prefix + "/sensor/" + nodeId + "_co2/config";
		payload = "{" + "\"name\":\"BME680 CO2\"," + "\"state"
				+ "topic\":\"" + baseTopic + "/co2\"," + "\"unit_of_measurement\":\"ppm\"," + "\"device_class\":\"carbon_dioxide\","
				+ "\"unique_id\":\"" + nodeId + "_co2\"" + "}";
		msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);
		
		// voc
		topic = prefix + "/sensor/" + nodeId + "_voc/config";
		payload = "{" + "\"name\":\"BME680 VOC\"," + "\"state"
				+ "topic\":\"" + baseTopic + "/voc\"," + "\"unit_of"
						+ "measurement\":\"ppb\"," + "\"device_class\":\"volatile_organic_compounds\","
				+ "\"unique_id\":\"" + nodeId + "_voc\"" + "}";
		msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setRetained(true);
		client.publish(topic, msg);
	} */

	private String formatValue(BigDecimal value) {
		if (value == null)
			return "0";
		// wymuszenie 2 miejsc po przecinku (np. 25.10). Jeśli nie chcesz, użyj
		// value.toPlainString()
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private void tryReconnect() {
		try {
			if (!client.isConnected()) {
				logger.info("Trying to reconnect MQTT client...");
				client.reconnect(); // używa automatic reconnect jeśli było ustawione
			}
		} catch (MqttException e) {
			logger.warn("Reconnect attempt failed", e);
		}
	}

	public void disconnect() {
		try {
			if (client.isConnected())
				client.disconnect();
		} catch (MqttException e) {
			logger.warn("Error while disconnecting MQTT client", e);
		}
	}
}
