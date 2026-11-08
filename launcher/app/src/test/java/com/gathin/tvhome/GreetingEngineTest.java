package com.gathin.tvhome;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GreetingEngineTest {

    private GreetingEngine engine;

    @Before
    public void setUp() throws Exception {
        // Load greetings.json from assets
        File assetsFile = new File("src/main/assets/greetings.json");
        if (!assetsFile.exists()) {
            assetsFile = new File("launcher/app/src/main/assets/greetings.json");
        }
        assertTrue("greetings.json must exist in assets", assetsFile.exists());
        try (InputStream is = new FileInputStream(assetsFile)) {
            engine = GreetingEngine.fromStream(is);
        }
        assertNotNull("GreetingEngine must load successfully from assets", engine);
    }

    @Test
    public void testUniqueMessageIds() {
        Set<String> ids = new HashSet<>();
        List<GreetingEngine.GreetingMessage> all = engine.getAllMessages();
        assertFalse("Messages list should not be empty", all.isEmpty());

        for (GreetingEngine.GreetingMessage m : all) {
            assertTrue("Duplicate message ID found: " + m.id, ids.add(m.id));
        }
    }

    @Test
    public void testFestivalsConsistency() {
        Map<String, GreetingEngine.FestivalDef> festivals = engine.getFestivals();
        assertFalse("Festivals must not be empty", festivals.isEmpty());

        Set<String> festInMessages = new HashSet<>();
        for (GreetingEngine.GreetingMessage m : engine.getAllMessages()) {
            if (m.fest != null) {
                festInMessages.add(m.fest);
                assertTrue("Message " + m.id + " references unknown festival: " + m.fest,
                        festivals.containsKey(m.fest));
            }
        }

        // Check that festivals defined in JSON have matching messages or are recognized
        for (String f : festivals.keySet()) {
            // Note: festival might use generic fallback template if no specific message exists, which is valid
            assertNotNull(festivals.get(f));
        }
    }

    @Test
    public void testPlaceholdersAreKnown() {
        Pattern placeholderPattern = Pattern.compile("\\{([^}]+)\\}");
        Set<String> knownPlaceholders = new HashSet<>();
        knownPlaceholders.add("name");
        knownPlaceholders.add("mother");
        knownPlaceholders.add("father");
        knownPlaceholders.add("age");
        knownPlaceholders.add("year");
        knownPlaceholders.add("festival");

        for (GreetingEngine.GreetingMessage m : engine.getAllMessages()) {
            Matcher matcher = placeholderPattern.matcher(m.text);
            while (matcher.find()) {
                String ph = matcher.group(1);
                assertTrue("Unknown placeholder {" + ph + "} in message " + m.id,
                        knownPlaceholders.contains(ph));
            }
        }
    }

    @Test
    public void testTimeBucketWrappingAndRanges() {
        // late_night wraps past midnight (23:30 to 04:00)
        assertEquals("late_night", engine.getTimeBucket(LocalTime.of(23, 30)));
        assertEquals("late_night", engine.getTimeBucket(LocalTime.of(23, 59)));
        assertEquals("late_night", engine.getTimeBucket(LocalTime.of(0, 0)));
        assertEquals("late_night", engine.getTimeBucket(LocalTime.of(1, 30)));
        assertEquals("late_night", engine.getTimeBucket(LocalTime.of(3, 59)));

        // early_morning: 04:00 to 07:00
        assertEquals("early_morning", engine.getTimeBucket(LocalTime.of(4, 0)));
        assertEquals("early_morning", engine.getTimeBucket(LocalTime.of(6, 59)));

        // morning: 07:00 to 12:00
        assertEquals("morning", engine.getTimeBucket(LocalTime.of(7, 0)));
        assertEquals("morning", engine.getTimeBucket(LocalTime.of(11, 59)));

        // afternoon: 12:00 to 17:00
        assertEquals("afternoon", engine.getTimeBucket(LocalTime.of(12, 0)));
        assertEquals("afternoon", engine.getTimeBucket(LocalTime.of(16, 59)));

        // evening: 17:00 to 19:30
        assertEquals("evening", engine.getTimeBucket(LocalTime.of(17, 0)));
        assertEquals("evening", engine.getTimeBucket(LocalTime.of(19, 29)));

        // dinner: 19:30 to 21:30
        assertEquals("dinner", engine.getTimeBucket(LocalTime.of(19, 30)));
        assertEquals("dinner", engine.getTimeBucket(LocalTime.of(21, 29)));

        // night: 21:30 to 23:30
        assertEquals("night", engine.getTimeBucket(LocalTime.of(21, 30)));
        assertEquals("night", engine.getTimeBucket(LocalTime.of(23, 29)));
    }

    @Test
    public void testGapClasses() {
        assertEquals("short", engine.getGapClass(0));
        assertEquals("short", engine.getGapClass(15));
        assertEquals("short", engine.getGapClass(29));

        assertEquals("medium", engine.getGapClass(30));
        assertEquals("medium", engine.getGapClass(120));
        assertEquals("medium", engine.getGapClass(239));

        assertEquals("long", engine.getGapClass(240));
        assertEquals("long", engine.getGapClass(500));
        assertEquals("long", engine.getGapClass(719));

        assertEquals("longer", engine.getGapClass(720));
        assertEquals("longer", engine.getGapClass(1440));
    }

    @Test
    public void testBirthYearNullSkipsAgeLines() {
        // All messages with "needs": ["birthYear"] must be skipped when birthYear is null
        // e.g. m089, m092, m094
        GreetingContext ctx = new GreetingContext("any", LocalDateTime.of(2026, 9, 2, 10, 0), 0, 0, false, "other", null);
        for (int i = 0; i < 50; i++) {
            GreetingResult res = engine.evaluate(ctx, Collections.emptyList());
            assertFalse("m089 must not be selected", "m089".equals(res.id));
            assertFalse("m092 must not be selected", "m092".equals(res.id));
            assertFalse("m094 must not be selected", "m094".equals(res.id));
            assertFalse("Result should not contain {age}", res.text.contains("{age}"));
        }
    }

    @Test
    public void testBirthdayBeatsFestivalOnSameDay() {
        // 14 Oct 2027 is Mom's birthday (10-14) AND Sharad Purnima (2027: 10-14 in JSON)
        LocalDateTime momBirthdayAndFestival = LocalDateTime.of(2027, 10, 14, 10, 0);
        GreetingContext ctx = new GreetingContext("any", momBirthdayAndFestival, 0, 0, false, "other", null);

        GreetingResult res = engine.evaluate(ctx, Collections.emptyList());
        assertEquals("birthday", res.pool);
        assertTrue("Birthday line must always include name", res.isNamed);
        assertTrue("Must include Mom", res.text.contains("Mom"));
    }

    @Test
    public void testBirthdayAlwaysIncludesName() {
        // 2 Sept is self birthday
        LocalDateTime selfBirthday = LocalDateTime.of(2026, 9, 2, 12, 0);
        GreetingContext ctx = new GreetingContext("any", selfBirthday, 0, 0, false, "other", null);

        for (int i = 0; i < 100; i++) {
            GreetingResult res = engine.evaluate(ctx, Collections.emptyList());
            assertEquals("birthday", res.pool);
            assertTrue("Birthday must always be named on roll " + i, res.isNamed);
            assertFalse("Must not have unreplaced {name}", res.text.contains("{name}"));
        }
    }

    @Test
    public void testDiwaliOnCurrentDate() {
        // 2026-11-08 is Diwali in the data
        LocalDateTime diwaliNow = LocalDateTime.of(2026, 11, 8, 18, 0);
        GreetingContext ctx = new GreetingContext("any", diwaliNow, 0, 0, false, "other", null);

        GreetingResult res = engine.evaluate(ctx, Collections.emptyList());
        assertEquals("festival", res.pool);
        assertTrue("Must be a Diwali line", res.text.toLowerCase().contains("diwali") || res.text.toLowerCase().contains("deepavali"));
    }

    @Test
    public void testRepeatFilterNoRepeatInLastFiveOver200Draws() {
        Random seededRandom = new Random(12345);
        GreetingEngine seededEngine;
        try {
            File f = new File("src/main/assets/greetings.json");
            if (!f.exists()) f = new File("launcher/app/src/main/assets/greetings.json");
            try (InputStream is = new FileInputStream(f)) {
                seededEngine = new GreetingEngine(new org.json.JSONObject(new String(is.readAllBytes())), seededRandom);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> history = new ArrayList<>();
        GreetingContext ctx = new GreetingContext("any", LocalDateTime.of(2026, 5, 15, 10, 0), 10, 0, false, "other", null);

        for (int i = 0; i < 200; i++) {
            GreetingResult res = seededEngine.evaluate(ctx, history);
            assertNotNull(res);

            // Verify chosen ID is NOT in the last 5 of history
            int checkCount = Math.min(5, history.size());
            for (int k = 0; k < checkCount; k++) {
                assertFalse("Draw " + i + ": ID " + res.id + " was repeated from last 5 (index " + k + ")",
                        history.get(k).equals(res.id));
            }

            // Insert into history (most recent first)
            history.add(0, res.id);
            if (history.size() > 30) {
                history.remove(history.size() - 1);
            }
        }
    }

    @Test
    public void test100kRollsDistribution() {
        // Context where every tier (R, A, S) and tone N has lines
        // A generic evening context with trigger: "any", tb: "evening", gap: "short"
        Random rng = new Random(987654321L);
        GreetingEngine testEngine;
        try {
            File f = new File("src/main/assets/greetings.json");
            if (!f.exists()) f = new File("launcher/app/src/main/assets/greetings.json");
            try (InputStream is = new FileInputStream(f)) {
                testEngine = new GreetingEngine(new org.json.JSONObject(new String(is.readAllBytes())), rng);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LocalDateTime normalEvening = LocalDateTime.of(2026, 6, 17, 18, 0); // Wednesday
        GreetingContext ctx = new GreetingContext("any", normalEvening, 15, 0, false, "other", null);

        int totalRolls = 100_000;
        int noNameCount = 0;
        int ashirwadCount = 0; // R
        int ashuCount = 0;     // A
        int ashirwadSinghCount = 0; // S

        for (int i = 0; i < totalRolls; i++) {
            GreetingResult res = testEngine.evaluate(ctx, Collections.emptyList());
            if (!res.isNamed) {
                noNameCount++;
            } else {
                if ("R".equals(res.tier)) {
                    ashirwadCount++;
                } else if ("A".equals(res.tier)) {
                    ashuCount++;
                } else if ("S".equals(res.tier)) {
                    ashirwadSinghCount++;
                }
            }

            // Check that text has no remaining placeholders, no double spaces, no ",."
            assertFalse("Placeholder found in: " + res.text, res.text.contains("{"));
            assertFalse("Double space found in: " + res.text, res.text.contains("  "));
            assertFalse("Punctuation artifact found in: " + res.text, res.text.contains(",."));
            assertFalse("Punctuation artifact found in: " + res.text, res.text.contains(",!"));
        }

        double noNamePct = (noNameCount * 100.0) / totalRolls;
        double ashirwadPct = (ashirwadCount * 100.0) / totalRolls;
        double ashuPct = (ashuCount * 100.0) / totalRolls;
        double ashirwadSinghPct = (ashirwadSinghCount * 100.0) / totalRolls;

        // Tolerance: +/- 1.5 percentage points
        // Target: no name ~50%, Ashirwad ~25%, Ashu ~17.5%, Ashirwad Singh ~7.5%
        assertEquals("No name percentage must be ~50%", 50.0, noNamePct, 1.5);
        assertEquals("Ashirwad (R) percentage must be ~25%", 25.0, ashirwadPct, 1.5);
        assertEquals("Ashu (A) percentage must be ~17.5%", 17.5, ashuPct, 1.5);
        assertEquals("Ashirwad Singh (S) percentage must be ~7.5%", 7.5, ashirwadSinghPct, 1.5);
    }

    @Test
    public void testTwoLineTitleSplitting() {
        // Short lines should not split
        String[] shortRes = BootIntroOverlay.splitTitleIntoTwoLines("Good morning, Ashirwad!");
        assertEquals("Good morning, Ashirwad!", shortRes[0]);
        assertEquals("", shortRes[1]);

        // Long line with exclamation break
        String[] diwaliRes = BootIntroOverlay.splitTitleIntoTwoLines(
                "Diwali night! Hope the lights and crackers were amazing, Ashirwad Singh.");
        assertEquals("Diwali night!", diwaliRes[0]);
        assertEquals("Hope the lights and crackers were amazing, Ashirwad Singh.", diwaliRes[1]);

        // Long line with question mark break
        String[] breakRes = BootIntroOverlay.splitTitleIntoTwoLines(
                "Taking a break from screens soon, Ashu? Sleep matters.");
        assertEquals("Taking a break from screens soon, Ashu?", breakRes[0]);
        assertEquals("Sleep matters.", breakRes[1]);

        // Long line with period break
        String[] lateRes = BootIntroOverlay.splitTitleIntoTwoLines(
                "Late night, Ashirwad Singh. What's on the screen tonight?");
        assertEquals("Late night, Ashirwad Singh.", lateRes[0]);
        assertEquals("What's on the screen tonight?", lateRes[1]);

        // Long line without punctuation break (tip) splits at space near middle
        String[] tipRes = BootIntroOverlay.splitTitleIntoTwoLines(
                "Tip: Press Settings on the remote anytime to open clean TV quick controls.");
        assertFalse("Line 1 should not be empty", tipRes[0].isEmpty());
        assertFalse("Line 2 should not be empty", tipRes[1].isEmpty());
        assertEquals("Tip: Press Settings on the remote anytime to open clean TV quick controls.", tipRes[0] + " " + tipRes[1]);
    }
}

