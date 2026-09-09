package io.jterm.demo;

import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct test of BitmapFontDemo's package-private {@code renderDemo} seam:
 * the three real fonts render visible text into the buffer with no terminal.
 */
class BitmapFontDemoUnitTest {

    @org.junit.jupiter.api.Test
    void renderDemoProducesInkWithAllFonts() throws Exception {
        var font16 = io.jterm.bitmapfont.BitmapFont.loadResource("bitmapfonts/block16.jfont");
        var font8 = io.jterm.bitmapfont.BitmapFont.loadResource("bitmapfonts/block8.jfont");
        var banner = io.jterm.bitmapfont.BitmapFont.loadResource("bitmapfonts/banner.jfont");

        var buf = new ScreenBuffer(new TerminalSize(80, 24));
        BitmapFontDemo.renderDemo(buf, font16, font8, banner);

        int ink = 0;
        for (int r = 0; r < 24; r++)
            for (int c = 0; c < 80; c++)
                if (buf.getCell(c, r).character().charAt(0) != ' ') ink++;
        org.junit.jupiter.api.Assertions.assertTrue(ink > 100,
            "demo renders substantial content, got " + ink + " ink cells");
    }
}
