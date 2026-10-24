import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {createTextAdapter,errorText} from './text-sidecar.mjs';

const schema={
 type:'object',additionalProperties:false,required:['content','tool_calls'],
 properties:{content:{type:'string'},tool_calls:{type:'array',maxItems:1,items:{
  type:'object',properties:{name:{enum:['android_state','media_queue']},arguments:{type:'string'}}
 }}}
};
const input={prompt:'只回傳指定 JSON，不使用主機工具。',schema,model:'gpt-6.1-sol',cwd:'/tmp/meadow-text-fixture'};
const json=value=>JSON.stringify(value);
const ok={content:'OK',tool_calls:[]};
const step=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(options={}){
 const calls=[],clients=[],timers=[],diagnostics=[];
 class FakeCodex extends EventEmitter{
  async start(){this.starts=(this.starts??0)+1;}
  async rpc(method,params){
   calls.push({method,params,client:this});
   if(options.rpc){const result=options.rpc(method,params,this);if(result!==undefined)return result;}
   if(method==='account/read')return {account:{type:'chatgpt'}};
   if(method==='thread/start')return {thread:{id:'thread-'+ ++this.threadCount}};
   if(method==='turn/start')return {turn:{id:'turn-'+ ++this.turnCount,status:'inProgress',items:[]}};
   return {};
  }
  close(){this.closed=true;}
 }
 const adapter=createTextAdapter({makeCodex:()=>{const c=new FakeCodex();c.threadCount=0;c.turnCount=0;clients.push(c);return c;},setTimer:(fn,ms)=>{const t={fn,ms,cleared:false,unref(){}};timers.push(t);return t;},clearTimer:t=>{if(t)t.cleared=true;},onDiagnostic:d=>diagnostics.push(d)});
 async function ready(){for(let n=0;n<20;n++){const call=calls.filter(c=>c.method==='turn/start').at(-1);if(call)return call;await step();}throw Error('Fixture turn did not start');}
 function emit(method,params){clients.at(-1).emit('notification',{method,params});}
 function complete(text=json(ok),threadId='thread-1',turnId='turn-1',status='completed'){
  emit('item/completed',{threadId,turnId,item:{id:'answer-'+turnId,type:'agentMessage',phase:'final_answer',text}});
  emit('turn/completed',{threadId,turn:{id:turnId,status,items:[]}});
 }
 return {adapter,calls,clients,timers,diagnostics,ready,emit,complete};
}

