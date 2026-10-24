package com.akai.livingtv;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import static com.akai.livingtv.TvUiSnapshot.jsonObject;

/** Snapshot-bound native UI actions. No arbitrary intents, coordinates, shell or key injection. */
final class TvUiExecutor {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Set<String> READ_ONLY=new HashSet<>(Arrays.asList("state","apps"));
    private static final Set<String> MUTATIONS=new HashSet<>(Arrays.asList("open_app","click","focus","scroll","set_text","back","home"));
    private TvUiExecutor(){}
    static void execute(Context context,JSONObject command,Consumer<JSONObject> done){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->execute(context,command,done));return;}
        String op=command.optString("operation");
        if(!validKeys(command,op)){done.accept(failure(op,"invalid_arguments","不支援這組 Android 工具參數。",null));return;}
        if(READ_ONLY.contains(op)){TvUiSnapshot state=RemoteService.readSnapshot(context);done.accept(jsonObject("ok",true,"operation",op,"dispatched",false,"verified",true,"verificationScope","observed_state","state",state.data,"apps",op.equals("apps")?apps(context):new JSONArray()));return;}
        if(!MUTATIONS.contains(op)){done.accept(failure(op,"unsupported","不支援此 Android 操作。",null));return;}
        RemoteService service=RemoteService.connected();TvUiSnapshot before=RemoteService.previousSnapshot(command.optString("snapshotId"));
        if(service==null){done.accept(failure(op,"permission_missing","無障礙服務尚未連接；沒有啟用或修改系統設定。",RemoteService.readSnapshot(context)));return;}
        if(before==null||SystemClock.uptimeMillis()-before.createdAt>TvUiSnapshot.MAX_AGE_MS){done.accept(failure(op,"stale_snapshot","畫面快照已過期，請先重新讀取 state。",RemoteService.readSnapshot(context)));return;}
        TvUiSnapshot current=TvUiSnapshot.capture(context,service);
        if(!current.data.optJSONObject("permission").optBoolean("windowContentAvailable")){done.accept(failure(op,"window_unavailable","目前沒有可讀取的畫面，沒有操作。",RemoteService.publish(current)));return;}
        if(!before.sameScreen(current)){done.accept(failure(op,"stale_snapshot","畫面已變更，沒有操作舊目標。請讀取新的 state。",RemoteService.publish(current)));return;}
        if(!op.equals("back")&&!op.equals("home")&&(current.sensitive||TvUiSnapshot.restrictedPackage(current.packageName))){done.accept(failure(op,"protected_ui","這是登入、密碼或系統權限／設定介面；工具不操作此畫面。",current));return;}
        boolean dispatched=false;String expectedPackage="",identity="",path="";String expectedText="";
        try{
            if(op.equals("open_app")){
                expectedPackage=command.optString("packageName");Intent intent=launchIntent(context,expectedPackage);if(intent==null){done.accept(failure(op,"unknown_app","此套件不在實際可開啟的電視 App 清單，或不允許開啟設定。",before));return;}
                context.startActivity(intent);dispatched=true;
            }else if(op.equals("back")||op.equals("home")){
                if(op.equals("home")){
                    // The physical Home key intentionally toggles HDMI when already
                    // at Home. A typed navigation command must simply return Home.
                    context.startActivity(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true));expectedPackage=context.getPackageName();dispatched=true;
                }else dispatched=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            }else{
                TvUiSnapshot.Target target=before.targets.get(command.optString("targetId"));if(target==null){done.accept(failure(op,"stale_target","目標不屬於此畫面快照，沒有操作。",before));return;}
                if(target.password||!target.enabled||!target.visible||protectedLabel(target)){done.accept(failure(op,"protected_target","此目標不可操作，或涉及密碼、帳戶、付款與系統設定。",before));return;}
                String actionName=op.equals("scroll")?"scroll_"+command.optString("direction"):op;
                if(!target.actions.contains(actionName)){done.accept(failure(op,"unsupported","這個節點沒有提供所需的原生操作，沒有使用替代點擊或按鍵。",before));return;}
                AccessibilityNodeInfo root=service.getRootInActiveWindow(),node=null;
                try{
                    if(root!=null&&!before.sameScreen(TvUiSnapshot.fromRoot(context,root))){done.accept(failure(op,"stale_snapshot","操作前畫面再次變更，沒有操作舊目標。",RemoteService.readSnapshot(context)));return;}
                    if(root!=null)node=TvUiSnapshot.find(root,target.path);if(node==null){done.accept(failure(op,"stale_target","節點已不存在，請重新讀取 state。",before));return;}
                    TvUiSnapshot.Target fresh=new TvUiSnapshot.Target(target.id,target.path,node);
                    if(!target.identity.equals(fresh.identity)||!target.text.equals(fresh.text)||!target.description.equals(fresh.description)||fresh.password||!fresh.enabled||!fresh.visible||!fresh.actions.contains(actionName)){done.accept(failure(op,"stale_target","節點內容或能力已變更，沒有操作。",before));return;}
                    Bundle args=null;int action=op.equals("click")?AccessibilityNodeInfo.ACTION_CLICK:op.equals("focus")?AccessibilityNodeInfo.ACTION_FOCUS:op.equals("scroll")?scrollAction(command.optString("direction")):AccessibilityNodeInfo.ACTION_SET_TEXT;
                    if(op.equals("set_text")){expectedText=command.optString("text");if(!validText(expectedText)){done.accept(failure(op,"invalid_text","文字需為 1–240 字，且不能包含控制字元。",before));return;}args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expectedText);}
                    identity=target.identity;path=target.path;dispatched=node.performAction(action,args);
                }finally{if(node!=null)node.recycle();if(root!=null)root.recycle();}
            }
        }catch(RuntimeException error){done.accept(failure(op,"dispatch_failed","Android 沒有接受這次操作。",before));return;}
        if(!dispatched){done.accept(failure(op,"not_accepted","系統沒有接受此操作，沒有宣稱完成。",before));return;}
        final String wantedPackage=expectedPackage,wantedIdentity=identity,wantedPath=path,wantedText=expectedText;
        MAIN.postDelayed(()->{
            TvUiSnapshot after=RemoteService.readSnapshot(context);TvUiSnapshot.Target observed=after.byIdentity(wantedIdentity);boolean verified=false;String scope="dispatch_only";
            if(op.equals("open_app")||op.equals("home")){verified=!wantedPackage.isEmpty()&&wantedPackage.equals(after.packageName);scope="foreground_app";}
            else if(op.equals("focus")){verified=observed!=null&&observed.focused;scope="input_focus";}
            else if(op.equals("set_text")){TvUiSnapshot.Target value=after.byPath(wantedPath);verified=value!=null&&!value.password&&wantedText.equals(value.text);scope="editable_text";}
            boolean changed=!before.sameScreen(after);
            done.accept(jsonObject("ok",true,"operation",op,"dispatched",true,"verified",verified,"verificationScope",scope,"effectObserved",changed,"settled",false,"message",verified?"已觀察到指定的 "+scope+" 狀態；不代表影片已播放。":"已送出原生操作；尚未驗證使用者目標或播放。","before",before.data,"after",after.data));
        },450);
    }
    static boolean validText(String text){if(text==null||text.trim().isEmpty()||text.codePointCount(0,text.length())>240)return false;for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))return false;return true;}
    static boolean validKeys(JSONObject value,String op){
        Set<String> allowed=new HashSet<>(Arrays.asList("id","action","operation"));
        if(MUTATIONS.contains(op))allowed.add("snapshotId");if(op.equals("open_app"))allowed.add("packageName");if(Arrays.asList("click","focus","scroll","set_text").contains(op))allowed.add("targetId");if(op.equals("scroll"))allowed.add("direction");if(op.equals("set_text"))allowed.add("text");
        for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))return false;
        if(!READ_ONLY.contains(op)&&!MUTATIONS.contains(op))return false;
        if(MUTATIONS.contains(op)&&!string(value,"snapshotId",80))return false;
        if(Arrays.asList("click","focus","scroll","set_text").contains(op)&&!string(value,"targetId",100))return false;
        if(op.equals("open_app")&&!string(value,"packageName",180))return false;
        if(op.equals("scroll")&&scrollAction(value.optString("direction"))==0)return false;
        return !op.equals("set_text")||(value.opt("text") instanceof String&&validText(value.optString("text")));
    }
    private static boolean string(JSONObject value,String key,int max){Object raw=value.opt(key);return raw instanceof String&&!((String)raw).isEmpty()&&((String)raw).length()<=max;}
    static boolean protectedLabel(TvUiSnapshot.Target target){String value=(target.text+" "+target.description+" "+target.resourceId).toLowerCase(Locale.ROOT);for(String word:new String[]{"password","purchase","checkout","subscribe","payment","sign in","sign out","log in","log out","uninstall","factory reset","wifi_settings","密碼","購買","付款","訂閱","登入","登出","移除應用","恢復原廠","系統設定","帳戶設定","網路設定"})if(value.contains(word))return true;return false;}
    static int scrollAction(String direction){switch(direction){case "forward":return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;case "backward":return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;case "up":return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();case "down":return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();case "left":return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();case "right":return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();default:return 0;}}
    static JSONArray apps(Context context){
        JSONArray out=new JSONArray();PackageManager pm=context.getPackageManager();TreeMap<String,String> known=new TreeMap<>();try{for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),0)){if(r.activityInfo==null||!r.activityInfo.enabled)continue;String pkg=r.activityInfo.packageName;if(TvUiSnapshot.restrictedPackage(pkg))continue;Intent launch=pm.getLeanbackLaunchIntentForPackage(pkg);if(launch!=null&&launch.resolveActivity(pm)!=null)known.put(pkg,TvUiSnapshot.clean(r.loadLabel(pm)));}}catch(RuntimeException ignored){}
        for(Map.Entry<String,String> app:known.entrySet()){if(out.length()>=60)break;out.put(jsonObject("packageName",app.getKey(),"label",app.getValue(),"installed",true,"launchable",true));}return out;
    }
    static Intent launchIntent(Context context,String packageName){
        JSONArray known=apps(context);boolean found=false;for(int i=0;i<known.length();i++)if(packageName.equals(known.optJSONObject(i).optString("packageName"))){found=true;break;}if(!found)return null;
        Intent intent=context.getPackageManager().getLeanbackLaunchIntentForPackage(packageName);if(intent==null)return null;intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(packageName.equals(context.getPackageName()))intent.putExtra("fromShortcut",true);return intent;
    }
    static JSONObject failure(String operation,String code,String message,TvUiSnapshot state){return jsonObject("ok",false,"operation",operation,"dispatched",false,"verified",false,"errorCode",code,"message",message,"after",state==null?JSONObject.NULL:state.data);}
}
