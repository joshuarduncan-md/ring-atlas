package com.ringatlas.app;

import org.json.*;
import java.time.*;
import java.util.*;

final class FhirExport {
    static final List<String> SUPPORTED=List.of("hr","rhr","hrv","overnight_hrv","resp","spo2","temp","temp_delta",
        "sleep","bed","steps","distance","active_cal","exercise","atlas_vo2","vo2");
    private static final String SYSTEM="urn:ringatlas:observation:v1";
    private static final java.time.format.DateTimeFormatter DATETIME=java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final Map<String,String> UNITS=Map.ofEntries(
        Map.entry("bpm","/min"),Map.entry("ms","ms"),Map.entry("br/min","/min"),Map.entry("%","%"),
        Map.entry("°C","Cel"),Map.entry("min","min"),Map.entry("km","km"),Map.entry("kcal","kcal"),
        Map.entry("mL/kg/min","mL/kg/min"),Map.entry("","{steps}"));

    static JSONObject bundle(Collection<Metrics.Day> readings,Set<String> selected,ZoneId zone,String source,Instant now) throws JSONException {
        var entries=new JSONArray();
        String patient=urn(), atlas=urn();
        entries.put(entry(patient,new JSONObject().put("resourceType","Patient").put("id",id(patient))));
        entries.put(entry(atlas,new JSONObject().put("resourceType","Device").put("id",id(atlas))
            .put("deviceName",new JSONArray().put(new JSONObject().put("name","Ring Atlas").put("type","user-friendly-name")))
            .put("version",new JSONArray().put(new JSONObject().put("value","0.3.0")))));
        var targets=new JSONArray();
        Set<String> codes=new LinkedHashSet<>();
        for (Metrics.Day day : readings) for (String key : SUPPORTED) {
            if (!selected.contains(key)) continue;
            Double value=day.get(key);
            if (value==null || !Double.isFinite(value)) continue;
            Metrics.Metric metric=Metrics.ALL.get(key);
            String observation=urn();
            boolean point=key.equals("atlas_vo2");
            String start=day.date.atStartOfDay(zone).toOffsetDateTime().format(DATETIME);
            String end=day.date.plusDays(1).atStartOfDay(zone).toOffsetDateTime().format(DATETIME);
            codes.add(key);
            var coding=new JSONArray().put(new JSONObject().put("system",SYSTEM)
                .put("code",key).put("display",metric.label()));
            // Keep wearable summaries and estimates distinct from point-in-time clinical vital signs.
            var resource=new JSONObject().put("resourceType","Observation").put("id",id(observation)).put("status","final")
                .put("subject",reference(patient)).put("code",new JSONObject().put("coding",coding).put("text",metric.label()))
                .put("effectivePeriod",new JSONObject().put("start",start).put("end",end))
                .put("valueQuantity",new JSONObject().put("value",value).put("system","http://unitsofmeasure.org")
                    .put("code",UNITS.get(metric.unit())).put("unit",metric.unit().isEmpty()?"steps":metric.unit()))
                .put("note",new JSONArray().put(new JSONObject().put("text",
                    (point?"One-mile walk-test estimate. Time of test unavailable.":"Daily wearable summary.")+" Source: "+source+".")));
            entries.put(entry(observation,resource));
            targets.put(reference(observation));
        }
        if (targets.length()==0) throw new IllegalArgumentException("No selected observations exist in this date range.");
        var provenance=new JSONObject().put("resourceType","Provenance").put("target",targets).put("recorded",now.toString())
            .put("agent",new JSONArray().put(new JSONObject().put("who",reference(atlas))))
            .put("entity",new JSONArray().put(new JSONObject().put("role","source")
                .put("what",new JSONObject().put("display",source))));
        entries.put(entry(urn(),provenance));
        JSONArray concepts=new JSONArray();
        for (String key : codes) concepts.put(new JSONObject().put("code",key).put("display",Metrics.ALL.get(key).label()));
        entries.put(entry(urn(),new JSONObject().put("resourceType","CodeSystem").put("url",SYSTEM)
            .put("status","active").put("name","RingAtlasWearableSummary").put("caseSensitive",true).put("content","fragment")
            .put("concept",concepts)));
        return new JSONObject().put("resourceType","Bundle").put("type","collection").put("timestamp",now.toString()).put("entry",entries);
    }

    private static String urn() {return "urn:uuid:"+UUID.randomUUID();}
    private static String id(String urn) {return urn.substring(9);}
    private static JSONObject entry(String urn,JSONObject resource) throws JSONException {
        if (!resource.has("id")) resource.put("id",id(urn));
        return new JSONObject().put("fullUrl",urn).put("resource",resource);
    }
    private static JSONObject reference(String urn) throws JSONException {return new JSONObject().put("reference",urn);}
}
