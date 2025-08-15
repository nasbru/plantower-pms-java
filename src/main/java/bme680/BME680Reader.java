package bme680;

//import bme680.SensorListener;
import bme680.measurement.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class BME680Reader implements Runnable{
    private final String line0 = "2025-02-28 10:58:23,[IAQ (0)]: 25.00,[T degC]: 0.00,[H %rH]: 0.00,[P hPa]: 0.00,[G Ohms]: 22477,[S]: 0,[eCO2 ppm]: 0.00,[bVOCe ppm]: 0.00";

    private volatile boolean continueReading = true;
    private volatile String lastLine;
    private int period;
    private ProcessBuilder builder;
    private ByteArrayOutputStream buffer;
    private final Logger logger;
    private ArrayList<SensorListener> listeners;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public BME680Reader(int seconds){
        period = seconds;
        buffer = new ByteArrayOutputStream();
        logger = LoggerFactory.getLogger(BME680Reader.class);
        scheduler = Executors.newScheduledThreadPool(1);
        listeners = new ArrayList<>();
        lastLine = line0;

        builder = new ProcessBuilder("./bsec_bme680");
        builder.directory(new File("src/main/c"));
    }

    @Override
    public void run(){
        try{
            logger.debug("Starting BME680Reader run method.");
            Process process = builder.start();

            future = scheduler.scheduleAtFixedRate(() -> {
                        logger.debug("scheduled task: start");

                        Measurement[] measurement = extractData(lastLine);
                        notifyListeners(measurement);
                        for(Measurement m : measurement){
                            logger.debug(m.toString());
                        }

                        logger.debug("scheduled task: end");
                }, 30, period, TimeUnit.SECONDS);

            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
                logger.debug("Entering try block");
                String line;
                while((line = reader.readLine()) != null){
                    if(!continueReading){
                        logger.info("BME680 sensor reading stopped.");
                        break;
                    }
                    if(!line.trim().isEmpty()){
                        lastLine = line;
                        logger.debug("Read line: " + line);
                    }
                    else{
                        logger.debug("Line is empty");
                    }
                }
                logger.debug("End of the stream.");
            }
            catch(IOException e){
                logger.error("Error during reading data from sensor", e);
            }
        }
        catch(IOException e){
            logger.error("Error during starting bsec_bme680 process.", e);
        }
        catch(Exception e){
            logger.error("General error.", e);
        }
    }

    public Measurement[] extractData(String dataLine){
        Measurement[] measurement = new Measurement[6];
        String[] parts = dataLine.split(",");
        float[] values = new float[8];
        int accuracy = -1;
        for(int i = 1; i <= values.length; i++){
            if(i == 2){
                char c = parts[i -1].split(": ")[0].charAt(6);
                accuracy = Integer.parseInt(c + "");
            }
            BigDecimal decimal = new BigDecimal(parts[i].split(": ")[1]);
            decimal = decimal.setScale(2, RoundingMode.HALF_UP);
            values[i - 1] = decimal.floatValue();
        }
        measurement[0] = new Temperature(values[1]);
        measurement[1] = new Humidity(values[2]);
        measurement[2] = new Pressure(values[3]);
        measurement[3] = new IAQ((int)values[0], accuracy);
        measurement[4] = new CO2((int)values[6]);
        measurement[5] = new VOC(values[7]);
        
        return measurement;
    }

    public void addListener(SensorListener listener){
        listeners.add(listener);
    }

    protected void notifyListeners(Measurement[] measurement){
        for(SensorListener listener : listeners){
            listener.onDataReceived(measurement);
        }
    }
    
    public void stopReading() {
        continueReading = false;
        future.cancel(true);
    }
}
