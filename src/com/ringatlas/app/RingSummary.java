package com.ringatlas.app;

import java.time.*;
import java.util.*;

final class RingSummary {
    record Result(Collection<Metrics.Day> days,List<ActivityDetection.Bout> bouts,Map<LocalDate,byte[]> stages,int undated) {}
    private static final long DAY=86400, MAX_DISTANCE=14*DAY;
    private static final Set<Integer> MEASUREMENTS=Set.of(0x5d,0x6a,0x75,0x69,0x6f,0x76,0x50,0x80);
    private final NavigableMap<Long,Long> anchors=new TreeMap<>();
    private final Map<LocalDate,Map<String,double[]>> stats=new TreeMap<>();
    private record Window(long start,long end,LocalDate date) {}
    private final List<Window> windows=new ArrayList<>();
    private final Map<LocalDate,List<Double>> nightHr=new HashMap<>();
    private final Map<LocalDate,byte[]> stagedNights=new TreeMap<>();
    private final NavigableMap<Long,Double> minuteMet=new TreeMap<>();
    private final long now;
    private final ZoneId zone;

    private RingSummary(long now,ZoneId zone) { this.now=now; this.zone=zone; }
    static Result summarize(List<RingProtocol.Event> events,long now,ZoneId zone) {
        RingSummary summary=new RingSummary(now,zone);
        summary.loadAnchors(events);
        int undated=0;
        for (RingProtocol.Event event : events) if (event.tag()==0x76 && !summary.bedWindow(event)) undated++;
        summary.stageNights(events);
        for (RingProtocol.Event event : events) if (event.tag()!=0x76 && !summary.add(event)) undated++;
        summary.activity();
        summary.nightHr.forEach((date,samples)->{
            if (samples.size()<12) return;
            samples.sort(Double::compare);
            summary.stats.computeIfAbsent(date,d->new LinkedHashMap<>())
                .put("rhr",new double[]{samples.get(samples.size()/10),1});
        });
        List<Metrics.Day> days=new ArrayList<>();
        summary.stats.forEach((date,metrics)->{
            Metrics.Day day=new Metrics.Day(date);
            metrics.forEach((key,value)->day.set(key,value[0]/value[1]));
            days.add(day);
        });
        return new Result(days,ActivityDetection.bouts(summary.minuteMet),summary.stagedNights,undated);
    }

    private void loadAnchors(List<RingProtocol.Event> events) {
        for (RingProtocol.Event e : events) {
            if ((e.tag()==0x42 || e.tag()==0x85 && e.body().length>=10) && e.body().length>=4) {
                long unix=RingProtocol.u32(e.body(),0);
                if (unix>=1_577_836_800L && unix<=now+3600) anchors.put(e.tick(),unix);
            }
        }
    }

    static void wearableSeries(WearableCompare.Series series,String source,List<RingProtocol.Event> events,long from,long to) {
        RingSummary clock=new RingSummary(Instant.now().getEpochSecond(),ZoneOffset.UTC);
        clock.loadAnchors(events);
        for (RingProtocol.Event event : events) {
            if (event.tag()!=0x5d) continue;
            Long unix=clock.wall(event.tick());
            if (unix==null) continue;
            byte[] body=event.body();
            for (int i=0;i+1<body.length;i+=2) {
                long time=unix+i/2*300L;
                if (time<from || time>=to) continue;
                int hr=RingProtocol.u8(body[i]), hrv=RingProtocol.u8(body[i+1]);
                if (hr>=25 && hr<=250) series.add(source,time,"hr",hr);
                if (hrv>=1 && hrv<=254) series.add(source,time,"hrv",hrv);
            }
        }
    }

