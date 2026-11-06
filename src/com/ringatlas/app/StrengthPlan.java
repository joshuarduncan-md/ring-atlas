package com.ringatlas.app;

import org.json.*;
import java.util.*;

final class StrengthPlan {
    static final String SQUAT="Squat", BENCH="Bench Press", ROW="Barbell Row", PRESS="Overhead Press", DEAD="Deadlift";
    static final String[] EXERCISES={SQUAT,BENCH,ROW,PRESS,DEAD,"Incline Bench Press","Pull-up","Dip","Dumbbell Curl","Triceps Extension","Plank",
        "Pause Squat","Pause Bench Press","Pause Deadlift","Feet-up Bench Press"};
    record Rule(double start,double increment,int every,int misses,int deload,boolean barbell,boolean timed) {}
    record Line(String name,int sets,int reps) {}
    record SetLog(Integer reps,double load) {}
    static final class Lift {
        final Line line;
        double plan;
        final List<SetLog> sets=new ArrayList<>();
        Lift(Line line,double plan) {
            this.line=line; this.plan=plan;
            for (int i=0;i<line.sets();i++) sets.add(new SetLog(null,plan));
        }
        boolean logged() {return sets.stream().anyMatch(set->set.reps()!=null);}
        int outcome() {
            if (sets.stream().anyMatch(set->set.reps()==null)) return 0;
            return sets.stream().allMatch(set->set.reps()>=line.reps() && set.load()+.001>=plan)?1:-1;
        }
    }
    static final class Session {
        final String id, unit, rotation;
        final long started;
        long finished, restUntil;
        String note="";
        final List<Lift> lifts=new ArrayList<>();
        Session(String id,String unit,String rotation,long started) {
            this.id=id; this.unit=unit; this.rotation=rotation; this.started=started;
        }
        boolean logged() {return lifts.stream().anyMatch(Lift::logged);}
    }
    record Target(int lift,int set,String name,int reps,double load) {}
    Target nextUnlogged() {
        if (active==null) return null;
        for (int i=0;i<active.lifts.size();i++) {
            Lift lift=active.lifts.get(i);
            for (int j=0;j<lift.sets.size();j++) if (lift.sets.get(j).reps()==null)
                return new Target(i,j,lift.line.name(),lift.line.reps(),lift.sets.get(j).load());
        }
        return null;
    }
    record Plate(double size,int perSide) {}
    record Loaded(double total,Map<Double,Integer> perSide) {}
    record Warmup(int reps,double load) {}
    record Next(double weight,String reason) {}
    record Record(long when,double load,int reps,double max,double volume,int totalReps) {}

    String unit="lb", title="Classic 5×5";
    double bar=45;
    int restSuccess=90, restMissed=300, breakDays=14, breakPercent=10;
    final List<Plate> plates=new ArrayList<>();
    final Map<String,Rule> rules=new LinkedHashMap<>();
    final Map<String,List<Line>> schedule=new LinkedHashMap<>();
    final List<Session> history=new ArrayList<>();
    Session active;

    StrengthPlan() {
        defaultPlates();
        preset("Classic 5×5");
        for (String name : EXERCISES) rules.put(name,defaultRule(name));
    }

    void preset(String name) {
        title=name;
        schedule.clear();
        int sets=name.equals("Low-volume A/B")?3:5;
        schedule.put("A",new ArrayList<>(List.of(new Line(SQUAT,sets,5),new Line(BENCH,sets,5),new Line(ROW,sets,5))));
        schedule.put("B",new ArrayList<>(List.of(new Line(SQUAT,sets,5),new Line(PRESS,sets,5),new Line(DEAD,1,5))));
        if (name.equals("Intermediate · pause lifts")) {
            schedule.put("A",new ArrayList<>(List.of(new Line(SQUAT,5,5),new Line(BENCH,5,5),new Line(ROW,5,8))));
            schedule.put("B",new ArrayList<>(List.of(new Line(DEAD,5,5),new Line("Incline Bench Press",5,8),new Line("Feet-up Bench Press",5,8))));
            schedule.put("C",new ArrayList<>(List.of(new Line("Pause Squat",5,3),new Line("Pause Bench Press",5,3),new Line("Pause Deadlift",2,3))));
            for (List<Line> lines : schedule.values()) for (Line line : lines) rules.putIfAbsent(line.name(),defaultRule(line.name()));
        }
        if (name.equals("5×5 with assistance")) {
            schedule.get("A").addAll(List.of(new Line("Dumbbell Curl",3,10),new Line("Dip",3,8)));
            schedule.get("B").addAll(List.of(new Line("Pull-up",3,8),new Line("Plank",3,30)));
        }
    }

