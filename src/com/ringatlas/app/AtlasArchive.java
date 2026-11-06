package com.ringatlas.app;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

final class AtlasArchive {
    private record Table(String name,String[] columns,String types) {
        Table(String name,String columns,String types) { this(name,columns.split(" "),types); }
    }
    private static final List<Table> TABLES=List.of(
        new Table("daily","source day metric value","sssd"),
        new Table("notes","id day tag body","isss"),
        new Table("events","device epoch tick tag body","siiib"),
        new Table("checkpoints","device epoch tick","sii"),
        new Table("workouts","id source start end type intensity status","ssiisii"),
        new Table("sleep_timeline","source day stages","ssb"),
        new Table("strength_state","slot payload","ss"),
        new Table("wearable_samples","source bucket metric value count","sisdi"));
    private static final int UNCOMPRESSED_LIMIT=128*1024*1024;

    static void write(LocalStore store,SharedPreferences prefs,OutputStream out,char[] password) throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try (var enc=BackupCipher.encrypt(BackupCipher.bound(out),password);
             var gzip=new GZIPOutputStream(enc);
             var writer=new JsonWriter(new OutputStreamWriter(
                 BackupCipher.bound(gzip,UNCOMPRESSED_LIMIT,"This backup exceeds the 128 MB archive limit."),StandardCharsets.UTF_8))) {
            writer.beginObject().name("format").value(1);
            writer.name("settings").beginObject();
            writer.name("step_goal").value(prefs.getInt("step_goal",0));
            writer.name("rest").value(prefs.getBoolean("rest",false));
            writer.name("source").value(prefs.getString("source","Sample data"));
            writer.name("address").value(prefs.getString("address",""));
            writer.endObject().name("tables").beginObject();
            for (Table table : TABLES) {
                writer.name(table.name()).beginArray();
                try (Cursor cursor=db.query(table.name(),table.columns(),null,null,null,null,null)) {
                    while (cursor.moveToNext()) {
                        writer.beginArray();
                        for (int i=0;i<table.columns().length;i++) switch (table.types().charAt(i)) {
                            case 'i' -> writer.value(cursor.getLong(i));
                            case 'd' -> writer.value(cursor.getDouble(i));
                            case 'b' -> writer.value(Base64.encodeToString(cursor.getBlob(i),Base64.NO_WRAP));
                            default -> writer.value(cursor.getString(i));
                        }
                        writer.endArray();
                    }
                }
                writer.endArray();
            }
            writer.endObject().endObject();
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    static void restore(LocalStore store,SharedPreferences prefs,InputStream in,char[] password) throws Exception {
        byte[] compressed=BackupCipher.decrypt(in,password);
        SQLiteDatabase db=store.getWritableDatabase();
        try (var gzip=new GZIPInputStream(new ByteArrayInputStream(compressed));
             var reader=new JsonReader(new InputStreamReader(limited(gzip),StandardCharsets.UTF_8))) {
            db.beginTransaction();
            try {
                reader.beginObject();
                if (!reader.nextName().equals("format") || reader.nextInt()!=1 || !reader.nextName().equals("settings"))
                    throw new IOException("Unsupported Ring Atlas backup format.");
                JSONObject settings=readSettings(reader);
                if (!reader.nextName().equals("tables")) throw new IOException("Backup data is incomplete.");
                reader.beginObject();
                for (Table table : TABLES) {
                    if (!reader.hasNext() || !reader.nextName().equals(table.name())) throw new IOException("Backup tables are incomplete.");
                    db.delete(table.name(),null,null);
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginArray();
                        ContentValues row=new ContentValues();
                        for (int i=0;i<table.columns().length;i++) {
                            String column=table.columns()[i];
                            switch (table.types().charAt(i)) {
                                case 'i' -> row.put(column,reader.nextLong());
                                case 'd' -> row.put(column,reader.nextDouble());
                                case 'b' -> row.put(column,Base64.decode(reader.nextString(),Base64.NO_WRAP));
                                default -> row.put(column,reader.nextString());
                            }
                        }
                        reader.endArray();
                        if (table.name().equals("strength_state")) StrengthPlan.decode(row.getAsString("payload"));
                        db.insertOrThrow(table.name(),null,row);
                    }
                    reader.endArray();
                }
                reader.endObject(); reader.endObject();
                if (reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Unexpected data after the archive.");
                ContentValues pending=new ContentValues(); pending.put("slot",1); pending.put("payload",settings.toString());
                db.insertWithOnConflict("restore_settings",null,pending,SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            applySettings(store,prefs);
        } finally {Arrays.fill(compressed,(byte)0);}
    }

    private static JSONObject readSettings(JsonReader reader) throws Exception {
        JSONObject settings=new JSONObject();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "step_goal" -> settings.put("step_goal",Math.max(0,Math.min(100000,reader.nextInt())));
                case "rest" -> settings.put("rest",reader.nextBoolean());
                case "source" -> settings.put("source",reader.nextString());
                case "address" -> settings.put("address",reader.nextString());
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return settings;
    }

    static void applySettings(LocalStore store,SharedPreferences prefs) throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();
        try (Cursor cursor=db.rawQuery("SELECT payload FROM restore_settings WHERE slot=1",null)) {
            if (!cursor.moveToFirst()) return;
            JSONObject data=new JSONObject(cursor.getString(0));
            if (!prefs.edit().putInt("step_goal",data.optInt("step_goal")).putBoolean("rest",data.optBoolean("rest"))
                .putString("source",data.optString("source","Sample data")).putString("address",data.optString("address")).commit())
                throw new IOException("Data restored. The app will retry applying settings next time it opens.");
        }
        db.delete("restore_settings","slot=1",null);
    }

    private static InputStream limited(InputStream source) {
        return new FilterInputStream(source) {
            private long read;
            private void count(int n) throws IOException {
                if (n>0 && (read+=n)>UNCOMPRESSED_LIMIT) throw new IOException("The backup expands beyond 128 MB.");
            }
            @Override public int read() throws IOException { int n=in.read(); if(n!=-1) count(1); return n; }
            @Override public int read(byte[] bytes,int start,int size) throws IOException {
                int n=in.read(bytes,start,size); count(n); return n;
            }
        };
    }
}
