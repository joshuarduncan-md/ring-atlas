package com.ringatlas.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.ringatlas.app.StrengthPlan.*;

public final class StrengthActivity extends AtlasScreen {
    private static final int NUMBER=InputType.TYPE_CLASS_NUMBER, DECIMAL=NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a");
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LocalStore store;
    private StrengthPlan plan;
    private String savedState;
    private String healthSessionId;
    private TextView timer;
    private int tab, historyLimit=20;
    private Session reviewing;
    private String graphLift=SQUAT;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new LocalStore(this); setResult(RESULT_OK);
        try {savedState=store.strengthState();plan=StrengthPlan.decode(savedState);}
        catch (Exception error) {toast("Training history could not be opened.");finish();return;}
        if (state!=null) {tab=state.getInt("tab");graphLift=state.getString("lift",SQUAT);healthSessionId=state.getString("healthSession");}
        render(false);
    }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putInt("tab",tab); state.putString("lift",graphLift);state.putString("healthSession",healthSessionId);super.onSaveInstanceState(state);
    }
    @Override protected void onStop() {handler.removeCallbacks(tick);super.onStop();}
    @Override protected void onStart() {
        super.onStart();
        if (plan!=null) {
            refreshTraining();
            if (plan.active!=null) StrengthTimer.schedule(this,plan.active.restUntil);
            handler.post(tick);
        }
    }
    @Override protected void onDestroy() {handler.removeCallbacks(tick);if(store!=null) store.close();super.onDestroy();}
    @Override public void onBackPressed() {
        if (reviewing!=null) {reviewing=null;render(false);}
        else super.onBackPressed();
    }

    private void render(boolean retain) {
        int offset=retain && scroll!=null?scroll.getScrollY():0;
        timer=null;
        page("RING ATLAS  /  STRENGTH","Your lifting log","Plan sessions, log sets, and follow your progression.");
        LinearLayout menu=new LinearLayout(this);
        String[] pages={"Train","History","Progress","Setup"};
        for (int i=0;i<pages.length;i++) {
            int choice=i;
            TextView button=button(pages[i],i==tab,()->{tab=choice;reviewing=null;render(false);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(3);menu.addView(button,p);
        }
        content.addView(menu);space(content,12);
        if (reviewing!=null) session(reviewing,false);
        else switch (tab) {case 1 -> history();case 2 -> progress();case 3 -> setup();default -> train();}
        if (retain) scroll.post(()->scroll.scrollTo(0,offset));
        updateTimer();
    }

    private void train() {
        if (plan.active!=null) {session(plan.active,true);return;}
        String rotation=plan.nextRotation();
        LinearLayout box=card("Next · Workout "+rotation);
        paragraph(box,plan.title+" · "+plan.unit+" · "+plan.history.size()+" completed sessions");
        for (Line line : plan.schedule.getOrDefault(rotation,List.of())) {
            Next next=plan.next(line.name(),Instant.now().getEpochSecond());
            paragraph(box,line.name()+" · "+line.sets()+" × "+line.reps()+" · "+load(next.weight(),plan.unit)+" · "+next.reason());
        }
        action(box,"Start workout "+rotation,true,()->begin(rotation));
        action(box,"Choose another workout",false,()->{
            String[] values=plan.schedule.keySet().stream().filter(k->!plan.schedule.get(k).isEmpty()).toArray(String[]::new);
            if (values.length==0) {toast("Add a workout in Setup first.");return;}
            new AlertDialog.Builder(this).setTitle("Choose a workout").setItems(values,(d,n)->begin(values[n])).show();
        });
    }

    private void begin(String rotation) {
        try {plan.begin(rotation,Instant.now().getEpochSecond());persist(null,null);render(false);}
        catch (IllegalArgumentException invalid) {toast(invalid.getMessage());}
    }

    private void session(Session session,boolean active) {
        LinearLayout head=card((active?"Workout ":"Completed workout ")+session.rotation);
        paragraph(head,format(session.started)+(active?"":" · "+Math.max(1,(session.finished-session.started)/60)+" min"));
        if (active) {
            timer=label("",20,Metrics.MINT,true);head.addView(timer);
            action(head,"Stop rest timer",false,()->{session.restUntil=0;persist(null,null);StrengthTimer.schedule(this,0);updateTimer();});
        } else {
            action(head,"Back to history",false,()->{reviewing=null;render(false);});
            if(Build.VERSION.SDK_INT>=34) action(head,"Save session to Health Connect",false,()->exportHealth(session));
        }
        if (!session.note.isEmpty()) paragraph(head,session.note);
        action(head,"Workout notes",false,()->notes(session));
        for (Lift lift : session.lifts) {
            LinearLayout box=card(lift.line.name()+" · "+lift.line.sets()+" × "+lift.line.reps()+(plan.rule(lift.line.name()).timed()?" sec":""));
            paragraph(box,"Planned load  "+load(lift.plan,session.unit)+" · Tap to log. Hold to edit.");
            if (active) {
                action(box,"Edit planned load",false,()->editLoad(session,lift));
                if (plan.rule(lift.line.name()).barbell()) action(box,"Warm-ups and plates",false,()->showPlates(session,lift));
            }
            for (int i=0;i<lift.sets.size();i+=5) {
                LinearLayout row=new LinearLayout(this);box.addView(row);
                for (int j=i;j<Math.min(i+5,lift.sets.size());j++) {
                    int index=j;SetLog logged=lift.sets.get(j);
                    TextView set=button(logged.reps()==null?"—":logged.reps().toString(),logged.reps()!=null && logged.reps()>=lift.line.reps(),()->{
                        if (active) tapSet(session,lift,index); else editSet(session,lift,index);
                    });
                    set.setContentDescription("Set "+(j+1)+(logged.reps()==null?", pending":", "+logged.reps()+" at "+load(logged.load(),session.unit)));
                    set.setOnLongClickListener(v->{editSet(session,lift,index);return true;});
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(4);row.addView(set,p);
                }
            }
            long logged=lift.sets.stream().filter(set->set.reps()!=null).count();
            paragraph(box,logged+"/"+lift.line.sets()+" sets logged");
        }
        if (active) {
            action(content,"Finish and save workout",true,()->confirmFinish(session));
            action(content,"Discard active workout",false,()->new AlertDialog.Builder(this).setTitle("Discard active workout?")
                .setMessage("Any sets logged in this session will be removed.")
                .setNegativeButton("Keep",null).setPositiveButton("Discard",(d,w)->{
                    plan.active=null;persist(null,null);StrengthTimer.schedule(this,0);render(false);
                }).show());
        } else action(content,"Delete this workout",false,()->new AlertDialog.Builder(this).setTitle("Delete workout?")
            .setMessage("The workout will be removed from your history and progression calculations.")
            .setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->{
                plan.history.remove(session);reviewing=null;persist(null,"strength:"+session.id);render(false);
            }).show());
    }

    private void tapSet(Session session,Lift lift,int index) {
        SetLog old=lift.sets.get(index);
        Integer reps=old.reps()==null?Integer.valueOf(lift.line.reps()):old.reps()>0?Integer.valueOf(old.reps()-1):null;
        set(session,lift,index,reps,old.load());
    }

    private void set(Session session,Lift lift,int index,Integer reps,double weight) {
        lift.sets.set(index,new SetLog(reps,weight));
        if (session==plan.active) {
            int seconds=reps==null?0:reps<lift.line.reps()?plan.restMissed:plan.restSuccess;
            session.restUntil=seconds==0?0:System.currentTimeMillis()+seconds*1000L;
        }
        if (persist(null,null) && session==plan.active) StrengthTimer.schedule(this,session.restUntil);
        render(true);
    }

    private void editSet(Session session,Lift lift,int index) {
        SetLog current=lift.sets.get(index);
        LinearLayout form=form();
        EditText reps=field(form,plan.rule(lift.line.name()).timed()?"Seconds":"Completed reps",
            current.reps()==null?Integer.toString(lift.line.reps()):current.reps().toString(),NUMBER);
        EditText weight=field(form,"Total load ("+session.unit+")",number(current.load()),DECIMAL);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Set "+(index+1)).setView(form).setNegativeButton("Cancel",null)
            .setNeutralButton("Clear set",(d,w)->set(session,lift,index,null,lift.plan)).setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {set(session,lift,index,integer(reps,0,1800),decimal(weight,0,1500));dialog.dismiss();}
            catch (IllegalArgumentException error) {reps.setError(error.getMessage());}
        });
    }

    private void editLoad(Session session,Lift lift) {
        LinearLayout form=form();
        EditText weight=field(form,"Total working load ("+session.unit+")",number(lift.plan),DECIMAL);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(lift.line.name()).setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {
                lift.plan=decimal(weight,plan.rule(lift.line.name()).barbell()?convert(plan.bar,plan.unit,session.unit):0,1500);
                for (int i=0;i<lift.sets.size();i++) if (lift.sets.get(i).reps()==null) lift.sets.set(i,new SetLog(null,lift.plan));
                persist(null,null);dialog.dismiss();render(true);
            } catch (IllegalArgumentException error) {weight.setError(error.getMessage());}
        });
    }

    private void showPlates(Session session,Lift lift) {
        double target=convert(lift.plan,session.unit,plan.unit);
        Loaded loaded=plan.loaded(target);
        StringJoiner message=new StringJoiner("\n");
        message.add("Bar  "+load(plan.bar,plan.unit)).add("Plates per side");
        if (loaded.perSide().isEmpty()) message.add("Empty bar");
        loaded.perSide().forEach((size,n)->message.add(n+" × "+load(size,plan.unit)));
        message.add("Loadable total  "+load(loaded.total(),plan.unit));
        if (Math.abs(loaded.total()-target)>.01) message.add("Requested load unavailable with this inventory.");
        message.add("").add("Suggested warm-up");
        List<Warmup> warmups=plan.warmups(target);
        if (warmups.isEmpty()) message.add("Working load is at the configured bar weight.");
        for (Warmup warmup : warmups) message.add(warmup.reps()+" reps at "+load(warmup.load(),plan.unit));
        new AlertDialog.Builder(this).setTitle(lift.line.name()).setMessage(message.toString()).setPositiveButton("Done",null).show();
    }

    private void notes(Session session) {
        LinearLayout form=form();
        EditText text=field(form,"Notes",session.note,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        new AlertDialog.Builder(this).setTitle("Workout notes").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->{session.note=text.getText().toString().trim();persist(null,null);render(true);}).show();
    }

    private void confirmFinish(Session session) {
        if (!session.logged()) {toast("Log at least one set before finishing.");return;}
        boolean pending=session.lifts.stream().flatMap(l->l.sets.stream()).anyMatch(set->set.reps()==null);
        if (pending) new AlertDialog.Builder(this).setTitle("Finish this partial workout?")
            .setMessage("Pending sets stay blank. Partially logged lifts repeat the planned load next time.")
            .setNegativeButton("Continue lifting",null).setPositiveButton("Save workout",(d,w)->finishSession(session)).show();
        else finishSession(session);
    }

    private void finishSession(Session session) {
        session.finished=Math.max(session.started+1,Instant.now().getEpochSecond());session.restUntil=0;
        plan.history.add(session);plan.active=null;
        LocalStore.Workout workout=new LocalStore.Workout("strength:"+session.id,"Strength training",session.started,session.finished,
            "Strength training",0,LocalStore.CONFIRMED);
        if (!persist(workout,null)) {plan.history.remove(session);plan.active=session;session.finished=0;return;}
        StrengthTimer.schedule(this,0);
        tab=1;reviewing=session;render(false);
    }

    private void history() {
        LinearLayout head=card("Training calendar");
        LocalDate today=LocalDate.now();
        ZoneId zone=ZoneId.systemDefault();
        Set<LocalDate> trained=new HashSet<>();
        for (Session session : plan.history) trained.add(Instant.ofEpochSecond(session.started).atZone(zone).toLocalDate());
        StringBuilder days=new StringBuilder();
        for (int i=27;i>=0;i--) {
            LocalDate date=today.minusDays(i);
            days.append(trained.contains(date)?"● ":"○ ");
            if (i%7==0 && i>0) days.append('\n');
        }
        paragraph(head,days.toString());
        paragraph(head,trained.stream().filter(day->!day.isBefore(today.minusDays(6))).count()+" days trained in the last 7 days · "+plan.history.size()+" sessions total");
        action(head,"Export all sets as CSV",false,this::pickCsv);
        for (int i=plan.history.size()-1;i>=Math.max(0,plan.history.size()-historyLimit);i--) {
            Session session=plan.history.get(i);
            LinearLayout box=card("Workout "+session.rotation+" · "+format(session.started));
            paragraph(box,session.lifts.size()+" lifts · "+session.lifts.stream().flatMap(l->l.sets.stream()).filter(set->set.reps()!=null).count()+" logged sets");
            action(box,"View or edit workout",false,()->{reviewing=session;render(false);});
        }
        if (plan.history.size()>historyLimit) action(content,"Show older sessions",false,()->{historyLimit+=20;render(true);});
    }

    private void progress() {
        LinearLayout box=card(graphLift);
        action(box,"Choose lift",false,()->{
            var names=new LinkedHashSet<>(plan.rules.keySet());
            for (Session session : plan.history) for (Lift lift : session.lifts) names.add(lift.line.name());
            String[] list=names.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Choose exercise").setItems(list,(d,w)->{graphLift=list[w];render(false);}).show();
        });
        List<StrengthPlan.Record> records=plan.records(graphLift);
        if (records.isEmpty()) {paragraph(box,"Log sets to see your records and progress.");return;}
        StrengthPlan.Record recent=records.get(records.size()-1);
        double best=records.stream().mapToDouble(StrengthPlan.Record::load).max().orElse(0);
        double max=records.stream().mapToDouble(StrengthPlan.Record::max).max().orElse(0);
        paragraph(box,"Personal best  "+load(best,plan.unit)+" · latest "+load(recent.load(),plan.unit)+" × "+recent.reps());
        if (!plan.rule(graphLift).timed() && max>0) paragraph(box,"Best estimated 1RM  "+load(max,plan.unit)+" · Epley estimate");
        List<StrengthPlan.Record> recentRecords=records.subList(Math.max(0,records.size()-30),records.size());
        chart("Heaviest set",recentRecords.stream().map(StrengthPlan.Record::load).collect(java.util.stream.Collectors.toList()),plan.unit);
        if (!plan.rule(graphLift).timed()) {
            chart("Estimated 1RM",recentRecords.stream().map(r->r.max()==0?null:r.max()).collect(java.util.stream.Collectors.toList()),plan.unit);
            chart("Training volume",recentRecords.stream().map(StrengthPlan.Record::volume).collect(java.util.stream.Collectors.toList()),plan.unit);
        }
        chart(plan.rule(graphLift).timed()?"Seconds logged":"Total reps",recentRecords.stream().map(r->(double)r.totalReps()).collect(java.util.stream.Collectors.toList()),"");
    }

    private void chart(String title,List<Double> values,String unit) {
        if (values.stream().noneMatch(Objects::nonNull)) return;
        LinearLayout box=card(title);
        var metric=new Metrics.Metric("training",title,unit,Metrics.MINT);
        box.addView(new Charts.Trend(this,metric,values),new LinearLayout.LayoutParams(-1,dp(126)));
        paragraph(box,values.size()+" most recent sessions with this exercise");
    }

    private void setup() {
        LinearLayout program=card(plan.title);
        action(program,"Choose a program template",false,()->{
            String[] presets={"Classic 5×5","5×5 with assistance","Low-volume A/B","Intermediate · pause lifts"};
            new AlertDialog.Builder(this).setTitle("Replace the planned rotation?").setItems(presets,(d,i)->{
                plan.preset(presets[i]);persist(null,null);render(false);
            }).setNegativeButton("Cancel",null).show();
        });
        for (String key : List.of("A","B","C")) action(program,"Edit workout "+key,false,()->template(key));
        paragraph(program,"Workout C is optional. A custom schedule can use two or three days in rotation.");
        LinearLayout lifts=card("Exercise rules");
        for (String name : plan.rules.keySet()) {
            Rule rule=plan.rule(name);
            action(lifts,name+" · +"+load(rule.increment(),plan.unit),false,()->ruleDialog(name));
        }
        action(lifts,"Create custom exercise",true,this::newExercise);
        LinearLayout equipment=card("Equipment and timer");
        action(equipment,"Bar and plate inventory",false,this::equipment);
        action(equipment,"Rest and return from break",false,this::rest);
        action(equipment,"Switch units ("+plan.unit+")",false,()->new AlertDialog.Builder(this).setTitle("Switch weight units?")
            .setMessage("Loads are converted. Plate inventory switches to defaults for the new units.")
            .setNegativeButton("Cancel",null).setPositiveButton("Switch",(d,w)->{
                plan.setUnits(plan.unit.equals("lb")?"kg":"lb");persist(null,null);render(true);
            }).show());
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            action(equipment,"Allow rest timer notifications",false,()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},83));
        AlarmManager alarms=getSystemService(AlarmManager.class);
        if (alarms!=null && !alarms.canScheduleExactAlarms())
            action(equipment,"Allow precise timer alarms",false,()->{
                try {startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                catch (ActivityNotFoundException error) {toast("Your device manages timer alarm timing.");}
            });
    }

    private void template(String key) {
        List<Line> lines=plan.schedule.computeIfAbsent(key,k->new ArrayList<>());
        String[] labels=new String[lines.size()+1];
        for (int i=0;i<lines.size();i++) {
            Line l=lines.get(i);labels[i]=l.name()+" · "+l.sets()+" × "+l.reps();
        }
        labels[lines.size()]="Add exercise";
        new AlertDialog.Builder(this).setTitle("Workout "+key).setItems(labels,(d,i)->lineDialog(key,i)).setNegativeButton("Done",null).show();
    }

    private void lineDialog(String key,int index) {
        List<Line> lines=plan.schedule.get(key);
        if (index>=20) {toast("A workout can include up to 20 exercises.");return;}
        Line prior=index<lines.size()?lines.get(index):null;
        LinearLayout form=form();
        String[] names=plan.rules.keySet().toArray(new String[0]);
        Spinner exercise=new Spinner(this);
        exercise.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));form.addView(exercise);
        if (prior!=null) exercise.setSelection(Math.max(0,Arrays.asList(names).indexOf(prior.name())));
        EditText sets=field(form,"Sets",prior==null?"3":Integer.toString(prior.sets()),NUMBER);
        EditText reps=field(form,"Reps, or seconds for timed exercises",prior==null?"5":Integer.toString(prior.reps()),NUMBER);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("Configure exercise").setView(form).setNegativeButton("Cancel",null);
        if (prior!=null) builder.setNeutralButton("Remove",(d,w)->{lines.remove(index);persist(null,null);template(key);});
        AlertDialog dialog=builder.setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {
                String name=names[exercise.getSelectedItemPosition()];
                for (int i=0;i<lines.size();i++) if (i!=index && lines.get(i).name().equals(name))
                    throw new IllegalArgumentException("This exercise is already in the workout.");
                Line item=new Line(name,integer(sets,1,20),integer(reps,1,plan.rule(name).timed()?1800:100));
                if (prior==null) lines.add(item);else lines.set(index,item);
                plan.title="Custom rotation";persist(null,null);dialog.dismiss();render(true);template(key);
            } catch (IllegalArgumentException error) {sets.setError(error.getMessage());}
        });
    }

    private void newExercise() {
        LinearLayout form=form();
        EditText name=field(form,"Exercise name","",InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create custom exercise").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Continue",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            String entered=name.getText().toString().trim();
            if (entered.isEmpty() || entered.length()>60 || plan.rules.containsKey(entered)) {name.setError("Choose a unique name with 1–60 characters.");return;}
            dialog.dismiss();ruleDialog(entered);
        });
    }

    private void ruleDialog(String name) {
        Rule current=plan.rule(name);
        LinearLayout form=form();
        EditText start=field(form,"Starting load ("+plan.unit+")",number(current.start()),DECIMAL);
        EditText increment=field(form,"Load increase ("+plan.unit+")",number(current.increment()),DECIMAL);
        EditText every=field(form,"Successful sessions per increase",Integer.toString(current.every()),NUMBER);
        EditText misses=field(form,"Missed sessions per deload",Integer.toString(current.misses()),NUMBER);
        EditText deload=field(form,"Deload percent",Integer.toString(current.deload()),NUMBER);
        CheckBox barbell=new CheckBox(this), timed=new CheckBox(this);
        barbell.setText("Barbell with plates");barbell.setChecked(current.barbell());form.addView(barbell);
        timed.setText("Timed exercise");timed.setChecked(current.timed());form.addView(timed);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name).setView(wrap(form))
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {
                Rule next=new Rule(decimal(start,0,1500),decimal(increment,0,200),integer(every,1,100),
                    integer(misses,1,100),integer(deload,0,90),barbell.isChecked(),timed.isChecked());
                plan.rules.put(name,next);persist(null,null);dialog.dismiss();render(true);
            } catch (IllegalArgumentException error) {start.setError(error.getMessage());}
        });
    }

    private void equipment() {
        LinearLayout form=form();
        EditText bar=field(form,"Bar weight ("+plan.unit+")",number(plan.bar),DECIMAL);
        StringJoiner listed=new StringJoiner(", ");
        for (Plate plate : plan.plates) listed.add(number(plate.size())+"x"+plate.perSide());
        EditText inventory=field(form,"Plate size × quantity per side",listed.toString(),InputType.TYPE_CLASS_TEXT);
        paragraph(form,"Example  45x4, 25x2, 10x2. Leave blank for an unloaded bar.");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Equipment").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {
                List<Plate> list=new ArrayList<>();Set<Double> sizes=new HashSet<>();
                String raw=inventory.getText().toString().trim();
                if (!raw.isEmpty()) for (String item : raw.split(",")) {
                    String[] pair=item.trim().split("[xX×]");
                    if (pair.length!=2 || list.size()>=15) throw new IllegalArgumentException();
                    double size=round(Double.parseDouble(pair[0].trim())); int qty=Integer.parseInt(pair[1].trim());
                    if (!(size>=.01 && size<=150) || qty<1 || qty>20 || !sizes.add(size)) throw new IllegalArgumentException();
                    list.add(new Plate(size,qty));
                }
                plan.bar=decimal(bar,.01,150);plan.plates.clear();plan.plates.addAll(list);
                persist(null,null);dialog.dismiss();render(true);
            } catch (IllegalArgumentException error) {inventory.setError("Use unique plate weights and 1–20 plates per side.");}
        });
    }

    private void rest() {
        LinearLayout form=form();
        EditText success=field(form,"Rest after target (seconds)",Integer.toString(plan.restSuccess),NUMBER);
        EditText missed=field(form,"Rest after missed reps (seconds)",Integer.toString(plan.restMissed),NUMBER);
        EditText days=field(form,"Days before return deload (0 disables)",Integer.toString(plan.breakDays),NUMBER);
        EditText percent=field(form,"Return deload percent",Integer.toString(plan.breakPercent),NUMBER);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rest and deloads").setView(wrap(form))
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            try {
                int completed=integer(success,0,1800), failed=integer(missed,0,1800);
                int after=integer(days,0,365), reduction=integer(percent,0,90);
                plan.restSuccess=completed;plan.restMissed=failed;plan.breakDays=after;plan.breakPercent=reduction;
                persist(null,null);dialog.dismiss();render(true);
            } catch (IllegalArgumentException error) {success.setError(error.getMessage());}
        });
    }

    private void pickCsv() {
        if (plan.history.isEmpty()) {toast("Finish a workout before exporting.");return;}
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"RingAtlas_Strength.csv"),311);
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request,result,intent);
        if (request!=311 || result!=RESULT_OK || intent==null || intent.getData()==null) return;
        try (OutputStream output=getContentResolver().openOutputStream(intent.getData(),"wt")) {
            if (output==null) throw new IOException();
            try (Writer writer=new OutputStreamWriter(output,StandardCharsets.UTF_8)) {
                writer.write("start,end,workout,exercise,set,reps_or_seconds,total_load,unit,notes\n");
                for (Session session : plan.history) for (Lift lift : session.lifts) for (int i=0;i<lift.sets.size();i++) {
                    SetLog log=lift.sets.get(i);
                    if (log.reps()==null) continue;
                    writer.write(Instant.ofEpochSecond(session.started)+","+Instant.ofEpochSecond(session.finished)+","+
                        quote(session.rotation)+","+quote(lift.line.name())+","+(i+1)+","+log.reps()+","+log.load()+","+session.unit+","+quote(session.note)+"\n");
                }
            }
            toast("Strength CSV exported.");
        } catch (IOException error) {toast("Could not export the strength CSV.");}
    }

    private static String quote(String text) {
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0))>=0) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    private boolean persist(LocalStore.Workout workout,String removeId) {
        try {
            String next=plan.encode();store.saveStrength(savedState,next,workout,removeId);savedState=next;return true;
        } catch (ConcurrentModificationException error) {
            toast("The lifting log changed. Your latest saved workout will be reloaded.");
            handler.post(this::refreshTraining);return false;
        } catch (Exception error) {toast("Could not save training data.");return false;}
    }
    private void refreshTraining() {
        try {
            String latest=store.strengthState();
            if (Objects.equals(latest,savedState)) return;
            plan=StrengthPlan.decode(latest);savedState=latest;reviewing=null;render(true);
        } catch (Exception ignored) {toast("Could not reload the lifting log.");}
    }

    private void exportHealth(Session session) {
        healthSessionId=session.id;
        if(checkSelfPermission("android.permission.health.WRITE_EXERCISE")==PackageManager.PERMISSION_GRANTED) sendHealth();
        else requestPermissions(new String[]{"android.permission.health.WRITE_EXERCISE"},84);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==84 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) sendHealth();
    }
    private void sendHealth() {
        if(Build.VERSION.SDK_INT<34) return;
        Session session=plan.history.stream().filter(s->s.id.equals(healthSessionId)).findFirst().orElse(null);
        healthSessionId=null;
        if(session!=null) {
            try {StrengthHealth.write(this,session,this::toast);}
            catch (RuntimeException unavailable) {toast("Health Connect is unavailable.");}
        }
    }

    private void updateTimer() {
        if (timer==null || plan.active==null) return;
        long time=Math.max(0,(plan.active.restUntil-System.currentTimeMillis()+999)/1000);
        timer.setText(time>0?String.format(Locale.ROOT,"Rest · %d:%02d",time/60,time%60):plan.active.restUntil>0?"Rest complete":"Rest timer ready");
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {updateTimer();handler.postDelayed(this,500);}
    };
    private LinearLayout form() {LinearLayout form=column();form.setPadding(dp(19),dp(8),dp(19),0);return form;}
    private ScrollView wrap(LinearLayout form) {ScrollView wrap=new ScrollView(this);wrap.addView(form);return wrap;}
    private static int integer(EditText field,int min,int max) {
        try {int n=Integer.parseInt(field.getText().toString().trim());if(n>=min && n<=max)return n;}
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Enter a whole number from "+min+" to "+max+".");
    }
    private static double decimal(EditText field,double min,double max) {
        try {double n=Double.parseDouble(field.getText().toString().trim());if(n>=min && n<=max)return round(n);}
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Enter a value from "+number(min)+" to "+number(max)+".");
    }
    private static String format(long seconds) {return Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(DATE);}
    private static String number(double weight) {return java.math.BigDecimal.valueOf(round(weight)).stripTrailingZeros().toPlainString();}
    private static String load(double weight,String unit) {return number(weight)+" "+unit;}
}
