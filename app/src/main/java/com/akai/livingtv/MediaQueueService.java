package com.akai.livingtv;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;

/** Real publisher audio, foreground MediaSession, one decoder, ordered completion. */
public final class MediaQueueService extends Service {
    private static MediaQueueService active;
    private MediaPlayer player;
    private MediaSession session;
    private MediaQueue queue;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean ducked,prepared;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable prepareTimeout=()->{if(queue.state.equals("preparing")){release();queue.update("error","音源連線逾時，請重試或切到下一集。");updateNotification();}};
    static void command(Context c,String action){if("PAUSE".equals(action)&&active==null)return;Intent i=new Intent(c,MediaQueueService.class).setAction(action);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    static void stop(Context c){c.stopService(new Intent(c,MediaQueueService.class));}
    static void stopPlayback(Context c){MediaQueue.get(c).update("stopped","");stop(c);}
    static long[] progress(){MediaQueueService s=active;if(s==null||s.player==null||!s.prepared)return new long[]{0,0};try{return new long[]{s.player.getCurrentPosition(),s.player.getDuration()};}catch(Exception ignored){return new long[]{0,0};}}
    static void seek(long milliseconds){MediaQueueService s=active;if(s==null||s.player==null||!s.prepared)return;try{s.player.seekTo((int)Math.max(0,Math.min(s.player.getDuration(),milliseconds)));s.updateNotification();}catch(Exception ignored){}}
    static void duck(boolean on){MediaQueueService s=active;if(s!=null){s.ducked=on;if(s.player!=null)s.player.setVolume(on?.18f:1,on?.18f:1);}}
    @Override public void onCreate(){super.onCreate();active=this;queue=MediaQueue.get(this);audio=(AudioManager)getSystemService(AUDIO_SERVICE);NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel("meadow_playback","Meadow 播放清單",NotificationManager.IMPORTANCE_LOW));session=new MediaSession(this,"Meadow Queue");session.setCallback(new MediaSession.Callback(){public void onPlay(){resume();}public void onPause(){pause();}public void onSkipToNext(){next();}public void onStop(){stopPlayback(MediaQueueService.this);}public void onSeekTo(long position){seek(position);}});session.setActive(true);}
    @Override public int onStartCommand(Intent i,int flags,int id){startForeground(51,notification());String action=i==null?"PAUSE":i.getAction();if("NEXT".equals(action))next();else if("PAUSE".equals(action))pause();else if("TOGGLE".equals(action)){if(player!=null&&prepared&&player.isPlaying())pause();else resume();}else play();return START_NOT_STICKY;}
    private void play(){release();MediaItem item=queue.current();if(item==null){queue.update("stopped","");stopSelf();return;}
        if(!item.provider.equals("podcast")){queue.update("external","請在影音 App 確認播放；完成後手動切下一項。");Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","queue_video").putExtra("itemId",item.id);startActivity(open);updateNotification();return;}
        if(!PodcastRepository.safe(item.audio,false)){queue.update("error","這一集沒有可用的公開 HTTPS 音源。");updateNotification();return;}
        queue.update("preparing","");updateNotification();
        try{AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(value->{if(value==AudioManager.AUDIOFOCUS_LOSS||value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)pause();else if(value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)duck(true);else if(value==AudioManager.AUDIOFOCUS_GAIN)duck(false);}).build();if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){queue.update("error","目前無法取得音訊播放焦點。");updateNotification();return;}
            MediaPlayer next=new MediaPlayer();player=next;next.setAudioAttributes(attrs);next.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);next.setOnPreparedListener(p->{if(player!=p)return;prepared=true;p.setVolume(ducked?.18f:1,ducked?.18f:1);timer.removeCallbacks(prepareTimeout);p.start();PodcastRepository.get(this).recordSuccessfulPlayback(item);queue.update("playing","");session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,item.title).putString(MediaMetadata.METADATA_KEY_ARTIST,item.show).build());updateNotification();android.util.Log.i("MeadowQueue","Publisher audio prepared and started");});
            next.setOnCompletionListener(p->{if(player==p){android.util.Log.i("MeadowQueue","Episode completed; advancing ordered queue");next();}});next.setOnErrorListener((p,what,extra)->{if(player==p){queue.update("error","這一集暫時無法播放，請重試或切到下一集。");release();updateNotification();}return true;});next.setDataSource(item.audio);next.prepareAsync();timer.postDelayed(prepareTimeout,15000);
        }catch(Exception e){release();queue.update("error","公開音源暫時無法開啟。");updateNotification();}
    }
    private void resume(){if(player!=null&&prepared&&queue.state.equals("paused")){try{player.start();queue.update("playing","");updateNotification();}catch(Exception e){play();}}else play();}
    private void pause(){if(player!=null&&prepared){try{player.pause();queue.update("paused","");}catch(Exception ignored){}}updateNotification();}
    private void next(){int next=queue.index+1;if(queue.select(next))play();else {release();queue.update("finished","");stopSelf();}}
    private void release(){timer.removeCallbacks(prepareTimeout);MediaPlayer old=player;player=null;prepared=false;if(old!=null){old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}}
    private PendingIntent action(String name,int id){return PendingIntent.getService(this,id,new Intent(this,MediaQueueService.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){MediaItem item=queue==null?null:queue.current();PendingIntent open=PendingIntent.getActivity(this,40,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","player"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"meadow_playback").setSmallIcon(R.drawable.ic_audio).setContentTitle(item==null?"Meadow 播放清單":item.title).setContentText(queue==null?"準備中":queue.state.equals("playing")?"正在播放 · "+item.show:queue.state.equals("external")?"請在影音 App 確認播放":queue.state.equals("paused")?"已暫停":"準備播放").setContentIntent(open).setOngoing(queue!=null&&queue.state.equals("playing")).addAction(new Notification.Action.Builder(null,"播放／暫停",action("TOGGLE",41)).build()).addAction(new Notification.Action.Builder(null,"下一項",action("NEXT",42)).build()).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1)).build();}
    private void updateNotification(){if(session==null)return;int state=queue.state.equals("playing")?PlaybackState.STATE_PLAYING:queue.state.equals("paused")?PlaybackState.STATE_PAUSED:PlaybackState.STATE_BUFFERING;session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SEEK_TO).setState(state,player==null?0:position(),1).build());if(Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(51,notification());}
    private long position(){if(!prepared)return 0;try{return player.getCurrentPosition();}catch(Exception e){return 0;}}
    @Override public void onDestroy(){release();if(session!=null){session.setActive(false);session.release();session=null;}if(active==this)active=null;if(!queue.state.equals("finished")&&!queue.state.equals("error"))queue.update("stopped","");stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
