package io.jterm.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless smoke tests for the demo applications.
 *
 * <p>Under Surefire the JVM has no console, so {@code new AnsiTerminal()}
 * skips stty and uses a fixed 80×24 size — exactly the headless conditions
 * these tests need. Each demo's {@code main} is invoked with
 * {@code System.in} pre-loaded with {@code 'q'}, which the demos' quit
 * handlers consume, so {@code main} returns after rendering one frame. The
 * smoke asserts: (a) main returns within the timeout, and (b) the demo
 * produced non-trivial ANSI output (it actually drew its UI).
 *
 * <p>All eleven demos pass. {@code AnimatedBordersDemo} originally hung
 * headlessly ({@code 'q'} was marked unconsumed and the GUI framework does
 * not quit on plain characters); its main loop now handles {@code 'q'}
 * directly, so it is included like the rest.
 */
class DemoSmokeTest {

    /**
     * Run a demo's main with 'q' pre-fed to System.in and output captured.
     *
     * @return number of ANSI bytes the demo rendered
     */
    private static int smoke(Class<?> demo) throws Exception {
        InputStream realIn = System.in;
        PrintStream realOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream("q".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(captured));
        try {
            var main = demo.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setIn(realIn);
            System.setOut(realOut);
        }
        return captured.size();
    }

    private static void assertRenders(Class<?> demo) throws Exception {
        int bytes = smoke(demo);
        assertTrue(bytes > 500, demo.getSimpleName() + " rendered only " + bytes + " bytes — expected a full frame");
    }

    @Test
    @Timeout(15)
    void helloWorldRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.HelloWorld.class);
    }

    @Test
    @Timeout(15)
    void basicWidgetsRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.BasicWidgetsDemo.class);
    }

    @Test
    @Timeout(15)
    void bordersRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.BordersDemo.class);
    }

    @Test
    @Timeout(15)
    void menuRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.MenuDemo.class);
    }

    @Test
    @Timeout(15)
    void themeRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.ThemeDemo.class);
    }

    @Test
    @Timeout(15)
    void dashboardRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.DashboardDemo.class);
    }

    @Test
    @Timeout(15)
    void textEditorRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.TextEditorDemo.class);
    }

    @Test
    @Timeout(15)
    void chartRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.ChartDemo.class);
    }

    @Test
    @Timeout(15)
    void spriteRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.SpriteDemo.class);
    }

    @Test
    @Timeout(15)
    void bitmapFontRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.BitmapFontDemo.class);
    }

    @Test
    @Timeout(15)
    void animatedBordersRendersAndQuits() throws Exception {
        assertRenders(io.jterm.demo.AnimatedBordersDemo.class);
    }
}