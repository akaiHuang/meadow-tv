package com.akai.livingtv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.content.pm.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** A bounded observation of public accessibility UI. No screenshots or retained node handles. */
final class TvUiSnapshot {
    static final int MAX_TARGETS=80,MAX_VISITED=320,MAX_DEPTH=18,MAX_TEXT=120;
    static final long MAX_AGE_MS=15000;
    final String id=UUID.randomUUID().toString(),packageName;
    final long createdAt=SystemClock.uptimeMillis();
    final int windowId;
    final JSONObject data;
    final LinkedHashMap<String,Target> targets=new LinkedHashMap<>();
    boolean truncated,sensitive;
    String focused="",signature="";
    private int visited,serializedBytes;
    private final StringBuilder digest=new StringBuilder();

    static final class Target {
        final String id,path,identity,resourceId,text,description,className;
        final Rect bounds;
        final boolean enabled,visible,focused,password,editable;
        final Set<String> actions;
        Target(String id,String path,AccessibilityNodeInfo n){
            this.id=id;this.path=path;resourceId=clean(n.getViewIdResourceName());className=clean(n.getClassName());bounds=new Rect();n.getBoundsInScreen(bounds);
            enabled=n.isEnabled();visible=n.isVisibleToUser();focused=n.isFocused();password=n.isPassword();editable=n.isEditable();
            text=password?"":cleanText(n.getText(),editable?240:MAX_TEXT);description=password?"":clean(n.getContentDescription());
            identity=path+"|"+resourceId+"|"+className+"|"+bounds.toShortString();actions=actions(n);
        }
        JSONObject json(String parent){return jsonObject("id",id,"parentId",parent,"resourceId",resourceId,"text",text,"label",description.isEmpty()?text:description,"class",className,"bounds",new JSONArray(Arrays.asList(bounds.left,bounds.top,bounds.right,bounds.bottom)),"enabled",enabled,"visible",visible,"focused",focused,"password",password,"editable",editable,"actions",new JSONArray(actions));}
        private static Set<String> actions(AccessibilityNodeInfo node){
            LinkedHashSet<String> out=new LinkedHashSet<>();if(!node.isEnabled()||!node.isVisibleToUser()||node.isPassword())return out;
            Set<Integer> supported=new HashSet<>();for(AccessibilityNodeInfo.AccessibilityAction a:node.getActionList())supported.add(a.getId());
            if(supported.contains(AccessibilityNodeInfo.ACTION_CLICK))out.add("click");if(supported.contains(AccessibilityNodeInfo.ACTION_FOCUS))out.add("focus");
            if(node.isEditable()&&supported.contains(AccessibilityNodeInfo.ACTION_SET_TEXT))out.add("set_text");
            if(supported.contains(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))out.add("scroll_forward");if(supported.contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))out.add("scroll_backward");
            if(supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()))out.add("scroll_up");if(supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()))out.add("scroll_down");
            if(supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()))out.add("scroll_left");if(supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()))out.add("scroll_right");return out;
        }
    }

    private TvUiSnapshot(Context context,AccessibilityNodeInfo root,boolean connected,String windowClass){
        packageName=root==null?"":clean(root.getPackageName());windowId=root==null?-1:root.getWindowId();
        JSONArray nodes=new JSONArray();if(root!=null)visit(root,"0","",0,nodes);
        signature=digest.toString();boolean settings=restrictedPackage(packageName);
        JSONObject permission=jsonObject("accessibilityEnabled",enabled(context),"accessibilityConnected",connected,"windowContentAvailable",root!=null);
        boolean nodeActions=false;for(Target target:targets.values())if(!target.actions.isEmpty()){nodeActions=true;break;}
        JSONObject capabilities=jsonObject("readTree",connected&&root!=null,"nodeActions",connected&&root!=null&&nodeActions&&!sensitive&&!settings,"globalBackHome",connected&&root!=null,"coordinateGestures",false,"shell",false,"screenshots",false,"protectedVideoPixels",false,"systemSettings",false);
        data=jsonObject("snapshotId",id,"observedAtMs",createdAt,"source","accessibility","packageName",packageName,"windowId",windowId,"windowClass",clean(windowClass),"focusedTargetId",focused,"targets",nodes,"permission",permission,"capabilities",capabilities,"sensitiveWindow",sensitive,"truncated",truncated,"visitedNodes",visited,"limits",jsonObject("targets",MAX_TARGETS,"depth",MAX_DEPTH,"expiresAfterMs",MAX_AGE_MS),"limitations",new JSONArray(Arrays.asList("UI tree is an observation, not the app navigation stack","Launch or click acceptance does not verify playback","Unlabelled custom views and protected video may be unavailable")));
    }
    static TvUiSnapshot capture(Context context,RemoteService service){
        AccessibilityNodeInfo root=null;try{if(service!=null)root=service.getRootInActiveWindow();return new TvUiSnapshot(context,root,service!=null,service==null||root==null?"":service.observedWindowClass(clean(root.getPackageName())));}
        catch(RuntimeException error){return new TvUiSnapshot(context,null,service!=null,"");}
        finally{if(root!=null)root.recycle();}
    }
    /** Fixture entry point does not take ownership of the supplied root. */
    static TvUiSnapshot fromRoot(Context context,AccessibilityNodeInfo root){return new TvUiSnapshot(context,root,true,"");}
    private void visit(AccessibilityNodeInfo node,String path,String parent,int depth,JSONArray output){
        if(depth>MAX_DEPTH||visited>=MAX_VISITED){truncated=true;return;}visited++;
        Target target=new Target("n"+visited,path,node);sensitive|=target.password;
        digest.append(target.identity).append('|').append(target.text).append('|').append(target.description).append('|').append(target.password?0:hash(node.getText())).append('|').append(target.password?0:hash(node.getContentDescription())).append('|').append(target.enabled).append('|').append(target.visible).append('|').append(target.focused).append('|').append(node.isChecked()).append('|').append(node.isSelected()).append('|').append(target.actions).append('\n');
        boolean meaningful=target.password||target.focused||!target.actions.isEmpty()||!target.text.isEmpty()||!target.description.isEmpty();
        if(meaningful&&target.visible){JSONObject value=target.json(parent);int bytes=value.toString().getBytes(StandardCharsets.UTF_8).length+1;if(targets.size()<MAX_TARGETS&&serializedBytes+bytes<=40000){serializedBytes+=bytes;targets.put(target.id,target);output.put(value);parent=target.id;if(target.focused)focused=target.id;}else truncated=true;}
        int childCount=node.getChildCount();for(int i=0;i<childCount;i++){if(visited>=MAX_VISITED){truncated=true;break;}AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{visit(child,path+"."+i,parent,depth+1,output);}finally{child.recycle();}}
    }
    boolean sameScreen(TvUiSnapshot other){return other!=null&&windowId==other.windowId&&packageName.equals(other.packageName)&&signature.equals(other.signature);}
    Target byIdentity(String identity){for(Target t:targets.values())if(t.identity.equals(identity))return t;return null;}
    Target byPath(String path){for(Target t:targets.values())if(t.path.equals(path))return t;return null;}
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root,String path){
        AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(root);String[] steps=path.split("\\.");try{for(int i=1;i<steps.length;i++){int index=Integer.parseInt(steps[i]);if(index<0||index>=node.getChildCount()){node.recycle();return null;}AccessibilityNodeInfo child=node.getChild(index);node.recycle();node=child;if(node==null)return null;}return node;}catch(RuntimeException error){if(node!=null)node.recycle();return null;}
    }
    static boolean enabled(Context context){
        try{android.view.accessibility.AccessibilityManager manager=(android.view.accessibility.AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);if(manager==null)return false;String expected=new ComponentName(context,RemoteService.class).flattenToString();for(AccessibilityServiceInfo info:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){ResolveInfo resolve=info.getResolveInfo();if(resolve!=null&&resolve.serviceInfo!=null&&expected.equals(new ComponentName(resolve.serviceInfo.packageName,resolve.serviceInfo.name).flattenToString()))return true;}}catch(RuntimeException ignored){}return false;
    }
    static boolean restrictedPackage(String name){return name.endsWith(".settings")||name.contains(".settings.")||name.contains("permissioncontroller")||name.contains("packageinstaller")||name.equals("com.android.systemui");}
    static String clean(CharSequence value){if(value==null)return "";String text=value.toString().replace('\0',' ');return text.length()>MAX_TEXT?text.substring(0,MAX_TEXT):text;}
    private static String cleanText(CharSequence value,int max){if(value==null)return "";String text=value.toString().replace('\0',' ');int count=text.codePointCount(0,text.length());return count>max?text.substring(0,text.offsetByCodePoints(0,max)):text;}
    private static int hash(CharSequence value){return value==null?0:value.toString().hashCode();}
    static JSONObject jsonObject(Object...pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);}catch(JSONException error){throw new IllegalArgumentException(error);}return value;}
}
