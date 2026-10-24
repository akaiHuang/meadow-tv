package com.akai.livingtv;

import android.content.Context;import android.graphics.Bitmap;import android.os.Looper;
import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ArtworkBudgetTest {
    @Test public void sameCachedTestImageUsesLessMemoryAsThumbnailWithoutShrinkingHero()throws Exception{
        Context c=RuntimeEnvironment.getApplication();FeedRepository images=new FeedRepository(c);
        try{
            String url=TestArtworkFixtures.installImage(c);
            AtomicReference<Bitmap> full=new AtomicReference<>(),thumb=new AtomicReference<>();images.image(url,full::set);await(full);
            images.thumbnail(url,thumb::set);await(thumb);
            assertEquals(1280,full.get().getWidth());assertEquals(720,full.get().getHeight());assertEquals(640,thumb.get().getWidth());assertEquals(360,thumb.get().getHeight());
            assertTrue("small cards should not retain hero-sized pixels",thumb.get().getAllocationByteCount()<full.get().getAllocationByteCount());
            AtomicReference<Bitmap> again=new AtomicReference<>();images.thumbnail(url,again::set);assertSame(thumb.get(),again.get());
        }finally{images.close();}
    }
    @Test public void overlappingHeroRequestsShareOneGradedBitmap()throws Exception{
        Context c=RuntimeEnvironment.getApplication();FeedRepository images=new FeedRepository(c);
        try{
            String url=TestArtworkFixtures.installImage(c);AtomicReference<Bitmap> first=new AtomicReference<>(),second=new AtomicReference<>();
            images.background(url,first::set);images.background(url,second::set);await(first);await(second);
            assertSame("one hero request should not grade two separate copies",first.get(),second.get());
            assertEquals(960,first.get().getWidth());assertEquals(540,first.get().getHeight());
            AtomicReference<Bitmap> cached=new AtomicReference<>();images.background(url,cached::set);assertSame(first.get(),cached.get());
        }finally{images.close();}
    }
    @Test public void selectedHeroRunsBeforeThumbnailWorkThatHasNotStarted()throws Exception{
        Context c=RuntimeEnvironment.getApplication();FeedRepository images=new FeedRepository(c);
        ThreadPoolExecutor workers=ReflectionHelpers.getField(images,"images");
        CountDownLatch entered=new CountDownLatch(2),releaseFirst=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
        try{
            // Occupy both workers so scheduling is deterministic, without a slow
            // network or timing assertions. Only one worker is then released.
            workers.execute(()->hold(entered,releaseFirst));workers.execute(()->hold(entered,releaseSecond));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            String url=TestArtworkFixtures.installImage(c);AtomicReference<String> firstReady=new AtomicReference<>();
            images.thumbnail(url,b->{assertNotNull(b);firstReady.compareAndSet(null,"thumbnail");});
            images.background(url,b->{assertNotNull(b);firstReady.compareAndSet(null,"hero");});
            releaseFirst.countDown();await(firstReady);
            assertEquals("new selection must not wait behind queued cover work","hero",firstReady.get());
        }finally{releaseFirst.countDown();releaseSecond.countDown();images.close();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void closeDiscardsPendingArtworkAndDoesNotDeliverToOldScreen()throws Exception{
        Context c=RuntimeEnvironment.getApplication();FeedRepository images=new FeedRepository(c);
        ThreadPoolExecutor workers=ReflectionHelpers.getField(images,"images");
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger delivered=new AtomicInteger();
        try{
            workers.execute(()->hold(entered,release));workers.execute(()->hold(entered,release));assertTrue(entered.await(5,TimeUnit.SECONDS));
            String url=TestArtworkFixtures.installImage(c);images.background(url,b->delivered.incrementAndGet());images.thumbnail(url,b->delivered.incrementAndGet());
            images.close();release.countDown();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle();
            images.background(url,b->delivered.incrementAndGet());assertEquals(0,delivered.get());assertTrue(workers.getQueue().isEmpty());
        }finally{release.countDown();images.close();}
    }
    private static void hold(CountDownLatch entered,CountDownLatch release){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private void await(AtomicReference<?> value)throws Exception{long until=System.currentTimeMillis()+10000;while(value.get()==null&&System.currentTimeMillis()<until){Thread.sleep(30);shadowOf(Looper.getMainLooper()).idle();}assertNotNull("artwork callback delivered",value.get());}
}
