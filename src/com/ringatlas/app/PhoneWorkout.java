package com.ringatlas.app;

import android.content.Context;
import android.hardware.*;
import android.location.*;
import android.os.*;
import java.time.Instant;
import java.util.*;

final class PhoneWorkout implements SensorEventListener, LocationListener {
    private final SensorManager sensors;
    private final LocationManager locations;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final List<Double> speeds=new ArrayList<>();
    private final Map<String,Integer> votes=new HashMap<>();
    private boolean running, hasSteps, gps;
    private long began, beganElapsed, window;
    private int steps, motionCount, accelCount;
    private String last="Collecting a 30-second sample";

    PhoneWorkout(Context context) {
        sensors=context.getSystemService(SensorManager.class);
        locations=context.getSystemService(LocationManager.class);
    }

    void start(boolean allowGps) {
        if (running) return;
        began=Instant.now().getEpochSecond();
        beganElapsed=window=SystemClock.elapsedRealtime();
        steps=motionCount=accelCount=0; votes.clear(); speeds.clear(); gps=false; hasSteps=false;
        last="Collecting a 30-second sample"; running=true;
        if (sensors!=null) {
            Sensor step=sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            if (step!=null) hasSteps=sensors.registerListener(this,step,SensorManager.SENSOR_DELAY_NORMAL);
            Sensor movement=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (movement!=null) sensors.registerListener(this,movement,SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (allowGps && locations!=null) {
            try {
                if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0,this,Looper.getMainLooper());
                    gps=true;
                }
            } catch (IllegalArgumentException|SecurityException ignored) { }
        }
        timer.postDelayed(sample,30000);
    }

    boolean running() { return running; }
    String status() {
        if (!running) return "";
        long elapsed=(SystemClock.elapsedRealtime()-beganElapsed)/1000;
        String supports=hasSteps?"Step sensor":"No step sensor";
        return elapsed/60+"m "+(elapsed%60)+"s · "+last+"\n"+supports+(gps?" + GPS speed":"");
    }

    LocalStore.Workout stop() {
        if (!running) return null;
        running=false; timer.removeCallbacks(sample);
        if (sensors!=null) sensors.unregisterListener(this);
        if (gps && locations!=null) locations.removeUpdates(this);
        long elapsed=(SystemClock.elapsedRealtime()-beganElapsed)/1000;
        if (elapsed<60) return null;
        update();
        String type=votes.entrySet().stream().max(Map.Entry.comparingByValue()).filter(v->v.getValue()>=2)
            .map(Map.Entry::getKey).orElse("Unclassified movement");
        return new LocalStore.Workout("phone:"+began,LocalStore.PHONE,began,began+elapsed,type,0,LocalStore.PENDING);
    }

    private final Runnable sample=new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            timer.postDelayed(this,30000);
        }
    };

    private void update() {
        long now=SystemClock.elapsedRealtime();
        long duration=now-window;
        if (duration<24000) return;
        speeds.sort(Double::compare);
        Double speed=speeds.size()<3?null:speeds.get(speeds.size()/2);
        boolean motion=accelCount>=5 && (double)motionCount/accelCount>.14;
        last=ActivityDetection.phoneType(steps*60000.0/duration,speed,motion,hasSteps);
        if (!last.equals("Unclassified movement")) votes.merge(last,1,Integer::sum);
        window=now; steps=motionCount=accelCount=0; speeds.clear();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        if (event.sensor.getType()==Sensor.TYPE_STEP_DETECTOR) steps++;
        else {
            double magnitude=Math.sqrt(event.values[0]*event.values[0]+event.values[1]*event.values[1]+event.values[2]*event.values[2]);
            accelCount++;
            if (Math.abs(magnitude-9.81)>1.1) motionCount++;
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
    @Override public void onLocationChanged(Location location) {
        if (running && location.hasSpeed() && location.hasAccuracy() && location.getAccuracy()<30 && speeds.size()<30) {
            if (!location.hasSpeedAccuracy() || location.getSpeedAccuracyMetersPerSecond()<2)
                speeds.add((double)location.getSpeed());
        }
    }
}
