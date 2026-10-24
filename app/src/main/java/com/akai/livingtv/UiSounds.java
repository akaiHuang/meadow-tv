package com.akai.livingtv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.*;
import android.view.*;
import java.util.*;
import java.util.function.Supplier;

/** Temporary felt-room set. Predecoded short PCM, bounded overlapping tails, no DSP loop. */
final class UiSounds {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SoundPool pool;
    private int focus,confirm,back;
    private final Set<Integer> ready=new HashSet<>();
    private long lastMove;
    private final Runnable release=this::close;
    UiSounds(Context c){context=c.getApplicationContext();}
    void start(){
        main.removeCallbacks(release);if(pool!=null)return;
        pool=new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->{if(status==0)ready.add(id);});
        focus=pool.load(context,R.raw.ui_focus,1);confirm=pool.load(context,R.raw.ui_confirm,1);back=pool.load(context,R.raw.ui_back,1);
    }
    void key(KeyEvent event,Supplier<View> currentFocus){
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return;int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0)play(confirm);return;}
        if(code==KeyEvent.KEYCODE_BACK){if(event.getRepeatCount()==0)play(back);return;}
        if(code!=KeyEvent.KEYCODE_DPAD_LEFT&&code!=KeyEvent.KEYCODE_DPAD_RIGHT&&code!=KeyEvent.KEYCODE_DPAD_UP&&code!=KeyEvent.KEYCODE_DPAD_DOWN)return;
        View before=currentFocus.get();main.post(()->{long now=SystemClock.uptimeMillis();if(before!=currentFocus.get()&&now-lastMove>=65){lastMove=now;play(focus);}});
    }
    private void play(int id){if(pool!=null&&ready.contains(id))pool.play(id,.60f,.60f,1,0,1);}
    void stop(){main.removeCallbacks(release);main.postDelayed(release,700);}
    void close(){main.removeCallbacks(release);if(pool!=null){pool.release();pool=null;}ready.clear();}
    static void disableDefault(View view){view.setSoundEffectsEnabled(false);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)disableDefault(group.getChildAt(i));}}
}