    private void defaultPlates() {
        plates.clear();
        double[] sizes=unit.equals("lb")?new double[]{45,35,25,10,5,2.5}:new double[]{20,15,10,5,2.5,1.25};
        for (double weight : sizes) plates.add(new Plate(weight,weight==sizes[0]?4:2));
    }

    Rule defaultRule(String name) {
        boolean kg=unit.equals("kg");
        boolean barbell=List.of(SQUAT,BENCH,ROW,PRESS,DEAD,"Incline Bench Press","Pause Squat","Pause Bench Press","Pause Deadlift","Feet-up Bench Press").contains(name);
        double start=barbell?(name.equals(DEAD)?(kg?40:95):name.equals(ROW)?(kg?30:65):bar):0;
        return new Rule(start,name.equals(DEAD)?(kg?5:10):(kg?2.5:5),1,3,10,barbell,name.equals("Plank"));
    }
    Rule rule(String name) {return rules.getOrDefault(name,defaultRule(name));}

    void setUnits(String next) {
        if (unit.equals(next)) return;
        rules.replaceAll((name,rule)->new Rule(round(convert(rule.start(),unit,next)),round(convert(rule.increment(),unit,next)),
            rule.every(),rule.misses(),rule.deload(),rule.barbell(),rule.timed()));
        bar=round(convert(bar,unit,next));
        unit=next; defaultPlates();
    }

    String nextRotation() {
        List<String> rotation=new ArrayList<>();
        schedule.forEach((key,lines)->{if (!lines.isEmpty()) rotation.add(key);});
        if (rotation.isEmpty()) return "A";
        if (history.isEmpty()) return rotation.get(0);
        String previous=history.get(history.size()-1).rotation;
        return rotation.get((rotation.indexOf(previous)+1)%rotation.size());
    }

    Session begin(String key,long time) {
        if (active!=null) return active;
        List<Line> lines=schedule.getOrDefault(key,List.of());
        if (lines.isEmpty()) throw new IllegalArgumentException("Add at least one lift to workout "+key+".");
        active=new Session(UUID.randomUUID().toString(),unit,key,time);
        for (Line line : lines) active.lifts.add(new Lift(line,next(line.name(),time).weight()));
        return active;
    }

    Next next(String name,long time) {
        Rule rule=rule(name);
        int successes=0, failures=0, lastOutcome=0;
        long lastTime=0;
        Double planned=null;
        for (int i=history.size()-1;i>=0;i--) {
            Session session=history.get(i);
            Lift lift=session.lifts.stream().filter(l->l.line.name().equals(name)&&l.logged()).findFirst().orElse(null);
            if (lift==null) continue;
            double weight=convert(lift.plan,session.unit,unit);
            if (planned==null) {
                planned=weight; lastTime=session.finished; lastOutcome=lift.outcome();
            }
            if (Math.abs(weight-planned)>.03 || lift.outcome()!=lastOutcome) break;
            if (lastOutcome==1) successes++;
            if (lastOutcome==-1) failures++;
        }
        double minimum=rule.barbell()?bar:0;
        if (planned==null) return new Next(round(Math.max(minimum,rule.start())),"Starting load");
        if (breakDays>0 && time-lastTime>=breakDays*86400L && breakPercent>0 && planned>minimum)
            return new Next(reduce(planned,breakPercent,minimum,rule.barbell()),"Return after a break");
        if (failures>=rule.misses() && rule.deload()>0 && planned>minimum)
            return new Next(reduce(planned,rule.deload(),minimum,rule.barbell()),"Deload after "+failures+" missed sessions");
        if (successes>=rule.every())
            return new Next(round(Math.max(minimum,planned+rule.increment())),"Progression after completed targets");
        return new Next(round(Math.max(minimum,planned)),lastOutcome<0?"Repeat after missed reps":lastOutcome==0?"Repeat after partial log":"Repeat before next increase");
    }

    private double reduce(double weight,int percent,double minimum,boolean barbell) {
        double reduced=Math.max(minimum,weight*(1-percent/100.0));
        return barbell?loaded(reduced).total():round(reduced);
    }

