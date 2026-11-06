package com.ringatlas.app;

import java.time.LocalDate;
import java.util.*;

final class Metrics {
    record Metric(String key, String label, String unit, int color) {
        String value(Double n) {
            if (n == null || !Double.isFinite(n)) return "—";
            if (unit.equals("min") && Set.of("sleep", "bed", "deep", "rem", "light", "awake").contains(key)) {
                long minutes = Math.round(n);
                return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
            }
            String number = switch (unit) {
                case "°C", "km", "br/min", "MET", "mL/kg/min" -> String.format(Locale.getDefault(), "%,.1f", n);
                default -> String.format(Locale.getDefault(), "%,d", Math.round(n));
            };
            return number + (unit.isEmpty() ? "" : unit.equals("%") ? "%" : " " + unit);
        }
    }

    static final int MINT = 0xffa5e5c1, BLUE = 0xffaabff9, GOLD = 0xffeec38e, ROSE = 0xffefaead;
    static final Map<String, Metric> ALL = new LinkedHashMap<>();
    static final String[] SLEEP = {"sleep_score","sleep","bed","eff","deep","rem","light","awake","latency","rhr","resp","spo2"};
    static final String[] ACTIVITY = {"activity_score","steps","active_cal","distance","exercise","workouts","total_cal","goal_steps","vo2","met"};
    static final String[] RECOVERY = {"readiness","hrv","rhr","hr","temp_delta","temp","resp","spo2","stress","restored","vascular_age","resilience"};
    static {
        add("readiness","Oura Readiness Score","",MINT); add("sleep_score","Oura Sleep Score","",BLUE);
        add("activity_score","Oura Activity Score","",GOLD); add("sleep","Time asleep","min",BLUE);
        add("bed","Time in bed","min",BLUE); add("eff","Sleep efficiency","%",BLUE);
        add("deep","Deep sleep","min",BLUE); add("rem","REM sleep","min",ROSE);
        add("light","Light sleep","min",MINT); add("awake","Awake in bed","min",GOLD);
        add("latency","Sleep latency","min",BLUE); add("steps","Steps","",GOLD);
        add("active_cal","Active energy","kcal",GOLD); add("total_cal","Total energy","kcal",GOLD);
        add("distance","Distance","km",GOLD); add("exercise","Exercise","min",GOLD);
        add("workouts","Workout sessions","",GOLD); add("goal_steps","Step goal","",GOLD);
        add("rhr","Lowest or resting heart rate","bpm",ROSE); add("hr","Average heart rate","bpm",ROSE);
        add("optical_hr","Average of valid optical HR packets","bpm",ROSE);
        add("hrv","Heart rate variability","ms",MINT);
        add("overnight_hrv","Overnight heart rate variability","ms",MINT); add("spo2","Average oxygen saturation","%",BLUE);
        add("resp","Breathing rate","br/min",BLUE); add("temp_delta","Temperature deviation","°C",ROSE);
        add("temp","Finger skin temperature","°C",ROSE); add("stress","High stress","min",ROSE);
        add("restored","Restorative time","min",MINT); add("vo2","Cardio capacity (VO₂ max)","mL/kg/min",GOLD);
        add("vascular_age","Cardiovascular age","yr",ROSE); add("resilience","Oura resilience level","",MINT);
        add("met","Recorded activity intensity","MET",GOLD);
        add("moderate_eq","Moderate-equivalent activity","min",GOLD);
        add("met_coverage","Ring activity coverage","min",GOLD);
        add("stage_coverage","Ring sleep stage coverage","min",BLUE);
        add("atlas_vo2","Atlas one-mile VO₂ max estimate","mL/kg/min",MINT);
        add("atlas_sleep","Atlas Sleep Score","",BLUE);
        add("atlas_bed","Atlas Sleep Opportunity Score","",BLUE);
        add("atlas_readiness","Atlas Readiness Score","",MINT);
        add("atlas_activity","Atlas Activity Score","",GOLD);
        add("atlas_fitness","Atlas Fitness Habits Score","",MINT);
        add("atlas_overall","Atlas Personal Fitness Index","",MINT);
    }

    private static void add(String key, String title, String unit, int color) {
        ALL.put(key, new Metric(key,title,unit,color));
    }
    static String value(String key, Double value) {
        if (key.equals("resilience") && value != null && value >= 1 && value <= 5)
            return new String[]{"Limited","Adequate","Solid","Strong","Exceptional"}[value.intValue()-1];
        return ALL.get(key).value(value);
    }

    static final class Day {
        final LocalDate date;
        final Map<String,Double> values = new LinkedHashMap<>();
        Day(LocalDate date) { this.date = date; }
        Double get(String key) { return values.get(key); }
        Day set(String key, double value) {
            if (ALL.containsKey(key) && Double.isFinite(value)) values.put(key, value);
            return this;
        }
    }

    static NavigableMap<LocalDate,Day> sample() {
        NavigableMap<LocalDate,Day> days = new TreeMap<>();
        Random random = new Random(4612);
        for (int i=59; i>=0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            int sleep = 429 + random.nextInt(78)-37;
            int score = 70+random.nextInt(22);
            int steps = 6500+random.nextInt(4700);
            Day day = new Day(date).set("readiness",score+3).set("sleep_score",score)
                .set("activity_score",69+random.nextInt(26)).set("sleep",sleep).set("bed",sleep+34)
                .set("eff",100.0*sleep/(sleep+34)).set("deep",Math.round(sleep*0.19))
                .set("rem",Math.round(sleep*0.24)).set("light",Math.round(sleep*0.57)).set("awake",34)
                .set("latency",11).set("steps",steps).set("distance",steps*0.00077)
                .set("moderate_eq",10+random.nextInt(45)).set("exercise",12+random.nextInt(35)).set("active_cal",steps*0.051).set("goal_steps",9000).set("total_cal",1800+steps*0.05)
                .set("rhr",47+random.nextInt(9)).set("hr",63+random.nextInt(8)).set("hrv",37+random.nextInt(29))
                .set("resp",14+random.nextDouble()*1.7).set("spo2",96+random.nextInt(3))
                .set("temp_delta",random.nextDouble()*.6-.3).set("stress",18+random.nextInt(65))
                .set("restored",13+random.nextInt(44)).set("vo2",43).set("resilience",4);
            days.put(date,day);
        }
        return days;
    }

    static Double mean(Collection<Double> values) {
        OptionalDouble average=values.stream().mapToDouble(Double::doubleValue).average();
        return average.isPresent()?average.getAsDouble():null;
    }

    static List<Double> values(NavigableMap<LocalDate,Day> days, String key, LocalDate end, int count) {
        List<Double> result = new ArrayList<>();
        for (int i = count-1; i>=0; i--) {
            Day day = days.get(end.minusDays(i));
            result.add(day == null ? null : day.get(key));
        }
        return result;
    }
}
