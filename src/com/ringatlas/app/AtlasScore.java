package com.ringatlas.app;

import java.time.LocalDate;
import java.util.*;

final class AtlasScore {
    static final int SLEEP_TARGET=420;
    static final int WEEK_TARGET=150;
    record Score(Double value,List<String> details) {}
    record Daily(Score sleep,Score opportunity,Score readiness,Score activity,Score fitness,Score overall,List<String> tips) {}

    private static final class Index {
        private double sum, weights;
        private final List<String> details=new ArrayList<>();
        void add(double normalized,double weight,String detail) {
            sum+=clamp(normalized)*weight;
            weights+=weight;
            details.add(detail);
        }
        Score finish(String missing) {
            return weights==0 ? new Score(null,List.of(missing)) : new Score(Math.round(100*sum/weights)*1.0,List.copyOf(details));
        }
    }

    static Daily evaluate(NavigableMap<LocalDate,Metrics.Day> days,LocalDate date,int stepGoal,boolean restMode) {
        Metrics.Day day=days.getOrDefault(date,new Metrics.Day(date));
        Double duration=day.get("sleep"), bed=day.get("bed"), efficiency=day.get("eff");
        String hrvKey=day.get("overnight_hrv")!=null?"overnight_hrv":"hrv";
        Double hrv=day.get(hrvKey), hrvTypical=median(days,hrvKey,date);
        Double rhr=day.get("rhr"), rhrTypical=median(days,"rhr",date);
        Double steps=day.get("steps"), exercise=day.get("exercise"), minutes=day.get("moderate_eq");
        Double personalSteps=median(days,"steps",date);
        Double nativeGoal=day.get("goal_steps");
        Double goal=stepGoal>0 ? Double.valueOf(stepGoal) : nativeGoal!=null && nativeGoal>0 ? nativeGoal : personalSteps;
        Index sleep=new Index(), opportunity=new Index(), readiness=new Index(), movement=new Index(), fitness=new Index();
        if (duration!=null && duration>0) {
            sleep.add(duration/SLEEP_TARGET,65,Metrics.value("sleep",duration)+" asleep · target 7h");
            if (efficiency!=null) sleep.add((efficiency-50)/40,25,Metrics.value("eff",efficiency)+" sleep efficiency");
            Double usual=median(days,"sleep",date);
            if (usual!=null) sleep.add(1-Math.abs(duration-usual)/180,10,"Duration compared with your prior 28 days");
        } else if (bed!=null && bed>0) {
            opportunity.add(bed/480,100,Metrics.value("bed",bed)+" in bed · 8h opportunity target");
        }
        Score night=sleep.finish("Sleep duration is needed. Time in bed has its own score.");
        if (hrvTypical!=null && hrvTypical>0 && hrv!=null && hrv>0)
            readiness.add((hrv/hrvTypical-.55)/.5,45,Math.round(hrv)+" ms HRV · "+Math.round(hrvTypical)+" ms baseline");
        if (rhrTypical!=null && rhr!=null)
            readiness.add(1-(rhr-rhrTypical)/18,35,Math.round(rhr)+" bpm overnight/resting HR · "+Math.round(rhrTypical)+" bpm baseline");
        if (readiness.weights>0 && night.value()!=null) readiness.add(night.value()/100,20,"Atlas Sleep score "+Math.round(night.value()));
        if (!restMode) {
            if (minutes!=null) movement.add(minutes/(WEEK_TARGET/7.0),65,Math.round(minutes)+" moderate-equivalent minutes");
            else if (exercise!=null) movement.add(exercise/30,65,Math.round(exercise)+" recorded exercise minutes · intensity unspecified");
            if (steps!=null && goal!=null && goal>0) movement.add(steps/goal,35,Math.round(steps)+" steps · reference "+Math.round(goal));
        }
        int observed=0, activeDays=0, sleepDays=0, targetSleepDays=0;
        double week=0;
        for (Metrics.Day observedDay : days.subMap(date.minusDays(6),true,date,true).values()) {
            Double activity=observedDay.get("moderate_eq");
            if (activity!=null) {
                observed++; week+=activity;
                if (activity>=15) activeDays++;
            }
            Double nightly=observedDay.get("sleep");
            if (nightly!=null && nightly>0) {
                sleepDays++;
                if (nightly>=SLEEP_TARGET) targetSleepDays++;
            }
        }
        if (observed>=4) {
            fitness.add(week/WEEK_TARGET,60,Math.round(week)+" moderate-equivalent minutes over 7 days · "+observed+"/7 observed");
            fitness.add(activeDays/4.0,25,activeDays+" days with 15+ moderate-equivalent minutes");
            if (sleepDays>=4) fitness.add((double)targetSleepDays/sleepDays,15,targetSleepDays+" of "+sleepDays+" observed nights reached 7h");
        }
        Score habits=fitness.finish("Needs moderate or vigorous activity coverage on at least four of the last seven days.");
        Score overall=fitnessIndex(days,date,habits);
        List<String> tips=new ArrayList<>();
        if (duration!=null && duration>0 && duration<SLEEP_TARGET) {
            long shortfall=Math.round(SLEEP_TARGET-duration);
            tips.add("You recorded "+shortfall+" minutes below your 7-hour sleep reference. If your schedule allows, set aside an earlier wind-down tonight.");
        }
        if (hrvTypical!=null && hrv!=null && hrv<.8*hrvTypical && rhrTypical!=null && rhr!=null && rhr>=rhrTypical+4)
            tips.add("HRV was below your baseline and overnight heart rate was above it. If you feel tired, consider an easier day and check tomorrow’s readings.");
        if (observed>=4 && week<WEEK_TARGET && !restMode)
            tips.add("You logged "+Math.round(week)+" of 150 moderate-equivalent minutes in the last 7 days. An easy walk can add movement if it fits your routine.");
        Double usualSleep=median(days,"sleep",date.minusDays(2));
        List<Double> recentSleep=new ArrayList<>();
        for (Metrics.Day recent : days.subMap(date.minusDays(2),true,date,true).values()) {
            Double sleepMinutes=recent.get("sleep");
            if (sleepMinutes!=null) recentSleep.add(sleepMinutes);
        }
        if (usualSleep!=null && recentSleep.size()==3) {
            double average=Metrics.mean(recentSleep);
            if (average<=usualSleep-30) tips.add("Your last three nights averaged "+Math.round(usualSleep-average)+
                " minutes less sleep than your prior baseline. Look for a schedule change in your journal.");
        }
        if (tips.isEmpty() && night.value()!=null && night.value()>=85)
            tips.add("Your recorded sleep met most of your sleep targets. Keep the schedule that worked for you.");
        return new Daily(night,opportunity.finish("A ring-detected or imported bed window is needed."),
            readiness.finish("Needs today’s HRV or overnight heart rate and at least seven prior readings of that metric."),
            movement.finish(restMode?"Daily movement scoring is paused in Rest Mode.":"Needs exercise data or steps and a goal or personal baseline."),
            habits,overall,List.copyOf(tips));
    }

