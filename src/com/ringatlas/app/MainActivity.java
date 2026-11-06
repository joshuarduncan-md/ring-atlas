package com.ringatlas.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BG=0xff0c1618, CARD=0xff142427, EDGE=0xff2a4043,
        TEXT=0xffedf7f2, MUTED=0xffadc1c0, QUIET=0xff8fa8a8;
    private static final int PICK=101, SAVE=102, PICK_KEY=103, HEALTH=201, BLUETOOTH=202, ENABLE_BT=203, MOTION=204;
    private static final String DEMO="Sample data";
    private static final String[] TABS={"Today","Sleep","Move","Trends","Data"};
    private static final String[] NAV_ICONS={"◉","◒","↗","∿","☰"};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("EEE, MMM d");
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,BluetoothDevice> discovered=new LinkedHashMap<>();
    private LocalStore store;
    private SharedPreferences prefs;
    private RingKey keys;
    private LinearLayout content;
    private TextView noticeView, breathView, liveValueView, workoutStatus;
    private PhoneWorkout phoneWorkout;
    private String workoutMessage="";
    private ScanCallback scanCallback;
    private BluetoothLeScanner scanner;
    private Runnable finishScan;
    private int tab, range=14;
    private String metric="hrv", source=DEMO, notice="", pendingExport="", pendingSource="";
    private LocalDate date=LocalDate.now();
    private boolean busy, scanning, journalPage, breathingPage, comparePage;
    private Integer liveBpm;
    private long breathStart;
    private RingLink.Result ringInfo;
    private BluetoothDevice ring;
    private NavigableMap<LocalDate,Metrics.Day> days=new TreeMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        store=new LocalStore(this); prefs=getSharedPreferences("atlas",0); keys=new RingKey(this);
        try {AtlasArchive.applySettings(store,prefs);}
        catch (Exception error) {notice="Ring Atlas will retry applying restored settings.";}
        source=prefs.getString("source",DEMO);
        phoneWorkout=new PhoneWorkout(this);
        if (state!=null) {
            tab=state.getInt("tab",0); date=LocalDate.parse(state.getString("date",LocalDate.now().toString()));
            pendingExport=state.getString("export",""); pendingSource=state.getString("export_source","");
        }
        reload();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab",tab); out.putString("date",date.toString());
        out.putString("export",pendingExport); out.putString("export_source",pendingSource);
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() {
        if (phoneWorkout.running()) finishPhoneWorkout(false);
        super.onStop();
    }
    @Override protected void onDestroy() {
        stopScan(); handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }

    private void reload() { loadDays(); render(); }
    private void loadDays() {
        days=source.equals(DEMO)?Metrics.sample():store.days(source);
        if (!source.equals(DEMO)) {
            store.days(LocalStore.CARDIO).forEach((day,reading)->{
                Double value=reading.get("atlas_vo2");
                if (value!=null) days.computeIfAbsent(day,Metrics.Day::new).set("atlas_vo2",value);
            });
            addLocalWorkouts();
        }
        boolean rest=prefs.getBoolean("rest",false);
        int goal=prefs.getInt("step_goal",0);
        for (Metrics.Day day : days.values()) {
            day.values.keySet().removeAll(List.of("atlas_sleep","atlas_bed","atlas_readiness","atlas_activity","atlas_fitness","atlas_overall"));
            AtlasScore.Daily score=AtlasScore.evaluate(days,day.date,goal,rest && day.date.equals(LocalDate.now()));
            setScore(day,"atlas_sleep",score.sleep()); setScore(day,"atlas_bed",score.opportunity());
            setScore(day,"atlas_readiness",score.readiness()); setScore(day,"atlas_activity",score.activity());
            setScore(day,"atlas_fitness",score.fitness()); setScore(day,"atlas_overall",score.overall());
        }
    }
    private void setScore(Metrics.Day day,String key,AtlasScore.Score score) {
        if (score.value()!=null) day.set(key,score.value());
    }
    private void addLocalWorkouts() {
        ZoneId zone=ZoneId.systemDefault();
        Map<Long,Integer> occupied=new HashMap<>();
        for (LocalStore.Workout workout : store.localWorkouts()) {
            for (long t=workout.start()/60;t<(workout.end()+30)/60;t++)
                occupied.merge(t,workout.intensity(),Math::max);
        }
        Map<LocalDate,int[]> totals=new HashMap<>();
        occupied.forEach((minute,intensity)->{
            LocalDate day=Instant.ofEpochSecond(minute*60).atZone(zone).toLocalDate();
            int[] total=totals.computeIfAbsent(day,d->new int[2]);
            total[0]++; total[1]+=intensity==3?2:intensity==2?1:0;
        });
        totals.forEach((day,total)->{
            Metrics.Day record=days.computeIfAbsent(day,Metrics.Day::new);
            record.set("exercise",Math.max(total[0],record.values.getOrDefault("exercise",0.0)));
            if (total[1]>0) record.set("moderate_eq",Math.max(total[1],record.values.getOrDefault("moderate_eq",0.0)));
        });
    }
    private void selectSource(String selected,boolean latest) {
        source=selected; prefs.edit().putString("source",source).apply();
        loadDays();
        if (latest) date=days.isEmpty()?LocalDate.now():days.lastKey();
        render();
    }
    private void setTab(int selected) {
        if (tab==4 && selected!=4) stopScan();
        tab=selected; journalPage=false; comparePage=false; breathingPage=false; stopBreathing(); render();
    }
    @Override public void onBackPressed() {
        if (journalPage || breathingPage || comparePage) setTab(tab);
        else if (tab!=0) setTab(0);
        else super.onBackPressed();
    }

    private void render() {
        LinearLayout root=column(); root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=column(); content.setPadding(dp(19),dp(18),dp(19),dp(26)); scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout mast=row(); mast.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordmark=text("RING ATLAS",13,Metrics.MINT,true); wordmark.setLetterSpacing(.22f);
        mast.addView(wordmark,new LinearLayout.LayoutParams(0,-2,1));
        TextView provenance=pill(sourceLabel(),source.equals(DEMO)?Metrics.GOLD:Metrics.MINT);
        provenance.setOnClickListener(v->sourcePicker()); mast.addView(provenance); content.addView(mast);
        gap(content,20);
        liveValueView=null; workoutStatus=null;
        if (journalPage) journal();
        else if (breathingPage) breathing();
        else if (comparePage) compare();
        else switch(tab) {
            case 1 -> sleep(); case 2 -> activity(); case 3 -> trends(); case 4 -> data(); default -> today();
        }
        LinearLayout nav=row(); nav.setPadding(dp(8),dp(6),dp(8),dp(8)); nav.setBackgroundColor(CARD);
        for (int i=0;i<TABS.length;i++) {
            int index=i;
            LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(2),dp(4),dp(2),dp(4));
            if (tab==i) item.setBackground(shape(0xff233a37,0xff233a37,15));
            int color=tab==i?Metrics.MINT:MUTED;
            TextView icon=text(NAV_ICONS[i],21,color,true), label=text(TABS[i],11,color,tab==i);
            icon.setGravity(Gravity.CENTER); label.setGravity(Gravity.CENTER);
            item.addView(icon); item.addView(label); item.setContentDescription(TABS[i]+(tab==i?", selected":""));
            item.setFocusable(true); item.setOnClickListener(v->setTab(index));
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2)); setContentView(root);
    }

    private void today() {
        heading("TODAY","Your daily rhythm","Sleep, recovery and movement in one view.");
        calendar(); demoBanner();
        Metrics.Day day=current();
        if (prefs.getBoolean("rest",false)) {
            LinearLayout card=card(); card.addView(text("Rest mode",16,Metrics.MINT,true));
            card.addView(text("Movement goals are paused in Ring Atlas.",14,MUTED,false));
        }
        AtlasScore.Daily local=localScores();
        LinearLayout scores=card(); scores.addView(kicker("ATLAS DAILY SCORES",Metrics.MINT)); gap(scores,5);
        LinearLayout dials=row();
        dial(dials,day,"atlas_readiness","Readiness");
        String sleepKey=day.get("atlas_sleep")==null && day.get("atlas_bed")!=null?"atlas_bed":"atlas_sleep";
        dial(dials,day,sleepKey,sleepKey.equals("atlas_bed")?"Bed window":"Sleep");
        dial(dials,day,"atlas_activity","Activity"); scores.addView(dials);
        action(scores,"See score contributors",false,()->scoreDetails(local));
        if (!local.tips().isEmpty()) {
            LinearLayout tips=card(); tips.addView(kicker("GUIDANCE FROM YOUR DATA",Metrics.GOLD));
            for (String tip : local.tips()) { gap(tips,8); tips.addView(text(tip,14,TEXT,false)); }
        }
        if (has(day,"readiness","sleep_score","activity_score")) {
            LinearLayout original=card(); original.addView(kicker("IMPORTED OURA SCORES",Metrics.BLUE));
            LinearLayout branded=row();
            dial(branded,day,"readiness","Readiness"); dial(branded,day,"sleep_score","Sleep");
            dial(branded,day,"activity_score","Activity"); original.addView(branded);
        }
        metricGrid("At a glance",day,new String[]{"sleep","hrv","rhr","steps"});
        if (day.values.isEmpty()) blankDay();
        section("Your usual range","Compares the selected day with the prior 28 days when at least seven readings exist.");
        LinearLayout baseline=card();
        int comparisons=0;
        for (String key : new String[]{"sleep","hrv","rhr","temp_delta","resp"}) {
            Double current=day.get(key);
            List<Double> history=Metrics.values(days,key,date.minusDays(1),28).stream().filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
            if (current==null || history.size()<7) continue;
            history.sort(Double::compare);
            double median=history.size()%2==0 ? (history.get(history.size()/2-1)+history.get(history.size()/2))/2
                : history.get(history.size()/2);
            comparisons++;
            metricRow(baseline,Metrics.ALL.get(key).label(),Metrics.value(key,current),
                "28-day median "+Metrics.value(key,median)+" · "+history.size()+" readings",Metrics.ALL.get(key).color());
        }
        if (comparisons==0) baseline.addView(text("Your baseline will appear as you build history in this source.",14,MUTED,false));
        metricGrid("Recovery and stress",day,new String[]{"readiness","hrv","overnight_hrv","rhr","hr","optical_hr",
            "temp_delta","temp","resp","spo2","stress","restored","vascular_age","resilience"});
        action(content,"Log a habit or note",false,()->{journalPage=true;render();});
        action(content,"One-minute breathing session",false,()->{breathingPage=true;render();});
    }

    private void sleep() {
        heading("SLEEP","A closer look at sleep","See duration, stage totals and overnight signals.");
        calendar(); demoBanner();
        Metrics.Day day=current();
        LinearLayout card=card();
        String lead=day.get("sleep")!=null?"sleep":"bed";
        card.addView(kicker(lead.equals("sleep")?"TIME ASLEEP":"TIME IN BED",Metrics.BLUE));
        card.addView(text(Metrics.value(lead,day.get(lead)),36,TEXT,true));
        byte[] sequence=source.startsWith(LocalStore.RING+"|")?store.timeline(source,date):new byte[0];
        if (sequence.length>0) {
            gap(card,12); card.addView(kicker("RING SLEEP STAGES · START TO END",Metrics.BLUE));
            card.addView(new Charts.SleepTimeline(this,sequence),new LinearLayout.LayoutParams(-1,dp(60)));
            card.addView(text("Awake · REM · Light · Deep, from top to bottom",12,QUIET,false));
        }
        Charts.StageBar stages=new Charts.StageBar(this,day);
        if (stages.hasData()) {
            gap(card,12); card.addView(stages,new LinearLayout.LayoutParams(-1,dp(15))); gap(card,10);
            for (String key : new String[]{"deep","light","rem","awake"}) if(day.get(key)!=null)
                metricRow(card,Metrics.ALL.get(key).label(),Metrics.value(key,day.get(key)),null,Metrics.ALL.get(key).color());
        } else {
            gap(card,8); card.addView(text("Sleep stage totals are unavailable for this date and source.",14,MUTED,false));
        }
        metricGrid("Sleep details",day,new String[]{"atlas_sleep","atlas_bed","sleep_score","bed","eff","stage_coverage","latency","rhr","overnight_hrv","resp","spo2","temp_delta"});
        addChart("Time asleep over 14 days","sleep",14);
        if (day.values.isEmpty()) blankDay();
    }

    private void activity() {
        heading("MOVEMENT","Make movement visible","Track activity volume and compare it with your usual days.");
        calendar(); demoBanner();
        Metrics.Day day=current();
        Double steps=day.get("steps"), goal=day.get("goal_steps");
        int localGoal=prefs.getInt("step_goal",0);
        if (localGoal>0) goal=(double)localGoal;
        LinearLayout box=card(); box.addView(kicker("DAILY STEPS",Metrics.GOLD));
        box.addView(text(Metrics.value("steps",steps),36,TEXT,true)); gap(box,8);
        boolean rest=prefs.getBoolean("rest",false);
        if (steps!=null && goal!=null && goal>0 && !rest) {
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100); bar.setProgress((int)Math.min(100,100*steps/goal)); box.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));
            gap(box,6); box.addView(text("Goal "+Metrics.value("goal_steps",goal)+" steps",13,MUTED,false));
        } else box.addView(text(rest?"Rest mode is on.":steps==null?"Step totals are unavailable for this date.":"No step goal is available in this source.",14,MUTED,false));
        action(box,"Set local step goal",false,this::stepGoal);
        fitnessCard(day);
        LinearLayout strength=card();
        strength.addView(kicker("STRENGTH TRAINING",Metrics.GOLD));
        action(strength,"Open lifting log",true,()->startActivityForResult(new Intent(this,StrengthActivity.class),330));
        workoutCards();
        metricGrid("Activity details",day,new String[]{"atlas_activity","activity_score","exercise","moderate_eq","met_coverage",
            "workouts","active_cal","distance","vo2","met"});
        addChart("Steps over 14 days","steps",14);
        if (day.values.isEmpty()) blankDay();
    }

    private AtlasScore.Daily localScores() {
        return AtlasScore.evaluate(days,date,prefs.getInt("step_goal",0),
            prefs.getBoolean("rest",false) && date.equals(LocalDate.now()));
    }

    private void scoreDetails(AtlasScore.Daily scores) {
        StringJoiner explanation=new StringJoiner("\n\n");
        describe(explanation,"Sleep",scores.sleep());
        if (scores.opportunity().value()!=null) describe(explanation,"Sleep opportunity",scores.opportunity());
        describe(explanation,"Readiness",scores.readiness());
        describe(explanation,"Activity",scores.activity());
        describe(explanation,"Fitness habits",scores.fitness());
        describe(explanation,"Personal fitness index",scores.overall());
        new AlertDialog.Builder(this).setTitle("How Atlas scores this day")
            .setMessage(explanation+"\n\nAtlas scores use local rules and available inputs. They’re personal wellness indicators.")
            .setPositiveButton("Done",null).show();
    }

    private void describe(StringJoiner out,String name,AtlasScore.Score score) {
        String heading=name+(score.value()==null?" · pending":" · "+Math.round(score.value())+"/100");
        out.add(heading+"\n"+String.join("\n",score.details()));
    }

    private void fitnessCard(Metrics.Day day) {
        LinearLayout box=card(); box.addView(kicker("FITNESS AND CARDIO CAPACITY",Metrics.MINT)); gap(box,6);
        Double fitness=day.get("atlas_fitness");
        box.addView(text(fitness==null?"Fitness habits · building history":"Fitness habits · "+Math.round(fitness)+"/100",20,TEXT,true));
        box.addView(text("Uses seven-day activity volume, consistency and sleep when enough measurements are available.",14,MUTED,false));
        Double overall=day.get("atlas_overall");
        gap(box,7);
        box.addView(text(overall==null?"Personal fitness index · awaiting workout history and repeat walk tests":
            "Personal fitness index · "+Math.round(overall)+"/100",15,Metrics.MINT,true));
        Double vo2=day.get("atlas_vo2");
        if (vo2!=null) { gap(box,8); box.addView(text("One-mile estimate  "+Metrics.value("atlas_vo2",vo2),16,Metrics.MINT,true)); }
        action(box,"Enter one-mile walk test",false,this::walkTest);
        if (!source.equals(DEMO) && days.values().stream().anyMatch(reading->reading.get("atlas_vo2")!=null))
            action(box,"Trend my VO₂ max estimates",false,()->{metric="atlas_vo2"; range=90;setTab(3);});
    }

    private void walkTest() {
        LinearLayout form=column(); form.setPadding(dp(21),dp(6),dp(21),0);
        form.addView(text("For people who already walk briskly safely. Use a measured level mile. Enter your heart rate at the finish. The study used healthy adults ages 30–69.",14,MUTED,false));
        EditText age=numeric(form,"Age in years",false), weight=numeric(form,"Weight in pounds",true),
            minutes=numeric(form,"Whole minutes",false), seconds=numeric(form,"Additional seconds (0–59)",false),
            hr=numeric(form,"Finish heart rate (bpm)",false);
        Spinner equation=new Spinner(this);
        equation.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Choose the study equation","Female equation","Male equation"})); form.addView(equation);
        ScrollView scroll=new ScrollView(this); scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("One-mile walk estimate").setView(scroll)
            .setNegativeButton("Cancel",null).setPositiveButton("Calculate and save",null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                if (equation.getSelectedItemPosition()==0) throw new IllegalArgumentException("Choose the study equation.");
                int sec=seconds.getText().toString().isEmpty()?0:Integer.parseInt(seconds.getText().toString());
                if (sec<0 || sec>59) throw new IllegalArgumentException("Additional seconds must be 0–59.");
                double estimate=AtlasScore.walkTest(Integer.parseInt(age.getText().toString()),
                    Double.parseDouble(weight.getText().toString()),equation.getSelectedItemPosition()==2,
                    Integer.parseInt(minutes.getText().toString())+sec/60.0,Integer.parseInt(hr.getText().toString()));
                store.saveDays(LocalStore.CARDIO,List.of(new Metrics.Day(date).set("atlas_vo2",estimate)));
                dialog.dismiss();
                if (source.equals(DEMO)) selectSource(LocalStore.CARDIO,false); else reload();
                new AlertDialog.Builder(this).setTitle(Metrics.value("atlas_vo2",estimate))
                    .setMessage("Estimated VO₂ max saved for "+date.format(DATE)+". The Kline one-mile walk equation estimates fitness from walk time, finish heart rate, age and weight.")
                    .setPositiveButton("Done",null).show();
            } catch (NumberFormatException badInput) { hr.setError("Complete the numeric fields."); }
            catch (IllegalArgumentException badInput) { hr.setError(badInput.getMessage()); }
        });
    }

    private EditText numeric(LinearLayout form,String hint,boolean decimal) {
        EditText field=new EditText(this); field.setHint(hint); field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));
        form.addView(field); return field;
    }

    private void workoutCards() {
        LinearLayout box=card(); box.addView(kicker("WORKOUTS AND DETECTION",Metrics.GOLD)); gap(box,5);
        box.addView(text("Ring sync can suggest sustained movement. Phone tracking can suggest walking, running or cycling while a session is open.",14,MUTED,false));
        workoutStatus=text(phoneWorkout.running()?phoneWorkout.status():workoutMessage,14,Metrics.MINT,false); box.addView(workoutStatus);
        if (phoneWorkout.running()) action(box,"End phone tracking",true,()->finishPhoneWorkout(true));
        else action(box,"Track with phone sensors",true,this::askMotion);
        action(box,"Log a workout",false,()->workoutEditor(null));
        ZoneId zone=ZoneId.systemDefault();
        List<LocalStore.Workout> workouts=store.workouts(date.atStartOfDay(zone).toEpochSecond(),
            date.plusDays(1).atStartOfDay(zone).toEpochSecond());
        for (LocalStore.Workout workout : workouts) {
            if (workout.source().startsWith(LocalStore.RING+"|") && !workout.source().equals(source)) continue;
            LinearLayout item=card();
            String origin=workout.source().startsWith(LocalStore.RING+"|")?LocalStore.RING:workout.source();
            item.addView(kicker(workout.status()==LocalStore.PENDING?"SUGGESTED · "+origin:"LOGGED · "+origin,Metrics.GOLD));
            item.addView(text(workout.type(),17,TEXT,true));
            String start=Instant.ofEpochSecond(workout.start()).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"));
            item.addView(text(start+" · "+Math.round((workout.end()-workout.start())/60.0)+" min · "+ActivityDetection.INTENSITIES[workout.intensity()],14,MUTED,false));
            if (workout.status()==LocalStore.PENDING) {
                action(item,"Confirm or change activity",false,()->workoutEditor(workout));
                action(item,"Dismiss suggestion",false,()->{store.resolveWorkout(workout,workout.type(),workout.intensity(),LocalStore.DISMISSED);reload();});
            } else if (!workout.source().equals(LocalStore.HEALTH))
                action(item,"Edit workout",false,()->workoutEditor(workout));
        }
    }

    private void workoutEditor(LocalStore.Workout existing) {
        LinearLayout form=column(); form.setPadding(dp(22),dp(6),dp(22),0);
        Spinner type=new Spinner(this), intensity=new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ActivityDetection.TYPES));
        intensity.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ActivityDetection.INTENSITIES));
        form.addView(text("Activity type",13,MUTED,true)); form.addView(type);
        form.addView(text("Intensity",13,MUTED,true)); form.addView(intensity);
        ZoneId zone=ZoneId.systemDefault();
        LocalTime[] start={date.equals(LocalDate.now()) ? LocalTime.now().minusMinutes(30).withSecond(0).withNano(0) : LocalTime.of(17,0)};
        EditText duration=null;
        if (existing!=null) {
            int index=Arrays.asList(ActivityDetection.TYPES).indexOf(existing.type());
            type.setSelection(index<0?ActivityDetection.TYPES.length-1:index); intensity.setSelection(existing.intensity());
        } else {
            type.setSelection(ActivityDetection.TYPES.length-1);
            action(form,"Set start time",false,()->new TimePickerDialog(this,(v,h,m)->start[0]=LocalTime.of(h,m),start[0].getHour(),start[0].getMinute(),false).show());
            duration=numeric(form,"Duration in minutes",false);
        }
        EditText input=duration;
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(existing==null?"Log a workout":"Review workout")
            .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save workout",null);
        if (existing!=null && existing.status()==LocalStore.CONFIRMED)
            builder.setNeutralButton("Remove",(d,w)->{store.resolveWorkout(existing,existing.type(),existing.intensity(),LocalStore.DISMISSED);reload();});
        AlertDialog dialog=builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=ActivityDetection.TYPES[type.getSelectedItemPosition()];
            int level=intensity.getSelectedItemPosition();
            if (existing!=null) store.resolveWorkout(existing,name,level,LocalStore.CONFIRMED);
            else {
                int length;
                try {length=Integer.parseInt(input.getText().toString());}
                catch (NumberFormatException invalid) { length=0; }
                if (length<1 || length>720) {input.setError("Enter 1–720 minutes.");return;}
                long beginning=date.atTime(start[0]).atZone(zone).toEpochSecond(), end=beginning+length*60L;
                if (end>Instant.now().getEpochSecond()+60) {input.setError("The workout must already have ended.");return;}
                store.saveWorkout(new LocalStore.Workout(UUID.randomUUID().toString(),LocalStore.MANUAL,beginning,end,name,level,LocalStore.CONFIRMED));
            }
            dialog.dismiss();
            if (source.equals(DEMO)) selectSource(LocalStore.MANUAL,false); else reload();
        });
    }

    private void askMotion() {
        if (phoneWorkout.running()) return;
        String[] permissions={Manifest.permission.ACTIVITY_RECOGNITION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};
        boolean missing=checkSelfPermission(permissions[0])!=PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(permissions[1])!=PackageManager.PERMISSION_GRANTED;
        if (!missing) { startPhoneWorkout(); return; }
        new AlertDialog.Builder(this).setTitle("Use phone sensors")
            .setMessage("Step cadence helps identify walking and running. Optional precise location supplies speed to help suggest cycling. Ring Atlas keeps speed samples in memory and saves no location points.")
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Start with current access",(d,w)->startPhoneWorkout())
            .setPositiveButton("Choose permissions",(d,w)->requestPermissions(permissions,MOTION)).show();
    }

    private void startPhoneWorkout() {
        if (phoneWorkout.running()) return;
        workoutMessage="";
        phoneWorkout.start(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(workoutTick); render();
    }

    private final Runnable workoutTick=new Runnable() {
        @Override public void run() {
            if (!phoneWorkout.running()) return;
            if (workoutStatus!=null) workoutStatus.setText(phoneWorkout.status());
            handler.postDelayed(this,1000);
        }
    };

    private void finishPhoneWorkout(boolean visible) {
        LocalStore.Workout workout=phoneWorkout.stop();
        handler.removeCallbacks(workoutTick); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        workoutMessage=workout==null?"Sessions need at least one minute.":"Review the phone suggestion below.";
        if (workout!=null) store.saveWorkout(workout);
        date=LocalDate.now();
        if (visible || !isFinishing()) render();
    }

    private void stepGoal() {
        EditText field=new EditText(this); field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_NUMBER);
        int saved=prefs.getInt("step_goal",0);
        field.setHint("Steps per day");
        if (saved>0) field.setText(Integer.toString(saved));
        LinearLayout form=column(); form.setPadding(dp(22),dp(6),dp(22),0); form.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Local step goal").setView(form)
            .setNegativeButton("Cancel",null).setNeutralButton("Use source goal",(d,w)->{
                prefs.edit().remove("step_goal").apply(); reload();
            }).setPositiveButton("Save",null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            int steps;
            try { steps=Integer.parseInt(field.getText().toString()); }
            catch (NumberFormatException error) { steps=0; }
            if (steps<1 || steps>100000) { field.setError("Enter a goal from 1 to 100,000 steps."); return; }
            prefs.edit().putInt("step_goal",steps).apply(); dialog.dismiss(); reload();
        });
    }

    private void trends() {
        heading("LONG VIEW","See what’s changing","Compare measurements over 7, 14, 30 or 90 days.");
        calendar(); demoBanner();
        List<String> available=Metrics.ALL.keySet().stream().filter(k->days.values().stream().anyMatch(d->d.get(k)!=null))
            .collect(java.util.stream.Collectors.toList());
        if (!available.isEmpty() && !available.contains(metric)) metric=available.get(0);
        action(content,Metrics.ALL.get(metric).label()+"  ▾",false,()->metricPicker(available,()->render()));
        LinearLayout ranges=row(); gap(content,8);
        for (int size : new int[]{7,14,30,90}) {
            TextView choice=button(size+"d",size==range,()->{range=size;render();});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(42),1);
            params.setMargins(0,0,dp(5),0); ranges.addView(choice,params);
        }
        content.addView(ranges); gap(content,12);
        addChart(Metrics.ALL.get(metric).label(),metric,range);
        action(content,"Compare days by habit",false,()->{comparePage=true;render();});
        if (!source.equals(DEMO)) action(content,"Review seven-day summary",false,this::weeklySummary);
        section("Reading the chart","A break in the line means there’s no reading for that date. The source chip switches the entire view.");
    }

    private void weeklySummary() {
        StringJoiner report=new StringJoiner("\n","Ring Atlas · seven days ending "+date.format(DATE)+"\nSource · "+sourceLabel()+"\n\n","");
        for (String key : List.of("sleep","hrv","rhr","steps","atlas_sleep","atlas_readiness","atlas_activity")) {
            List<Double> readings=new ArrayList<>();
            for (Double value : Metrics.values(days,key,date,7)) if (value!=null) readings.add(value);
            if (!readings.isEmpty()) report.add(Metrics.ALL.get(key).label()+" · "+Metrics.value(key,Metrics.mean(readings))+
                " average ("+readings.size()+"/7 days)");
        }
        List<Double> activity=new ArrayList<>();
        for (Double value : Metrics.values(days,"moderate_eq",date,7)) if (value!=null) activity.add(value);
        if (!activity.isEmpty()) report.add("Moderate-equivalent activity · "+Math.round(activity.stream().mapToDouble(Double::doubleValue).sum())+
            " minutes ("+activity.size()+"/7 days)");
        if (report.toString().endsWith("\n\n"))
            report.add("No readings in this week.");
        new AlertDialog.Builder(this).setTitle("Seven-day summary").setMessage(report.toString())
            .setNegativeButton("Done",null).setPositiveButton("Share…",(d,w)->{
                Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report.toString());
                startActivity(Intent.createChooser(send,"Share Ring Atlas summary"));
            }).show();
    }

    private void addChart(String title,String key,int count) {
        LinearLayout box=card(); box.addView(text(title,16,TEXT,true));
        List<Double> observations=Metrics.values(days,key,date,count);
        List<Double> observed=observations.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toList());
        if (observed.isEmpty()) {
            gap(box,8); box.addView(text("No readings in this date range.",14,MUTED,false)); return;
        }
        gap(box,7);
        box.addView(text("Average  "+Metrics.value(key,Metrics.mean(observed))+"   ·   "+observed.size()+"/"+count+" days",14,MUTED,false));
        box.addView(new Charts.Trend(this,Metrics.ALL.get(key),observations),new LinearLayout.LayoutParams(-1,dp(150)));
        box.addView(text(date.minusDays(count-1).format(DATE)+"  –  "+date.format(DATE),12,QUIET,false));
    }

    private void data() {
        heading("CONTROL CENTER","Your ring. Your data.","Choose a source and keep your history on this phone.");
        noticeView=text(notice,14,Metrics.MINT,false);
        content.addView(noticeView);
        noticeView.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);
        if (!notice.isEmpty()) gap(content,12);
        LinearLayout connect=card(); connect.addView(kicker("01 / RING CONNECTION",Metrics.MINT)); gap(connect,7);
        connect.addView(text("Direct Bluetooth",20,TEXT,true)); gap(connect,4);
        connect.addView(text("Wake the ring on its charger and close Oura. Ring Atlas creates and stores a key during setup when the ring is new or has already been factory-reset.",14,MUTED,false));
        gap(connect,10);
        action(connect,scanning?"Scanning for rings…":"Find nearby Oura rings",true,this::startScan);
        if (ring!=null) connect.addView(text("Selected  "+safeName(ring),13,Metrics.MINT,true));
        if (ringInfo!=null) {
            String info="Firmware "+ringInfo.firmware()+"   ·   Battery "+(ringInfo.battery()==null?"—":ringInfo.battery()+"%");
            connect.addView(text(info,14,TEXT,false));
            if (ringInfo.bpm()!=null || ringInfo.oxygen()!=null) {
                connect.addView(text("Latest cached · "+(ringInfo.bpm()==null?"—":ringInfo.bpm()+" bpm")+"  /  "+
                    (ringInfo.oxygen()==null?"—":ringInfo.oxygen()+"% SpO₂"),14,MUTED,false));
            }
        }
        if (ring!=null || !ringAddress().isEmpty()) {
            action(connect,"Connect and sync",true,this::connectRing);
            action(connect,"Pairing help and key import",false,this::pairingHelp);
            action(connect,"Live heart rate · 25 seconds",false,this::liveRing);
            if (keys.has(ringAddress())) action(connect,"View saved recovery key",false,()->showRecoveryKey(ringAddress()));
            liveValueView=text(liveBpm==null?"":"Last live reading · "+liveBpm+" bpm",15,Metrics.ROSE,true);
            connect.addView(liveValueView);
        }
        connect.addView(text(String.format(Locale.getDefault(),"%,d raw history events archived",store.eventCount()),12,QUIET,false));
        LinearLayout health=card(); health.addView(kicker("02 / ANDROID INTEGRATION",Metrics.BLUE)); gap(health,7);
        health.addView(text("Health Connect",20,TEXT,true)); gap(health,4);
        health.addView(text("Android 14 and newer. Enable sharing in Oura first. Oura requires an active membership for Health Connect on Gen3 and newer rings. Ring Atlas reads up to 30 days of available Oura records.",14,MUTED,false));
        if (Build.VERSION.SDK_INT>=34) action(health,"Sync Oura from Health Connect",false,this::healthPermissions);
        LinearLayout files=card(); files.addView(kicker("03 / PORTABLE HISTORY",Metrics.GOLD)); gap(files,7);
        files.addView(text("Import and export",20,TEXT,true)); gap(files,4);
        files.addView(text("Import supported daily CSV, JSON or a Membership Hub ZIP. Oura’s scores appear when the file includes them.",14,MUTED,false));
        action(files,"Import an Oura data file",false,this::pickFile);
        action(files,"Export current source as CSV",false,()->export("daily"));
        action(files,"Export raw Bluetooth events",false,()->export("raw"));
        action(files,"Export journal",false,()->export("journal"));
        action(files,"Export workout log",false,()->export("workouts"));
        action(files,"Encrypted backup and restore",false,()->dataTool("backup"));
        action(files,"Compare wearables",false,()->dataTool("compare"));
        action(files,"Export FHIR R4",false,()->dataTool("fhir"));
        LinearLayout settings=card(); settings.addView(kicker("04 / PREFERENCES",Metrics.ROSE));
        action(settings,"Switch data source",false,this::sourcePicker);
        Switch rest=new Switch(this); rest.setText("Rest mode"); rest.setTextSize(15); rest.setTextColor(TEXT);
        rest.setPadding(dp(8),0,dp(8),0); rest.setMinHeight(dp(46)); rest.setChecked(prefs.getBoolean("rest",false));
        rest.setOnCheckedChangeListener((view,checked)->{
            prefs.edit().putBoolean("rest",checked).apply(); loadDays();
        }); settings.addView(rest);
        action(settings,"Privacy and permissions",false,()->startActivity(new Intent(this,PrivacyActivity.class)));
        action(settings,"Clear local data",false,this::confirmErase);
        gap(content,8); content.addView(text("RING ATLAS  /  0.3.0  /  INDEPENDENT AND LOCAL",10,QUIET,true));
    }

    private void journal() {
        heading("JOURNAL","Log what changed","Tag the habits and routines you want to compare with your data.");
        calendar();
        action(content,"Add a journal entry",true,this::addNote);
        section("Recent entries",null);
        List<LocalStore.Note> notes=store.notes();
        if (notes.isEmpty()) { LinearLayout box=card(); box.addView(text("No journal entries yet.",14,MUTED,false)); }
        for (LocalStore.Note note : notes.subList(0,Math.min(notes.size(),100))) {
            LinearLayout box=card(); box.addView(kicker(note.date().format(DATE).toUpperCase(Locale.getDefault()),Metrics.MINT));
            box.addView(text(note.tag(),17,TEXT,true));
            if (!note.text().isEmpty()) { gap(box,4); box.addView(text(note.text(),14,MUTED,false)); }
            action(box,"Delete entry",false,()->new AlertDialog.Builder(this).setTitle("Delete this entry?")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{store.deleteNote(note.id());render();}).show());
        }
    }

    private void addNote() {
        String[] tags={"Caffeine","Alcohol","Late meal","Travel","Exercise","Illness","Stress","Rest day","Meditation","Period","Custom"};
        LinearLayout form=column(); form.setPadding(dp(22),dp(8),dp(22),0);
        Spinner picker=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,tags);
        picker.setAdapter(adapter); form.addView(picker);
        EditText custom=new EditText(this); custom.setSingleLine(); custom.setHint("Custom tag"); form.addView(custom);
        EditText note=new EditText(this); note.setHint("Optional note"); note.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)}); form.addView(note);
        new AlertDialog.Builder(this).setTitle("Log for "+date.format(DATE)).setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->{
                String tag=tags[picker.getSelectedItemPosition()];
                if (tag.equals("Custom") && !custom.getText().toString().trim().isEmpty()) {
                    tag=custom.getText().toString().trim();
                    tag=tag.substring(0,Math.min(50,tag.length()));
                }
                store.addNote(date,tag,note.getText().toString().trim()); render();
            }).show();
    }

    private void compare() {
        heading("HABIT COMPARISON","Compare tagged days","Uses up to 90 days ending on "+date.format(DATE)+".");
        if (source.equals(DEMO)) { section("Choose your own data","Sample readings are excluded from habit comparisons."); action(content,"Switch source",false,this::sourcePicker); return; }
        List<LocalStore.Note> notes=store.notes();
        List<String> tags=notes.stream().map(LocalStore.Note::tag).distinct().sorted().collect(java.util.stream.Collectors.toList());
        if (tags.isEmpty()) { section("Add a tag first","Log a habit on the days it occurs, then return here."); return; }
        action(content,"Choose a habit and metric",true,()->new AlertDialog.Builder(this).setTitle("Habit")
            .setItems(tags.toArray(new String[0]),(d,w)->{
                String tag=tags.get(w);
                metricPicker(new ArrayList<>(Metrics.ALL.keySet()),()->showComparison(notes,tag,metric));
            }).show());
    }

    private void showComparison(List<LocalStore.Note> notes,String tag,String key) {
        Set<LocalDate> tagged=new HashSet<>();
        for (LocalStore.Note note : notes) if (note.tag().equals(tag)) tagged.add(note.date());
        List<Double> with=new ArrayList<>(), without=new ArrayList<>();
        for (Metrics.Day day : days.subMap(date.minusDays(89),true,date,true).values()) {
            Double value=day.get(key);
            if (value!=null) (tagged.contains(day.date)?with:without).add(value);
        }
        String msg="Tagged days  "+Metrics.value(key,Metrics.mean(with))+"  (n="+with.size()+")\n"+
            "Days without this tag  "+Metrics.value(key,Metrics.mean(without))+"  (n="+without.size()+")\n\n"+
            "These are averages of available readings. Days without a tag may still include the habit. Other factors may contribute to differences.";
        new AlertDialog.Builder(this).setTitle(tag+" · "+Metrics.ALL.get(key).label()).setMessage(msg).setPositiveButton("Done",null).show();
    }

    private void breathing() {
        heading("PAUSE","A minute to breathe","Breathe gently. Follow four seconds in and six seconds out, or use your own pace.");
        LinearLayout box=card(); breathView=text("Ready",37,Metrics.MINT,true);
        breathView.setGravity(Gravity.CENTER); box.addView(breathView,new LinearLayout.LayoutParams(-1,dp(170)));
        action(box,"Start 60 seconds",true,()->{ stopBreathing(); breathStart=SystemClock.elapsedRealtime(); handler.post(breathTick); });
        action(box,"Stop",false,()->{stopBreathing();breathView.setText("Ready");});
    }
    private final Runnable breathTick=new Runnable() {
        @Override public void run() {
            if (!breathingPage || breathStart==0 || breathView==null) return;
            long s=(SystemClock.elapsedRealtime()-breathStart)/1000;
            if (s>=60) { breathView.setText("Complete"); breathStart=0; return; }
            breathView.setText((s%10<4?"Inhale":"Exhale")+"\n"+(60-s)+"s");
            handler.postDelayed(this,250);
        }
    };
    private void stopBreathing() { breathStart=0; handler.removeCallbacks(breathTick); }

    private void calendar() {
        LinearLayout bar=row();
        TextView back=button("‹",false,()->{date=date.minusDays(1);render();});
        back.setContentDescription("Previous day");
        TextView label=button(date.equals(LocalDate.now())?"Today, "+date.format(DateTimeFormatter.ofPattern("MMM d")):date.format(DATE),false,this::pickDate);
        TextView next=button("›",false,()->{if (date.isBefore(LocalDate.now())) {date=date.plusDays(1);render();}});
        next.setContentDescription("Next day"); next.setEnabled(date.isBefore(LocalDate.now())); next.setAlpha(next.isEnabled()?1f:.48f);
        bar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(43)));
        LinearLayout.LayoutParams center=new LinearLayout.LayoutParams(0,dp(43),1);
        center.setMargins(dp(7),0,dp(7),0); bar.addView(label,center);
        bar.addView(next,new LinearLayout.LayoutParams(dp(44),dp(43)));
        content.addView(bar); gap(content,13);
    }
    private void pickDate() {
        DatePickerDialog dialog=new DatePickerDialog(this,(v,y,m,d)->{date=LocalDate.of(y,m+1,d);render();},
            date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis()); dialog.show();
    }
    private void demoBanner() {
        if (!source.equals(DEMO)) return;
        LinearLayout box=card();
        box.addView(text("You’re viewing sample data",14,Metrics.GOLD,true));
        box.addView(text("Add a source to see your own measurements.",13,MUTED,false));
        action(box,"Add my data",false,()->setTab(4));
    }
    private Metrics.Day current() { return days.getOrDefault(date,new Metrics.Day(date)); }
    private void blankDay() {
        section("No readings for this day",days.isEmpty()?"Sync or import data to get started.":"Choose another date or switch data sources.");
        action(content,days.isEmpty()?"Open data settings":"Jump to latest date",false,
            ()->{if(days.isEmpty()) setTab(4); else {date=days.lastKey();render();}});
    }
    private void metricGrid(String title,Metrics.Day day,String[] selected) {
        List<String> present=Arrays.stream(selected).filter(k->day.get(k)!=null).collect(java.util.stream.Collectors.toList());
        if (present.isEmpty()) return;
        section(title,null);
        for (int i=0;i<present.size();i+=2) {
            LinearLayout row=row(); row.setBaselineAligned(false);
            for (int j=0;j<2;j++) {
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);
                if (j==0) params.setMargins(0,0,dp(9),0);
                if (i+j>=present.size()) { row.addView(new View(this),params); continue; }
                String key=present.get(i+j); Metrics.Metric def=Metrics.ALL.get(key);
                LinearLayout tile=column(); tile.setPadding(dp(12),dp(12),dp(12),dp(12)); tile.setBackground(shape(CARD,EDGE,17));
                tile.addView(text(def.label(),13,MUTED,false)); gap(tile,4);
                tile.addView(text(Metrics.value(key,day.get(key)),19,def.color(),true));
                tile.setOnClickListener(v->{metric=key;setTab(3);});
                row.addView(tile,params);
            }
            content.addView(row); gap(content,9);
        }
    }
    private boolean has(Metrics.Day day,String... keys) {
        return Arrays.stream(keys).anyMatch(key->day.get(key)!=null);
    }
    private void dial(LinearLayout row,Metrics.Day day,String key,String name) {
        LinearLayout stack=column(); stack.setGravity(Gravity.CENTER);
        stack.addView(new Charts.Dial(this,day.get(key),Metrics.ALL.get(key).color(),name),new LinearLayout.LayoutParams(-1,dp(82)));
        TextView label=text(name,12,MUTED,true); label.setGravity(Gravity.CENTER); stack.addView(label);
        row.addView(stack,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void metricRow(LinearLayout parent,String label,String value,String detail,int accent) {
        LinearLayout row=row(); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text=column(); text.addView(text(label,14,TEXT,true));
        if (detail!=null) text.addView(text(detail,12,MUTED,false));
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1)); row.addView(text(value,14,accent,true));
        parent.addView(row); gap(parent,9);
    }

    private void metricPicker(List<String> items,Runnable after) {
        if (items.isEmpty()) return;
        String[] labels=items.stream().map(k->Metrics.ALL.get(k).label()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Choose a metric").setItems(labels,(d,w)->{metric=items.get(w);after.run();}).show();
    }
    private String sourceLabel() { return source.startsWith(LocalStore.RING+"|")?LocalStore.RING:source; }
    private String ringAddress() { return prefs.getString("address",""); }
    private void sourcePicker() {
        List<String> ids=new ArrayList<>(List.of(DEMO,LocalStore.FILES,LocalStore.HEALTH,LocalStore.CARDIO,LocalStore.MANUAL));
        List<String> labels=new ArrayList<>(ids);
        if (!ringAddress().isEmpty()) { ids.add(RingLink.source(ringAddress())); labels.add(LocalStore.RING); }
        if (source.startsWith(LocalStore.RING+"|") && !ids.contains(source)) {
            ids.add(source); labels.add("Previous Bluetooth ring");
        }
        new AlertDialog.Builder(this).setTitle("Data source").setSingleChoiceItems(labels.toArray(new String[0]),ids.indexOf(source),
            (d,w)->{d.dismiss();selectSource(ids.get(w),true);}).setNegativeButton("Cancel",null).show();
    }

    private void healthPermissions() {
        if (busy || Build.VERSION.SDK_INT<34) return;
        String[] required=HealthBridge.permissions();
        String[] missing=Arrays.stream(required).filter(p->checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED).toArray(String[]::new);
        if (missing.length==0) syncHealth();
        else {
            new AlertDialog.Builder(this).setTitle("Allow Oura data in Health Connect")
                .setMessage("Ring Atlas reads the categories you grant. You can grant some or all. Data is copied into private app storage.")
                .setNegativeButton("Cancel",null).setNeutralButton("Sync granted only",(d,w)->syncHealth())
                .setPositiveButton("Continue",(d,w)->requestPermissions(missing,HEALTH)).show();
        }
    }
    private void syncHealth() {
        if (Build.VERSION.SDK_INT<34) return;
        runWork("Reading Health Connect…",()->new HealthBridge(this).sync(store),count->{
            setNotice(count==0?"No Oura records were found. Check that Oura is writing data to Health Connect.":
                "Health Connect synced "+count+" days of Oura data.");
            selectSource(LocalStore.HEALTH,true);
        });
    }
    private void dataTool(String tool) {
        if (busy || scanning) return;
        startActivityForResult(new Intent(this,DataToolsActivity.class).putExtra("mode",tool)
            .putExtra("source",source).putExtra("day",date.toString()),331);
    }

    private void pickFile() {
        if (busy) return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,PICK);
    }
    private void export(String kind) {
        if (busy) return;
        if (kind.equals("daily") && source.equals(DEMO)) { setNotice("Switch to your data source before exporting measurements."); return; }
        pendingExport=kind; pendingSource=source;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"RingAtlas_"+kind+".csv"),SAVE);
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code==330 && result==RESULT_OK) {reload();return;}
        if (code==331 && result==RESULT_OK) {
            ring=null;ringInfo=null;liveBpm=null;notice="Backup restored.";
            selectSource(prefs.getString("source",DEMO),true);
            return;
        }
        if (code==ENABLE_BT) {
            if (result==RESULT_OK) scan(); else setNotice("Turn Bluetooth on to search for your ring.");
            return;
        }
        if (result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if (code==PICK_KEY) {
            BluetoothDevice device=selectedRing();
            if (device!=null) importKey(device,()->{
                try (InputStream in=getContentResolver().openInputStream(uri)) {
                    if (in==null) throw new IOException("Could not open the selected key file.");
                    return RingProtocol.readKey(in);
                }
            });
        }
        if (code==PICK) runWork("Importing daily records…",()->{
            String filename="Oura export";
            try (Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if (c!=null && c.moveToFirst()) filename=c.getString(0);
            }
            try (InputStream in=getContentResolver().openInputStream(uri)) {
                if (in==null) throw new IOException("The selected file could not be opened.");
                Collection<Metrics.Day> records=OuraImport.read(in,filename==null?"":filename);
                store.saveDays(LocalStore.FILES,records); return records.size();
            }
        },count->{setNotice("Imported "+count+" days.");selectSource(LocalStore.FILES,true);});
        if (code==SAVE) {
            String kind=pendingExport, exportSource=pendingSource;
            if (kind.isEmpty()) return;
            pendingExport=""; pendingSource="";
            Collection<Metrics.Day> readings=kind.equals("daily")
                ? List.copyOf((source.equals(exportSource)?days:store.days(exportSource)).values()) : List.of();
            runWork("Writing your CSV…",()->{
                OutputStream output=getContentResolver().openOutputStream(uri,"wt");
                if (output==null) throw new IOException("Could not open the destination.");
                try (Writer out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8))) {
                    switch(kind) {
                        case "raw" -> store.exportEvents(out);
                        case "journal" -> store.exportNotes(out);
                        case "workouts" -> store.exportWorkouts(out);
                        default -> store.exportDays(out,readings);
                    }
                }
                return "CSV exported.";
            },this::setNotice);
        }
    }

    private boolean canBluetooth() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothAdapter adapter() {
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        return manager==null?null:manager.getAdapter();
    }
    private void startScan() {
        if (busy || scanning) return;
        if (!canBluetooth()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLUETOOTH); return;
        }
        BluetoothAdapter adapter=adapter();
        if (adapter==null) { setNotice("Bluetooth LE is unavailable on this phone."); return; }
        if (!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT); return; }
        scan();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if (code==HEALTH) {
            if (Arrays.stream(grants).anyMatch(g->g==PackageManager.PERMISSION_GRANTED)
                || Arrays.stream(HealthBridge.permissions()).anyMatch(p->checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED)) syncHealth();
            else setNotice("No Health Connect read permissions were granted.");
        }
        if (code==MOTION) startPhoneWorkout();
        if (code==BLUETOOTH) { if (canBluetooth()) startScan(); else setNotice("Nearby devices permission is required for direct sync."); }
    }
    private void scan() {
        if (scanning || !canBluetooth()) return;
        BluetoothAdapter adapter=adapter();
        if (adapter==null) { setNotice("Bluetooth LE is unavailable on this phone."); return; }
        scanner=adapter.getBluetoothLeScanner();
        if (scanner==null) { setNotice("Bluetooth LE scanning is unavailable."); return; }
        discovered.clear();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (safeName(device).toLowerCase(Locale.ROOT).contains("oura") || device.getAddress().equals(ringAddress()))
                discovered.put(device.getAddress(),device);
        }
        scanCallback=new ScanCallback() {
            @Override public void onScanResult(int callbackType,ScanResult result) {
                BluetoothDevice device=result.getDevice();
                ScanRecord record=result.getScanRecord();
                String name=record==null?null:record.getDeviceName();
                boolean service=record!=null && record.getServiceUuids()!=null && record.getServiceUuids().contains(new ParcelUuid(RingLink.SERVICE));
                if (service || (name!=null && name.toLowerCase(Locale.ROOT).contains("oura"))) {
                    runOnUiThread(()->{if (scanning) discovered.put(device.getAddress(),device);});
                }
            }
            @Override public void onScanFailed(int code) { runOnUiThread(()->{stopScan();setNotice("Bluetooth scan failed ("+code+"). Try again.");render();}); }
        };
        scanning=true; setNotice("Searching for nearby Oura rings…"); render();
        try {
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        } catch (RuntimeException error) {
            stopScan(); setNotice("Android could not start a Bluetooth scan."); render(); return;
        }
        finishScan=()->{
            stopScan();
            if (discovered.isEmpty()) {setNotice("No Oura ring found. Wake it on the charger and close the Oura app.");render();return;}
            notice=""; render();
            List<BluetoothDevice> results=new ArrayList<>(discovered.values());
            String[] names=results.stream().map(d->safeName(d)+" · "+d.getAddress()).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("Select your Oura ring").setItems(names,(dialog,which)->{
                ring=results.get(which); ringInfo=null; liveBpm=null;
                prefs.edit().putString("address",ring.getAddress()).apply();
                render(); connectRing();
            }).setNegativeButton("Cancel",null).show();
        };
        handler.postDelayed(finishScan,7500);
    }
    private void stopScan() {
        if (finishScan!=null) handler.removeCallbacks(finishScan);
        if (scanner!=null && scanCallback!=null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanner=null; scanCallback=null; scanning=false;
    }
    private String safeName(BluetoothDevice device) {
        if (!canBluetooth()) return "Oura ring";
        String name=device.getName();
        return name==null || name.trim().isEmpty()?"Oura ring":name;
    }
    private void connectRing() {
        if (busy || scanning) return;
        BluetoothDevice device=selectedRing();
        if (device==null) return;
        runWork("Connecting to your ring…",()->{
            Consumer<String> progress=message->runOnUiThread(()->setNotice(message));
            RingLink.bond(device,progress);
            try (RingLink link=new RingLink(this,device)) {
                return link.connectOrSetUp(store,device.getAddress(),keys,progress);
            }
        },result->ringComplete(device,result));
    }

    private void pairingHelp() {
        if (busy || scanning) return;
        new AlertDialog.Builder(this).setTitle("Using a ring that was set up in Oura")
            .setMessage("A paired ring proves that you already have its key through a Bluetooth challenge. It does not share that key, and Oura’s data export contains measurements. To switch without a key, sync or back up your data in Oura first. A factory reset in Oura erases data still on the ring. After the reset, close Oura before it sets up the ring again, forget the old Bluetooth pairing if it remains, then find the ring in Ring Atlas.")
            .setNegativeButton("Close",null)
            .setNeutralButton("Enter existing key",(d,w)->enterKey())
            .setPositiveButton("Import key file",(d,w)->pickKey()).show();
    }

    private void pickKey() {
        if (busy || selectedRing()==null) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE),PICK_KEY);
    }

    private void enterKey() {
        BluetoothDevice device=selectedRing();
        if (device==null) return;
        EditText field=new EditText(this);
        field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("32 hexadecimal characters");
        LinearLayout form=column(); form.setPadding(dp(22),dp(4),dp(22),0); form.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Existing ring key").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Authenticate and sync",null).show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String entered=field.getText().toString().trim();
            if (!entered.matches("(?i)[a-f0-9]{32}")) {field.setError("Enter exactly 32 hexadecimal characters.");return;}
            field.setText(""); dialog.dismiss(); importKey(device,()->RingProtocol.unhex(entered));
        });
    }

    private void importKey(BluetoothDevice device,Callable<byte[]> read) {
        runWork("Checking the ring key…",()->{
            byte[] key=read.call();
            try {
                Consumer<String> progress=message->runOnUiThread(()->setNotice(message));
                RingLink.bond(device,progress);
                try (RingLink link=new RingLink(this,device)) {
                    return link.sync(store,device.getAddress(),key,progress,()->keys.save(device.getAddress(),key));
                }
            } finally { Arrays.fill(key,(byte)0); }
        },result->ringComplete(device,result));
    }

    private void ringComplete(BluetoothDevice device,RingLink.Result result) {
        ringInfo=result;
        String message=String.format(Locale.getDefault(),"%,d events archived this sync.",result.eventCount());
        if (result.undated()>0) message+=" Some archived measurements lacked a reliable timestamp.";
        if (result.partial()) message+=" Run sync again to continue.";
        setNotice(message); selectSource(RingLink.source(device.getAddress()),true);
    }

    private BluetoothDevice selectedRing() {
        if (!canBluetooth() || !BluetoothAdapter.checkBluetoothAddress(ringAddress())) { startScan(); return null; }
        BluetoothAdapter adapter=adapter();
        if (adapter==null || !adapter.isEnabled()) { startScan(); return null; }
        return ring!=null?ring:adapter.getRemoteDevice(ringAddress());
    }

    private void showRecoveryKey(String address) {
        new AlertDialog.Builder(this).setTitle("Save your ring recovery key?")
            .setMessage("Anyone who has the key and Bluetooth access to your ring may read its history. Keep it in a private password manager.")
            .setNegativeButton("Close",null).setPositiveButton("Show key",(d,w)->runWork("Opening the saved key…",()->keys.read(address),key->{
                if (key==null) {setNotice("There is no saved key for this ring.");return;}
                String hex=RingProtocol.hex(key); Arrays.fill(key,(byte)0);
                TextView field=text(hex,16,TEXT,false); field.setTextIsSelectable(true); field.setPadding(dp(22),dp(10),dp(22),dp(10));
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ring recovery key").setView(field).setPositiveButton("Done",null).create();
                if (dialog.getWindow()!=null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                dialog.show(); setNotice("Recovery key opened.");
            })).show();
    }

    private void liveRing() {
        if (busy || scanning) return;
        BluetoothDevice device=selectedRing();
        if (device==null) return;
        if (!keys.has(device.getAddress())) {setNotice("Authenticate with your ring key before starting live heart rate.");return;}
        liveBpm=null;
        if (liveValueView!=null) liveValueView.setText("Wear the ring and hold still. Connecting…");
        runWork("Wear the ring and hold still. Connecting…",()->{
            byte[] key=keys.read(device.getAddress());
            try (RingLink link=new RingLink(this,device)) {
                return link.live(key,bpm->runOnUiThread(()->{
                    liveBpm=bpm;
                    if (liveValueView!=null) liveValueView.setText("Live heart rate · "+bpm+" bpm");
                    setNotice("Live heart rate · "+bpm+" bpm");
                }));
            } finally { if (key!=null) Arrays.fill(key,(byte)0); }
        },beats->{
            setNotice(beats==0?"No valid beats received. Wear the ring and hold still during the reading.":"Live reading complete.");
            render();
        });
    }

    private void confirmErase() {
        if (busy || scanning || phoneWorkout.running()) return;
        new AlertDialog.Builder(this).setTitle("Clear local data?")
            .setMessage("This deletes all imported readings, Bluetooth history, journal entries and saved ring keys from this phone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->runWork("Clearing local data…",()->{
                store.erase(); keys.forget(); prefs.edit().clear().commit(); return "Local data cleared.";
            },message->{ring=null; ringInfo=null; liveBpm=null; setNotice(message); selectSource(DEMO,true);})).show();
    }

    private <T> void runWork(String start,Callable<T> task,Consumer<T> success) {
        if (busy) return;
        busy=true; setNotice(start);
        worker.execute(()->{
            try {
                T result=task.call();
                runOnUiThread(()->{if (!isDestroyed()) {busy=false;success.accept(result);}});
            } catch (Exception error) {
                runOnUiThread(()->{
                    if (isDestroyed()) return;
                    busy=false;
                    Throwable detail=error instanceof ExecutionException && error.getCause()!=null?error.getCause():error;
                    String msg=detail.getMessage();
                    setNotice(msg==null || msg.isEmpty()?"The operation did not finish. Please retry.":msg);
                    if (detail instanceof RingLink.PairingNeeded && tab==4) pairingHelp();
                });
            }
        });
    }
    private void setNotice(String msg) {
        notice=msg;
        if (noticeView!=null && tab==4 && !isDestroyed()) {
            noticeView.setText(msg); noticeView.setVisibility(msg.isEmpty()?View.GONE:View.VISIBLE);
        }
    }

    private void heading(String eyebrow,String title,String detail) {
        content.addView(kicker(eyebrow,Metrics.GOLD)); gap(content,5);
        content.addView(text(title,32,TEXT,true)); gap(content,4);
        content.addView(text(detail,15,MUTED,false)); gap(content,17);
    }
    private void section(String title,String detail) {
        gap(content,5); content.addView(text(title,19,TEXT,true));
        if (detail!=null) { gap(content,4); content.addView(text(detail,14,MUTED,false)); }
        gap(content,10);
    }
    private LinearLayout card() {
        LinearLayout card=column();
        card.setPadding(dp(15),dp(14),dp(15),dp(14)); card.setBackground(shape(CARD,EDGE,19));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(11);
        content.addView(card,params); return card;
    }
    private void action(LinearLayout parent,String label,boolean strong,Runnable work) {
        TextView button=button(label,strong,work);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(6); params.bottomMargin=dp(3);
        parent.addView(button,params);
    }
    private TextView button(String label,boolean strong,Runnable work) {
        TextView button=text(label,14,strong?BG:TEXT,true);
        button.setGravity(Gravity.CENTER); button.setMinHeight(dp(44)); button.setPadding(dp(10),dp(8),dp(10),dp(8));
        button.setBackground(shape(strong?Metrics.MINT:0xff1c3033,strong?Metrics.MINT:EDGE,12));
        button.setOnClickListener(v->work.run()); button.setFocusable(true); button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(view,node); node.setClassName(Button.class.getName());
            }
        });
        return button;
    }
    private TextView pill(String label,int color) {
        TextView chip=button(label,false,this::sourcePicker); chip.setTextColor(color); chip.setTextSize(11);
        chip.setMinHeight(dp(40)); chip.setPadding(dp(9),dp(5),dp(9),dp(5)); return chip;
    }
    private TextView kicker(String value,int color) {
        TextView t=text(value,11,color,true); t.setLetterSpacing(.12f); return t;
    }
    private TextView text(String value,int size,int color,boolean strong) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("tnum"); t.setLineSpacing(dp(2),1);
        if (strong) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return t;
    }
    private GradientDrawable shape(int fill,int stroke,int radius) {
        GradientDrawable result=new GradientDrawable(); result.setColor(fill); result.setCornerRadius(dp(radius));
        result.setStroke(dp(1),stroke); return result;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density*value); }
    private LinearLayout row() { return new LinearLayout(this); }
    private LinearLayout column() { LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private void gap(LinearLayout parent,int height) { parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }
}
