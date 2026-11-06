package com.ringatlas.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DataToolsActivity extends AtlasScreen {
    private static final int SAVE_BACKUP=321, OPEN_BACKUP=322, SAVE_FHIR=323, OPEN_CSV=324, SAVE_COMPARE=325, COMPARE_ACCESS=326;
    private static final String HEART=HealthBridge.PREFIX+"HEART_RATE", HRV=HealthBridge.PREFIX+"HEART_RATE_VARIABILITY";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LocalStore store;
    private SharedPreferences prefs;
    private String mode, source, status="";
    private LocalDate end;
    private int range=7;
    private boolean busy;
    private String metric="hr", left="",right="";
    private WearableCompare.Result comparison;
    private List<WearableCompare.Point> readings=List.of();
    private final Set<String> selected=new LinkedHashSet<>(FhirExport.SUPPORTED);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new LocalStore(this);prefs=getSharedPreferences("atlas",0);
        mode=getIntent().getStringExtra("mode");if (mode==null) mode="backup";
        source=getIntent().getStringExtra("source");if (source==null) source=LocalStore.FILES;
        String day=getIntent().getStringExtra("day");end=day==null?LocalDate.now():LocalDate.parse(day);
        if(state!=null) {
            range=state.getInt("range",7);metric=state.getString("metric","hr");
            end=LocalDate.parse(state.getString("end",end.toString()));
            ArrayList<String> restored=state.getStringArrayList("selected");
            if (restored!=null) {selected.clear();selected.addAll(restored);}
        }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("range",range);state.putString("metric",metric);state.putString("end",end.toString());
        state.putStringArrayList("selected",new ArrayList<>(selected));super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        worker.shutdown();
        if (!busy && store!=null) store.close();
        super.onDestroy();
    }

    private void render() {
        switch (mode) {
            case "compare" -> comparePage();
            case "fhir" -> fhirPage();
            default -> backupPage();
        }
    }

    private void message() {
        if (!status.isEmpty()) {paragraph(content,status);space(content,8);}
    }

    private void backupPage() {
        page("RING ATLAS  /  DATA","Encrypted backup","Save your data and settings to a passphrase-protected file you control.");
        message();
        LinearLayout box=card("Backup and restore");
        paragraph(box,"Includes readings, workouts and lifting plans, journal, raw ring history, and app preferences.");
        paragraph(box,"Ring keys and Android Bluetooth pairing are stored separately. On a new phone, pair or import your ring key as needed.");
        action(box,"Create encrypted backup",true,()->create("application/octet-stream","RingAtlas-backup.ratlas",SAVE_BACKUP));
        action(box,"Restore an encrypted backup",false,()->open(OPEN_BACKUP));
    }

    private void fhirPage() {
        page("RING ATLAS  /  DATA","Export FHIR R4","Choose wearable summaries from one source and save a FHIR collection Bundle as JSON.");
        message();
        LinearLayout box=card("Measurements");
        paragraph(box,"Source · "+sourceLabel()+"\nEnd date · "+end);
        action(box,"Range · last "+range+" days",false,()->chooseRange(new int[]{7,30,90}));
        for (String key : FhirExport.SUPPORTED) {
            CheckBox toggle=new CheckBox(this);toggle.setText(Metrics.ALL.get(key).label());toggle.setChecked(selected.contains(key));box.addView(toggle);
            toggle.setOnCheckedChangeListener((b,checked)->{if(checked) selected.add(key);else selected.remove(key);});
        }
        paragraph(box,"The file includes a generated patient reference and the source of the readings. It contains no name or ring address.");
        action(box,"Save FHIR JSON",true,()->{
            if(selected.isEmpty()) {toast("Select at least one measurement.");return;}
            if(source.equals("Sample data")) {toast("Choose your own data source before exporting.");return;}
            create("application/fhir+json","RingAtlas-FHIR.json",SAVE_FHIR);
        });
    }

    private String sourceLabel() {return source.startsWith(LocalStore.RING+"|")?LocalStore.RING:source;}

    private void comparePage() {
        page("RING ATLAS  /  DATA","Compare wearables","Align recorded heart rate or RMSSD HRV into matching five-minute windows.");
        message();
        LinearLayout box=card("Compare sources");
        action(box,"Metric · "+metricLabel(),false,()->{
            if(busy) return;
            new AlertDialog.Builder(this).setTitle("Measurement").setItems(new String[]{"Heart rate","RMSSD HRV"},(d,i)->{
                metric=i==0?"hr":"hrv";readings=List.of();comparison=null;render();
            }).show();
        });
        action(box,"Range · "+range+" days",false,()->chooseRange(new int[]{1,7,14,30}));
        action(box,"End date · "+end,false,this::pickCompareEnd);
        if(Build.VERSION.SDK_INT>=34) action(box,"Allow Health Connect sources",false,()->{
            if(!busy) {
                String requested=metric.equals("hr")?HEART:HRV;
                if(checkSelfPermission(requested)==PackageManager.PERMISSION_GRANTED) {loadComparison();return;}
                requestPermissions(new String[]{requested},COMPARE_ACCESS);
            }
        });
        action(box,"Import wearable CSV",false,()->open(OPEN_CSV));
        paragraph(box,"CSV columns  timestamp,source,metric,value. Use ISO 8601 with offset, then hr in bpm or hrv in ms.");
        action(box,"Load available sources",true,this::loadComparison);
        if (!readings.isEmpty()) {
            action(box,"Source A · "+(left.isEmpty()?"Choose":left),false,()->chooseSource(true));
            action(box,"Source B · "+(right.isEmpty()?"Choose":right),false,()->chooseSource(false));
        }
        if(comparison!=null) results();
    }

    private void chooseRange(int[] options) {
        if(busy) return;
        String[] labels=Arrays.stream(options).mapToObj(i->i+" days").toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Date range").setItems(labels,(d,i)->{
            range=options[i];readings=List.of();comparison=null;render();
        }).show();
    }

    private void pickCompareEnd() {
        if(busy)return;
        DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{
            end=LocalDate.of(y,m+1,d);readings=List.of();comparison=null;render();
        },end.getYear(),end.getMonthValue()-1,end.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();
    }

    private void loadComparison() {
        if(busy) return;
        final String key=metric;
        ZoneId zone=ZoneId.systemDefault();
        long until=Math.min(Instant.now().getEpochSecond()+1,end.plusDays(1).atStartOfDay(zone).toEpochSecond());
        long from=Math.floorDiv(end.minusDays(range-1L).atStartOfDay(zone).toEpochSecond(),300)*300;
        run("Loading wearable samples…",()->{
            WearableCompare.Series series=new WearableCompare.Series();
            for (String device : store.ringDevices()) for (int epoch : store.eventEpochs(device)) {
                String label="Bluetooth ring · "+device.substring(Math.max(0,device.length()-5));
                RingSummary.wearableSeries(series,label,store.events(device,epoch),from,until);
            }
            String caution="";
            if (Build.VERSION.SDK_INT>=34) {
                try {new HealthCompare(this).load(series,from,until,key);}
                catch (Exception unavailable) {caution=" Health Connect was unavailable for this range.";}
            }
            List<WearableCompare.Point> all=new ArrayList<>(store.wearableSamples(from,until));
            all.addAll(series.points());
            return new Load(all.stream().filter(p->p.metric().equals(key)).collect(java.util.stream.Collectors.toList()),caution);
        },loaded->{
            readings=loaded.points();
            List<String> choices=sources();
            if(!choices.contains(left)) left=choices.isEmpty()?"":choices.get(0);
            if(!choices.contains(right) || right.equals(left))
                right=choices.stream().filter(s->!s.equals(left)).findFirst().orElse("");
            refreshResult();
            status=choices.size()<2?"Found "+choices.size()+" source(s). Add another source with samples for this metric.":choices.size()+" sources available.";
            status+=loaded.caution();render();
        });
    }
    private record Load(List<WearableCompare.Point> points,String caution) {}
    private List<String> sources() {
        return readings.stream().map(WearableCompare.Point::source).distinct().sorted().collect(java.util.stream.Collectors.toList());
    }
    private void chooseSource(boolean first) {
        if(busy) return;
        String[] choices=sources().stream().filter(s->!s.equals(first?right:left)).toArray(String[]::new);
        if(choices.length==0) {toast("Add another source first.");return;}
        new AlertDialog.Builder(this).setTitle(first?"Choose source A":"Choose source B").setItems(choices,(d,i)->{
            if(first) left=choices[i];else right=choices[i];
            refreshResult();render();
        }).show();
    }
    private void refreshResult() {
        comparison=left.isEmpty() || right.isEmpty()?null:WearableCompare.compare(readings,metric,left,right);
    }
    private String metricLabel() {return metric.equals("hr")?"Heart rate":"RMSSD HRV";}
    private String unit() {return metric.equals("hr")?"bpm":"ms";}
    private String summary(Double value) {return value==null?"—":String.format(Locale.getDefault(),"%.1f %s",value,unit());}

    private void results() {
        LinearLayout box=card("Agreement and coverage");
        paragraph(box,"A · "+left+"\nB · "+right);
        paragraph(box,comparison.pairs()+" matched windows · "+comparison.onlyA()+" A only · "+comparison.onlyB()+" B only");
        if(comparison.pairs()>0) paragraph(box,"Mean A−B  "+summary(comparison.bias())+"\nMean absolute difference  "+
            summary(comparison.mae())+"\nRoot mean square difference  "+summary(comparison.rmse()));
        else paragraph(box,"These sources had no overlapping five-minute windows.");
        if(!comparison.windows().isEmpty()) {
            List<WearableCompare.Match> recent=comparison.windows().subList(Math.max(0,comparison.windows().size()-60),comparison.windows().size());
            box.addView(new ComparisonChart(recent),new LinearLayout.LayoutParams(-1,dp(130)));
            paragraph(box,"Recent samples · A green · B blue. Gaps indicate missing or nonadjacent samples.");
        }
        paragraph(box,"A match compares averages within a shared clock window. It does not establish device accuracy.");
        action(box,"Export matched and missing windows",false,()->create("text/csv","RingAtlas-wearable-comparison.csv",SAVE_COMPARE));
    }

    private final class ComparisonChart extends View {
        private final List<WearableCompare.Match> values;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ComparisonChart(List<WearableCompare.Match> values) {
            super(DataToolsActivity.this);this.values=values;setContentDescription("Recent wearable samples for source A and source B");
        }
        @Override protected void onDraw(Canvas canvas) {
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for (var v : values) for (Double n : new Double[]{v.a(),v.b()}) if(n!=null){min=Math.min(min,n);max=Math.max(max,n);}
            if(max<min)return;
            float h=getHeight()-dp(12),w=getWidth()-dp(10),origin=dp(5);
            long first=values.get(0).bucket(), last=values.get(values.size()-1).bucket();
            paint.setStrokeWidth(dp(2));
            for(int k=0;k<2;k++){
                paint.setColor(k==0?Metrics.MINT:Metrics.BLUE);
                float px=0,py=0;long previous=Long.MIN_VALUE;
                for(var v : values){
                    Double n=k==0?v.a():v.b();
                    if(n==null) {previous=Long.MIN_VALUE;continue;}
                    float x=origin+w*(v.bucket()-first)/Math.max(300,last-first);
                    float y=dp(6)+(float)(h*(1-(n-min+1)/(max-min+2)));
                    if(previous!=Long.MIN_VALUE && v.bucket()-previous==300)canvas.drawLine(px,py,x,y,paint);
                    canvas.drawCircle(x,y,dp(2),paint);
                    px=x;py=y;previous=v.bucket();
                }
            }
        }
    }

    private void open(int code) {
        if(busy)return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),code);
    }
    private void create(String mime,String name,int code) {
        if(busy)return;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,name),code);
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(code,permissions,result);
        if(code==COMPARE_ACCESS) loadComparison();
    }

    @Override protected void onActivityResult(int code,int result,Intent intent) {
        super.onActivityResult(code,result,intent);
        if(result!=RESULT_OK || intent==null || intent.getData()==null)return;
        Uri uri=intent.getData();
        switch(code) {
            case SAVE_BACKUP -> password(uri,false);
            case OPEN_BACKUP -> new AlertDialog.Builder(this).setTitle("Replace Ring Atlas data?")
                .setMessage("Restoring replaces this phone’s Ring Atlas readings, journals, and workout history with the backup. The passphrase and file are checked before any changes are made.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->password(uri,true)).show();
            case SAVE_FHIR -> saveFhir(uri);
            case OPEN_CSV -> importCsv(uri);
            case SAVE_COMPARE -> saveComparison(uri);
            default -> { }
        }
    }

    private void password(Uri uri,boolean restore) {
        if(busy) return;
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),0);
        int input=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText password=field(form,"Backup passphrase","",input);
        EditText repeat=restore?null:field(form,"Repeat passphrase","",input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(restore?"Unlock backup":"Set a backup passphrase").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton(restore?"Restore":"Encrypt and save",null).show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(-1).setOnClickListener(v->{
            if(password.length()<12) {password.setError("Enter at least 12 characters.");return;}
            if(repeat!=null && !TextUtils.equals(password.getText(),repeat.getText())) {repeat.setError("Passphrases differ.");return;}
            char[] pass=new char[password.length()];TextUtils.getChars(password.getText(),0,password.length(),pass,0);
            password.setText("");if(repeat!=null) repeat.setText("");dialog.dismiss();
            run(restore?"Verifying and restoring…":"Encrypting backup…",()->{
                try {
                    if(restore) try (InputStream in=input(uri)) {AtlasArchive.restore(store,prefs,in,pass);}
                    else try(OutputStream out=output(uri)) {AtlasArchive.write(store,prefs,out,pass);}
                    return restore?"Backup restored.":"Encrypted backup saved.";
                } finally {Arrays.fill(pass,'\0');}
            },message->{if(restore) {setResult(RESULT_OK);StrengthTimer.schedule(this,0);}status=message;render();});
        });
    }

    private void saveFhir(Uri uri) {
        var keys=Set.copyOf(selected);
        run("Building FHIR Bundle…",()->{
            var days=store.days(source);
            var rows=days.subMap(end.minusDays(range-1L),true,end,true);
            var bundle=FhirExport.bundle(rows.values(),keys,ZoneId.systemDefault(),sourceLabel(),Instant.now());
            try (Writer out=new BufferedWriter(new OutputStreamWriter(output(uri),StandardCharsets.UTF_8))) {
                out.write(bundle.toString(2));out.write('\n');
            }
            return "FHIR R4 Bundle saved.";
        },this::done);
    }

    private void importCsv(Uri uri) {
        run("Importing wearable samples…",()->{
            try(Reader in=new InputStreamReader(input(uri),StandardCharsets.UTF_8)) {
                var imported=WearableCompare.csv(in);
                store.saveWearableSamples(imported);return imported.size();
            }
        },n->{status="Imported "+n+" source windows. Load available sources to compare.";render();});
    }

    private void saveComparison(Uri uri) {
        if(comparison==null) {toast("Load two sources again before exporting.");return;}
        WearableCompare.Result result=comparison;
        String a=left,b=right,key=metric;
        run("Exporting wearable comparison…",()->{
            try (Writer out=new BufferedWriter(new OutputStreamWriter(output(uri),StandardCharsets.UTF_8))) {
                out.write("window_start,metric,source_a,source_b,value_a,value_b,difference_a_minus_b\n");
                for(var m : result.windows()) {
                    String diff=m.a()==null||m.b()==null?"":Double.toString(m.a()-m.b());
                    out.write(Instant.ofEpochSecond(m.bucket())+","+key+","+quote(a)+","+quote(b)+","+
                        (m.a()==null?"":m.a())+","+(m.b()==null?"":m.b())+","+diff+"\n");
                }
            }
            return "Wearable comparison CSV saved.";
        },this::done);
    }

    private static String quote(String text) {return "\""+text.replace("\"","\"\"")+"\"";}
    private InputStream input(Uri uri) throws IOException {
        InputStream in=getContentResolver().openInputStream(uri);
        if(in==null)throw new IOException("The file could not be opened.");
        return in;
    }
    private OutputStream output(Uri uri) throws IOException {
        OutputStream out=getContentResolver().openOutputStream(uri,"wt");
        if(out==null)throw new IOException("The destination could not be opened.");
        return out;
    }
    private void done(String message){status=message;render();}
    private <T> void run(String message,Callable<T> task,Consumer<T> finish) {
        if(busy)return;
        busy=true;status=message;render();
        worker.execute(()->{
            try {
                T result=task.call();
                runOnUiThread(()->{if(!isDestroyed()){busy=false;finish.accept(result);}});
            } catch(Exception error){
                runOnUiThread(()->{if(!isDestroyed()){busy=false;done(error.getMessage()==null?"The operation did not finish.":error.getMessage());}});
            } finally {if(isDestroyed()) store.close();}
        });
    }
}
