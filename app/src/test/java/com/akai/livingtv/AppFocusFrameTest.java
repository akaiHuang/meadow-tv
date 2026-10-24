package com.akai.livingtv;

import android.graphics.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppFocusFrameTest {
    @Test public void focusLightSitsOutsideTheArtworkAndLeavesItsInteriorUntouched(){
        AppFocusFrame frame=new AppFocusFrame();frame.setBounds(20,20,284,169);Bitmap image=Bitmap.createBitmap(304,189,Bitmap.Config.ARGB_8888);frame.draw(new Canvas(image));
        assertEquals("The frame must never tint the wordmark",0,Color.alpha(image.getPixel(152,94)));
        int outerPeak=0;for(int x=8;x<20;x++)outerPeak=Math.max(outerPeak,Color.alpha(image.getPixel(x,94)));
        assertTrue("The visible white line belongs outside the card",outerPeak>200);
        assertTrue("Inside the artwork there may only be a faint halo, not an inset gray frame",Color.alpha(image.getPixel(22,94))<96);
        assertEquals(0,Color.alpha(image.getPixel(27,94)));assertTrue(frame.getDirtyBounds().left<frame.getBounds().left);
        frame.setAlpha(0);image.eraseColor(Color.TRANSPARENT);frame.draw(new Canvas(image));int[] pixels=new int[304*189];image.getPixels(pixels,0,304,0,0,304,189);for(int pixel:pixels)assertEquals("A hidden frame must not leave an outer halo",0,Color.alpha(pixel));
    }
}
