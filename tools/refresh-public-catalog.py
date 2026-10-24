#!/usr/bin/env python3
"""Taiwan public catalog snapshots. No login, US schedule fallback, or entitlement claims."""
import argparse,concurrent.futures,datetime,hashlib,html,json,re,sys,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SOURCES={'apple':['https://tv.apple.com/tw'],'netflix':['https://www.netflix.com/tw/browse/genre/83'],'hbo':['https://www.hbomax.com/tw/zh/series','https://www.hbomax.com/tw/zh/movies']}
LOCAL={'apple':['apple-tw-live.html'],'netflix':['netflix-tw-browse.html'],'hbo':['hbo-tw-series-20261025.html','hbo-tw-movies-20261025.html']}
NOW=datetime.datetime.now(datetime.timezone.utc)

def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Accept-Language':'zh-TW,zh;q=0.9'}),timeout=15) as response:
        if urllib.parse.urlparse(response.geturl()).scheme!='https':raise ValueError('HTTPS required')
        data=response.read(8*1024*1024+1)
        if len(data)>8*1024*1024:raise ValueError('oversized public page')
        return data.decode('utf-8')
def walk(value):
    if isinstance(value,dict):
        yield value
        for child in value.values():yield from walk(child)
    elif isinstance(value,list):
        for child in value:yield from walk(child)
def canonical(url):
    p=urllib.parse.urlparse(url)
    return urllib.parse.urlunparse((p.scheme,p.netloc,p.path.rstrip('/'),'','',''))
def parsed_url(value):
    try:
        if not isinstance(value,str):return None
        parsed=urllib.parse.urlparse(value);parsed.port
        return parsed
    except ValueError:return None
def image_url(url):
    p=parsed_url(url)
    if p is None:return False
    host=p.hostname or ''
    return p.scheme=='https' and not p.username and p.port in (None,443) and (host.endswith('.mzstatic.com') or host.endswith('.nflxso.net') or host.endswith('.nflximg.net') or host=='beam-images.warnermediacdn.com')
def official_tw(provider,url):
    p=parsed_url(url)
    if p is None:return False
    if p.scheme!='https' or p.username or p.port not in (None,443):return False
    if provider=='netflix':return p.hostname=='www.netflix.com' and bool(re.fullmatch(r'/tw/title/[0-9]+',p.path.rstrip('/')))
    if provider=='apple':return p.hostname=='tv.apple.com' and bool(re.match(r'^/tw/(show|movie)/',p.path))
    return provider=='hbo' and p.hostname=='www.hbomax.com' and bool(re.fullmatch(r'/tw/zh/(show|movie)/[A-Za-z0-9-]+',p.path.rstrip('/')))
def iso(value):
    try:return datetime.datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(datetime.timezone.utc)
    except (ValueError,TypeError,AttributeError):return None

def verified(item,now=NOW):
    if item.get('provider') not in SOURCES or item.get('region')!='TW' or item.get('locale')!='zh-TW' or not item.get('verifiedAt'):return False
    at=item['verifiedAt']
    if not isinstance(at,int) or isinstance(at,bool) or at<=0 or at>int(now.timestamp()*1000)+300000:return False
    provider=item['provider'];source=item.get('source','');p=parsed_url(source)
    if p is None:return False
    expected={'apple':('tv.apple.com','/tw'),'netflix':('www.netflix.com','/tw/'),'hbo':('www.hbomax.com','/tw/zh')}
    host,path=expected[provider]
    if p.scheme!='https' or p.username or p.port not in (None,443) or p.hostname!=host or not (p.path==path.rstrip('/') or p.path.startswith(path.rstrip('/')+'/')) or not official_tw(provider,item.get('official','')):return False
    if not item.get('title') or not image_url(item.get('poster')) or not image_url(item.get('backdrop')):return False
    if provider=='hbo':
        start,end=iso(item.get('availabilityStarts')),iso(item.get('availabilityEnds'))
        return item.get('availability')=='offering_active_tw' and start is not None and end is not None and start<=now<end
    return item.get('availability')=='listed_tw'

def entry(provider,title,poster,backdrop,official,source,collection,observed=NOW,starts='',ends='',wide=False):
    official=canonical(official)
    return dict(id=-int(hashlib.sha256((provider+'|'+official).encode()).hexdigest()[:7],16),title=title,provider=provider,date='',poster=poster,backdrop=backdrop or poster,official=official,summary='台灣官方公開目錄收錄；實際播放、訂閱及租購條件依平台為準。',source=source,wide=wide,region='TW',locale='zh-TW',availability='offering_active_tw' if provider=='hbo' else 'listed_tw',collection=collection,verifiedAt=int(observed.timestamp()*1000),availabilityStarts=starts,availabilityEnds=ends)

