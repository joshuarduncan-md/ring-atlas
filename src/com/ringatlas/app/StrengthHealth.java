package com.ringatlas.app;

import android.content.Context;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.os.OutcomeReceiver;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

final class StrengthHealth {
    static void write(Context context,StrengthPlan.Session session,Consumer<String> result) {
        HealthConnectManager manager=context.getSystemService(HealthConnectManager.class);
        if(manager==null) {result.accept("Health Connect is unavailable.");return;}
        var metadata=new Metadata.Builder().setClientRecordId("ringatlas-strength-"+session.id)
            .setClientRecordVersion(System.currentTimeMillis()).build();
        var record=new ExerciseSessionRecord.Builder(metadata,Instant.ofEpochSecond(session.started),Instant.ofEpochSecond(session.finished),
            ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING).setTitle("Ring Atlas · workout "+session.rotation).build();
        manager.insertRecords(List.of(record),context.getMainExecutor(),new OutcomeReceiver<InsertRecordsResponse,HealthConnectException>() {
            @Override public void onResult(InsertRecordsResponse response) {result.accept("Workout saved to Health Connect.");}
            @Override public void onError(HealthConnectException error) {result.accept("Health Connect did not accept this workout.");}
        });
    }
}
