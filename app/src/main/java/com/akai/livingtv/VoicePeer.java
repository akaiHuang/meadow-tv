package com.akai.livingtv;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.util.Base64;
import android.util.Log;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Codex subscription transcription and cove playback. Microphone PCM stays in memory. */
final class VoicePeer {
    interface Listener {
        void state(String value);void level(float value);void connected();void ended();void failed(String message);
        default void transcript(String text){}default void inputTranscript(String text,boolean complete){}
        default void inputLevel(float level){}default void inputStopped(){}default void inputFailed(String message){}
    }
    private final Context context;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("MeadowVoice");private final Handler work;
    private final ExecutorService network=Executors.newSingleThreadExecutor(),microphoneWorker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService polling=Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor uploads=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(3));
    private final AssistantClient client;private PeerConnectionFactory factory;private PeerConnection peer;
    private JavaAudioDeviceModule audio;private DataChannel channel;private AudioSource silentSource;private org.webrtc.AudioTrack silentTrack;
    private long clockTick;private volatile boolean closed,connected,capturing,outputAllowed;
    private final AtomicLong outputEpoch=new AtomicLong();
    private boolean offerSent,utteranceDone;private volatile AudioRecord microphone;
    private volatile long firstSound,lastSound,lastLevelAt,cursor,captureGeneration;private String session="";
    private long captureEndedAt;private volatile long lastTranscriptAt;private boolean captureEndAccepted;private final TranscriptTurn turn=new TranscriptTurn();
    private static boolean initialized;
    private final Runnable finish=()->fail("連線逾時，請再按一次啟動。");
    private final Runnable awaitTranscript=this::finishTranscriptIfReady;
    VoicePeer(Context c,Listener l,boolean capture){context=c.getApplicationContext();listener=l;client=new AssistantClient(c);thread.start();work=new Handler(thread.getLooper());uploads.allowCoreThreadTimeOut(true);}
    void connect(){listener.state("正在接通 Codex 聲音…");work.post(this::create);main.postDelayed(finish,45000);}
    void muteOutput(){invalidateOutput();}
    private long invalidateOutput(){long epoch=outputEpoch.incrementAndGet();outputAllowed=false;work.post(()->{if(audio!=null)audio.setSpeakerMute(true);});return epoch;}
    private void allowOutput(long epoch){if(!closed&&epoch==outputEpoch.get()&&!capturing&&!captureEndAccepted){outputAllowed=true;if(audio!=null)audio.setSpeakerMute(false);}}
    private void finishOutput(long epoch,String message){main.post(()->{if(closed||epoch!=outputEpoch.get()||utteranceDone)return;utteranceDone=true;muteOutput();listener.ended();if(message!=null)listener.state(message);});}
    void speak(String message){
        if(closed||!connected||message==null||message.trim().isEmpty())return;
        long epoch=invalidateOutput();firstSound=0;lastSound=0;utteranceDone=false;String text=message.length()>4000?message.substring(0,4000):message;
        main.postDelayed(()->{if(epoch==outputEpoch.get()&&firstSound==0)finishOutput(epoch,"尚未收到聲音，文字回覆已保留。");},20000);
        network.execute(()->{if(closed||epoch!=outputEpoch.get())return;try{client.request("/voice/speak",new JSONObject().put("id",session).put("text",text));
            work.post(()->allowOutput(epoch));
        }catch(Exception e){finishOutput(epoch,"Codex 聲音暫時未接通，文字回覆已保留。");}});
    }
    private void create(){
        if(closed)return;try{
            synchronized(VoicePeer.class){if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(false).createInitializationOptions());initialized=true;}}
            audio=JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).setEnableVolumeLogger(false)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioBufferCallback((buffer,format,count,rate,bytes,time)->{buffer.clear();while(buffer.hasRemaining())buffer.put((byte)0);buffer.rewind();long now=System.nanoTime();if(clockTick==0||now-clockTick>50000000L)clockTick=now;clockTick+=10000000L;long wait=clockTick-now;if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(wait);return System.nanoTime();})
                .setPlaybackSamplesReadyCallback(this::samples).createAudioDeviceModule();
            audio.setAudioRecordEnabled(false);audio.setMicrophoneMute(true);audio.setSpeakerMute(true);
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory();
            List<PeerConnection.IceServer> servers=Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(servers);config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer=factory.createPeerConnection(config,new PeerConnection.Observer(){
                public void onSignalingChange(PeerConnection.SignalingState s){}public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED)work.post(VoicePeer.this::ready);if(s==PeerConnection.IceConnectionState.FAILED)fail("聲音連線未接通，請再試一次。");}
                public void onConnectionChange(PeerConnection.PeerConnectionState s){if(s==PeerConnection.PeerConnectionState.CONNECTED)work.post(VoicePeer.this::ready);}public void onIceConnectionReceivingChange(boolean r){}public void onIceGatheringChange(PeerConnection.IceGatheringState s){if(s==PeerConnection.IceGatheringState.COMPLETE)work.post(VoicePeer.this::offer);}public void onIceCandidate(IceCandidate c){}public void onIceCandidatesRemoved(IceCandidate[] c){}public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel c){}public void onRenegotiationNeeded(){}
            });if(peer==null)throw new IllegalStateException();
            silentSource=factory.createAudioSource(new MediaConstraints());silentTrack=factory.createAudioTrack("meadow-playback-clock",silentSource);peer.addTrack(silentTrack,Collections.singletonList("meadow-clock"));
            channel=peer.createDataChannel("oai-events",new DataChannel.Init());channel.registerObserver(new DataChannel.Observer(){public void onBufferedAmountChange(long amount){}public void onStateChange(){work.post(VoicePeer.this::ready);}public void onMessage(DataChannel.Buffer buffer){/* App-server sideband is the single transcript authority. */}});
            peer.createOffer(new Sdp(){public void onCreateSuccess(SessionDescription s){peer.setLocalDescription(new Sdp(){public void onSetSuccess(){work.postDelayed(VoicePeer.this::offer,3500);}},s);}},new MediaConstraints());
        }catch(Exception|LinkageError e){Log.w("MeadowVoice","Initialization failed: "+e.getClass().getSimpleName());fail("這次聲音無法開啟，請再試一次。");}
    }
    private void offer(){
        if(closed||offerSent||peer==null||peer.getLocalDescription()==null)return;offerSent=true;String sdp=peer.getLocalDescription().description;
        network.execute(()->{try{JSONObject result=client.request("/voice/start",new JSONObject().put("sdp",sdp));String id=result.getString("id");if(closed){client.request("/voice/stop",new JSONObject().put("id",id));return;}session=id;cursor=result.optLong("cursor");String answer=result.getString("sdp");work.post(()->{if(!closed)peer.setRemoteDescription(new Sdp(),new SessionDescription(SessionDescription.Type.ANSWER,answer));});}catch(Exception e){fail("Codex 語音這次沒有接通，請重新連線。");}});
    }
    private void ready(){if(closed||connected||session.isEmpty()||channel==null||channel.state()!=DataChannel.State.OPEN)return;connected=true;main.removeCallbacks(finish);polling.scheduleWithFixedDelay(this::poll,0,300,TimeUnit.MILLISECONDS);main.post(()->{if(!closed)listener.connected();});}
    private void poll(){
        if(closed||!connected)return;try{JSONObject r=client.request("/voice/poll",new JSONObject().put("id",session).put("after",cursor));if(r.optBoolean("dropped"))throw new IllegalStateException("Transcript gap");JSONArray events=r.optJSONArray("events");if(events!=null)for(int n=0;n<events.length();n++){JSONObject e=events.getJSONObject(n);long seq=e.optLong("seq");if(seq<=cursor)continue;cursor=seq;if(e.optString("role").equals("user")){long generation=e.optLong("generation");String value=e.optString("text");boolean done=e.optString("phase").equals("done");main.post(()->acceptTranscript(generation,value,done));}else if(e.optString("kind").equals("closed")){fail("語音連線已結束，按一下可重新連線。");return;}}cursor=Math.max(cursor,r.optLong("cursor",cursor));}catch(Exception e){if(!closed)fail("語音連線中斷，請重新連線。");}
    }
    void startListening(){
        if(closed||!connected||capturing)return;muteOutput();long generation=++captureGeneration;capturing=true;turn.reset();captureEndedAt=0;captureEndAccepted=false;lastTranscriptAt=0;main.removeCallbacks(awaitTranscript);
        network.execute(()->{try{JSONObject r=client.request("/voice/listen",new JSONObject().put("id",session).put("generation",generation).put("operation","begin"));if(closed||generation!=captureGeneration)return;cursor=Math.max(cursor,r.optLong("cursor",cursor));if(!capturing){inputFailed(generation,"已停止收音，請再按一次開始。");return;}microphoneWorker.execute(()->record(generation));}catch(Exception e){inputFailed(generation,"無法開始 Codex 轉錄，請再按一次。");}});
    }
    void finishListening(){capturing=false;stopRecorder();}
    void cancelListening(){muteOutput();long old=captureGeneration++;capturing=false;captureEndAccepted=false;main.removeCallbacks(awaitTranscript);stopRecorder();cancelRemote(old);}
    private void cancelRemote(long generation){if(!closed&&generation>0&&!session.isEmpty())network.execute(()->{try{client.request("/voice/listen",new JSONObject().put("id",session).put("generation",generation).put("operation","cancel"));}catch(Exception ignored){}});}
    private void stopRecorder(){AudioRecord recorder=microphone;if(recorder!=null)try{recorder.stop();}catch(Exception ignored){}}
    private void record(long generation){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);AudioRecord recorder=null;byte[] frame=new byte[19200];int filled=0,sequence=0;long started=SystemClock.uptimeMillis(),lastVoice=started,lastMeter=0;boolean heard=false;
        try{
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){inputFailed(generation,"麥克風權限尚未允許，也可以改用文字搜尋。");return;}
            int min=AudioRecord.getMinBufferSize(24000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IllegalStateException("Unsupported recorder format");
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,24000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,9600));if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Recorder unavailable");
            microphone=recorder;if(closed||generation!=captureGeneration||!capturing)return;recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Recorder did not start");
            byte[] part=new byte[960];while(capturing&&!closed&&generation==captureGeneration){int count=recorder.read(part,0,part.length,AudioRecord.READ_BLOCKING);if(count<=0){if(!capturing)break;throw new IllegalStateException("Recorder read failed");}
                float level=rms(part,count);long now=SystemClock.uptimeMillis();if(level>.006f){heard=true;lastVoice=now;}if(now-lastMeter>=100){lastMeter=now;float meter=level;main.post(()->{if(!closed&&generation==captureGeneration&&capturing)listener.inputLevel(Math.min(1,meter*7));});}
                int from=0;while(from<count){int take=Math.min(count-from,frame.length-filled);System.arraycopy(part,from,frame,filled,take);from+=take;filled+=take;if(filled==frame.length){upload(generation,++sequence,frame.clone());filled=0;}}
                if(heard&&now-lastVoice>=3200||!heard&&now-started>=12000||now-started>=90000)capturing=false;
            }
            if(closed||generation!=captureGeneration)return;if(filled>0)upload(generation,++sequence,Arrays.copyOf(frame,filled));
            if(!heard&&lastTranscriptAt==0){inputFailed(generation,"沒有收到清楚的聲音，請再按一次。沒有使用 Google 語音備援。");return;}
            uploads.execute(()->{if(closed||generation!=captureGeneration)return;try{client.request("/voice/listen",new JSONObject().put("id",session).put("generation",generation).put("operation","end"));main.post(()->{if(!closed&&generation==captureGeneration){captureEndAccepted=true;captureEndedAt=SystemClock.uptimeMillis();listener.inputStopped();listener.state("正在聽懂，請稍候…");main.postDelayed(awaitTranscript,650);}});}catch(Exception e){inputFailed(generation,"這次轉錄沒有完成，請再試一次。");}});
        }catch(Exception e){if(!closed&&generation==captureGeneration)inputFailed(generation,"麥克風無法開始收音，請確認權限或輸入裝置。");}
        finally{if(microphone==recorder)microphone=null;if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}if(generation==captureGeneration)capturing=false;Arrays.fill(frame,(byte)0);}
    }
    private void upload(long generation,int sequence,byte[] pcm){
        try{uploads.execute(()->{try{if(!closed&&generation==captureGeneration)client.request("/voice/audio",new JSONObject().put("id",session).put("generation",generation).put("sequence",sequence).put("sampleRate",24000).put("numChannels",1).put("data",Base64.encodeToString(pcm,Base64.NO_WRAP)));}catch(Exception e){inputFailed(generation,"音訊傳送未完成，請再說一次。");}finally{Arrays.fill(pcm,(byte)0);}});}catch(RejectedExecutionException e){Arrays.fill(pcm,(byte)0);inputFailed(generation,"連線速度跟不上收音，請重新連線後再試。");throw e;}
    }
    private void acceptTranscript(long generation,String text,boolean complete){
        if(closed||generation!=captureGeneration||text.trim().isEmpty())return;turn.accept(text,complete);lastTranscriptAt=SystemClock.uptimeMillis();listener.inputTranscript(turn.text(),false);if(captureEndAccepted){main.removeCallbacks(awaitTranscript);main.postDelayed(awaitTranscript,650);}
    }
    private void finishTranscriptIfReady(){
        if(closed||capturing||!captureEndAccepted)return;long now=SystemClock.uptimeMillis();if(turn.complete()&&now-lastTranscriptAt>=600){captureEndAccepted=false;String text=turn.text();++captureGeneration;listener.inputTranscript(text,true);return;}if(now-captureEndedAt>12000){inputFailed(captureGeneration,"這次沒有收到完整轉錄，請再說一次。沒有使用 Google 語音備援。");return;}main.postDelayed(awaitTranscript,250);
    }
    private void inputFailed(long generation,String message){main.post(()->{if(closed||generation!=captureGeneration)return;++captureGeneration;capturing=false;captureEndAccepted=false;main.removeCallbacks(awaitTranscript);stopRecorder();cancelRemote(generation);listener.inputFailed(message);});}
    private static float rms(byte[] pcm,int count){double sum=0;int n=0;for(int i=0;i+1<count;i+=4){int value=(short)((pcm[i]&255)|(pcm[i+1]<<8));double x=value/32768.0;sum+=x*x;n++;}return n==0?0:(float)Math.sqrt(sum/n);}
    private void samples(JavaAudioDeviceModule.AudioSamples sample){
        if(closed||!outputAllowed)return;long epoch=outputEpoch.get();byte[] data=sample.getData();float level=rms(data,data.length);long now=SystemClock.uptimeMillis();if(level>.002f){lastSound=now;if(firstSound==0)firstSound=now;}if(now-lastLevelAt>100){lastLevelAt=now;main.post(()->{if(!closed&&outputAllowed&&epoch==outputEpoch.get()){listener.level(Math.min(1,level*7));if(firstSound!=0&&now-lastSound>1900&&!utteranceDone)finishOutput(epoch,null);}});}
    }
    private void fail(String message){main.post(()->{if(!closed){listener.failed(message);close();}});}
    void close(){
        if(closed)return;muteOutput();closed=true;capturing=false;++captureGeneration;main.removeCallbacks(finish);main.removeCallbacks(awaitTranscript);stopRecorder();polling.shutdownNow();uploads.shutdownNow();microphoneWorker.shutdownNow();
        work.post(()->{if(channel!=null){channel.close();channel.dispose();channel=null;}if(peer!=null){peer.close();peer.dispose();peer=null;}if(silentTrack!=null){silentTrack.dispose();silentTrack=null;}if(silentSource!=null){silentSource.dispose();silentSource=null;}if(factory!=null){factory.dispose();factory=null;}if(audio!=null){audio.release();audio=null;}thread.quitSafely();});
        if(!network.isShutdown())network.execute(()->{try{if(!session.isEmpty())client.request("/voice/stop",new JSONObject().put("id",session));}catch(Exception ignored){}finally{client.close();network.shutdown();}});
    }
    /** One capture generation may contain several server turns; finals replace deltas, never duplicate them. */
    static final class TranscriptTurn {
        private final List<String> parts=new ArrayList<>();private String partial="";
        void reset(){parts.clear();partial="";}
        void accept(String text,boolean complete){if(text==null||text.trim().isEmpty())return;if(complete){String value=text.trim();if(parts.isEmpty()||!parts.get(parts.size()-1).equals(value))parts.add(value);partial="";}else partial+=text;}
        boolean hasFinal(){return !parts.isEmpty();}
        boolean complete(){return hasFinal()&&partial.isEmpty();}
        String text(){String joined=android.text.TextUtils.join("，",parts);return partial.isEmpty()?joined:joined+(joined.isEmpty()?"":"，")+partial;}
    }
    private class Sdp implements SdpObserver {public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String s){fail("聲音連線未能建立，請稍後再試。");}public void onSetFailure(String s){fail("聲音連線未能建立，請稍後再試。");}}
}
