package com.ringatlas.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.Record;
import android.os.OutcomeReceiver;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class HealthCompare {
    private final Context context;
    private final HealthConnectManager manager;
    HealthCompare(Context context) {
        this.context=context;
        manager=context.getSystemService(HealthConnectManager.class);
    }

    void load(WearableCompare.Series series,long from,long to,String metric) throws Exception {
        if (manager==null) return;
        if (metric.equals("hr")) {
            if (!permitted("HEART_RATE")) return;
            read(HeartRateRecord.class,from,to,record->{
                String source=label(record);
                for (var sample : record.getSamples()) {
                    long time=sample.getTime().getEpochSecond();
                    if (time>=from && time<to) series.add(source,time,"hr",sample.getBeatsPerMinute());
                }
            });
        } else if (permitted("HEART_RATE_VARIABILITY")) {
            read(HeartRateVariabilityRmssdRecord.class,from,to,record->
                series.add(label(record),record.getTime().getEpochSecond(),"hrv",record.getHeartRateVariabilityMillis()));
        }
    }

    private boolean permitted(String name) {
        return context.checkSelfPermission(HealthBridge.PREFIX+name)==PackageManager.PERMISSION_GRANTED;
    }

    private static String label(Record record) {
        var data=record.getMetadata();
        var device=data.getDevice();
        String model=device==null?null:device.getModel();
        return "Health Connect · "+data.getDataOrigin().getPackageName()+(model==null || model.isEmpty()?"":" · "+model);
    }

    private <T extends Record> void read(Class<T> kind,long from,long to,Consumer<T> consume) throws Exception {
        long token=-1;
        int pages=0;
        do {
            if (++pages>150) throw new IllegalStateException("Choose a shorter comparison window.");
            var builder=new ReadRecordsRequestUsingFilters.Builder<>(kind).setPageSize(1000)
                .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(Instant.ofEpochSecond(from))
                    .setEndTime(Instant.ofEpochSecond(to)).build());
            if (token!=-1) builder.setPageToken(token);
            var future=new CompletableFuture<ReadRecordsResponse<T>>();
            manager.readRecords(builder.build(),Runnable::run,new OutcomeReceiver<ReadRecordsResponse<T>,HealthConnectException>() {
                @Override public void onResult(ReadRecordsResponse<T> response) {future.complete(response);}
                @Override public void onError(HealthConnectException error) {future.completeExceptionally(error);}
            });
            var page=future.get(20,TimeUnit.SECONDS);
            for (T record : page.getRecords()) consume.accept(record);
            token=page.getNextPageToken();
        } while (token!=-1);
    }
}