    private static Score fitnessIndex(NavigableMap<LocalDate,Metrics.Day> days,LocalDate date,Score habits) {
        Index index=new Index();
        if (habits.value()!=null) {
            List<Metrics.Day> tests=new ArrayList<>();
            for (Metrics.Day test : days.subMap(date.minusDays(180),true,date,true).values()) {
                Double value=test.get("atlas_vo2");
                if (value!=null && value>=5 && value<=85) tests.add(test);
            }
            if (tests.size()>=2) {
                Metrics.Day first=tests.get(0), last=tests.get(tests.size()-1);
                if (!last.date.isBefore(first.date.plusDays(14)) && !last.date.isBefore(date.minusDays(45))) {
                    double percent=100*(last.get("atlas_vo2")/first.get("atlas_vo2")-1);
                    index.add(habits.value()/100,70,"Fitness habits "+Math.round(habits.value())+"/100");
                    index.add(.5+percent/20,30,String.format(Locale.getDefault(),"%+.1f%% VO₂ max estimate change since %s",percent,first.date));
                }
            }
        }
        return index.finish("Needs a fitness habits score and two one-mile estimates at least 14 days apart. The latest test must be within 45 days.");
    }

    static Double median(NavigableMap<LocalDate,Metrics.Day> days,String key,LocalDate before) {
        List<Double> values=new ArrayList<>();
        for (Metrics.Day day : days.subMap(before.minusDays(28),true,before,false).values()) {
            Double value=day.get(key);
            if (value!=null) values.add(value);
        }
        if (values.size()<7) return null;
        values.sort(Double::compare);
        int middle=values.size()/2;
        return values.size()%2==0 ? (values.get(middle-1)+values.get(middle))/2 : values.get(middle);
    }

    static double walkTest(int age,double pounds,boolean maleEquation,double minutes,int finishBpm) {
        if (age<30 || age>69 || !Double.isFinite(pounds) || pounds<80 || pounds>440 ||
            !Double.isFinite(minutes) || minutes<8 || minutes>30 || finishBpm<60 || finishBpm>210)
            throw new IllegalArgumentException("Enter age 30–69, weight 80–440 lb, a mile time of 8–30 min and finish HR of 60–210 bpm.");
        // Kline et al. (1987), track walk estimate in L/min, converted to mL/kg/min.
        double liters=6.9652+.0091*pounds-.0257*age+(maleEquation?.5955:0)-.2240*minutes-.0115*finishBpm;
        double estimate=liters*1000/(pounds*.45359237);
        if (estimate<5 || estimate>85) throw new IllegalArgumentException("The result is outside the app’s reporting range. Check the entries.");
        return Math.round(estimate*10)/10.0;
    }

    private static double clamp(double x) { return Math.max(0,Math.min(1,x)); }
}
