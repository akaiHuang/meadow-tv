// Adapted from the user-designated Jero Classroom subscription voice client.
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {EventEmitter} from 'node:events';
import {existsSync,readFileSync} from 'node:fs';
import {join} from 'node:path';
import {homedir} from 'node:os';
import {once} from 'node:events';
// Empty TOML maps merge with user config; they do not revoke host MCP servers.
export function isolatedMcpArguments(text){
 const names=new Set();
 for(const line of text.split(/\r?\n/)){
  const match=line.match(/^\s*\[\s*(?:[^\]\r\n]*\.)?mcp_servers\.("(?:[^"\\]|\\.)*"|'[^']*'|[A-Za-z0-9_-]+)(?:\.[^\]]*)?\s*\]/);
  if(!match)continue;
  const raw=match[1];let name;
  try{name=raw.startsWith('"')?JSON.parse(raw):raw.startsWith("'")?raw.slice(1,-1):raw}catch{throw Error('Invalid MCP configuration key')}
  if(!name||name.length>200||/[\x00-\x1f]/.test(name))throw Error('Invalid MCP configuration key');names.add(name);
 }
 if(/^\s*mcp_servers\s*=/m.test(text))throw Error('Inline MCP config cannot be isolated; use named server tables');
 return names.size?['-c','mcp_servers={'+[...names].sort().map(name=>`${JSON.stringify(name)}={enabled=false}`).join(',')+'}']:[];
}
export class Codex extends EventEmitter {
 constructor(){super();this.seq=0;this.pending=new Map();}
 async start(){
  if(this.closed)throw new Error('Codex 連線已關閉');
  const bundled='/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex';
  const legacy='/Applications/ChatGPT.app/Contents/Resources/codex';
  const binary=process.env.MEADOW_TV_VOICE_CODEX || (existsSync(bundled)?bundled:existsSync(legacy)?legacy:'codex');
  const env={...process.env}; delete env.OPENAI_API_KEY;delete env.CODEX_API_KEY;delete env.CODEX_ACCESS_TOKEN;
  const configPath=join(process.env.CODEX_HOME||join(homedir(),'.codex'),'config.toml');
  let config='';if(existsSync(configPath)){config=readFileSync(configPath,'utf8');if(Buffer.byteLength(config)>1024*1024)throw Error('Codex config is too large to isolate safely')}
  const args=['app-server',...isolatedMcpArguments(config),'-c','model_reasoning_effort="low"','-c','skills.bundled.enabled=false','-c','skills.include_instructions=false'];
  for(const feature of ['plugins','hooks','apps','memories','shell_tool','unified_exec','multi_agent','computer_use','browser_use','browser_use_external','code_mode_host','js_repl','executor_capability_discovery','recommended_plugins','tool_suggest','skill_search','skill_mcp_dependency_install'])args.push('-c',`features.${feature}=false`);
  const proc=this.proc=spawn(binary,args,{env,stdio:['pipe','pipe','pipe']});
  let diagnostics='';proc.stderr.on('data',data=>{diagnostics=(diagnostics+String(data)).slice(-3000)});
  proc.on('error',e=>{if(this.proc===proc)this.fail(e)});proc.on('exit',()=>{if(this.proc===proc){const detail=diagnostics.replace(/Bearer\s+\S+/gi,'Bearer [REDACTED]').replace(/\bsk-[A-Za-z0-9_-]+/g,'[REDACTED]').slice(-500);this.fail(new Error('Codex 連線已關閉'+(detail?': '+detail:'')))}});
  createInterface({input:proc.stdout}).on('line',line=>{
   if(this.proc!==proc)return;
   let m;try{m=JSON.parse(line)}catch{return}
   if(m.id!==undefined&&this.pending.has(m.id)){const p=this.pending.get(m.id);this.pending.delete(m.id);clearTimeout(p.timer);m.error?p.reject(new Error(m.error.message)):p.resolve(m.result);}
   else if(m.id!==undefined){proc.stdin.write(JSON.stringify({id:m.id,error:{code:-32601,message:'Tools and approvals are unavailable in this voice renderer'}})+'\n');}
   else this.emit('notification',m);
  });
  await this.rpc('initialize',{clientInfo:{name:'meadow_tv_voice',title:'Meadow TV Voice',version:'1.0.0'},capabilities:{experimentalApi:true}});
  this.proc.stdin.write(JSON.stringify({method:'initialized',params:{}})+'\n');
 }
 rpc(method,params={},timeout=45000){if(!this.proc||this.proc.exitCode!==null||this.proc.signalCode!==null)return Promise.reject(new Error('Codex 連線尚未就緒'));return new Promise((resolve,reject)=>{const id=++this.seq;const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`${method} 等待逾時`))},timeout);this.pending.set(id,{resolve,reject,timer});this.proc.stdin.write(JSON.stringify({id,method,params})+'\n');});}
 fail(e,notify=true){for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(e)}this.pending.clear();if(notify)this.emit('disconnected',e.message)}
 reloadAccount(){
  if(this.reloading)return this.reloading;
  const reload=(async()=>{
   const old=this.proc;this.proc=null;this.fail(new Error('Codex 正在更新登入狀態'),false);
   if(old&&old.exitCode===null&&old.signalCode===null){
    const stopped=once(old,'exit');old.kill('SIGTERM');
    let timer;await Promise.race([stopped,new Promise(resolve=>{timer=setTimeout(resolve,2000)})]);clearTimeout(timer);
    if(old.exitCode===null&&old.signalCode===null){old.kill('SIGKILL');await stopped}
   }
   await this.start();
   const result=await this.rpc('account/read',{refreshToken:true});
   return {account:result.account?{type:result.account.type,plan:result.account.planType}:null,accountReady:result.account?.type==='chatgpt'};
  })();this.reloading=reload;
  void reload.finally(()=>{if(this.reloading===reload)this.reloading=null}).catch(()=>{});
  return reload;
 }
 close(){this.closed=true;const proc=this.proc;this.proc=null;this.fail(new Error('Codex 連線已關閉'),false);proc?.kill();}
}