    Loaded loaded(double target) {
        int max=(int)Math.floor((target-bar)*50+.000001);
        if (max<=0) return new Loaded(bar,Map.of());
        max=Math.min(max,100000);
        record Choice(Choice previous,double size,int count) {}
        Choice[] found=new Choice[max+1];
        found[0]=new Choice(null,0,0);
        List<Plate> sizes=new ArrayList<>(plates);
        sizes.sort(Comparator.comparingDouble(Plate::size).reversed());
        for (Plate plate : sizes) {
            int add=(int)Math.round(plate.size()*100);
            if (add<=0) continue;
            for (int quantity=0;quantity<plate.perSide();quantity++)
                for (int weight=max;weight>=add;weight--) {
                    Choice previous=found[weight-add];
                    if (previous!=null && (found[weight]==null || found[weight].count()>previous.count()+1))
                        found[weight]=new Choice(previous,plate.size(),previous.count()+1);
                }
        }
        int attainable=max;
        while (found[attainable]==null) attainable--;
        Map<Double,Integer> use=new TreeMap<>(Comparator.reverseOrder());
        for (Choice choice=found[attainable];choice.previous()!=null;choice=choice.previous())
            use.merge(choice.size(),1,Integer::sum);
        return new Loaded(round(bar+attainable/50.0),use);
    }

    List<Warmup> warmups(double target) {
        if (target<=bar+.001) return List.of();
        var result=new ArrayList<Warmup>();
        result.add(new Warmup(5,bar));
        for (int i=0;i<3;i++) {
            double weight=loaded(target*new double[]{.45,.65,.80}[i]).total();
            if (weight>result.get(result.size()-1).load() && weight<target)
                result.add(new Warmup(new int[]{5,3,2}[i],weight));
        }
        return result;
    }

    List<Record> records(String name) {
        List<Record> results=new ArrayList<>();
        for (Session session : history) {
            double heaviest=0, volume=0, max=0;
            int bestReps=0,reps=0;
            boolean found=false;
            for (Lift lift : session.lifts) if (lift.line.name().equals(name)) {
                for (SetLog set : lift.sets) if (set.reps()!=null && set.reps()>0) {
                    found=true;
                    double weight=convert(set.load(),session.unit,unit);
                    if (weight>heaviest || weight==heaviest && set.reps()>bestReps) {heaviest=weight;bestReps=set.reps();}
                    if (!rule(name).timed()) {
                        if (set.reps()<=12) max=Math.max(max,set.reps()==1?weight:weight*(1+set.reps()/30.0));
                        volume+=weight*set.reps();
                    }
                    reps+=set.reps();
                }
            }
            if (found) results.add(new Record(session.finished,round(heaviest),bestReps,round(max),round(volume),reps));
        }
        return results;
    }

    String encode() throws JSONException {
        JSONObject json=new JSONObject().put("v",1).put("unit",unit).put("bar",bar).put("title",title)
            .put("rest",restSuccess).put("missRest",restMissed).put("breakDays",breakDays).put("breakPct",breakPercent);
        JSONArray plateData=new JSONArray();
        for (Plate p : plates) plateData.put(new JSONArray().put(p.size()).put(p.perSide()));
        json.put("plates",plateData);
        JSONObject ruleData=new JSONObject(), plan=new JSONObject();
        for (var entry : rules.entrySet()) {
            Rule r=entry.getValue();
            ruleData.put(entry.getKey(),new JSONArray().put(r.start()).put(r.increment()).put(r.every())
                .put(r.misses()).put(r.deload()).put(r.barbell()).put(r.timed()));
        }
        for (var entry : schedule.entrySet()) {
            JSONArray lines=new JSONArray();
            for (Line l : entry.getValue()) lines.put(new JSONArray().put(l.name()).put(l.sets()).put(l.reps()));
            plan.put(entry.getKey(),lines);
        }
        JSONArray completed=new JSONArray();
        for (Session session : history) completed.put(encode(session));
        return json.put("rules",ruleData).put("schedule",plan).put("history",completed)
            .put("active",active==null?JSONObject.NULL:encode(active)).toString();
    }

    private static JSONObject encode(Session session) throws JSONException {
        JSONArray lifts=new JSONArray();
        for (Lift lift : session.lifts) {
            JSONArray reps=new JSONArray();
            for (SetLog set : lift.sets) reps.put(new JSONArray().put(set.reps()==null?JSONObject.NULL:set.reps()).put(set.load()));
            lifts.put(new JSONArray().put(lift.line.name()).put(lift.line.sets()).put(lift.line.reps()).put(lift.plan).put(reps));
        }
        return new JSONObject().put("id",session.id).put("unit",session.unit).put("rotation",session.rotation)
            .put("start",session.started).put("end",session.finished).put("restUntil",session.restUntil)
            .put("note",session.note).put("lifts",lifts);
    }