    private boolean add(RingProtocol.Event e) {
        int tag=e.tag();
        byte[] body=e.body();
        if (!MEASUREMENTS.contains(tag)) return true;
        Long unix=wall(e.tick());
        if (unix==null) return false;
        if (tag==0x5d && body.length%2==0) {
            for (int i=0;i<body.length;i+=2) {
                long at=unix+i/2*300L;
                add(at,"hr",RingProtocol.u8(body[i]),25,250);
                int hrv=RingProtocol.u8(body[i+1]);
                add(at,"hrv",hrv,1,254);
                Window window=overnight(at);
                if (window!=null && hrv>=1 && hrv<=254) add(window.date(),"overnight_hrv",hrv);
            }
        } else if (tag==0x6a && body.length>=10 && RingProtocol.u8(body[6])<120 && RingProtocol.u8(body[7])<3) {
            Window window=overnight(unix);
            double hr=RingProtocol.u8(body[0])*.5, resp=RingProtocol.u8(body[4])/8.0;
            if (window!=null && hr>=30 && hr<=130) nightHr.computeIfAbsent(window.date(),d->new ArrayList<>()).add(hr);
            if (window!=null && resp>=4 && resp<=55) add(window.date(),"resp",resp);
            else add(unix,"resp",resp,4,55);
        } else if ((tag==0x75 || tag==0x69) && body.length%2==0) {
            for (int i=0;i<body.length;i+=2) add(unix,"temp",(short)RingProtocol.u16(body,i)/100.0,10,47);
        } else if (tag==0x6f) {
            for (int i=1;i<body.length;i++) add(unix+i-1,"spo2",RingProtocol.u8(body[i]),50,100);
        } else if (tag==0x80 && body.length%2==0) {
            double sum=0; int count=0;
            for (int i=0;i<body.length;i+=2) {
                int flags=RingProtocol.u8(body[i+1]);
                int interval=(RingProtocol.u8(body[i])<<3)|(flags&7);
                if (((flags>>3)&3)==1 && interval>=300 && interval<=2000) { sum+=60000.0/interval; count++; }
            }
            if (count>0) add(unix,"optical_hr",sum/count,30,200);
        } else if (tag==0x50) {
            for (int i=1;i<body.length;i++) {
                int sample=RingProtocol.u8(body[i]);
                long minute=Math.floorDiv(unix+30,60)+i-1;
                double met=sample<128 ? sample*.1 : 12.8+(sample-128)*.2;
                if (minute*60<=now && met<=30) minuteMet.put(minute,met);
            }
        }
        return true;
    }

    private boolean bedWindow(RingProtocol.Event event) {
        byte[] body=event.body();
        if (body.length<8) return true;
        long start=RingProtocol.u32(body,0), end=RingProtocol.u32(body,4);
        if (end-start<6000 || end-start>12*3600*10) return true;
        Long a=wall(start), b=wall(end);
        if (a==null || b==null || Math.abs((b-a)-(end-start)/10)>120) return false;
        LocalDate date=date(b);
        var metrics=stats.computeIfAbsent(date,d->new LinkedHashMap<>());
        double minutes=(end-start)/600.0;
        if (!metrics.containsKey("bed") || minutes>metrics.get("bed")[0]) metrics.put("bed",new double[]{minutes,1});
        windows.add(new Window(a,b,date));
        return true;
    }

    // open_health's Ring Gen3 captures show 52 half-minute stages per numbered page.
    // Require a page-zero start, contiguous numbering, and a plausible dated window.
    private void stageNights(List<RingProtocol.Event> events) {
        var pages=new java.io.ByteArrayOutputStream();
        int expected=-1;
        long lastTick=-1;
        List<RingProtocol.Event> sorted=new ArrayList<>();
        for (RingProtocol.Event event : events) if (event.tag()==0x5a && event.body().length==14) sorted.add(event);
        sorted.sort(Comparator.comparingLong(RingProtocol.Event::tick));
        for (RingProtocol.Event event : sorted) {
            int number=RingProtocol.u8(event.body()[0]);
            if (number==0) {
                finishStages(pages.toByteArray(),lastTick);
                pages.reset(); expected=0;
            } else if (number!=expected || event.tick()-lastTick>1200) {
                // An incomplete run cannot supply a duration or efficiency.
                pages.reset(); expected=-1;
            }
            if (expected!=number || number>=28) continue;
            for (int i=1;i<event.body().length;i++) {
                int value=RingProtocol.u8(event.body()[i]);
                for (int shift=6;shift>=0;shift-=2) pages.write(1+((value>>shift)&3));
            }
            lastTick=event.tick(); expected++;
        }
        finishStages(pages.toByteArray(),lastTick);
    }

