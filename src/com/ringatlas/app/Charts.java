package com.ringatlas.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

final class Charts {
    static final int GRID=0xff30464a, DIM=0xffa4b8b9;
    static final class Trend extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Double> values;
        private final Metrics.Metric metric;
        private final float density;
        Trend(Context context,Metrics.Metric metric,List<Double> values) {
            super(context); this.metric=metric; this.values=values;
            density=getResources().getDisplayMetrics().density;
            long count=values.stream().filter(Objects::nonNull).count();
            setContentDescription(metric.label()+" trend with "+count+" observations");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<Double> observed=values.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toList());
            if (observed.isEmpty()) return;
            float l=6*density,r=getWidth()-8*density,t=22*density,b=getHeight()-27*density;
            double low=Collections.min(observed), high=Collections.max(observed);
            double pad=high==low ? Math.max(1,Math.abs(low)*.07) : (high-low)*.09;
            low-=pad; high+=pad;
            paint.setStrokeWidth(density);
            paint.setColor(GRID);
            for (int i=0;i<3;i++) {
                float y=t+(b-t)*i/2; canvas.drawLine(l,y,r,y,paint);
            }
            paint.setColor(DIM); paint.setTextSize(10*density);
            canvas.drawText(metric.value(high-pad),l,13*density,paint);
            canvas.drawText(metric.value(low+pad),l,b+14*density,paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("Most recent day",r,b+14*density,paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(metric.color()); paint.setStrokeWidth(2.2f*density);
            Float lastX=null,lastY=null;
            for (int i=0;i<values.size();i++) {
                Double n=values.get(i);
                if (n==null) { lastX=null; lastY=null; continue; }
                float x=l+(r-l)*i/Math.max(1,values.size()-1), y=(float)(b-(b-t)*(n-low)/(high-low));
                if (lastX!=null) canvas.drawLine(lastX,lastY,x,y,paint);
                canvas.drawCircle(x,y,values.size()>31 ? 1.4f*density : 2.4f*density,paint);
                lastX=x; lastY=y;
            }
        }
    }

    static final class StageBar extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double[] minutes=new double[4];
        private final int[] colors={Metrics.BLUE,Metrics.MINT,Metrics.ROSE,Metrics.GOLD};
        private double total;
        StageBar(Context context,Metrics.Day day) {
            super(context);
            String[] keys={"deep","light","rem","awake"};
            StringJoiner description=new StringJoiner(", ","Sleep stage totals. ",".");
            for (int i=0;i<keys.length;i++) {
                Double value=day.get(keys[i]);
                if (value==null || value<=0) continue;
                minutes[i]=value; total+=value;
                description.add(Metrics.ALL.get(keys[i]).label()+" "+Metrics.value(keys[i],value));
            }
            setContentDescription(description.toString());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        boolean hasData() { return total>0; }
        @Override protected void onDraw(Canvas canvas) {
            if (total<=0) return;
            float density=getResources().getDisplayMetrics().density, h=getHeight(), left=0;
            for (int i=0;i<minutes.length;i++) {
                if (minutes[i]<=0) continue;
                float width=(float)(getWidth()*minutes[i]/total);
                paint.setColor(colors[i]);
                canvas.drawRoundRect(left,0,Math.max(left,left+width-2*density),h,5*density,5*density,paint);
                left+=width;
            }
        }
    }

    static final class SleepTimeline extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final byte[] stages;
        private final int[] colors={GRID,Metrics.BLUE,Metrics.MINT,Metrics.ROSE,Metrics.GOLD};
        SleepTimeline(Context context,byte[] stages) {
            super(context); this.stages=stages;
            setContentDescription("Ring sleep stages from start to end, with "+stages.length+" half-minute observations.");
        }
        @Override protected void onDraw(Canvas canvas) {
            float height=getHeight()/4f, width=getWidth()/(float)stages.length;
            for (int i=0;i<4;i++) {
                paint.setColor(GRID); canvas.drawLine(0,i*height+height/2,getWidth(),i*height+height/2,paint);
            }
            for (int i=0;i<stages.length;i++) {
                int value=stages[i];
                if (value<1 || value>4) continue;
                paint.setColor(colors[value]);
                float top=(4-value)*height+height/4;
                canvas.drawRect(i*width,top,(i+1)*width,top+height/2,paint);
            }
        }
    }

    static final class Dial extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final Double value;
        Dial(Context context,Double value,int color,String label) {
            super(context); this.color=color; this.value=value;
            setContentDescription(label+(value==null?" unavailable":" "+Math.round(value)+" out of 100"));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        @Override protected void onDraw(Canvas canvas) {
            float d=getResources().getDisplayMetrics().density;
            float side=Math.min(getWidth(),getHeight()), x=getWidth()/2f, y=getHeight()/2f, rad=side/2-9*d;
            if (rad<=0) return;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4*d); paint.setStrokeCap(Paint.Cap.ROUND);
            RectF box=new RectF(x-rad,y-rad,x+rad,y+rad);
            paint.setColor(GRID); canvas.drawArc(box,133,274,false,paint);
            if (value!=null) {
                paint.setColor(color); canvas.drawArc(box,133,(float)(274*Math.max(0,Math.min(100,value))/100),false,paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(value==null? DIM : 0xffeff9f5);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(23*d);
            paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            canvas.drawText(value==null?"—":Long.toString(Math.round(value)),x,y+7*d,paint);
        }
    }
}
