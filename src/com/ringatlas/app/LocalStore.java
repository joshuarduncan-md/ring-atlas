package com.ringatlas.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.time.*;
import java.util.*;

final class LocalStore extends SQLiteOpenHelper {
    static final String FILES = "Oura files", HEALTH = "Health Connect", RING = "Direct Bluetooth",
        CARDIO = "Atlas cardio tests", PHONE = "Phone activity", MANUAL = "Manual activity";
    record Workout(String id,String source,long start,long end,String type,int intensity,int status) {}
    static final int PENDING=0, CONFIRMED=1, DISMISSED=2;
    record Note(long id, LocalDate date, String tag, String text) {}
    LocalStore(Context context) { super(context, "ring-atlas.db", null, 3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily(source TEXT NOT NULL, day TEXT NOT NULL, metric TEXT NOT NULL, value REAL NOT NULL, PRIMARY KEY(source,day,metric))");
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, day TEXT NOT NULL, tag TEXT NOT NULL, body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events(device TEXT NOT NULL, epoch INTEGER NOT NULL, tick INTEGER NOT NULL, tag INTEGER NOT NULL, body BLOB NOT NULL, PRIMARY KEY(device,epoch,tick,tag,body))");
        db.execSQL("CREATE TABLE checkpoints(device TEXT PRIMARY KEY, epoch INTEGER NOT NULL, tick INTEGER NOT NULL)");
        createV2Tables(db);
        createV3Tables(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if (oldVersion==1) createV2Tables(db);
        if (oldVersion<=2) createV3Tables(db);
    }
    private void createV3Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE strength_state(slot TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE wearable_samples(source TEXT NOT NULL, bucket INTEGER NOT NULL, metric TEXT NOT NULL, value REAL NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(source,bucket,metric))");
        db.execSQL("CREATE TABLE restore_settings(slot INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
    }

    String strengthState() {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM strength_state WHERE slot='training'",null)) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    void saveStrength(String expected,String payload,Workout completed,String removeId) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            if (!Objects.equals(expected,strengthState())) throw new ConcurrentModificationException("The lifting log changed.");
            ContentValues row=new ContentValues(); row.put("slot","training"); row.put("payload",payload);
            db.insertWithOnConflict("strength_state",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            if (removeId!=null) db.delete("workouts","id=?",new String[]{removeId});
            if (completed!=null) saveWorkout(db,completed);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    void saveWearableSamples(List<WearableCompare.Point> points) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            for (WearableCompare.Point point : points) {
                ContentValues row=new ContentValues();
                row.put("source",point.source()); row.put("bucket",point.bucket()); row.put("metric",point.metric());
                row.put("value",point.value()); row.put("count",point.count());
                db.insertWithOnConflict("wearable_samples",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    List<WearableCompare.Point> wearableSamples(long from,long to) {
        List<WearableCompare.Point> points=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT source,bucket,metric,value,count FROM wearable_samples WHERE bucket>=? AND bucket<?",
                new String[]{Long.toString(from),Long.toString(to)})) {
            while (c.moveToNext()) points.add(new WearableCompare.Point(c.getString(0),c.getLong(1),c.getString(2),c.getDouble(3),c.getInt(4)));
        }
        return points;
    }
    private void createV2Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE workouts(id TEXT PRIMARY KEY, source TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, type TEXT NOT NULL, intensity INTEGER NOT NULL, status INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX workouts_time ON workouts(start,end)");
        db.execSQL("CREATE TABLE sleep_timeline(source TEXT NOT NULL, day TEXT NOT NULL, stages BLOB NOT NULL, PRIMARY KEY(source,day))");
    }

    void saveTimelines(String source,Map<LocalDate,byte[]> stages) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            stages.forEach((day,sequence)->{
                ContentValues row=new ContentValues();
                row.put("source",source); row.put("day",day.toString()); row.put("stages",sequence);
                db.insertWithOnConflict("sleep_timeline",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            });
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    byte[] timeline(String source,LocalDate day) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT stages FROM sleep_timeline WHERE source=? AND day=?",
                new String[]{source,day.toString()})) {
            return c.moveToFirst()?c.getBlob(0):new byte[0];
        }
    }

    void saveWorkout(Workout workout) {
        saveWorkout(getWritableDatabase(),workout);
    }

    private void saveWorkout(SQLiteDatabase db,Workout workout) {
        if (workout.end()<=workout.start()) return;
        ContentValues row=new ContentValues();
        row.put("id",workout.id()); row.put("source",workout.source());
        row.put("start",workout.start()); row.put("end",workout.end());
        row.put("type",workout.type()); row.put("intensity",workout.intensity()); row.put("status",workout.status());
        db.insertWithOnConflict("workouts",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }

    void resolveWorkout(Workout workout,String type,int intensity,int status) {
        ContentValues row=new ContentValues();
        row.put("type",type); row.put("intensity",intensity); row.put("status",status);
        getWritableDatabase().update("workouts",row,"id=? AND status=?",
            new String[]{workout.id(),Integer.toString(workout.status())});
    }

    void saveRingBouts(String source,List<ActivityDetection.Bout> bouts) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            for (var bout : bouts) {
                String[] match={source,Long.toString(bout.end()),Long.toString(bout.start())};
                String id="ring:"+source+":"+bout.start();
                try (Cursor c=db.rawQuery("SELECT id,status FROM workouts WHERE source=? AND start<? AND end>? ORDER BY status DESC LIMIT 1",match)) {
                    if (c.moveToFirst()) {
                        if (c.getInt(1)!=PENDING) continue;
                        id=c.getString(0);
                    }
                }
                saveWorkout(db,new Workout(id,source,bout.start(),bout.end(),bout.type(),bout.intensity(),PENDING));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void replaceHealthWorkouts(List<Workout> workouts,Instant from,Instant through) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("workouts","source=? AND start>=? AND start<?",
                new String[]{HEALTH,Long.toString(from.getEpochSecond()),Long.toString(through.getEpochSecond())});
            for (Workout workout : workouts) saveWorkout(db,workout);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<Workout> workouts(long start,long end) {
        return workouts("status<>2 AND start<? AND end>?",new String[]{Long.toString(end),Long.toString(start)});
    }

    List<Workout> localWorkouts() {
        return workouts("status=1 AND (source=? OR source=? OR source=?)",new String[]{PHONE,MANUAL,"Strength training"});
    }

    private List<Workout> workouts(String selection,String[] args) {
        List<Workout> rows=new ArrayList<>();
        try (Cursor c=getReadableDatabase().query("workouts",new String[]{"id","source","start","end","type","intensity","status"},
                selection,args,null,null,"start DESC")) {
            while (c.moveToNext()) rows.add(new Workout(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),
                c.getString(4),c.getInt(5),c.getInt(6)));
        }
        return rows;
    }

    void exportWorkouts(Writer out) throws IOException {
        out.write("start,end,activity,intensity,source,status\n");
        for (Workout workout : workouts("status<>2",null)) {
            String label=workout.source().startsWith(RING+"|")?RING:workout.source();
            out.write(Instant.ofEpochSecond(workout.start())+","+Instant.ofEpochSecond(workout.end())+","+
                quote(workout.type())+","+ActivityDetection.INTENSITIES[workout.intensity()]+","+quote(label)+","+
                (workout.status()==CONFIRMED?"Confirmed":"Suggested")+"\n");
        }
    }
    NavigableMap<LocalDate,Metrics.Day> days(String source) {
        NavigableMap<LocalDate,Metrics.Day> result = new TreeMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day,metric,value FROM daily WHERE source=? ORDER BY day",new String[]{source})) {
            while (cursor.moveToNext()) {
                LocalDate date = LocalDate.parse(cursor.getString(0));
                result.computeIfAbsent(date,Metrics.Day::new).set(cursor.getString(1),cursor.getDouble(2));
            }
        }
        return result;
    }
    void saveDays(String source, Collection<Metrics.Day> days) {
        replaceDays(source,days,null,null);
    }
    void replaceDays(String source, Collection<Metrics.Day> days, LocalDate from, LocalDate through) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (from != null) db.delete("daily","source=? AND day>=? AND day<=?",new String[]{source,from.toString(),through.toString()});
            for (Metrics.Day day : days) {
                for (Map.Entry<String,Double> entry : day.values.entrySet()) {
                    ContentValues row = new ContentValues();
                    row.put("source",source); row.put("day",day.date.toString());
                    row.put("metric",entry.getKey()); row.put("value",entry.getValue());
                    db.insertWithOnConflict("daily",null,row,SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void addNote(LocalDate date,String tag,String body) {
        ContentValues row = new ContentValues();
        row.put("day",date.toString()); row.put("tag",tag); row.put("body",body);
        getWritableDatabase().insertOrThrow("notes",null,row);
    }
    void deleteNote(long id) { getWritableDatabase().delete("notes","id=?",new String[]{Long.toString(id)}); }
    List<Note> notes() {
        List<Note> notes = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,day,tag,body FROM notes ORDER BY day DESC,id DESC",null)) {
            while (cursor.moveToNext()) notes.add(new Note(cursor.getLong(0),LocalDate.parse(cursor.getString(1)),cursor.getString(2),cursor.getString(3)));
        }
        return notes;
    }
    record Checkpoint(int epoch,long tick) {}
    Checkpoint checkpoint(String device) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT epoch,tick FROM checkpoints WHERE device=?",new String[]{device})) {
            return c.moveToFirst() ? new Checkpoint(c.getInt(0),c.getLong(1)) : new Checkpoint(0,0);
        }
    }
    void events(String device, Checkpoint checkpoint, List<RingProtocol.Event> events) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (RingProtocol.Event event : events) {
                ContentValues row = new ContentValues();
                row.put("device",device); row.put("epoch",checkpoint.epoch()); row.put("tick",event.tick());
                row.put("tag",event.tag()); row.put("body",event.body());
                db.insertWithOnConflict("events",null,row,SQLiteDatabase.CONFLICT_IGNORE);
            }
            ContentValues row = new ContentValues();
            row.put("device",device); row.put("epoch",checkpoint.epoch()); row.put("tick",checkpoint.tick());
            db.insertWithOnConflict("checkpoints",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    List<RingProtocol.Event> events(String device,int epoch) {
        List<RingProtocol.Event> events = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT tick,tag,body FROM events WHERE device=? AND epoch=? ORDER BY tick",
                new String[]{device,Integer.toString(epoch)})) {
            while (c.moveToNext()) events.add(new RingProtocol.Event(c.getLong(0),c.getInt(1),c.getBlob(2)));
        }
        return events;
    }
    List<String> ringDevices() {
        List<String> devices=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT device FROM events ORDER BY device",null)) {
            while (c.moveToNext()) devices.add(c.getString(0));
        }
        return devices;
    }
    List<Integer> eventEpochs(String device) {
        List<Integer> epochs=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT epoch FROM events WHERE device=? ORDER BY epoch",new String[]{device})) {
            while (c.moveToNext()) epochs.add(c.getInt(0));
        }
        return epochs;
    }
    int eventCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM events",null)) { c.moveToFirst(); return c.getInt(0); }
    }
    void exportEvents(Writer out) throws IOException {
        out.write("device,epoch,ring_tick_deciseconds,event_tag_hex,body_hex\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT device,epoch,tick,tag,body FROM events ORDER BY device,epoch,tick",null)) {
            while (c.moveToNext()) out.write(c.getString(0)+","+c.getInt(1)+","+c.getLong(2)+","+
                    Integer.toHexString(c.getInt(3))+","+RingProtocol.hex(c.getBlob(4))+"\n");
        }
    }
    void exportDays(Writer out,Collection<Metrics.Day> readings) throws IOException {
        out.write("day");
        for (String key : Metrics.ALL.keySet()) out.write(","+key);
        out.write("\n");
        for (Metrics.Day day : readings) {
            out.write(day.date.toString());
            for (String key : Metrics.ALL.keySet()) {
                Double value = day.get(key);
                out.write(","+(value == null ? "" : value.toString()));
            }
            out.write("\n");
        }
    }
    void exportNotes(Writer out) throws IOException {
        out.write("day,tag,note\n");
        for (Note note : notes()) out.write(note.date()+","+quote(note.tag())+","+quote(note.text())+"\n");
    }
    private static String quote(String text) {
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"","\"\"") + "\"";
    }
    void erase() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : List.of("daily","notes","events","checkpoints","workouts","sleep_timeline","strength_state","wearable_samples","restore_settings")) db.delete(table,null,null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