def unique(items,limit=24):
    seen=set();result=[]
    for item in items:
        if not verified(item) or item['official'] in seen:continue
        seen.add(item['official']);result.append(item)
        if len(result)>=limit:break
    return result

class NetflixCards(HTMLParser):
    def __init__(self):super().__init__();self.heading='台灣官方節目精選';self.header=None;self.card=None;self.cards=[];self.in_p=False
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if tag=='h2':self.header=[]
        if tag=='a' and official_tw('netflix',attrs.get('href','')):self.card={'url':attrs['href'],'title':[],'image':'','collection':self.heading}
        if self.card is not None:
            if tag=='p':self.in_p=True
            style=attrs.get('style','');match=re.search(r'background-image\s*:\s*url\((https://[^)]+)\)',style)
            if match and image_url(match.group(1)):self.card['image']=html.unescape(match.group(1))
    def handle_data(self,data):
        if self.header is not None:self.header.append(data)
        if self.card is not None and self.in_p:self.card['title'].append(data)
    def handle_endtag(self,tag):
        if tag=='h2' and self.header is not None:self.heading=''.join(self.header).strip();self.header=None
        if tag=='p':self.in_p=False
        if tag=='a' and self.card is not None:
            card=self.card;card['title']=''.join(card['title']).strip()
            if card['title'] and card['image']:self.cards.append(card)
            self.card=None;self.in_p=False

def parse_netflix(text,source,observed):
    if not re.search(r'<html[^>]+lang=[\"\']zh-TW[\"\']',text,re.I):raise ValueError('Netflix locale is not Taiwan Traditional Chinese')
    country=re.search(r'"requestCountry"\s*:\s*\{[^{}]*"id"\s*:\s*"([^"]+)"',text)
    if not country or country.group(1)!='TW':raise ValueError('Netflix request country is not Taiwan')
    parser=NetflixCards();parser.feed(text);groups={}
    for card in parser.cards:groups.setdefault(card['collection'],[]).append(card)
    # A mixed official selection, not an invented newest/top-ten ranking.
    items=[]
    for n in range(20):
        for cards in groups.values():
            if n<len(cards):
                c=cards[n];items.append(entry('netflix',c['title'],c['image'],c['image'],c['url'],source,c['collection'],observed,wide=True))
    return unique(items)

def apple_art(art,width,height):
    template=art.get('template','') if isinstance(art,dict) else ''
    for old,new in {'{w}':str(width),'{h}':str(height),'{c}':'','{f}':'jpg'}.items():template=template.replace(old,new)
    return template if image_url(template) else ''
def parse_apple(text,source,observed):
    match=re.search(r'<script[^>]+id="serialized-server-data"[^>]*>(.*?)</script>',text,re.S)
    if not match:raise ValueError('Apple public data missing')
    data=json.loads(match.group(1));hero={};posters={};shelves=[]
    for obj in walk(data):
        if obj.get('type') not in ('Show','Movie') or not obj.get('title'):continue
        link=obj.get('segue',{}).get('url','')
        if not official_tw('apple',link):continue
        link=canonical(link);art=obj.get('artwork') or {}
        if isinstance(art.get('wide'),dict):hero[link]=apple_art(art['wide'],1280,720)
        if isinstance(art.get('tall'),dict):posters.setdefault(link,apple_art(art['tall'],384,576))
        # Ranking-number composite sprites are 1680x3636; do not turn them into covers.
        if art.get('template') and int(art.get('height',0))<=int(art.get('width',1))*1.6:posters[link]=apple_art(art,384,576)
    for label in ['新上架','10 大熱門電視節目']:
        for obj in walk(data):
            if not isinstance(obj.get('header'),dict) or obj['header'].get('title')!=label:continue
            for item in obj.get('items',[]):
                if item.get('type') in ('Show','Movie'):
                    link=canonical(item.get('segue',{}).get('url',''));poster=posters.get(link,'')
                    if official_tw('apple',link) and poster:shelves.append(entry('apple',item['title'],poster,hero.get(link,poster),link,source,label,observed))
    return unique(shelves)

