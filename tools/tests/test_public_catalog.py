import datetime,importlib.util,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('catalog',ROOT/'tools/refresh-public-catalog.py');catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)
NOW=catalog.NOW
ART='https://is1-ssl.mzstatic.com/image/test.jpg'

class PublicCatalogTests(unittest.TestCase):
    def item(self,provider,number):
        official={'apple':f'https://tv.apple.com/tw/show/test/umc.{number}','netflix':f'https://www.netflix.com/tw/title/{80000000+number}','hbo':f'https://www.hbomax.com/tw/zh/show/test-{number}'}[provider]
        return catalog.entry(provider,f'測試 {number}',ART,ART,official,catalog.SOURCES[provider][0],'台灣官方測試',NOW,(NOW-datetime.timedelta(days=1)).isoformat() if provider=='hbo' else '',(NOW+datetime.timedelta(days=1)).isoformat() if provider=='hbo' else '')
    def test_netflix_requires_actual_tw_country_and_uses_localized_cards(self):
        card='<h2>亞洲節目</h2><a href="https://www.netflix.com/tw/title/80000001"><span style="background-image:url(https://occ-0-1.nflxso.net/test.jpg)"></span><p>繁體中文節目</p></a>'
        text='<html lang="zh-TW"><script>"requestCountry":{"id":"TW"}</script>'+card+'</html>'
        result=catalog.parse('netflix',text);self.assertEqual(['繁體中文節目'],[i['title'] for i in result]);self.assertEqual('',result[0]['date']);self.assertEqual('亞洲節目',result[0]['collection'])
        with self.assertRaises(ValueError):catalog.parse('netflix',text.replace('"id":"TW"','"id":"US"'))
        with self.assertRaises(ValueError):catalog.parse('netflix',text.replace('lang="zh-TW"','lang="en-US"'))
    def test_apple_flat_artwork_is_supported_but_rank_composite_is_not_a_cover(self):
        item={'type':'Show','title':'新上架測試','segue':{'url':'https://tv.apple.com/tw/show/test/umc.a?ctx_brand=test'},'artwork':{'template':'https://is1-ssl.mzstatic.com/image/{w}x{h}.{f}','width':2000,'height':3000}}
        data=[{'header':{'title':'新上架'},'items':[item]}];text='<script type="application/json" id="serialized-server-data">'+json.dumps(data)+'</script>'
        result=catalog.parse('apple',text);self.assertEqual(1,len(result));self.assertIn('384x576.jpg',result[0]['poster']);self.assertNotIn('?',result[0]['official'])
        item['artwork'].update(width=1680,height=3636);text='<script id="serialized-server-data">'+json.dumps(data)+'</script>';self.assertEqual([],catalog.parse('apple',text))
    def hbo(self,status='published',country='TW',start=None,end=None):
        source=catalog.SOURCES['hbo'][0];item={'status':status,'title':{'full':'台灣影集'},'offeringDates':{'startDate':(start or NOW-datetime.timedelta(days=1)).isoformat(),'endDate':(end or NOW+datetime.timedelta(days=1)).isoformat()},'imageUrlLink':'/tw/zh/show/title-a','images':{'cover-artwork-horizontal':'https://beam-images.warnermediacdn.com/title.jpg?partner=beamcom'}}
        page={'componentType':'ContentTray','mappings':[{'property':'collection','value':'new-items'},{'property':'ovTitle','value':'new-label'}]}
        data={'props':{'pageProps':{'userCountry':country,'isUserOutOfRegion':False,'isPublished':True,'canonicalURL':source,'pageData':json.dumps(page),'mappedData':{'new-items':{'items':[item]},'new-label':'最近新增的影集'}}}}
        return '<script id="__NEXT_DATA__">'+json.dumps(data)+'</script>'
    def test_hbo_follows_mapping_and_checks_country_publication_and_offering(self):
        result=catalog.parse('hbo',self.hbo());self.assertEqual(1,len(result));self.assertTrue(result[0]['wide']);self.assertIn('w=640',result[0]['poster']);self.assertEqual('offering_active_tw',result[0]['availability']);self.assertEqual('',result[0]['date'])
        self.assertEqual([],catalog.parse('hbo',self.hbo(status='unpublished')));self.assertEqual([],catalog.parse('hbo',self.hbo(start=NOW+datetime.timedelta(days=1))))
        self.assertEqual([],catalog.parse('hbo',self.hbo(end=NOW-datetime.timedelta(seconds=1))))
        with self.assertRaises(ValueError):catalog.parse('hbo',self.hbo(country='US'))
    def test_partial_provider_does_not_erase_other_tw_cache(self):
        old=[self.item(p,n) for p in ['apple','netflix','hbo'] for n in range(20)];fresh=[self.item('netflix',100+n) for n in range(24)]+[self.item('hbo',999)]
        result=catalog.merge_verified(fresh,old);counts={p:sum(i['provider']==p for i in result) for p in catalog.SOURCES};self.assertEqual({'apple':20,'netflix':24,'hbo':21},counts)
        self.assertEqual(60,len(catalog.merge_verified([],old)))
    def test_legacy_us_and_expired_hbo_are_not_retained(self):
        old=self.item('netflix',1);old.pop('region');self.assertFalse(catalog.verified(old));self.assertEqual([],catalog.merge_verified([],[old]))
        item=self.item('hbo',1);item['availabilityEnds']=(NOW-datetime.timedelta(seconds=1)).isoformat();self.assertFalse(catalog.verified(item))
        item=self.item('apple',1);item['source']='https://tv.apple.com/twisted';self.assertFalse(catalog.verified(item))
        item=self.item('netflix',1);item['source']='https://user@www.netflix.com/tw/browse/genre/83';self.assertFalse(catalog.verified(item))
    def test_bundled_snapshot_has_verified_tw_source_and_sufficient_items(self):
        data=json.loads((ROOT/'app/src/main/assets/official-catalog.json').read_text());self.assertEqual(2,data['schemaVersion'])
        for provider in catalog.SOURCES:
            items=[i for i in data['items'] if i['provider']==provider];self.assertGreaterEqual(len(items),16);self.assertLessEqual(len(items),24)
            for item in items:self.assertTrue(catalog.verified(item),item['title']);self.assertNotIn('tvmaze',item['source']);self.assertNotIn('tudum',item['source'])

if __name__=='__main__':unittest.main()
