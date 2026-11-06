package com.ringatlas.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.Record;
import android.os.OutcomeReceiver;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static android.health.connect.datatypes.SleepSessionRecord.StageType.*;
import static android.health.connect.datatypes.ExerciseSessionType.*;

final class HealthBridge {
    static final String PREFIX = "android.permission.health.READ_";
    static final String[] NAMES = {"SLEEP","HEART_RATE","HEART_RATE_VARIABILITY","STEPS","DISTANCE","ACTIVE_CALORIES_BURNED","EXERCISE"};
    static String[] permissions() { return Arrays.stream(NAMES).map(name -> PREFIX+name).toArray(String[]::new); }
    private final Context context;
    private final HealthConnectManager manager;
    private final LocalDate today = LocalDate.now();
    private final LocalDate from = today.minusDays(29);
    private final ZoneId zone = ZoneId.systemDefault();
    private final Map<LocalDate,Metrics.Day> days = new TreeMap<>();
    private final Map<LocalDate,double[]> hr = new HashMap<>(), hrv = new HashMap<>();
    private final List<LocalStore.Workout> workouts=new ArrayList<>();
    private final DataOrigin origin = new DataOrigin.Builder().setPackageName("com.ouraring.oura").build();

    HealthBridge(Context context) {
        this.context=context;
        manager=context.getSystemService(HealthConnectManager.class);
    }

    int sync(LocalStore store) throws Exception {
        if (manager == null) throw new IllegalStateException("Health Connect is unavailable on this phone.");
        if (Arrays.stream(NAMES).noneMatch(this::allowed)) throw new IllegalStateException("Allow at least one Health Connect permission.");
        read("SLEEP",SleepSessionRecord.class,this::sleep);
        read("HEART_RATE",HeartRateRecord.class,(unused,record) -> {
            for (HeartRateRecord.HeartRateSample sample : record.getSamples()) {
                LocalDate day = date(sample.getTime());
                if (!day.isBefore(from) && !day.isAfter(today)) tally(hr,day,sample.getBeatsPerMinute());
            }
        });
        read("HEART_RATE_VARIABILITY",HeartRateVariabilityRmssdRecord.class,
            (day,record) -> tally(hrv,day,record.getHeartRateVariabilityMillis()));
        read("STEPS",StepsRecord.class,(day,r) -> distribute("steps",r.getStartTime(),r.getEndTime(),r.getCount()));
        read("DISTANCE",DistanceRecord.class,(day,r) -> distribute("distance",r.getStartTime(),r.getEndTime(),r.getDistance().getInMeters()/1000));
        read("ACTIVE_CALORIES_BURNED",ActiveCaloriesBurnedRecord.class,
            (day,r) -> distribute("active_cal",r.getStartTime(),r.getEndTime(),r.getEnergy().getInCalories()/1000));
        read("EXERCISE",ExerciseSessionRecord.class,(day,r) -> {
            if (r.getExerciseType()==EXERCISE_SESSION_TYPE_GUIDED_BREATHING) return;
            long start=r.getStartTime().getEpochSecond(), end=r.getEndTime().getEpochSecond();
            String label=r.getTitle()==null || r.getTitle().toString().trim().isEmpty() ? type(r.getExerciseType()) : r.getTitle().toString();
            workouts.add(new LocalStore.Workout("health:"+r.getMetadata().getId(),LocalStore.HEALTH,start,end,label,0,LocalStore.CONFIRMED));
            accumulate(day,"workouts",1);
            distribute("exercise",r.getStartTime(),r.getEndTime(),minutes(r.getStartTime(),r.getEndTime()));
        });
        hr.forEach((day,stat)->get(day).set("hr",stat[0]/stat[1]));
        hrv.forEach((day,stat)->get(day).set("hrv",stat[0]/stat[1]));
        for (Metrics.Day day : days.values()) {
            Double asleep=day.get("sleep"), bed=day.get("bed"), staged=day.get("awake");
            if (asleep != null && bed != null && staged != null && bed>0 && asleep+staged>=bed*.95)
                day.set("eff",Math.min(100,100*asleep/bed));
        }
        store.replaceDays(LocalStore.HEALTH,days.values(),from,today);
        if (allowed("EXERCISE")) store.replaceHealthWorkouts(workouts,from.atStartOfDay(zone).toInstant(),Instant.now());
        return days.size();
    }