def parse_hbo(text,source,observed):
    data=json.loads(re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>',text,re.S).group(1))['props']['pageProps']
    if data.get('userCountry')!='TW' or data.get('isUserOutOfRegion') is not False or data.get('isPublished') is not True or canonical(data.get('canonicalURL',''))!=canonical(source):raise ValueError('HBO page is not an active Taiwan page')
    page=json.loads(data['pageData']) if isinstance(data.get('pageData'),str) else data.get('pageData',{});mapped=data['mappedData'];rows=[]
    for tray in walk(page):
        if tray.get('componentType')!='ContentTray':continue
        mappings={m['property']:m.get('value') for m in tray.get('mappings',[]) if isinstance(m,dict) and m.get('property')}
        label=mapped.get(mappings.get('ovTitle'),'')
        if label not in ('最近新增的影集','最近新增的電影'):continue
        collection=mapped.get(mappings.get('collection'),{})
        for obj in collection.get('items',[]):
            dates=obj.get('offeringDates') or {};start,end=iso(dates.get('startDate')),iso(dates.get('endDate'))
            if obj.get('status')!='published' or start is None or end is None or not start<=observed<end:continue
            link='https://www.hbomax.com'+obj.get('imageUrlLink','');images=obj.get('images') or {};art=images.get('cover-artwork-horizontal','');title=(obj.get('title') or {}).get('full') or (obj.get('title') or {}).get('short')
            if not title or not official_tw('hbo',link) or not image_url(art):continue
            separator='&' if '?' in art else '?'
            rows.append(entry('hbo',title,art+separator+'w=640',art+separator+'w=1280',link,source,label,observed,dates['startDate'],dates['endDate'],True))
    rows.sort(key=lambda item:item['availabilityStarts'],reverse=True)
    return unique(rows,12)

def parse(provider,text,source=None,observed=NOW):
    source=source or SOURCES[provider][0]
    return {'apple':parse_apple,'netflix':parse_netflix,'hbo':parse_hbo}[provider](text,source,observed)

def merge_verified(fresh,old):
    result=[]
    for provider in SOURCES:
        new=[item for item in fresh if item.get('provider')==provider and verified(item)]
        previous=[item for item in old if item.get('provider')==provider and verified(item)]
        # Retain the last-good TW selection if one page is blocked, incomplete, or changes schema.
        result.extend(unique(new if len(new)>=16 else new+previous))
    return result

def enrich_apple(items,local=False):
    def enrich(item):
        if item['provider']!='apple' or item['backdrop']!=item['poster']:return item
        identifier=item['official'].split('/')[-1];cached=ROOT/'artifacts/private'/('apple-detail-'+identifier+'.html')
        try:
            text=cached.read_text() if cached.exists() else '' if local else fetch(item['official'])
            if text and not cached.exists():cached.parent.mkdir(exist_ok=True,parents=True);cached.write_text(text);cached.chmod(0o600)
            match=re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"',text)
            if match and image_url(html.unescape(match.group(1))):item=dict(item,backdrop=html.unescape(match.group(1)))
        except Exception:pass
        return item
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:return list(pool.map(enrich,items))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('output',nargs='?',type=Path,default=ROOT/'app/src/main/assets/official-catalog.json');parser.add_argument('--local',action='store_true');args=parser.parse_args()
    def run(provider):
        items=[];errors=[]
        for source,name in zip(SOURCES[provider],LOCAL[provider]):
            try:text=(ROOT/'artifacts/private'/name).read_text() if args.local else fetch(source);items.extend(parse(provider,text,source))
            except Exception as error:errors.append(type(error).__name__)
        return provider,unique(items),errors
    groups=list(concurrent.futures.ThreadPoolExecutor(max_workers=3).map(run,SOURCES));fresh=[item for _,items,_ in groups for item in items];old=[]
    if args.output.exists():
        try:old=json.loads(args.output.read_text()).get('items',[])
        except (ValueError,OSError):pass
    items=enrich_apple(merge_verified(fresh,old),args.local)
    if not items:raise SystemExit('No verified Taiwan catalog; previous snapshot retained unchanged.')
    statuses={p:{'status':'ok' if len(found)>=16 else 'retained_or_partial','count':sum(i['provider']==p for i in items),'urls':SOURCES[p],'errors':errors} for p,found,errors in groups}
    data={'schemaVersion':2,'region':'TW','locale':'zh-TW','fetchedAt':max(i['verifiedAt'] for i in items),'generatedAt':int(NOW.timestamp()*1000),'sources':statuses,'items':items}
    args.output.parent.mkdir(parents=True,exist_ok=True);temporary=args.output.with_suffix('.tmp');temporary.write_text(json.dumps(data,ensure_ascii=False));temporary.replace(args.output)
    print(json.dumps(statuses,ensure_ascii=False))
if __name__=='__main__':main()
