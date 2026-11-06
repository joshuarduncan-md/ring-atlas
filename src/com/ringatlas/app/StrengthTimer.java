package com.ringatlas.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

public final class StrengthTimer extends BroadcastReceiver {
    private static final String CHANNEL="strength-rest";
    private static final String LOG_SET="com.ringatlas.app.LOG_STRENGTH_SET";

    static void schedule(Context context,long deadline) {
        AlarmManager alarms=context.getSystemService(AlarmManager.class);
        if (alarms==null) return;
        PendingIntent pending=alarm(context);
        alarms.cancel(pending);
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if (manager!=null && (deadline==0 || deadline>System.currentTimeMillis())) manager.cancel(312);
        if (deadline<=System.currentTimeMillis()) return;
        if (alarms.canScheduleExactAlarms()) {
            try {alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,deadline,pending);return;}
            catch (SecurityException revoked) { }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,deadline,pending);
    }
    private static PendingIntent alarm(Context context) {
        return PendingIntent.getBroadcast(context,311,new Intent(context,StrengthTimer.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onReceive(Context context,Intent intent) {
        if (LOG_SET.equals(intent.getAction())) {log(context,intent);return;}
        if (Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED) return;
        StrengthPlan plan;
        try (LocalStore store=new LocalStore(context)) {
            plan=StrengthPlan.decode(store.strengthState());
            if (plan.active==null || plan.active.restUntil<=0 || plan.active.restUntil>System.currentTimeMillis()
                    || System.currentTimeMillis()-plan.active.restUntil>180000) return;
        } catch (Exception ignored) {return;}
        NotificationManager notifications=context.getSystemService(NotificationManager.class);
        if (notifications==null) return;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Strength rest timer",NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open=PendingIntent.getActivity(context,312,new Intent(context,StrengthActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        StrengthPlan.Target target=plan.nextUnlogged();
        Notification.Builder alert=new Notification.Builder(context,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Rest timer complete")
            .setContentText(target==null?"Open Ring Atlas to review your workout.":target.name()+" · next set "+target.reps()+" at "+target.load()+" "+plan.active.unit)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true);
        if (target!=null) {
            alert.addAction(new Notification.Action.Builder(null,"Log target",action(context,plan,target,true)).build());
            alert.addAction(new Notification.Action.Builder(null,"Log one short",action(context,plan,target,false)).build());
        }
        notifications.notify(312,alert.build());
    }

    private static PendingIntent action(Context context,StrengthPlan plan,StrengthPlan.Target target,boolean complete) {
        Intent intent=new Intent(context,StrengthTimer.class).setAction(LOG_SET)
            .setData(android.net.Uri.parse("ringatlas-strength:"+plan.active.id+"/"+plan.active.restUntil+"/"+complete))
            .putExtra("id",plan.active.id).putExtra("deadline",plan.active.restUntil).putExtra("lift",target.lift())
            .putExtra("set",target.set()).putExtra("success",complete);
        return PendingIntent.getBroadcast(context,complete?313:314,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void log(Context context,Intent intent) {
        try (LocalStore store=new LocalStore(context)) {
            String prior=store.strengthState();
            StrengthPlan plan=StrengthPlan.decode(prior);
            StrengthPlan.Target next=plan.nextUnlogged();
            if (next==null || !plan.active.id.equals(intent.getStringExtra("id")) ||
                    plan.active.restUntil!=intent.getLongExtra("deadline",-1) ||
                    next.lift()!=intent.getIntExtra("lift",-1) || next.set()!=intent.getIntExtra("set",-1)) return;
            boolean done=intent.getBooleanExtra("success",false);
            plan.active.lifts.get(next.lift()).sets.set(next.set(),new StrengthPlan.SetLog(next.reps()-(done?0:1),next.load()));
            int seconds=done?plan.restSuccess:plan.restMissed;
            plan.active.restUntil=seconds==0?0:System.currentTimeMillis()+seconds*1000L;
            store.saveStrength(prior,plan.encode(),null,null);
            NotificationManager notifications=context.getSystemService(NotificationManager.class);
            if (notifications!=null) notifications.cancel(312);
            schedule(context,plan.active.restUntil);
        } catch (Exception ignored) { }
    }
}
