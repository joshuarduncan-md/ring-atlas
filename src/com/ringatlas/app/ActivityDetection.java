package com.ringatlas.app;

import java.util.*;

final class ActivityDetection {
    static final String[] TYPES={"Walking","Running","Cycling","Hiking","Strength training","Swimming",
        "Rowing","Elliptical","Yoga","Pilates","Dance","Racket sport","Team sport","Other"};
    static final String[] INTENSITIES={"Unspecified","Light","Moderate","Vigorous"};
    static final double MODERATE_MET=3.0, VIGOROUS_MET=6.0;
    record Bout(long start,long end,String type,int intensity) {}

    static double equivalents(double met) {
        return met>=VIGOROUS_MET?2:met>=MODERATE_MET?1:0;
    }

    static List<Bout> bouts(NavigableMap<Long,Double> minuteMet) {
        List<Bout> result=new ArrayList<>();
        long first=-1,last=-1;
        int active=0,vigorous=0;
        for (var sample : minuteMet.entrySet()) {
            long minute=sample.getKey();
            if (sample.getValue()<MODERATE_MET) continue;
            if (last>=0 && minute-last>2) {
                add(result,first,last,active,vigorous);
                first=-1; active=0; vigorous=0;
            }
            if (first<0) first=minute;
            last=minute; active++;
            if (sample.getValue()>=VIGOROUS_MET) vigorous++;
        }
        if (first>=0) add(result,first,last,active,vigorous);
        return result;
    }

    private static void add(List<Bout> result,long first,long last,int active,int vigorous) {
        if (active<8 || active<.8*(last-first+1)) return;
        int intensity=vigorous*2>=active?3:2;
        result.add(new Bout(first*60,(last+1)*60,"Movement · confirm type",intensity));
    }

    static String phoneType(double cadence,Double speed,boolean motion,boolean hasSteps) {
        if (speed!=null && speed>12) return "Unclassified movement";
        if (hasSteps && cadence>=140 && (speed==null || speed<7.5)) return "Running";
        if (hasSteps && cadence>=45 && (speed==null || speed<3.7)) return "Walking";
        if (hasSteps && cadence<30 && speed!=null && speed>=2.5 && motion) return "Cycling";
        return "Unclassified movement";
    }
}
