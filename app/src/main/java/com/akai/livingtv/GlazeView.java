package com.akai.livingtv;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.os.*;
import android.view.*;
import java.nio.*;

/** Original Meadow crystalline glaze, ported to a small ES2 texture in the native dialog. */
final class GlazeView extends TextureView implements TextureView.SurfaceTextureListener {
    private final HandlerThread thread=new HandlerThread("MeadowGlaze");
    private final Handler render;
    private boolean running;
    private volatile boolean closed;
    private volatile float energy;
    private volatile boolean animated;
    private float phase,flowX,flowY;
    private long last;
    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface surface;
    private int program;
    private final FloatBuffer quad=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final Runnable frame=()->draw();
    GlazeView(Context c){super(c);setOpaque(false);setFocusable(false);setSurfaceTextureListener(this);quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);thread.start();render=new Handler(thread.getLooper());}
    void state(boolean moves,float level){if(closed)return;animated=moves;energy=Math.max(0,Math.min(1,level));render.post(()->{if(running){render.removeCallbacks(frame);draw();}});}
    void level(float value){energy=Math.max(0,Math.min(1,value));}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){texture.setDefaultBufferSize(192,192);render.post(()->init(texture));}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(!render.post(()->{release();texture.release();}))texture.release();return false;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
    private void init(SurfaceTexture texture){
        if(closed)return;try{
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];EGL14.eglInitialize(display,version,0,version,1);
            int[] attributes={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0);if(count[0]==0)throw new IllegalStateException();
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);surface=EGL14.eglCreateWindowSurface(display,configs[0],texture,new int[]{EGL14.EGL_NONE},0);if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException();
            String fragment;try(java.io.InputStream in=getContext().getAssets().open("glaze.frag")){fragment=new String(FeedRepository.readLimited(in,32000),java.nio.charset.StandardCharsets.UTF_8);}
            int vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;varying vec2 v_uv;void main(){v_uv=position*.5+.5;gl_Position=vec4(position,0,1);}"),frag=shader(GLES20.GL_FRAGMENT_SHADER,fragment);program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,frag);GLES20.glBindAttribLocation(program,0,"position");GLES20.glLinkProgram(program);GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(frag);int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);if(linked[0]==0)throw new IllegalStateException();
            GLES20.glViewport(0,0,192,192);GLES20.glUseProgram(program);GLES20.glUniform2f(uniform("u_resolution"),192,192);GLES20.glUniform3f(uniform("u_air"),.86f,.88f,.69f);GLES20.glUniform3f(uniform("u_mist"),.53f,.75f,.68f);GLES20.glUniform3f(uniform("u_green"),.19f,.43f,.52f);GLES20.glUniform3f(uniform("u_forest"),.045f,.13f,.27f);running=true;last=SystemClock.uptimeMillis();android.util.Log.i("MeadowGlaze","192x192 crystalline shader linked");draw();
        }catch(Exception e){android.util.Log.w("MeadowGlaze","Static fallback used");release();post(()->setAlpha(0));}
    }
    private int shader(int kind,String text){int id=GLES20.glCreateShader(kind);GLES20.glShaderSource(id,text);GLES20.glCompileShader(id);int[] ok=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){GLES20.glDeleteShader(id);throw new IllegalStateException();}return id;}
    private int uniform(String name){return GLES20.glGetUniformLocation(program,name);}
    private void draw(){
        if(!running||closed)return;long now=SystemClock.uptimeMillis();float dt=Math.min(.12f,(now-last)/1000f);last=now;phase+=dt*(.26f+energy*.50f);flowX+=dt*(.013f+energy*.055f);flowY+=dt*(.009f+energy*.032f);
        GLES20.glUseProgram(program);GLES20.glUniform1f(uniform("u_time"),phase);GLES20.glUniform1f(uniform("u_energy"),energy);GLES20.glUniform4f(uniform("u_flow"),flowX,flowY,.03f+energy*.13f,-.02f-energy*.10f);GLES20.glClearColor(0,0,0,0);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glEnableVertexAttribArray(0);GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,quad);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(0);EGL14.eglSwapBuffers(display,surface);if(animated)render.postDelayed(frame,83);
    }
    private void release(){running=false;render.removeCallbacks(frame);if(display!=null){if(program!=0&&EGL14.eglGetCurrentContext().equals(context))GLES20.glDeleteProgram(program);program=0;EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(surface!=null)EGL14.eglDestroySurface(display,surface);if(context!=null)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);display=null;surface=null;context=null;}}
    void close(){closed=true;render.post(()->{release();thread.quitSafely();});}
}