    static StrengthPlan decode(String raw) throws JSONException {
        StrengthPlan out=new StrengthPlan();
        if (raw==null) return out;
        JSONObject json=new JSONObject(raw);
        if (json.getInt("v")!=1) throw new JSONException("Unknown strength training data version.");
        out.unit=unit(json.getString("unit"));
        out.title=json.getString("title"); out.bar=weight(json.getDouble("bar"));
        out.restSuccess=bound(json.getInt("rest"),0,1800); out.restMissed=bound(json.getInt("missRest"),0,1800);
        out.breakDays=bound(json.getInt("breakDays"),0,365); out.breakPercent=bound(json.getInt("breakPct"),0,90);
        out.plates.clear(); out.rules.clear(); out.schedule.clear();
        JSONArray plates=json.getJSONArray("plates");
        if (plates.length()>15) throw new JSONException("Too many plate sizes.");
        for (int i=0;i<plates.length();i++) {
            JSONArray p=plates.getJSONArray(i);
            out.plates.add(new Plate(weight(p.getDouble(0)),bound(p.getInt(1),0,20)));
        }
        JSONObject rules=json.getJSONObject("rules");
        for (Iterator<String> names=rules.keys();names.hasNext();) {
            String name=names.next(); JSONArray r=rules.getJSONArray(name);
            out.rules.put(name,new Rule(weight(r.getDouble(0)),weight(r.getDouble(1)),bound(r.getInt(2),1,100),
                bound(r.getInt(3),1,100),bound(r.getInt(4),0,90),r.getBoolean(5),r.getBoolean(6)));
        }
        JSONObject schedule=json.getJSONObject("schedule");
        for (String key : List.of("A","B","C")) {
            JSONArray items=schedule.optJSONArray(key);
            if (items==null) continue;
            List<Line> lines=new ArrayList<>();
            if (items.length()>20) throw new JSONException("Too many lifts in workout.");
            for (int i=0;i<items.length();i++) lines.add(line(items.getJSONArray(i)));
            out.schedule.put(key,lines);
        }
        JSONArray sessions=json.getJSONArray("history");
        for (int i=0;i<sessions.length();i++) out.history.add(decode(sessions.getJSONObject(i)));
        if (!json.isNull("active")) out.active=decode(json.getJSONObject("active"));
        return out;
    }

    private static Session decode(JSONObject data) throws JSONException {
        Session session=new Session(data.getString("id"),unit(data.getString("unit")),data.getString("rotation"),data.getLong("start"));
        session.finished=data.getLong("end"); session.restUntil=data.getLong("restUntil"); session.note=data.getString("note");
        JSONArray lifts=data.getJSONArray("lifts");
        if (lifts.length()>20) throw new JSONException("Too many lifts in workout.");
        for (int i=0;i<lifts.length();i++) {
            JSONArray row=lifts.getJSONArray(i);
            Lift lift=new Lift(line(row),weight(row.getDouble(3)));
            JSONArray sets=row.getJSONArray(4);
            if (sets.length()!=lift.line.sets()) throw new JSONException("Unexpected number of logged sets.");
            for (int k=0;k<sets.length();k++) {
                JSONArray log=sets.getJSONArray(k);
                lift.sets.set(k,new SetLog(log.isNull(0)?null:bound(log.getInt(0),0,1800),weight(log.getDouble(1))));
            }
            session.lifts.add(lift);
        }
        return session;
    }
    private static Line line(JSONArray l) throws JSONException {
        return new Line(l.getString(0),bound(l.getInt(1),1,20),bound(l.getInt(2),1,1800));
    }
    private static int bound(int x,int min,int max) throws JSONException {
        if (x<min || x>max) throw new JSONException("Value outside the training settings range.");
        return x;
    }
    private static double weight(double x) throws JSONException {
        if (!Double.isFinite(x) || x<0 || x>1500) throw new JSONException("Load outside the training settings range.");
        return x;
    }
    private static String unit(String s) throws JSONException {
        if (!s.equals("lb") && !s.equals("kg")) throw new JSONException("Unknown strength training units.");
        return s;
    }
    static double convert(double load,String from,String to) {
        return from.equals(to)?load:from.equals("lb")?load*.45359237:load/.45359237;
    }
    static double round(double x) {return Math.round(x*100)/100.0;}
}