test('rejects malformed or oversized input before starting Codex',async()=>{
 const f=fixture();
 for(const p of [{...input,prompt:''},{...input,prompt:'台'.repeat(45000)},{...input,schema:[]},{...input,model:''},{...input,cwd:'relative'},{...input,apiKey:'unused'}])await assert.rejects(f.adapter.call('infer',p));
 assert.equal(f.clients.length,0);await f.adapter.stop();
});
test('starts a restricted ephemeral thread with local schema fields and requires actual completion',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();
 const start=f.calls.find(c=>c.method==='thread/start').params,turn=f.calls.find(c=>c.method==='turn/start').params;
 assert.equal(start.ephemeral,true);assert.equal(start.sandbox,'read-only');assert.equal(start.approvalPolicy,'never');assert.equal(start.allowProviderModelFallback,false);assert.deepEqual(start.environments,[]);assert.deepEqual(start.dynamicTools,[]);assert.equal(start.config['features.code_mode_host'],false);assert.equal(start.config['features.shell_tool'],false);assert.equal(start.config['features.hooks'],false);
 assert.equal(turn.effort,'low');assert.equal(turn.model,'gpt-6.1-sol');assert.deepEqual(turn.outputSchema,schema);assert.deepEqual(turn.environments,[]);assert.equal(turn.input[0].type,'text');assert.equal(turn.input[0].text,input.prompt);
 let resolved=false;void result.then(()=>{resolved=true});await step();assert.equal(resolved,false);
 f.emit('item/agentMessage/delta',{threadId:'thread-1',turnId:'turn-1',itemId:'answer-turn-1',delta:'{"content":"old'});
 f.complete();assert.deepEqual(await result,ok);assert.equal(f.calls.at(-1).method,'thread/unsubscribe');assert.equal((await f.adapter.call('health')).busy,false);await f.adapter.stop();
});
test('reuses initialized app-server but opens a different thread on every inference',async()=>{
 const f=fixture();let result=f.adapter.call('infer',input);await f.ready();f.complete();await result;
 result=f.adapter.call('infer',input);for(let n=0;n<10&&f.calls.filter(c=>c.method==='turn/start').length<2;n++)await step();f.complete(json(ok),'thread-2','turn-2');await result;
 assert.equal(f.clients.length,1);assert.equal(f.clients[0].starts,1);assert.equal(f.calls.filter(c=>c.method==='account/read').length,1);assert.equal(f.calls.filter(c=>c.method==='thread/unsubscribe').length,2);await f.adapter.stop();
});
test('supports final deltas and ignores notifications for another thread or turn',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();
 f.complete('not JSON','other-thread','turn-1');f.complete('not JSON','thread-1','other-turn');
 for(const delta of ['{"content":','"OK","tool_calls":[]}'])f.emit('item/agentMessage/delta',{threadId:'thread-1',turnId:'turn-1',itemId:'message-1',delta});
 f.emit('turn/completed',{threadId:'thread-1',turn:{id:'turn-1',status:'completed',items:[]}});assert.deepEqual(await result,ok);await f.adapter.stop();
});
test('terminal turn errors, malformed final JSON and unauthorized outer tool names fail closed',async()=>{
 for(const text of ['not JSON',json({content:'ok',tool_calls:[],extra:true}),json({content:'ok',tool_calls:[{name:'shell',arguments:'{}'}]}),json({content:'ok',tool_calls:[{name:'media_queue',arguments:'null'}]})]){
  const f=fixture(),result=f.adapter.call('infer',input);await f.ready();f.complete(text);await assert.rejects(result);assert.ok(f.calls.some(c=>c.method==='turn/interrupt'));assert.ok(f.calls.some(c=>c.method==='thread/unsubscribe'));await f.adapter.stop();
 }
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();f.emit('turn/completed',{threadId:'thread-1',turn:{id:'turn-1',status:'failed',items:[],error:{message:'provider unavailable'}}});await assert.rejects(result,/provider unavailable/);await f.adapter.stop();
});
test('returns a supplied outer tool call as data without executing it',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();const value={content:'',tool_calls:[{name:'android_state',arguments:'{}'}]};f.complete(json(value));assert.deepEqual(await result,value);assert.equal(f.calls.some(c=>c.method==='android_state'),false);await f.adapter.stop();
});
test('single in-flight and cancel interrupt the real turn then unsubscribe',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();await assert.rejects(f.adapter.call('infer',input),/already active/);
 assert.deepEqual(await f.adapter.call('cancel'),{cancelled:true});await assert.rejects(result,/cancelled/);
 assert.deepEqual(f.calls.find(c=>c.method==='turn/interrupt').params,{threadId:'thread-1',turnId:'turn-1'});assert.equal(f.calls.at(-1).method,'thread/unsubscribe');await f.adapter.stop();
});
test('cancelling while turn/start awaits its reply interrupts once its actual ID is known',async()=>{
 let release;const reply=new Promise(resolve=>{release=resolve});const f=fixture({rpc:method=>method==='turn/start'?reply:undefined});
 const result=f.adapter.call('infer',input);await f.ready();await f.adapter.call('cancel');release({turn:{id:'late-turn',status:'inProgress',items:[]}});await assert.rejects(result,/cancelled/);
 assert.equal(f.calls.find(c=>c.method==='turn/interrupt').params.turnId,'late-turn');await f.adapter.stop();
});
test('deadline interrupts, and host-tool attempts never become successful results',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();f.timers.find(t=>t.ms===100000).fn();await assert.rejects(result,/timed out/);assert.ok(f.calls.some(c=>c.method==='turn/interrupt'));await f.adapter.stop();
 const g=fixture(),attempt=g.adapter.call('infer',input);await g.ready();g.emit('item/started',{threadId:'thread-1',turnId:'turn-1',item:{id:'host-call',type:'mcpToolCall'}});await assert.rejects(attempt,/Host tools/);await g.adapter.stop();
});
test('retry notifications stay diagnostic, non-retry errors fail, and diagnostics are bounded',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();f.emit('error',{threadId:'thread-1',turnId:'turn-1',willRetry:true,error:{message:'retry '+ 'x'.repeat(1000)}});assert.equal((await f.adapter.call('health')).stage,'retrying');assert.ok((await f.adapter.call('health')).error.length<=500);f.complete();await result;await f.adapter.stop();
 const g=fixture(),failure=g.adapter.call('infer',input);await g.ready();g.emit('error',{threadId:'thread-1',turnId:'turn-1',willRetry:false,error:{message:'connection lost'}});await assert.rejects(failure,/connection lost/);await g.adapter.stop();
 assert.equal(errorText(Error('Bearer abc token=secret api_key=sk-test')).includes('secret'),false);
});
test('disconnect rejects the active inference and stop closes the reusable child',async()=>{
 const f=fixture(),result=f.adapter.call('infer',input);await f.ready();f.clients[0].emit('disconnected','network ended');await assert.rejects(result,/disconnected/);assert.equal(f.clients[0].closed,true);await f.adapter.stop();assert.equal((await f.adapter.call('health')).stage,'stopped');await assert.rejects(f.adapter.call('infer',input),/stopped/);
 const g=fixture(),pending=g.adapter.call('infer',input);await g.ready();await g.adapter.stop();await assert.rejects(pending,/cancelled/);assert.equal(g.clients[0].closed,true);
});
test('API-key accounts and failed thread creation cannot return a fabricated inference',async()=>{
 const f=fixture({rpc:method=>method==='account/read'?{account:{type:'apiKey'}}:undefined});await assert.rejects(f.adapter.call('infer',input),/ChatGPT subscription/);assert.equal(f.calls.some(c=>c.method==='thread/start'),false);await f.adapter.stop();
 const g=fixture({rpc:method=>method==='thread/start'?{thread:{}}:undefined});await assert.rejects(g.adapter.call('infer',input),/ephemeral thread/);await g.adapter.stop();
});
