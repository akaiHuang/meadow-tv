#!/usr/bin/env python3
"""Bundle the exact official artwork used by a verified TW snapshot; never substitute another title."""
import concurrent.futures,hashlib,importlib.util,json,sys,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('public_catalog',ROOT/'tools/refresh-public-catalog.py');catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)

def main():
    document=json.loads((ROOT/'app/src/main/assets/official-catalog.json').read_text());items=document['items']
    if not all(catalog.verified(item) for item in items):raise SystemExit('Unverified catalog refused')
    targets=sorted({item[key] for item in items for key in ('poster','backdrop')});folder=ROOT/'app/src/main/assets/art';folder.mkdir(exist_ok=True,parents=True)
    def download(url):
        name=hashlib.sha256(url.encode()).hexdigest();target=folder/name;result={'url':url,'asset':name}
        try:
            data=target.read_bytes() if target.exists() else None
            if data is None:
                with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=15) as response:
                    if not response.headers.get('Content-Type','').startswith('image/'):raise ValueError('Expected official image')
                    data=response.read(4*1024*1024+1)
                if len(data)>4*1024*1024:raise ValueError('Artwork exceeds Android decoder budget')
                temporary=target.with_suffix('.tmp');temporary.write_bytes(data);temporary.replace(target)
            if not (data.startswith(b'\xff\xd8') or data.startswith(b'\x89PNG') or data.startswith(b'RIFF')):raise ValueError('Unsupported image signature')
            result.update(ok=True,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
        except Exception as error:result.update(ok=False,error=type(error).__name__+': '+str(error))
        return result
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(download,targets))
    report=ROOT/'artifacts/catalog-0.8.16/artwork-verification.json';report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps({'items':len(items),'urls':len(targets),'results':results},ensure_ascii=False,indent=2))
    failed=[record for record in results if not record['ok']];print(json.dumps({'items':len(items),'artwork':len(results),'verified':len(results)-len(failed),'failed':len(failed),'bytes':sum(record.get('bytes',0) for record in results),'report':str(report)}))
    if failed:print(json.dumps(failed,ensure_ascii=False));return 1
    return 0
if __name__=='__main__':sys.exit(main())
