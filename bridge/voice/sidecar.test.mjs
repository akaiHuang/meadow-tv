import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {createVoiceAdapter,validatedAudio} from './sidecar.mjs';

function fixture(){
 const calls=[],timers=[];let closed=false;
 class FakeCodex extends EventEmitter{
  async start(){}async rpc(method,params){calls.push({method,params});if(method==='account/read')return {account:{type:'chatgpt'}};if(method==='thread/start')return {thread:{id:'local-test-thread'}};if(method==='thread/realtime/start')queueMicrotask(()=>this.emit('notification',{method:'thread/realtime/sdp',params:{threadId:'local-test-thread',sdp:'v=0\r\nfixture-answer'}}));return {};}
  close(){closed=true;}
 }
 const codex=new FakeCodex(),adapter=createVoiceAdapter({makeCodex:()=>codex,setTimer:(fn,ms)=>{const timer={fn,ms,cleared:false,unref(){}};timers.push(timer);return timer;},clearTimer:t=>{if(t)t.cleared=true;}});
 return {adapter,codex,calls,timers,isClosed:()=>closed};
}
const pcm=Buffer.alloc(19200).toString('base64');
async function begin(f,generation=1){const start=await f.adapter.call('start',{sdp:'v=0\r\nfixture-offer'});await f.adapter.call('listen',{id:start.id,generation,operation:'begin'});return start.id;}

test('only bounded PCM16 24kHz mono is accepted',()=>{
 assert.equal(validatedAudio({sampleRate:24000,numChannels:1,data:pcm}).samplesPerChannel,9600);
 for(const value of [{sampleRate:48000,numChannels:1,data:pcm},{sampleRate:24000,numChannels:2,data:pcm},{sampleRate:24000,numChannels:1,data:'not base64'},{sampleRate:24000,numChannels:1,data:Buffer.alloc(3).toString('base64')},{sampleRate:24000,numChannels:1,data:Buffer.alloc(48002).toString('base64')}])assert.throws(()=>validatedAudio(value));
});
test('real Codex method names and explicit audio-only speech contract',async()=>{
 const f=fixture(),id=await begin(f);const start=f.calls.find(c=>c.method==='thread/realtime/start').params;assert.equal(start.version,'v3');assert.equal(start.voice,'cove');assert.equal(start.clientManagedHandoffs,true);assert.equal(start.delegationAckFiller,false);
 await f.adapter.call('audio',{id,generation:1,sequence:1,sampleRate:24000,numChannels:1,data:pcm});const call=f.calls.find(c=>c.method==='thread/realtime/appendAudio');assert.equal(call.params.audio.sampleRate,24000);assert.equal(call.params.audio.numChannels,1);assert.equal(call.params.audio.data,pcm);
 await f.adapter.call('listen',{id,generation:1,operation:'end'});assert.equal(f.calls.filter(c=>c.method==='thread/realtime/appendAudio').length,4);
 await f.adapter.call('speak',{id,text:'工具已回報：音量為二十。'});assert.equal(f.calls.at(-1).method,'thread/realtime/appendSpeech');assert.equal(f.calls.at(-1).params.text,'工具已回報：音量為二十。');assert.equal(f.calls.some(c=>c.method==='thread/realtime/appendText'),false);await f.adapter.stop();
});
test('capture generations and PCM sequence reject stale or missing frames',async()=>{
 const f=fixture(),id=await begin(f,3);const audio={id,generation:3,sampleRate:24000,numChannels:1,data:pcm};await f.adapter.call('audio',{...audio,sequence:1});await assert.rejects(f.adapter.call('audio',{...audio,sequence:3}));await assert.rejects(f.adapter.call('audio',{...audio,generation:2,sequence:2}));await f.adapter.call('audio',{...audio,sequence:2});await f.adapter.call('listen',{id,generation:3,operation:'cancel'});await assert.rejects(f.adapter.call('audio',{...audio,sequence:3}));await assert.rejects(f.adapter.call('listen',{id,generation:3,operation:'begin'}));await f.adapter.call('listen',{id,generation:5,operation:'begin'});await assert.rejects(f.adapter.call('listen',{id,generation:3,operation:'end'}));await f.adapter.stop();
});
test('poll returns normalized transcript phases and never extends paid session idle',async()=>{
 const f=fixture(),id=await begin(f,7);const timersBefore=f.timers.length;
 f.codex.emit('notification',{method:'thread/realtime/transcript/delta',params:{threadId:'local-test-thread',role:'user',delta:'找心理學'}});f.codex.emit('notification',{method:'thread/realtime/transcript/done',params:{threadId:'local-test-thread',role:'user',text:'找心理學節目'}});
 const events=await f.adapter.call('poll',{id,after:0});assert.equal(events.events.length,2);assert.equal(events.events[0].phase,'delta');assert.equal(events.events[1].phase,'done');assert.equal(events.events[1].generation,7);assert.equal(events.events[1].role,'user');
 const next=await f.adapter.call('poll',{id,after:events.cursor});assert.deepEqual(next.events,[]);assert.equal(f.timers.length,timersBefore,'poll is transport, not human activity');await f.adapter.stop();assert.equal(f.isClosed(),true);
});
test('user transcripts are accepted only while capturing or finalizing, never from idle or speech',async()=>{
 const f=fixture(),start=await f.adapter.call('start',{sdp:'v=0\r\nfixture-offer'}),id=start.id;
 const transcript=(role,text,phase='done')=>f.codex.emit('notification',{method:`thread/realtime/transcript/${phase}`,params:{threadId:'local-test-thread',role,text}});
 transcript('user','開場雜訊');transcript('assistant','輸出角色保持獨立');
 await f.adapter.call('listen',{id,generation:1,operation:'begin'});transcript('user','找節目','delta');
 await f.adapter.call('listen',{id,generation:1,operation:'end'});transcript('user','找心理學節目');
 await f.adapter.call('speak',{id,text:'已找到可選節目。'});transcript('user','朗讀時的回音');
 await f.adapter.call('listen',{id,generation:1,operation:'cancel'});transcript('user','取消後的殘留');
 const result=await f.adapter.call('poll',{id,after:0});assert.deepEqual(result.events.filter(e=>e.role==='user').map(e=>e.text),['找節目','找心理學節目']);assert.equal(result.events.filter(e=>e.role==='assistant').length,1);await f.adapter.stop();
});
test('bounded event history reports gaps instead of fabricating a full transcript',async()=>{
 const f=fixture(),id=await begin(f);for(let n=0;n<140;n++)f.codex.emit('notification',{method:'thread/realtime/transcript/delta',params:{threadId:'local-test-thread',role:'user',delta:'字'}});const r=await f.adapter.call('poll',{id,after:1});assert.equal(r.dropped,true);assert.ok(r.events.length<=64);await f.adapter.stop();
});
