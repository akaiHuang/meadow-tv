import test from 'node:test';
import assert from 'node:assert/strict';
import {isolatedMcpArguments} from './codex-client.mjs';
test('isolates all configured named MCP servers without reading credential values',()=>{
 const text=`[mcp_servers.node_repl]\nenv={TOKEN="secret"}\n[mcp_servers."kid-minecraft"]\ncommand="ignored"\n[mcp_servers.node_repl.env]\nTOKEN="secret"\n[mcp_servers.'tool.with.dots']\n`;
 assert.deepEqual(isolatedMcpArguments(text),['-c','mcp_servers={"kid-minecraft"={enabled=false},"node_repl"={enabled=false},"tool.with.dots"={enabled=false}}']);
});
test('only configuration table names influence isolation arguments',()=>{
 assert.deepEqual(isolatedMcpArguments('[other]\ntext="[mcp_servers.fake]"\n# [mcp_servers.comment]\n[mcp_servers.safe]\ncommand="$(bad)"'),['-c','mcp_servers={"safe"={enabled=false}}']);
});
test('refuses inline configuration instead of silently inheriting host tools',()=>{
 assert.throws(()=>isolatedMcpArguments('mcp_servers = {other={command="x"}}'),/cannot be isolated/);
});
