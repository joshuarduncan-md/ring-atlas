package com.ringatlas.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;

public final class AtlasChecks {
    public static void main(String[] args) throws Exception {
        check("legacy event framing", () -> {
            var batch=RingProtocol.batch(packets("430864000000746573741106000000000000"));
            equal(100L,batch.events().get(0).tick());
            equal(0L,batch.bytesLeft());
            equal("74657374",RingProtocol.hex(batch.events().get(0).body()));
        });
        check("extended event split across notifications", () -> {
            var frames=packets("2f024306","2f084300aa430364bbcc","2f0a42010000000000000000");
            var batch=RingProtocol.batch(frames);
            equal(1L,batch.events().get(0).tick());
            equal("bbcc",RingProtocol.hex(batch.events().get(0).body()));
        });
        check("reject partial or declined Bluetooth history", () -> {
            expectError(()->RingProtocol.batch(packets("43086400000074657374")));
            expectError(()->RingProtocol.batch(packets("2f024306","2f0a42010000000000000000")));
            expectError(()->RingProtocol.batch(packets("2f0a420000000000000000ff")));
            equal(true,RingProtocol.batch(packets("2f0100")).unsupported());
        });
        check("AES challenge known-answer vector", () -> {
            byte[] key=RingProtocol.unhex("4431967d8bacc2659743142b68391d9a");
            byte[] nonce=RingProtocol.unhex("0e2d6a0a08c99b4365f458e6e97382");
            equal("2f112da38a8772d3acb6db5c2b516dd56987c8",RingProtocol.hex(RingProtocol.auth(key,nonce)));
        });
        check("live heart rate requires a valid interval", () -> {
            equal(60,RingProtocol.liveBpm(packets("2f08280200000000e813").get(0)));
            equal(null,RingProtocol.liveBpm(packets("2f08280200000000e803").get(0)));
        });
        check("calendar anchoring and decoded metrics", () -> {
            long epoch=Instant.parse("2026-10-21T15:00:00Z").getEpochSecond();
            var events=List.of(new RingProtocol.Event(0,0x42,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)epoch).array()),
                new RingProtocol.Event(600,0x5d,RingProtocol.unhex("3c284632")),
                new RingProtocol.Event(600,0x6f,RingProtocol.unhex("006260ff")),
                new RingProtocol.Event(600,0x75,RingProtocol.unhex("e40c")));
            var result=RingSummary.summarize(events,epoch+3600,ZoneOffset.UTC);
            var day=result.days().iterator().next();
            equal(LocalDate.parse("2026-10-21"),day.date);
            equal(65.0,day.get("hr")); equal(45.0,day.get("hrv"));
            equal(97.0,day.get("spo2")); equal(33.0,day.get("temp"));
            var missing=RingSummary.summarize(events.subList(1,events.size()),epoch+3600,ZoneOffset.UTC);
            equal(0,missing.days().size()); equal(3,missing.undated());
        });
        check("daily CSV units and score", () -> {
            String csv="day,score,total_sleep_duration,awake_time,average_hrv\r\n2026-01-01,84,25200,3600,44";
            var day=OuraImport.read(bytes(csv),"daily_sleep.csv").iterator().next();
            equal(84.0,day.get("sleep_score")); equal(420.0,day.get("sleep"));
            equal(60.0,day.get("awake")); equal(44.0,day.get("hrv"));
            equal(List.of(List.of("day","note"),List.of("one","quoted, \"line\"\ntwo")),
                OuraImport.csv("day,note\r\none,\"quoted, \"\"line\"\"\ntwo\""));
        });
        check("nested JSON scores and overnight session precedence", () -> {
            String json="{\"daily_sleep\":{\"data\":[{\"day\":\"2026-01-01\",\"score\":87}]},"+
                "\"sleep\":[{\"day\":\"2026-01-01\",\"bedtime_start\":\"22:00\",\"total_sleep_duration\":24000,\"average_hrv\":50},"+
                "{\"day\":\"2026-01-01\",\"bedtime_start\":\"14:00\",\"total_sleep_duration\":1800,\"average_hrv\":15}]}";
            var day=OuraImport.read(bytes(json),"export.json").iterator().next();
            equal(87.0,day.get("sleep_score")); equal(400.0,day.get("sleep")); equal(50.0,day.get("hrv"));
        });
        check("Membership Hub ZIP merges day types", () -> {
            ByteArrayOutputStream archive=new ByteArrayOutputStream();
            try (ZipOutputStream zip=new ZipOutputStream(archive)) {
                add(zip,"dailyreadiness.csv","day,score,temperature_deviation\n2026-01-01,88,-0.24");
                add(zip,"dailyactivity.csv","day,score,steps,equivalent_walking_distance\n2026-01-01,92,10000,8200");
            }
            var day=OuraImport.read(new ByteArrayInputStream(archive.toByteArray()),"download.zip").iterator().next();
            equal(88.0,day.get("readiness")); equal(-.24,day.get("temp_delta"));
            equal(92.0,day.get("activity_score")); equal(8.2,day.get("distance"));
        });
        check("Oura daily oxygen JSON", () -> {
            String json="{\"data\":[{\"day\":\"2026-01-01\",\"spo2_percentage\":{\"average\":98.5}}]}";
            var day=OuraImport.read(bytes(json),"daily_spo2.json").iterator().next();
            equal(98.5,day.get("spo2"));
        });
        check("native CSV reimport keeps the same units", () -> {
            String csv="day,sleep,bed,active_cal,distance,temp_delta\n2026-01-01,410,450,450,9,-0.2";
            var day=OuraImport.read(bytes(csv),"RingAtlas_daily.csv").iterator().next();
            equal(410.0,day.get("sleep")); equal(450.0,day.get("bed")); equal(9.0,day.get("distance"));
        });
        check("local score coverage, recovery, and fitness", () -> {
            LocalDate date=LocalDate.of(2026,10,21);
            NavigableMap<LocalDate,Metrics.Day> days=new TreeMap<>();
            for (int i=28;i>=1;i--) {
                LocalDate prior=date.minusDays(i);
                days.put(prior,new Metrics.Day(prior).set("sleep",440).set("hrv",50).set("rhr",52).set("moderate_eq",22));
            }
            days.get(date.minusDays(20)).set("atlas_vo2",40);
            days.put(date,new Metrics.Day(date).set("sleep",350).set("eff",85).set("hrv",33)
                .set("rhr",59).set("moderate_eq",20).set("atlas_vo2",42));
            var score=AtlasScore.evaluate(days,date,0,false);
            equal(81.0,score.sleep().value());
            equal(true,score.readiness().value()<80);
            equal(98.0,score.fitness().value());
            equal(true,score.overall().value()>85);
            equal(true,score.tips().size()>=2);
            equal(null,AtlasScore.evaluate(days,date,0,true).activity().value());
            var sparse=new TreeMap<LocalDate,Metrics.Day>();
            sparse.put(date,new Metrics.Day(date).set("bed",460).set("hrv",50));
            var missing=AtlasScore.evaluate(sparse,date,0,false);
            equal(null,missing.readiness().value()); equal(null,missing.sleep().value());
            equal(96.0,missing.opportunity().value()); equal(null,missing.fitness().value());
        });
        check("one-mile VO₂ max study formula and bounds", () -> {
            equal(38.9,AtlasScore.walkTest(45,175,true,15,135));
            try { AtlasScore.walkTest(70,175,true,15,135); throw new AssertionError("Expected age guard"); }
            catch (IllegalArgumentException expected) { }
        });
        check("existing key-file formats", () -> {
            String hex="0123456789abcdef0123456789abcdef";
            equal(hex,RingProtocol.hex(RingProtocol.readKey(bytes(hex+"\r\n"))));
            equal(hex,RingProtocol.hex(RingProtocol.readKey(new ByteArrayInputStream(RingProtocol.unhex(hex)))));
            expectError(()->RingProtocol.readKey(bytes("{\"some data export\": true}")));
        });
        check("native movement detection and device classification", () -> {
            NavigableMap<Long,Double> met=new TreeMap<>();
            for (long minute=100;minute<111;minute++) met.put(minute,minute==105?0.5:6.2);
            for (long minute=130;minute<137;minute++) met.put(minute,3.5);
            var bouts=ActivityDetection.bouts(met);
            equal(1,bouts.size()); equal(6000L,bouts.get(0).start()); equal(6660L,bouts.get(0).end());
            equal(3,bouts.get(0).intensity()); equal(2.0,ActivityDetection.equivalents(6));
            equal("Walking",ActivityDetection.phoneType(90,1.4,true,true));
            equal("Running",ActivityDetection.phoneType(160,3.2,true,true));
            equal("Cycling",ActivityDetection.phoneType(2,5.5,true,true));
            equal("Unclassified movement",ActivityDetection.phoneType(2,19.0,true,true));
            equal("Unclassified movement",ActivityDetection.phoneType(2,5.5,true,false));
        });
        check("native MET minutes and coverage exclude duplicate frames", () -> {
            long epoch=Instant.parse("2026-10-21T15:00:00Z").getEpochSecond();
            var events=new ArrayList<RingProtocol.Event>();
            events.add(new RingProtocol.Event(0,0x42,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)epoch).array()));
            byte[] met=new byte[14]; Arrays.fill(met,(byte)35); met[0]=0;
            for (int block=0;block<5;block++) events.add(new RingProtocol.Event(block*7800,0x50,met));
            events.add(new RingProtocol.Event(0,0x50,met));
            var result=RingSummary.summarize(events,epoch+4000,ZoneOffset.UTC);
            var day=result.days().iterator().next();
            equal(65.0,day.get("met_coverage")); equal(65.0,day.get("moderate_eq")); equal(1,result.bouts().size());
        });
        check("native sleep staging requires sequential pages", () -> {
            long epoch=Instant.parse("2026-10-21T03:00:00Z").getEpochSecond();
            var events=new ArrayList<RingProtocol.Event>();
            events.add(new RingProtocol.Event(0,0x42,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)epoch).array()));
            for (int page=0;page<3;page++) {
                byte[] data=new byte[14]; Arrays.fill(data,(byte)0x55); data[0]=(byte)page;
                events.add(new RingProtocol.Event(72000+page,0x5a,data));
            }
            var result=RingSummary.summarize(events,epoch+10000,ZoneOffset.UTC);
            equal(1,result.stages().size()); equal(156,result.stages().values().iterator().next().length);
            equal(78.0,result.days().iterator().next().get("sleep"));
            events.remove(2);
            equal(0,RingSummary.summarize(events,epoch+10000,ZoneOffset.UTC).stages().size());
        });
        check("empty and unrecognized files", () -> {
            expectError(()->OuraImport.read(bytes("date,random\n2026-01-01,4"),"wrong.csv"));
            expectError(()->OuraImport.read(bytes(""),"empty.csv"));
        });
    }

    interface Checked { void run() throws Exception; }
    static void check(String name,Checked check) throws Exception { check.run(); System.out.println("PASS "+name); }
    static void equal(Object expected,Object actual) {
        if (!Objects.equals(expected,actual)) throw new AssertionError("Expected "+expected+", got "+actual);
    }
    static void expectError(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
    static InputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static List<RingProtocol.Packet> packets(String... frames) {
        List<RingProtocol.Packet> packets=new ArrayList<>();
        for (String frame : frames) packets.addAll(RingProtocol.unpack(RingProtocol.unhex(frame)));
        return packets;
    }
    static void add(ZipOutputStream zip,String name,String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(contents.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