    private void finishStages(byte[] stages,long endTick) {
        if (stages.length<156 || stages.length>1440 || endTick<stages.length*300L) return;
        Long end=wall(endTick), start=wall(endTick-stages.length*300L);
        if (end==null || start==null || Math.abs(end-start-stages.length*30L)>120) return;
        LocalDate date=date(end);
        byte[] prior=stagedNights.get(date);
        if (prior!=null && prior.length>=stages.length) return;
        int[] count=new int[5];
        for (byte stage : stages) count[stage]++;
        int asleep=count[1]+count[2]+count[3];
        if (asleep<120) return;
        var metrics=stats.computeIfAbsent(date,d->new LinkedHashMap<>());
        double covered=stages.length/2.0, bed=metrics.containsKey("bed")?metrics.get("bed")[0]:0;
        if (bed>covered+30) return;
        metrics.put("deep",new double[]{count[1]/2.0,1});
        metrics.put("light",new double[]{count[2]/2.0,1});
        metrics.put("rem",new double[]{count[3]/2.0,1});
        metrics.put("awake",new double[]{count[4]/2.0,1});
        metrics.put("sleep",new double[]{asleep/2.0,1});
        metrics.put("stage_coverage",new double[]{covered,1});
        if (bed==0 || Math.abs(bed-covered)<=5) metrics.put("eff",new double[]{100.0*asleep/stages.length,1});
        if (bed<covered) metrics.put("bed",new double[]{covered,1});
        stagedNights.put(date,stages);
        windows.add(0,new Window(start,end,date));
    }

    private Window overnight(long unix) {
        for (Window window : windows) if (unix>=window.start() && unix<window.end()) return window;
        return null;
    }

    private void activity() {
        Map<LocalDate,double[]> totals=new TreeMap<>();
        minuteMet.forEach((minute,met)->{
            LocalDate date=date(minute*60);
            add(date,"met",met);
            double[] coverage=totals.computeIfAbsent(date,d->new double[2]);
            coverage[0]++; coverage[1]+=ActivityDetection.equivalents(met);
        });
        totals.forEach((date,coverage)->{
            add(date,"met_coverage",coverage[0]);
            if (coverage[0]>=60) add(date,"moderate_eq",coverage[1]);
        });
    }

    private Long wall(long tick) {
        var before=anchors.floorEntry(tick);
        var after=anchors.ceilingEntry(tick);
        if (before!=null && after!=null && !before.getKey().equals(after.getKey())) {
            long wall=after.getValue()-before.getValue(), counter=(after.getKey()-before.getKey())/10;
            if (Math.abs(wall-counter)>Math.max(1800,counter/50)) return null;
        }
        var anchor=before==null ? after : after==null || tick-before.getKey()<=after.getKey()-tick ? before : after;
        if (anchor==null || Math.abs(tick-anchor.getKey())>MAX_DISTANCE*10) return null;
        long unix=anchor.getValue()+(tick-anchor.getKey())/10;
        return unix>now+120 ? null : unix;
    }
    private void add(long unix,String key,double number,double lower,double upper) {
        if (number<lower || number>upper || unix>now+120) return;
        add(date(unix),key,number);
    }

    private LocalDate date(long unix) { return Instant.ofEpochSecond(unix).atZone(zone).toLocalDate(); }

    private void add(LocalDate date,String key,double number) {
        double[] sum=stats.computeIfAbsent(date,d->new LinkedHashMap<>()).computeIfAbsent(key,k->new double[2]);
        sum[0]+=number; sum[1]++;
    }
}
