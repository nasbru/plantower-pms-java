package com.github.nasbru;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.measurements.Measurement;

public class SensorDataHandler implements SensorListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(SensorDataHandler.class);
	
	private String discoveryPrefix;
	
	private final MqttClient bme680Client;
	private final String bme680BaseTopic;
	private String nodeId;

	/**
	 * @param brokerUrl e.g. "tcp://localhost:1883"
	 * @param clientId  client id for MQTT
	 * @param baseTopic e.g. "home/bme680_1" (no trailing slash)
	 */
	public SensorDataHandler(String brokerUrl, String clientId, String baseTopic) throws MqttException {
		this.bme680BaseTopic = baseTopic.endsWith("/") ? baseTopic.substring(0, baseTopic.length() - 1) : baseTopic;
		this.bme680Client = new MqttClient(brokerUrl, clientId);
		MqttConnectOptions options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);

		bme680Client.connect(options);

		bme680Client.setCallback(new MqttCallbackExtended() {
			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				LOGGER.info("MQTT connectComplete (reconnect={}): {}", reconnect, serverURI);

				if (discoveryPrefix != null && nodeId != null) {
					try {
						publishDiscovery(discoveryPrefix, nodeId);
					} catch (MqttException e) {
						LOGGER.warn("Failed to publish discovery on connectComplete", e);
					}
				}
			}

			@Override
			public void connectionLost(Throwable cause) {
				LOGGER.warn("MQTT connection lost", cause);
			}

			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
				/* no-op */ }

			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
				/* no-op */ }
		});
		LOGGER.info("Connected to MQTT broker {} as {}", brokerUrl, clientId);
	}

	public void setDiscoveryConfig(String discoveryPrefix, String nodeId) {
		this.discoveryPrefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
		this.nodeId = nodeId == null ? "bme680_1" : nodeId;
	}

	@Override
	public void onDataReceived(Measurement[] m) {
		if (m == null || m.length < 6) {
			LOGGER.warn("Received measurement array too short: {}", (m == null ? 0 : m.length));
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
			LOGGER.error("MQTT publish failed", ex);

			tryReconnect();
		} catch (Exception ex) {
			LOGGER.error("Unexpected error while handling sensor data", ex);
		}
	}

	private void publishValue(String suffix, String valueStr) throws MqttException {
		String topic = bme680BaseTopic + "/" + suffix;
		MqttMessage msg = new MqttMessage(valueStr.getBytes(StandardCharsets.UTF_8));
		msg.setQos(1);
		msg.setRetained(true);
		bme680Client.publish(topic, msg);
		LOGGER.debug("Published {} -> {}", topic, valueStr);
	}

	public void publishDiscovery(String discoveryPrefix, String nodeId) throws MqttException {
		if (bme680Client == null || !bme680Client.isConnected()) {
			LOGGER.warn("Cannot publish discovery - client disconnected");
			return;
		}
		String prefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
		String devName = "BME680 " + nodeId;
		String deviceJson = String.format(
				"\"device\":{\"identifiers\":[\"%s\"],\"name\":\"%s\",\"model\":\"BME680\",\"manufacturer\":\"Custom\"}",
				nodeId, devName);

		// helper lambda-like style: build and publish
		BiConsumer<String, String> pub = (topic, payload) -> {
			try {
				MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
				msg.setRetained(true);
				msg.setQos(1);
				bme680Client.publish(topic, msg);
			} catch (MqttException e) {
				LOGGER.warn("Failed to publish discovery topic {}: {}", topic, e.getMessage());
			}
		};

		// temperature
		String topic = prefix + "/sensor/" + nodeId + "_temperature/config";
		String payload = String.format(
				"{\"name\":\"BME680 Temperature\",\"state_topic\":\"%s/temperature\",\"unit_of_measurement\":\"°C\",\"device_class\":\"temperature\",\"unique_id\":\"%s_temperature\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// humidity
		topic = prefix + "/sensor/" + nodeId + "_humidity/config";
		payload = String.format(
				"{\"name\":\"BME680 Humidity\",\"state_topic\":\"%s/humidity\",\"unit_of_measurement\":\"%%\",\"device_class\":\"humidity\",\"unique_id\":\"%s_humidity\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// pressure
		topic = prefix + "/sensor/" + nodeId + "_pressure/config";
		payload = String.format(
				"{\"name\":\"BME680 Pressure\",\"state_topic\":\"%s/pressure\",\"unit_of_measurement\":\"hPa\",\"device_class\":\"pressure\",\"unique_id\":\"%s_pressure\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// iaq
		topic = prefix + "/sensor/" + nodeId + "_iaq/config";
		payload = String.format("{\"name\":\"BME680 IAQ\",\"state_topic\":\"%s/iaq\",\"unique_id\":\"%s_iaq\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// co2
		topic = prefix + "/sensor/" + nodeId + "_co2/config";
		payload = String.format(
				"{\"name\":\"BME680 CO2\",\"state_topic\":\"%s/co2\",\"unit_of_measurement\":\"ppm\",\"device_class\":\"carbon_dioxide\",\"state_class\":\"measurement\",\"unique_id\":\"%s_co2\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// voc
		topic = prefix + "/sensor/" + nodeId + "_voc/config";
		payload = String.format(
				"{\"name\":\"BME680 VOC\",\"state_topic\":\"%s/voc\",\"unit_of_measurement\":\"ppb\",\"state_class\":\"measurement\",\"unique_id\":\"%s_voc\",%s}",
				bme680BaseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		LOGGER.info("Published MQTT discovery for node {}", nodeId);
	}

	private String formatValue(BigDecimal value) {
		if (value == null)
			return "0";
		// wymuszenie 2 miejsc po przecinku (np. 25.10). Jeśli nie chcesz, użyj
		// value.toPlainString()
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private void tryReconnect() {
		try {
			if (!bme680Client.isConnected()) {
				LOGGER.info("Trying to reconnect MQTT client...");
				bme680Client.reconnect(); // używa automatic reconnect jeśli było ustawione
			}
		} catch (MqttException e) {
			LOGGER.warn("Reconnect attempt failed", e);
		}
	}

	public void disconnect() {
		try {
			if (bme680Client.isConnected())
				bme680Client.disconnect();
		} catch (MqttException e) {
			LOGGER.warn("Error while disconnecting MQTT client", e);
		}
	}
}
