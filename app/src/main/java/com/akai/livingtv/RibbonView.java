package com.akai.livingtv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Opaque 640x360 ES2 ribbon: seamless 24s cycle, at most 20fps while visible/focused. */
final class RibbonView extends FrameLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GLSurfaceView gl;
    private Renderer renderer;
    private long started;
    static final long LOOP_MS=24000,FRAME_MS=50;
    private boolean active,resumed,windowFocused=true,shaderFailed;
    private int requestedFrames,framesAtResume;
    static float phaseAt(long elapsed){return (float)((elapsed%LOOP_MS)*Math.PI*2/LOOP_MS);}
    private final Runnable tick=new Runnable(){public void run(){if(!active||gl==null)return;renderer.time=phaseAt(SystemClock.uptimeMillis()-started);gl.requestRender();requestedFrames++;handler.postDelayed(this,FRAME_MS);}};
    RibbonView(Context c){
        super(c);setFocusable(false);
        // Remains visible if the GPU cannot compile the shader or no ES2 surface is available.
        addView(new View(c){private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);@Override protected void onDraw(Canvas canvas){int w=getWidth(),h=getHeight();p.setColor(0xFF03070C);canvas.drawRect(0,0,w,h,p);p.setStyle(Paint.Style.STROKE);p.setShader(new LinearGradient(w*.3f,0,w,0,new int[]{0x00000000,0xFF557CA6,0xFF13283E},null,Shader.TileMode.CLAMP));for(int i=0;i<3;i++){Path line=new Path();line.moveTo(w*.32f,h*(.76f+i*.04f));line.cubicTo(w*.65f,h*.84f,w*.54f,h*(.25f-i*.07f),w,h*(.38f+i*.13f));p.setStrokeWidth(i==0?2.8f:1.5f);canvas.drawPath(line,p);}p.setShader(null);p.setStyle(Paint.Style.FILL);}},new LayoutParams(-1,-1));
        ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null&&manager.getDeviceConfigurationInfo().reqGlEsVersion>=0x20000){
            gl=new GLSurfaceView(c);gl.setEGLContextClientVersion(2);gl.setEGLConfigChooser(8,8,8,0,0,0);gl.getHolder().setFormat(PixelFormat.OPAQUE);gl.setPreserveEGLContextOnPause(true);gl.setZOrderOnTop(false);gl.getHolder().setFixedSize(640,360);
            renderer=new Renderer(()->handler.post(()->{shaderFailed=true;updateRunning();gl.setVisibility(View.INVISIBLE);getChildAt(1).setVisibility(View.VISIBLE);}));gl.setRenderer(renderer);gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);addView(gl,0,new LayoutParams(-1,-1));
            getChildAt(1).setVisibility(View.GONE);
        }
    }
    void resume(){resumed=true;updateRunning();}
    void pause(){resumed=false;updateRunning();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);windowFocused=focused;updateRunning();}
    private void updateRunning(){
        boolean run=resumed&&windowFocused&&gl!=null&&!shaderFailed;if(run==active)return;active=run;handler.removeCallbacks(tick);
        if(run){requestedFrames=0;framesAtResume=renderer.drawnFrames;started=SystemClock.uptimeMillis();gl.onResume();handler.post(tick);}
        else if(gl!=null){gl.onPause();android.util.Log.i("MeadowRibbon","Paused; requested frames="+requestedFrames+"; drawn frames="+(renderer.drawnFrames-framesAtResume)+"; elapsed="+(SystemClock.uptimeMillis()-started));}
    }
    int scheduledFrames(){return requestedFrames;}
    private static final class Renderer implements GLSurfaceView.Renderer {
        volatile float time;
        volatile int drawnFrames;
        private int program,resolution,clock;
        private final Runnable failed;
        private int width=640,height=360;
        private final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private static final String VERTEX="attribute vec2 position;void main(){gl_Position=vec4(position,0.0,1.0);}";
        private static final String FRAGMENT="precision mediump float;uniform vec2 resolution;uniform float clock;float band(float d,float width){float v=d*width;return 1.0/(1.0+v*v);}void main(){vec2 uv=gl_FragCoord.xy/resolution;float x=uv.x;float y=uv.y;float t=clock;float a=0.17+0.83*smoothstep(0.34,0.78,x)+0.055*sin(t)*sin(x*3.14159);float b=0.19+0.57*sin((x-0.35)*2.7)+0.055*sin(t+1.5)*sin(x*3.14159);float c=0.17+0.35*sin((x-0.32)*3.8)+0.045*sin(t-1.0)*sin(x*3.14159);float d=abs(y-a),e=abs(y-b),f=abs(y-c);float mask=smoothstep(0.30,0.67,x);float glow=(band(d,25.0)*0.22+band(e,28.0)*0.16+band(f,32.0)*0.12)*mask;float edge=(band(d,230.0)*0.75+band(e,220.0)*0.50+band(f,220.0)*0.30)*mask;vec3 color=vec3(0.012,0.023,0.036)+vec3(0.20,0.35,0.57)*glow+vec3(0.52,0.72,0.98)*edge;gl_FragColor=vec4(color,1.0);}";
        Renderer(Runnable onFailure){failed=onFailure;vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);}
        public void onSurfaceCreated(GL10 ignored,EGLConfig config){
            int vertex=compile(GLES20.GL_VERTEX_SHADER,VERTEX),fragment=compile(GLES20.GL_FRAGMENT_SHADER,FRAGMENT);if(vertex==0||fragment==0){if(vertex!=0)GLES20.glDeleteShader(vertex);if(fragment!=0)GLES20.glDeleteShader(fragment);failed.run();return;}
            program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glBindAttribLocation(program,0,"position");GLES20.glLinkProgram(program);GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0){GLES20.glDeleteProgram(program);program=0;failed.run();return;}
            resolution=GLES20.glGetUniformLocation(program,"resolution");clock=GLES20.glGetUniformLocation(program,"clock");
        }
        public void onSurfaceChanged(GL10 ignored,int w,int h){width=w;height=h;GLES20.glViewport(0,0,w,h);android.util.Log.i("MeadowRibbon","Shader surface "+w+"x"+h+"; linked="+(program!=0));}
        public void onDrawFrame(GL10 ignored){GLES20.glClearColor(.012f,.023f,.036f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);if(program==0)return;drawnFrames++;GLES20.glUseProgram(program);GLES20.glUniform2f(resolution,width,height);GLES20.glUniform1f(clock,time);GLES20.glEnableVertexAttribArray(0);GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(0);}
        private static int compile(int kind,String source){int shader=GLES20.glCreateShader(kind);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] ok=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){GLES20.glDeleteShader(shader);return 0;}return shader;}
    }
}
