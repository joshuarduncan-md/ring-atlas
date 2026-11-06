package com.ringatlas.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;

abstract class AtlasScreen extends Activity {
    static final int BG=0xff0c1618, CARD=0xff142427, EDGE=0xff2a4043, TEXT=0xffedf7f2, MUTED=0xffadc1c0;
    LinearLayout content;
    ScrollView scroll;

    void page(String eyebrow,String title,String detail) {
        scroll=new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        content=column(); content.setPadding(dp(18),dp(20),dp(18),dp(32));
        scroll.addView(content);
        setContentView(scroll);
        content.addView(label(eyebrow,12,Metrics.MINT,true));
        content.addView(label(title,29,TEXT,true));
        paragraph(content,detail);
        space(content,11);
    }
    LinearLayout column() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);return box;
    }
    LinearLayout card(String title) {
        LinearLayout box=column(); box.setPadding(dp(13),dp(11),dp(13),dp(11));
        box.setBackground(shape(CARD));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(9);
        content.addView(box,p);
        if (!title.isEmpty()) box.addView(label(title,17,TEXT,true));
        return box;
    }
    void paragraph(LinearLayout box,String text) {
        if (text==null || text.isEmpty()) return;
        TextView line=label(text,14,MUTED,false);line.setPadding(0,dp(4),0,dp(4));box.addView(line);
    }
    void action(LinearLayout parent,String title,boolean strong,Runnable run) {
        TextView button=button(title,strong,run);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);
        parent.addView(button,p);
    }
    TextView button(String title,boolean strong,Runnable run) {
        TextView text=label(title,14,strong?BG:TEXT,true);
        text.setBackground(shape(strong?Metrics.MINT:0xff203436));text.setGravity(Gravity.CENTER);
        text.setMinHeight(dp(44));text.setPadding(dp(8),dp(6),dp(8),dp(6));
        text.setFocusable(true);text.setOnClickListener(v->run.run());
        return text;
    }
    TextView label(String title,int size,int color,boolean bold) {
        TextView text=new TextView(this);text.setText(title);text.setTextSize(size);text.setTextColor(color);
        if(bold) text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return text;
    }
    EditText field(LinearLayout box,String caption,String value,int input) {
        box.addView(label(caption,13,MUTED,true));
        EditText field=new EditText(this);field.setText(value);field.setInputType(input);
        if((input&InputType.TYPE_TEXT_FLAG_MULTI_LINE)==0) field.setSingleLine(true);
        box.addView(field);return field;
    }
    void space(LinearLayout box,int height) {box.addView(new android.view.View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    GradientDrawable shape(int color) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(13));d.setStroke(dp(1),EDGE);return d;
    }
    int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    void toast(String message) {Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
