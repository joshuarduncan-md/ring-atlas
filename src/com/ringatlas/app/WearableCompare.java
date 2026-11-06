package com.ringatlas.app;

import java.io.*;
import java.time.*;
import java.util.*;

final class WearableCompare {
    static final int WINDOW_SECONDS=300;
    record Point(String source,long bucket,String metric,double value,int count) {}
    record Match(long bucket,Double a,Double b) {}
    record Result(List<Match> windows,int pairs,int onlyA,int onlyB,Double bias,Double mae,Double rmse) {}

    private record Key(String source,long bucket,String metric) {}
    static final class Series {
        private final Map<Key,double[]> buckets=new LinkedHashMap<>();

        void add(String source,long seconds,String metric,double value) {
            if (source==null || source.trim().isEmpty() || !valid(metric,value)) return;
            long bucket=Math.floorDiv(seconds,WINDOW_SECONDS)*WINDOW_SECONDS;
            double[] bin=buckets.computeIfAbsent(new Key(source,bucket,metric),key->new double[2]);
            bin[0]+=value; bin[1]++;
        }

        List<Point> points() {
            List<Point> points=new ArrayList<>(buckets.size());
            buckets.forEach((key,bin)->points.add(new Point(key.source(),key.bucket(),key.metric(),bin[0]/bin[1],(int)bin[1])));
            return points;
        }
    }

    static Result compare(List<Point> points,String metric,String a,String b) {
        NavigableMap<Long,Double[]> values=new TreeMap<>();
        for (Point p : points) {
            if (!p.metric().equals(metric) || !valid(metric,p.value())) continue;
            int index=p.source().equals(a)?0:p.source().equals(b)?1:-1;
            if (index>=0) values.computeIfAbsent(p.bucket(),key->new Double[2])[index]=p.value();
        }
        int pairs=0, onlyA=0, onlyB=0;
        double signed=0, absolute=0, squared=0;
        List<Match> matches=new ArrayList<>();
        for (var entry : values.entrySet()) {
            Double x=entry.getValue()[0], y=entry.getValue()[1];
            matches.add(new Match(entry.getKey(),x,y));
            if (x==null) {onlyB++;continue;}
            if (y==null) {onlyA++;continue;}
            double diff=x-y;
            pairs++; signed+=diff; absolute+=Math.abs(diff); squared+=diff*diff;
        }
        return new Result(matches,pairs,onlyA,onlyB,pairs==0?null:signed/pairs,pairs==0?null:absolute/pairs,
            pairs==0?null:Math.sqrt(squared/pairs));
    }

    static List<Point> csv(Reader input) throws IOException {
        BufferedReader reader=new BufferedReader(input);
        String header=reader.readLine();
        if (header==null || !header.replace("\ufeff","").trim().equalsIgnoreCase("timestamp,source,metric,value"))
            throw new IOException("Expected columns timestamp,source,metric,value in that order.");
        Series series=new Series();
        int count=0;
        for (String line;(line=reader.readLine())!=null;) {
            if (++count>150000 || line.length()>5000) throw new IOException("The CSV exceeds the 150,000-row or line-size limit.");
            if (line.trim().isEmpty()) continue;
            try {
                List<String> row=OuraImport.csv(line).get(0);
                if (row.size()!=4) throw new IllegalArgumentException();
                long time=OffsetDateTime.parse(row.get(0).trim()).toEpochSecond();
                if (time<946684800L || time>Instant.now().getEpochSecond()+300) throw new IllegalArgumentException();
                String label=row.get(1).trim(), metric=row.get(2).trim().toLowerCase(Locale.ROOT);
                double value=Double.parseDouble(row.get(3).trim());
                if (label.isEmpty() || label.length()>80 || !valid(metric,value)) throw new IllegalArgumentException();
                series.add("CSV · "+label,time,metric,value);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid wearable CSV row "+(count+1)+". Use ISO date/time with UTC offset and hr or hrv.");
            }
        }
        List<Point> points=series.points();
        if (points.isEmpty()) throw new IOException("The wearable CSV has no samples.");
        return points;
    }

    static boolean valid(String metric,double value) {
        return Double.isFinite(value) && (metric.equals("hr") && value>=20 && value<=260 ||
            metric.equals("hrv") && value>=0 && value<=350);
    }
}
