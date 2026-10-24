package com.akai.livingtv;
import org.junit.Test;
import static org.junit.Assert.*;
public class ContentPolicyTest {
    @Test public void classifiesOnlyRequestedProviders(){assertEquals("hbo",ContentPolicy.provider("HBO Max"));assertEquals("apple",ContentPolicy.provider("Apple TV"));assertEquals("",ContentPolicy.provider("Netflix Ads Partner"));assertEquals("",ContentPolicy.provider("Prime Video"));}
    @Test public void rejectsLookalikeAndCredentialUrls(){assertFalse(ContentPolicy.officialLink("netflix","https://www.netflix.com.evil.example/title/1"));assertFalse(ContentPolicy.officialLink("netflix","https://user:password@www.netflix.com/title/1"));assertFalse(ContentPolicy.officialLink("netflix","http://www.netflix.com/title/1"));assertFalse(ContentPolicy.officialLink("hbo","https://www.netflix.com/title/1"));}
    @Test public void opensOnlyMatchingOfficialSites(){assertTrue(ContentPolicy.officialLink("netflix","https://www.netflix.com/title/80994082"));assertTrue(ContentPolicy.officialLink("apple","https://tv.apple.com/show/a/umc.x"));assertTrue(ContentPolicy.officialLink("hbo","https://play.hbomax.com/show/123"));}
    @Test public void artworkMustComeFromTheDocumentedHost(){assertTrue(ContentPolicy.artworkLink("https://static.tvmaze.com/uploads/images/1.jpg"));assertFalse(ContentPolicy.artworkLink("https://static.tvmaze.com.evil.example/1.jpg"));assertFalse(ContentPolicy.artworkLink("file:///data/data/private"));}
}
