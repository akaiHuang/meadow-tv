import {createInterface} from 'node:readline';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {Codex} from './codex-client.mjs';

export function validatedAudio(p){
 if(p.sampleRate!==24000||p.numChannels!==1||typeof p.data!=='string'||!p.data.length||p.data.length>64000||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(p.data))throw Error('PCM16 24kHz mono audio required');
 const bytes=Buffer.from(p.data,'base64');if(!bytes.length||bytes.length%2||bytes.length>48000||bytes.toString('base64')!==p.data)throw Error('Invalid PCM audio');
 return {data:p.data,sampleRate:24000,numChannels:1,samplesPerChannel:bytes.length/2};
}
export function createVoiceAdapter({makeCodex=()=>new Codex(),setTimer=setTimeout,clearTimer=clearTimeout}={}){
 let session=null;
 async function stop(){const old=session;session=null;if(!old)return;clearTimer(old.expiry);old.closed=true;await old.codex.rpc('thread/realtime/stop',{threadId:old.thread},6000).catch(()=>{});await old.codex.rpc('thread/unsubscribe',{threadId:old.thread},3000).catch(()=>{});old.codex.close();}
 function touch(s){clearTimer(s.expiry);s.expiry=setTimer(()=>{if(session===s)void stop()},195000);s.expiry?.unref?.();}
 function event(s,data){s.events.push({seq:++s.sequence,generation:s.generation,...data});if(s.events.length>128)s.events.shift();}
 async function call(method,p={}){
  if(method==='start'){
   if(typeof p.sdp!=='string'||!p.sdp.startsWith('v=0')||p.sdp.length>64000)throw Error('Invalid offer');
   await stop();const codex=makeCodex(),s=session={id:randomUUID(),codex,thread:null,events:[],sequence:0,generation:0,audioSequence:0,mode:'idle',closed:false};touch(s);
   let ready,failed;const answer=new Promise((resolve,reject)=>{ready=resolve;failed=reject});answer.catch(()=>{});
   const timer=setTimer(()=>failed(Error('Voice negotiation timed out')),35000);
   codex.on('notification',m=>{
    if(s.closed||m.params?.threadId!==s.thread)return;
    if(m.method==='thread/realtime/sdp')ready(m.params.sdp);
    if(m.method==='thread/realtime/error'||m.method==='error'){failed(Error(m.params.message||m.params.error?.message||'Voice connection failed'));event(s,{kind:'closed',text:'Voice connection failed'});}
    if(m.method==='thread/realtime/transcript/delta'||m.method==='thread/realtime/transcript/done'){
     const role=m.params.role,text=String(m.params.text??m.params.delta??'').slice(0,4000);if(role==='user'&&!['input','transcribing'].includes(s.mode))return;if((role==='user'||role==='assistant')&&text)event(s,{kind:'transcript',phase:m.method.endsWith('/done')?'done':'delta',role,text});
    }
    if(m.method==='thread/realtime/closed')event(s,{kind:'closed',text:'Voice session ended'});
   });
   try{
    await codex.start();const account=await codex.rpc('account/read',{});if(account.account?.type!=='chatgpt')throw Error('ChatGPT subscription login required');
    const t=await codex.rpc('thread/start',{model:'gpt-6-luna',ephemeral:true,environments:[],sandbox:'read-only',approvalPolicy:'never',baseInstructions:'You provide voice transcription and exact speech output for Meadow TV. The external Meadow Harness owns every answer and device action. Never start a backing task, tools, file access, web or shell.',config:{model_reasoning_effort:'low',project_doc_max_bytes:0,'features.shell_tool':false,'features.apply_patch_freeform':false,'features.apps':false,'features.code_mode':false,'features.multi_agent':false,web_search:'disabled'}});s.thread=t.thread.id;
    await codex.rpc('thread/realtime/start',{threadId:s.thread,version:'v3',model:'gpt-live-1-codex',outputModality:'audio',voice:'cove',includeStartupContext:false,clientManagedHandoffs:true,delegationAckFiller:false,flushTranscriptTailOnSessionEnd:false,prompt:'你是 Meadow TV 的語音輸入與朗讀端。使用繁體中文。收到使用者音訊時，只轉錄原話並交給 client；不要回答、不要操作、不要推測工具結果、不要說話或主動開場。所有聊天、搜尋與電視操作都交給外部 Meadow Harness。只有明確收到 appendSpeech 的文字時，才用 cove 聲音逐字朗讀該文字；不要加開場或其他內容。',transport:{type:'webrtc',sdp:p.sdp}});
    const sdp=await answer;clearTimer(timer);return {id:s.id,sdp,cursor:s.sequence,voice:'cove',model:'gpt-live-1-codex',auth:'chatgpt_subscription'};
   }catch(e){clearTimer(timer);if(session===s)await stop();throw e;}
  }
  const s=session;if(!s||s.closed||p.id!==s.id)throw Error('Voice session ended');
  if(method==='poll'){
   const after=Number.isSafeInteger(p.after)&&p.after>=0?p.after:0,found=s.events.filter(e=>e.seq>after);const events=[];let bytes=0;for(const e of found){const n=Buffer.byteLength(JSON.stringify(e));if(events.length>=64||bytes+n>96000)break;events.push(e);bytes+=n;}
   return {events,cursor:events.length?events.at(-1).seq:s.sequence,dropped:after>0&&s.events.length>0&&after<s.events[0].seq-1};
  }
  if(method==='listen'){
   if(!Number.isSafeInteger(p.generation)||p.generation<1)throw Error('Capture generation required');
   if(p.operation==='begin'){if(p.generation<=s.generation)throw Error('Stale capture');s.generation=p.generation;s.audioSequence=0;s.mode='input';touch(s);return {ok:true,cursor:s.sequence};}
   if(p.generation!==s.generation)throw Error('Stale capture');
   if(p.operation==='cancel'){s.mode='idle';touch(s);return {ok:true};}
   if(p.operation==='end'){
    if(s.mode!=='input')throw Error('Capture is not active');s.mode='transcribing';touch(s);
    // V3 has input_audio.append, not input_audio_buffer.commit. A bounded silence
    // tail lets its native turn detector finalize a manually stopped utterance.
    const data=Buffer.alloc(19200).toString('base64');for(let n=0;n<3;n++)await s.codex.rpc('thread/realtime/appendAudio',{threadId:s.thread,audio:{data,sampleRate:24000,numChannels:1,samplesPerChannel:9600}});
    return {ok:true,cursor:s.sequence};
   }
   throw Error('Unsupported capture action');
  }
  if(method==='audio'){
   if(s.mode!=='input'||p.generation!==s.generation||!Number.isSafeInteger(p.sequence)||p.sequence!==s.audioSequence+1)throw Error('Out-of-order capture audio');const audio=validatedAudio(p);await s.codex.rpc('thread/realtime/appendAudio',{threadId:s.thread,audio});s.audioSequence=p.sequence;touch(s);return {ok:true};
  }
  if(method==='speak'){
   if(typeof p.text!=='string'||!p.text.trim()||p.text.length>4000)throw Error('Invalid speech');s.mode='speech';touch(s);const cursor=s.sequence;await s.codex.rpc('thread/realtime/appendSpeech',{threadId:s.thread,text:p.text});return {ok:true,cursor};
  }
  if(method==='stop'){await stop();return {ok:true};}
  throw Error('Unsupported voice action');
 }
 return {call,stop};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const adapter=createVoiceAdapter(),emit=data=>process.stdout.write(JSON.stringify(data)+'\n'),errText=e=>String(e?.message||e).slice(0,500);
 createInterface({input:process.stdin}).on('line',line=>{let m;try{m=JSON.parse(line)}catch{return}void adapter.call(m.method,m.params||{}).then(result=>emit({id:m.id,result}),error=>emit({id:m.id,error:errText(error)}));});
 process.stdin.on('end',()=>void adapter.stop().finally(()=>process.exit(0)));for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>void adapter.stop().finally(()=>process.exit(0)));
}
