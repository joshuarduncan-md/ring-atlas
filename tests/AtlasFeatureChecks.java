package com.ringatlas.app;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.ringatlas.app.AtlasChecks.*;

public final class AtlasFeatureChecks {
    public static void main(String[] args) throws Exception {
        check("backup encryption round trip and tamper rejection", () -> {
            char[] secret="test password with spaces".toCharArray();
            byte[] clear="private workout record and ring history".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream file=new ByteArrayOutputStream();
            try(OutputStream encrypted=BackupCipher.encrypt(file,secret)) {encrypted.write(clear);}
            byte[] contents=file.toByteArray();
            equal(true,Arrays.equals(clear,BackupCipher.decrypt(new ByteArrayInputStream(contents),secret)));
            equal(false,new String(contents,StandardCharsets.UTF_8).contains("workout record"));
            expectError(()->BackupCipher.decrypt(new ByteArrayInputStream(contents),"wrong-passphrase".toCharArray()));
            contents[contents.length-2]^=1;
            expectError(()->BackupCipher.decrypt(new ByteArrayInputStream(contents),secret));
            expectError(()->BackupCipher.decrypt(new ByteArrayInputStream(new byte[3]),secret));
        });

        check("wearable five-minute alignment, averages, missing windows, and error", () -> {
            var series=new WearableCompare.Series();
            series.add("ring",610,"hr",50);series.add("ring",700,"hr",60);series.add("ring",905,"hr",60);
            series.add("watch",615,"hr",65);series.add("watch",1205,"hr",77);
            series.add("watch",630,"hrv",75);
            var result=WearableCompare.compare(series.points(),"hr","ring","watch");
            equal(1,result.pairs());equal(1,result.onlyA());equal(1,result.onlyB());
            equal(-10.0,result.bias());equal(10.0,result.mae());equal(10.0,result.rmse());
            equal(55.0,result.windows().get(0).a());equal(65.0,result.windows().get(0).b());
            equal(null,WearableCompare.compare(series.points(),"hrv","ring","watch").mae());
        });

        check("wearable CSV requires explicit time offsets and rejects invalid values", () -> {
            String header="timestamp,source,metric,value\n";
            var points=WearableCompare.csv(new StringReader(header+"2025-05-01T15:01:00-04:00,\"Polar, H10\",hr,120\n"+
                "2025-05-01T19:04:00Z,\"Polar, H10\",hr,126"));
            equal(1,points.size());equal(123.0,points.get(0).value());equal(2,points.get(0).count());
            equal("CSV · Polar, H10",points.get(0).source());
            expectError(()->WearableCompare.csv(new StringReader(header+"2025-05-01T19:04:00,Watch,hr,70")));
            expectError(()->WearableCompare.csv(new StringReader(header+"2025-05-01T19:04:00Z,Watch,hr,500")));
        });

        check("FHIR bundle references and DST-aware effective periods", () -> {
            Metrics.Day day=new Metrics.Day(LocalDate.of(2026,3,8)).set("hr",72).set("temp",33.4).set("atlas_vo2",42).set("sleep",420);
            JSONObject bundle=FhirExport.bundle(List.of(day),Set.of("hr","temp","atlas_vo2"),ZoneId.of("America/New_York"),
                "Direct Bluetooth",Instant.parse("2026-04-01T15:00:00Z"));
            equal("collection",bundle.getString("type"));
            JSONArray entries=bundle.getJSONArray("entry");
            equal(7,entries.length());
            Set<String> references=new HashSet<>();
            for (int i=0;i<entries.length();i++) references.add(entries.getJSONObject(i).getString("fullUrl"));
            JSONObject hr=entries.getJSONObject(2).getJSONObject("resource");
            equal("Observation",hr.getString("resourceType"));
            equal("/min",hr.getJSONObject("valueQuantity").getString("code"));
            equal("2026-03-08T00:00:00-05:00",hr.getJSONObject("effectivePeriod").getString("start"));
            equal("2026-03-09T00:00:00-04:00",hr.getJSONObject("effectivePeriod").getString("end"));
            equal(true,references.contains(hr.getJSONObject("subject").getString("reference")));
            JSONObject provenance=entries.getJSONObject(5).getJSONObject("resource");
            equal("Provenance",provenance.getString("resourceType"));
            for (int i=0;i<3;i++) equal(true,references.contains(provenance.getJSONArray("target").getJSONObject(i).getString("reference")));
            equal("CodeSystem",entries.getJSONObject(6).getJSONObject("resource").getString("resourceType"));
            if (args.length>0) Files.writeString(Path.of(args[0]),bundle.toString(2));
        });

        check("classic 5×5 rotation, one deadlift set, and successful progression", () -> {
            StrengthPlan plan=new StrengthPlan();
            var a=plan.begin("A",1000);
            equal(List.of("Squat","Bench Press","Barbell Row"),a.lifts.stream().map(l->l.line.name()).toList());
            equal(5,a.lifts.get(0).sets.size());
            complete(plan,1,1050);
            equal("B",plan.nextRotation());
            equal(50.0,plan.next("Squat",2000).weight());
            var b=plan.begin("B",2000);
            equal(1,b.lifts.get(2).sets.size());equal("Deadlift",b.lifts.get(2).line.name());
            equal(95.0,b.lifts.get(2).plan);
        });

        check("missed reps deload, pending sets repeat, and break reduction", () -> {
            StrengthPlan plan=new StrengthPlan();
            plan.rules.put("Squat",new StrengthPlan.Rule(100,5,1,3,10,true,false));
            long time=1700000000;
            for (int i=0;i<3;i++) {
                plan.begin("A",time);
                equal(100.0,plan.active.lifts.get(0).plan);
                complete(plan,-1,time+60);time+=86400;
            }
            equal(90.0,plan.next("Squat",time).weight());
            var partial=plan.begin("B",time);
            partial.lifts.get(0).sets.set(0,new StrengthPlan.SetLog(5,90));
            partial.finished=time+60;plan.history.add(partial);plan.active=null;
            equal(90.0,plan.next("Squat",time+3600).weight());
            equal(true,plan.next("Squat",time+15*86400).weight()<90);
        });

        check("plate calculator uses inventory combinations and keeps warmups below working load", () -> {
            var plan=new StrengthPlan();plan.bar=20;plan.plates.clear();
            plan.plates.add(new StrengthPlan.Plate(4,1));plan.plates.add(new StrengthPlan.Plate(3,2));
            var exact=plan.loaded(32);
            equal(32.0,exact.total());equal(Map.of(3.0,2),exact.perSide());
            equal(32.0,plan.loaded(33).total());
            var warm=plan.warmups(34);
            equal(true,warm.stream().allMatch(row->row.load()<34));equal(20.0,warm.get(0).load());
        });

        check("strength history, live timer, and per-set edits persist", () -> {
            StrengthPlan plan=new StrengthPlan();
            plan.preset("Intermediate · pause lifts");
            equal(3,plan.schedule.size());
            equal("Pause Squat",plan.schedule.get("C").get(0).name());
            var active=plan.begin("A",1000);
            active.note="Evening session";
            active.restUntil=9999;
            active.lifts.get(0).sets.set(0,new StrengthPlan.SetLog(4,55));
            StrengthPlan saved=StrengthPlan.decode(plan.encode());
            equal("Evening session",saved.active.note);equal(9999L,saved.active.restUntil);
            equal(new StrengthPlan.SetLog(4,55),saved.active.lifts.get(0).sets.get(0));
            equal(1,saved.nextUnlogged().set());
            complete(saved,1,1500);
            var records=saved.records("Squat");
            equal(1,records.size());equal(25,records.get(0).totalReps());
        });
    }
    private static void complete(StrengthPlan plan,int outcome,long end) {
        for (StrengthPlan.Lift lift : plan.active.lifts) for (int i=0;i<lift.sets.size();i++)
            lift.sets.set(i,new StrengthPlan.SetLog(lift.line.reps()-(outcome<0?1:0),lift.plan));
        plan.active.finished=end;
        plan.history.add(plan.active);plan.active=null;
    }
}