    private static String type(int kind) {
        return switch (kind) {
            case EXERCISE_SESSION_TYPE_WALKING -> "Walking";
            case EXERCISE_SESSION_TYPE_RUNNING, EXERCISE_SESSION_TYPE_RUNNING_TREADMILL -> "Running";
            case EXERCISE_SESSION_TYPE_BIKING, EXERCISE_SESSION_TYPE_BIKING_STATIONARY -> "Cycling";
            case EXERCISE_SESSION_TYPE_HIKING -> "Hiking";
            case EXERCISE_SESSION_TYPE_STRENGTH_TRAINING, EXERCISE_SESSION_TYPE_WEIGHTLIFTING -> "Strength training";
            case EXERCISE_SESSION_TYPE_SWIMMING_POOL, EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER -> "Swimming";
            case EXERCISE_SESSION_TYPE_ROWING, EXERCISE_SESSION_TYPE_ROWING_MACHINE -> "Rowing";
            case EXERCISE_SESSION_TYPE_ELLIPTICAL -> "Elliptical";
            case EXERCISE_SESSION_TYPE_YOGA -> "Yoga";
            case EXERCISE_SESSION_TYPE_PILATES -> "Pilates";
            case EXERCISE_SESSION_TYPE_DANCING -> "Dance";
            case EXERCISE_SESSION_TYPE_TENNIS, EXERCISE_SESSION_TYPE_BADMINTON, EXERCISE_SESSION_TYPE_SQUASH -> "Racket sport";
            case EXERCISE_SESSION_TYPE_SOCCER, EXERCISE_SESSION_TYPE_BASKETBALL, EXERCISE_SESSION_TYPE_VOLLEYBALL -> "Team sport";
            default -> "Exercise";
        };
    }

    private boolean allowed(String permission) {
        return context.checkSelfPermission(PREFIX+permission)==PackageManager.PERMISSION_GRANTED;
    }

    private <T extends Record> void read(String permission,Class<T> kind,BiConsumer<LocalDate,T> consume) throws Exception {
        if (!allowed(permission)) return;
        long token=-1;
        int pages=0;
        do {
            if (++pages>250) throw new IllegalStateException("Health Connect returned too many pages.");
            var builder=new ReadRecordsRequestUsingFilters.Builder<>(kind)
                .addDataOrigins(origin).setPageSize(1000)
                .setTimeRangeFilter(new TimeInstantRangeFilter.Builder()
                    .setStartTime(from.atStartOfDay(zone).toInstant()).setEndTime(Instant.now()).build());
            if (token!=-1) builder.setPageToken(token);
            CompletableFuture<ReadRecordsResponse<T>> future=new CompletableFuture<>();
            manager.readRecords(builder.build(),Runnable::run,new OutcomeReceiver<ReadRecordsResponse<T>,HealthConnectException>() {
                @Override public void onResult(ReadRecordsResponse<T> response) { future.complete(response); }
                @Override public void onError(HealthConnectException error) { future.completeExceptionally(error); }
            });
            ReadRecordsResponse<T> page=future.get(25,TimeUnit.SECONDS);
            for (T record : page.getRecords()) {
                if (!origin.getPackageName().equals(record.getMetadata().getDataOrigin().getPackageName())) continue;
                Instant when = record instanceof SleepSessionRecord r ? r.getEndTime()
                    : record instanceof IntervalRecord r ? r.getStartTime() : ((InstantRecord)record).getTime();
                LocalDate day=date(when);
                if (!day.isBefore(from) && !day.isAfter(today)) consume.accept(day,record);
            }
            token=page.getNextPageToken();
        } while (token!=-1);
    }

    private void sleep(LocalDate date,SleepSessionRecord record) {
        accumulate(date,"bed",minutes(record.getStartTime(),record.getEndTime()));
        for (var stage : record.getStages()) {
            double duration=minutes(stage.getStartTime(),stage.getEndTime());
            switch (stage.getType()) {
                case STAGE_TYPE_SLEEPING_DEEP -> { accumulate(date,"sleep",duration); accumulate(date,"deep",duration); }
                case STAGE_TYPE_SLEEPING_REM -> { accumulate(date,"sleep",duration); accumulate(date,"rem",duration); }
                case STAGE_TYPE_SLEEPING_LIGHT -> { accumulate(date,"sleep",duration); accumulate(date,"light",duration); }
                case STAGE_TYPE_SLEEPING -> accumulate(date,"sleep",duration);
                case STAGE_TYPE_AWAKE,STAGE_TYPE_AWAKE_IN_BED,STAGE_TYPE_AWAKE_OUT_OF_BED -> accumulate(date,"awake",duration);
                default -> { }
            }
        }
    }
    private void distribute(String key,Instant start,Instant end,double value) {
        double duration=minutes(start,end);
        if (duration<=0) return;
        for (LocalDate d=date(start); !d.isAfter(date(end)) && !d.isAfter(today); d=d.plusDays(1)) {
            if (d.isBefore(from)) continue;
            Instant lower=d.atStartOfDay(zone).toInstant(), upper=d.plusDays(1).atStartOfDay(zone).toInstant();
            double overlap=minutes(start.isAfter(lower)?start:lower,end.isBefore(upper)?end:upper);
            if (overlap>0) accumulate(d,key,value*overlap/duration);
        }
    }
    private static void tally(Map<LocalDate,double[]> map,LocalDate day,double value) {
        if (!Double.isFinite(value) || value<=0) return;
        double[] count=map.computeIfAbsent(day,k->new double[2]); count[0]+=value; count[1]++;
    }
    private Metrics.Day get(LocalDate d) { return days.computeIfAbsent(d,Metrics.Day::new); }
    private void accumulate(LocalDate d,String key,double amount) { get(d).set(key,get(d).values.getOrDefault(key,0.0)+amount); }
    private LocalDate date(Instant instant) { return instant.atZone(zone).toLocalDate(); }
    private static double minutes(Instant start,Instant end) { return Duration.between(start,end).toMillis()/60000.0; }
}
