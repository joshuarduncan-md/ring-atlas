package com.ringatlas.app;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;

final class OuraImport {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Map<String,String> ALIASES = Map.ofEntries(
        Map.entry("readiness_score","readiness"), Map.entry("sleep_duration","sleep"),
        Map.entry("total_sleep_duration","sleep"), Map.entry("total_sleep","sleep"),
        Map.entry("time_in_bed","bed"), Map.entry("sleep_efficiency","eff"),
        Map.entry("efficiency","eff"), Map.entry("deep_sleep_duration","deep"),
        Map.entry("rem_sleep_duration","rem"), Map.entry("light_sleep_duration","light"),
        Map.entry("awake_time","awake"), Map.entry("sleep_latency","latency"),
        Map.entry("lowest_heart_rate","rhr"), Map.entry("resting_heart_rate","rhr"),
        Map.entry("average_heart_rate","hr"), Map.entry("average_hrv","hrv"),
        Map.entry("average_breath","resp"), Map.entry("average_breathing_rate","resp"),
        Map.entry("temperature_deviation","temp_delta"), Map.entry("body_temperature_deviation","temp_delta"),
        Map.entry("active_calories","active_cal"), Map.entry("activity_burn","active_cal"),
        Map.entry("total_calories","total_cal"), Map.entry("total_burn","total_cal"),
        Map.entry("target_steps","goal_steps"), Map.entry("equivalent_walking_distance","distance"),
        Map.entry("stress_high","stress"), Map.entry("recovery_high","restored"),
        Map.entry("vo2_max","vo2"), Map.entry("cardiovascular_age","vascular_age"),
        Map.entry("spo2_percentage","spo2"), Map.entry("spo2_average","spo2"), Map.entry("average_spo2","spo2"),
        Map.entry("lowest_resting_heart_rate","rhr"), Map.entry("low_resting_heart_rate","rhr"),
        Map.entry("deep_sleep","deep"), Map.entry("rem_sleep","rem"), Map.entry("light_sleep","light"),
        Map.entry("total_bedtime","bed"), Map.entry("latency","latency"),
        Map.entry("temperature_delta","temp_delta"), Map.entry("activity_steps","steps"),
        Map.entry("resting_heart_rate_lowest","rhr"), Map.entry("heart_rate_average","hr"),
        Map.entry("hrv_average","hrv"), Map.entry("breath_average","resp"),
        Map.entry("sleep_total","sleep"), Map.entry("sleep_deep","deep"), Map.entry("sleep_rem","rem"),
        Map.entry("sleep_light","light"), Map.entry("sleep_awake","awake"));
    private static final Set<String> DURATION = Set.of("sleep","bed","deep","rem","light","awake","latency","stress","restored");
    private final Map<LocalDate,Metrics.Day> days = new TreeMap<>();
    private final Map<LocalDate,Double> sleepLength = new HashMap<>();
    private long bytes;

    static Collection<Metrics.Day> read(InputStream input,String filename) throws Exception {
        OuraImport parser = new OuraImport();
        try (PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(input),4)) {
            byte[] header = new byte[4];
            int length=0, n;
            while (length<header.length && (n=in.read(header,length,header.length-length))!=-1) length+=n;
            header=Arrays.copyOf(header,length);
            in.unread(header);
            if (header.length == 4 && header[0]=='P' && header[1]=='K' && header[2]==3 && header[3]==4) {
                try (ZipInputStream zip = new ZipInputStream(in)) {
                    ZipEntry entry;
                    int count = 0;
                    while ((entry=zip.getNextEntry()) != null) {
                        if (++count>200) throw new IOException("The ZIP contains too many files.");
                        String lower = entry.getName().toLowerCase(Locale.ROOT);
                        if (!entry.isDirectory() && (lower.endsWith(".csv") || lower.endsWith(".json"))) {
                            parser.parse(parser.readEntry(zip),entry.getName());
                        } else if (!entry.isDirectory()) {
                            parser.readEntry(zip);
                        }
                        zip.closeEntry();
                    }
                }
            } else {
                parser.parse(parser.readEntry(in),filename);
            }
        }
        parser.days.values().removeIf(day -> day.values.isEmpty());
        if (parser.days.isEmpty()) throw new IOException("No recognized daily data. Choose an Oura daily CSV, JSON, or Membership Hub ZIP.");
        return parser.days.values();
    }

    private String readEntry(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n=in.read(buffer)) != -1) {
            bytes += n;
            if (bytes>MAX_BYTES) throw new IOException("The file expands beyond 32 MB. Import individual daily files instead.");
            out.write(buffer,0,n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private void parse(String text,String name) throws JSONException {
        String input = text.replaceFirst("^\uFEFF","").trim();
        if (input.isEmpty()) return;
        if (input.startsWith("[") || input.startsWith("{")) parseJson(new JSONTokener(input).nextValue(), name,0);
        else {
            List<List<String>> rows = csv(input);
            if (rows.size()<2) return;
            List<String> header = rows.get(0);
            boolean atlas = header.contains("bed") && header.contains("temp_delta") && header.contains("active_cal");
            for (int row=1; row<rows.size(); row++) {
                Map<String,Object> record = new LinkedHashMap<>();
                for (int col=0; col<Math.min(header.size(),rows.get(row).size()); col++) record.put(key(header.get(col)),rows.get(row).get(col));
                add(record,name,atlas);
            }
        }
    }

    private void parseJson(Object value,String context,int depth) throws JSONException {
        if (depth>12) throw new JSONException("JSON nesting exceeds the import limit.");
        if (value instanceof JSONArray array) {
            for (int i=0; i<array.length(); i++) parseJson(array.get(i),context,depth+1);
        } else if (value instanceof JSONObject object) {
            Map<String,Object> record = new LinkedHashMap<>();
            for (Iterator<String> it = object.keys(); it.hasNext();) {
                String key = it.next();
                Object child = object.get(key);
                record.put(key(key),child);
                if (child instanceof JSONArray || child instanceof JSONObject)
                    parseJson(child,key.equals("data") ? context : context + " " + key,depth+1);
            }
            add(record,context,false);
        }
    }

    private void add(Map<String,Object> record,String context,boolean atlas) {
        Object dateRaw = record.getOrDefault("day",record.getOrDefault("date",record.get("summary_date")));
        if (dateRaw == null) return;
        String dayText = dateRaw.toString();
        if (dayText.length()<10) return;
        LocalDate date;
        try { date = LocalDate.parse(dayText.substring(0,10)); }
        catch (RuntimeException ignored) { return; }
        if (date.getYear()<2000 || date.isAfter(LocalDate.now())) return;
        Metrics.Day day = days.computeIfAbsent(date,Metrics.Day::new);
        String type = key(context).replace("_","");
        String score = type.contains("readiness") ? "readiness" : type.contains("activity") ? "activity_score"
            : type.contains("sleep") && !type.contains("spo2") ? "sleep_score" : "";
        Double totalSleep = number(record.getOrDefault("total_sleep_duration",record.get("total_sleep")));
        boolean isSleepSession = record.containsKey("bedtime_start") && totalSleep != null;
        if (isSleepSession && "late_nap".equalsIgnoreCase(String.valueOf(record.get("type")))) return;
        if (isSleepSession && totalSleep < sleepLength.getOrDefault(date,-1.0)) return;
        if (isSleepSession) sleepLength.put(date,totalSleep);
        for (Map.Entry<String,Object> entry : record.entrySet()) {
            String field = entry.getKey();
            String metric = field.equals("score") ? (isSleepSession ? "" : score) : ALIASES.getOrDefault(field,field);
            if (metric.equals("level") && type.contains("resilience")) metric = "resilience";
            if (!Metrics.ALL.containsKey(metric)) continue;
            if (metric.equals("spo2") && entry.getValue() instanceof JSONObject oxygen) {
                Double avg = number(oxygen.opt("average"));
                if (avg != null && avg>=50 && avg<=100) day.set(metric,avg);
                continue;
            }
            Double n = number(entry.getValue());
            if (n == null && metric.equals("resilience")) {
                int level = List.of("limited","adequate","solid","strong","exceptional").indexOf(key(String.valueOf(entry.getValue())));
                if (level>=0) n = (double)level+1;
            }
            if (n == null || !Double.isFinite(n)) continue;
            if (!atlas && DURATION.contains(metric)) n /= 60;
            if (!atlas && metric.equals("distance")) n /= 1000;
            if (valid(metric,n)) day.set(metric,n);
        }
    }

    private static boolean valid(String key,double n) {
        return switch (key) {
            case "temp_delta" -> n>=-15 && n<=15;
            case "sleep","bed","deep","rem","light","awake","latency","stress","restored","exercise" -> n>=0 && n<=1440;
            case "readiness","sleep_score","activity_score","eff" -> n>=0 && n<=100;
            case "spo2" -> n>=50 && n<=100;
            case "hr","rhr" -> n>=25 && n<=250;
            case "resp" -> n>=3 && n<=80;
            case "temp" -> n>=5 && n<=50;
            default -> n>=0 && n<=1_000_000;
        };
    }

    private static Double number(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        String raw = value.toString().trim();
        try {
            if (raw.indexOf(':')>=0) {
                String[] parts = raw.split(":");
                if (parts.length==3) return Double.parseDouble(parts[0])*3600+
                    Double.parseDouble(parts[1])*60+Double.parseDouble(parts[2]);
            }
            return Double.valueOf(raw.replace(",",""));
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String key(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");
    }

    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i=0; i<text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch=='"' && i+1<text.length() && text.charAt(i+1)=='"') { cell.append(ch); i++; }
                else if (ch=='"') quoted=false;
                else cell.append(ch);
            } else if (ch=='"' && cell.length()==0) quoted=true;
            else if (ch==',' || ch=='\n' || ch=='\r') {
                row.add(cell.toString()); cell.setLength(0);
                if (ch!=',') {
                    rows.add(row); row=new ArrayList<>();
                    if (ch=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                }
            } else cell.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("A quoted CSV field was incomplete.");
        if (cell.length()!=0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
