package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Smart greeting engine for boot/wake intro.
 * Pure Java class without Android UI dependencies for standalone JVM unit testing.
 */
public final class GreetingEngine {
    private static final String TAG = "GreetingEngine";

    static final class TimeRange {
        final LocalTime start;
        final LocalTime end;
        final boolean wrapsMidnight;

        TimeRange(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
            this.wrapsMidnight = start.isAfter(end);
        }

        boolean contains(LocalTime time) {
            if (wrapsMidnight) {
                return !time.isBefore(start) || time.isBefore(end);
            } else {
                return !time.isBefore(start) && time.isBefore(end);
            }
        }
    }

    static final class FestivalDef {
        final String fixedDate; // "MM-dd" or null
        final Map<String, String> yearlyDates; // year -> "MM-dd"

        FestivalDef(String fixedDate, Map<String, String> yearlyDates) {
            this.fixedDate = fixedDate;
            this.yearlyDates = yearlyDates != null ? yearlyDates : Collections.emptyMap();
        }
    }

    public static final class GreetingMessage {
        public final String id;
        public final String text;
        public final String tone; // "R", "A", "S", "N"
        public final String trigger; // "boot", "wake", "screensaver", "any"
        public final String boot; // "overnight_morning", "other", or null
        public final List<String> gap; // ["short", ...], or null
        public final List<String> tb; // ["morning", ...], or null
        public final List<String> days; // ["mon", ...], or null
        public final String daytype; // "weekday", "weekend", or null
        public final String fest; // festival key, or null
        public final String special; // "birthday_self", etc., or null
        public final boolean alwaysName;
        public final String kind; // "tip", "rare", or null
        public final List<String> needs; // ["birthYear", ...], or null
        public final String cond; // "bridge_on", or null
        public final boolean ssLong;

        public GreetingMessage(String id, String text, String tone, String trigger,
                               String boot, List<String> gap, List<String> tb,
                               List<String> days, String daytype, String fest,
                               String special, boolean alwaysName, String kind,
                               List<String> needs, String cond, boolean ssLong) {
            this.id = id != null ? id : "";
            this.text = text != null ? text : "";
            this.tone = tone != null ? tone.toUpperCase(Locale.ROOT) : "N";
            this.trigger = trigger != null ? trigger.toLowerCase(Locale.ROOT) : "any";
            this.boot = boot;
            this.gap = gap;
            this.tb = tb;
            this.days = days;
            this.daytype = daytype;
            this.fest = fest;
            this.special = special;
            this.alwaysName = alwaysName;
            this.kind = kind;
            this.needs = needs;
            this.cond = cond;
            this.ssLong = ssLong;
        }
    }

    // JSON configuration fields
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Double> tierWeightsOfNamed = new LinkedHashMap<>();
    private final double nameShare;
    private final Integer birthYear;
    private final Map<String, String> birthdays = new HashMap<>();
    private final Map<String, String> relatives = new HashMap<>();

    private final double freshShare;
    private final int historySize;
    private final int excludeLastN;

    private final double chanceTip;
    private final double chanceRare;

    private final Map<String, TimeRange> timeBuckets = new LinkedHashMap<>();
    private final Map<String, int[]> gapClassesMinutes = new LinkedHashMap<>();
    private final String genericFestivalTemplate;
    private final Map<String, FestivalDef> festivals = new HashMap<>();
    private final List<GreetingMessage> messages = new ArrayList<>();

    private final Random random;

    public GreetingEngine(String jsonContent) throws Exception {
        this(jsonContent, new Random());
    }

    public GreetingEngine(String jsonContent, Random random) throws Exception {
        this(new JSONObject(jsonContent), random);
    }

    public GreetingEngine(JSONObject json, Random random) throws Exception {
        this.random = random != null ? random : new Random();

        // 1. Personal
        JSONObject personal = json.getJSONObject("personal");
        JSONObject namesObj = personal.getJSONObject("names");
        for (String k : new String[]{"A", "R", "S"}) {
            if (namesObj.has(k)) {
                this.names.put(k, namesObj.getString(k));
            }
        }
        this.nameShare = personal.optDouble("nameShare", 0.5);

        JSONObject weightsObj = personal.getJSONObject("tierWeightsOfNamed");
        for (String k : new String[]{"R", "A", "S"}) {
            if (weightsObj.has(k)) {
                this.tierWeightsOfNamed.put(k, weightsObj.getDouble(k));
            }
        }

        if (personal.has("birthYear") && !personal.isNull("birthYear")) {
            this.birthYear = personal.getInt("birthYear");
        } else {
            this.birthYear = null;
        }

        if (personal.has("birthdays")) {
            JSONObject bdays = personal.getJSONObject("birthdays");
            for (String k : new String[]{"self", "mother", "father"}) {
                if (bdays.has(k)) {
                    this.birthdays.put(k, bdays.getString(k));
                }
            }
        }

        if (personal.has("relatives")) {
            JSONObject rels = personal.getJSONObject("relatives");
            for (String k : new String[]{"mother", "father"}) {
                if (rels.has(k)) {
                    this.relatives.put(k, rels.getString(k));
                }
            }
        }

        JSONObject repeat = personal.getJSONObject("repeat");
        this.freshShare = repeat.optDouble("freshShare", 0.9);
        this.historySize = repeat.optInt("historySize", 30);
        this.excludeLastN = repeat.optInt("excludeLastN", 5);

        JSONObject chances = personal.getJSONObject("chances");
        this.chanceTip = chances.optDouble("tip", 0.12);
        this.chanceRare = chances.optDouble("rare", 0.04);

        // 2. Time Buckets
        JSONObject tbObj = json.getJSONObject("timeBuckets");
        for (String key : new String[]{"late_night", "early_morning", "morning", "afternoon", "evening", "dinner", "night"}) {
            if (tbObj.has(key)) {
                JSONArray arr = tbObj.getJSONArray(key);
                LocalTime start = LocalTime.parse(arr.getString(0));
                LocalTime end = LocalTime.parse(arr.getString(1));
                this.timeBuckets.put(key, new TimeRange(start, end));
            }
        }

        // 3. Gap Classes
        JSONObject gapObj = json.getJSONObject("gapClassesMinutes");
        for (String key : new String[]{"short", "medium", "long", "longer"}) {
            if (gapObj.has(key)) {
                JSONArray arr = gapObj.getJSONArray(key);
                int min = arr.getInt(0);
                int max = arr.isNull(1) ? Integer.MAX_VALUE : arr.getInt(1);
                this.gapClassesMinutes.put(key, new int[]{min, max});
            }
        }

        // 4. Templates
        JSONObject tmplObj = json.optJSONObject("templates");
        this.genericFestivalTemplate = tmplObj != null ? tmplObj.optString("genericFestival", "Happy {festival}, {name}!") : "Happy {festival}, {name}!";

        // 5. Festivals
        JSONObject festObj = json.getJSONObject("festivals");
        JSONArray festNames = festObj.names();
        if (festNames != null) {
            for (int i = 0; i < festNames.length(); i++) {
                String festKey = festNames.getString(i);
                JSONObject fEntry = festObj.getJSONObject(festKey);
                String fixed = fEntry.optString("fixed", null);
                Map<String, String> yearly = new HashMap<>();
                JSONArray keys = fEntry.names();
                if (keys != null) {
                    for (int j = 0; j < keys.length(); j++) {
                        String yk = keys.getString(j);
                        if (!"fixed".equals(yk) && !"note".equals(yk)) {
                            yearly.put(yk, fEntry.getString(yk));
                        }
                    }
                }
                this.festivals.put(festKey, new FestivalDef(fixed, yearly));
            }
        }

        // 6. Messages
        JSONArray msgArr = json.getJSONArray("messages");
        for (int i = 0; i < msgArr.length(); i++) {
            JSONObject m = msgArr.getJSONObject(i);
            String id = m.getString("id");
            String text = m.getString("text");
            String tone = m.optString("tone", "N");
            String trigger = m.optString("trigger", "any");
            String boot = m.optString("boot", null);
            List<String> gap = optStringList(m, "gap");
            List<String> tb = optStringList(m, "tb");
            List<String> days = optStringList(m, "days");
            String daytype = m.optString("daytype", null);
            String fest = m.optString("fest", null);
            String special = m.optString("special", null);
            boolean alwaysName = m.optBoolean("alwaysName", false);
            String kind = m.optString("kind", null);
            List<String> needs = optStringList(m, "needs");
            String cond = m.optString("cond", null);
            boolean ssLong = m.optBoolean("ssLong", false);

            this.messages.add(new GreetingMessage(id, text, tone, trigger, boot, gap, tb, days, daytype, fest, special, alwaysName, kind, needs, cond, ssLong));
        }
    }

    private static List<String> optStringList(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) return null;
        JSONArray arr = obj.optJSONArray(key);
        if (arr == null) return null;
        List<String> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.optString(i));
        }
        return list;
    }

    public static GreetingEngine fromStream(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return new GreetingEngine(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public List<GreetingMessage> getAllMessages() {
        return Collections.unmodifiableList(messages);
    }

    public Map<String, FestivalDef> getFestivals() {
        return Collections.unmodifiableMap(festivals);
    }

    public int getHistorySize() {
        return historySize;
    }

    public int getExcludeLastN() {
        return excludeLastN;
    }

    /**
     * Finds the time bucket for a given local time.
     */
    public String getTimeBucket(LocalTime time) {
        for (Map.Entry<String, TimeRange> entry : timeBuckets.entrySet()) {
            if (entry.getValue().contains(time)) {
                return entry.getKey();
            }
        }
        return "morning";
    }

    /**
     * Maps gapMinutes to gap class ("short", "medium", "long", "longer").
     */
    public String getGapClass(long gapMinutes) {
        if (gapMinutes < 30) return "short";
        if (gapMinutes < 240) return "medium";
        if (gapMinutes < 720) return "long";
        return "longer";
    }

    private static String getDayString(DayOfWeek dow) {
        switch (dow) {
            case MONDAY: return "mon";
            case TUESDAY: return "tue";
            case WEDNESDAY: return "wed";
            case THURSDAY: return "thu";
            case FRIDAY: return "fri";
            case SATURDAY: return "sat";
            case SUNDAY: return "sun";
            default: return "mon";
        }
    }

    private static String getDayType(DayOfWeek dow) {
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? "weekend" : "weekday";
    }

    /**
     * Evaluates context and history, selecting a message and returning the final formatted result.
     */
    public GreetingResult evaluate(GreetingContext context, List<String> history) {
        if (context == null) {
            context = new GreetingContext("boot", LocalDateTime.now(), 0, 0, false, "other", null);
        }
        if (history == null) {
            history = Collections.emptyList();
        }

        LocalDateTime now = context.now;
        boolean clockSet = now.getYear() >= 2024;
        DateTimeFormatter mmddFormat = DateTimeFormatter.ofPattern("MM-dd");
        String todayMmDd = now.format(mmddFormat);
        String tomorrowMmDd = now.plusDays(1).format(mmddFormat);
        String yearStr = String.valueOf(now.getYear());

        // 1. Identify active special events (birthdays & active festivals)
        Set<String> activeBirthdays = new HashSet<>();
        Set<String> activeFestivals = new HashSet<>();

        if (clockSet) {
            // Check birthdays
            String selfBday = birthdays.get("self");
            if (selfBday != null) {
                if (selfBday.equals(todayMmDd)) activeBirthdays.add("birthday_self");
                if (selfBday.equals(tomorrowMmDd)) activeBirthdays.add("birthday_self_eve");
            }
            String motherBday = birthdays.get("mother");
            if (motherBday != null) {
                if (motherBday.equals(todayMmDd)) activeBirthdays.add("birthday_mother");
                if (motherBday.equals(tomorrowMmDd)) activeBirthdays.add("birthday_mother_eve");
            }
            String fatherBday = birthdays.get("father");
            if (fatherBday != null) {
                if (fatherBday.equals(todayMmDd)) activeBirthdays.add("birthday_father");
                if (fatherBday.equals(tomorrowMmDd)) activeBirthdays.add("birthday_father_eve");
            }

            // Check festivals
            for (Map.Entry<String, FestivalDef> entry : festivals.entrySet()) {
                FestivalDef fDef = entry.getValue();
                if (fDef.fixedDate != null && fDef.fixedDate.equals(todayMmDd)) {
                    activeFestivals.add(entry.getKey());
                } else if (fDef.yearlyDates.containsKey(yearStr) && todayMmDd.equals(fDef.yearlyDates.get(yearStr))) {
                    activeFestivals.add(entry.getKey());
                }
            }
        }

        String currentTb = getTimeBucket(now.toLocalTime());
        String currentGapClass = getGapClass(context.gapMinutes);
        String currentDay = getDayString(now.getDayOfWeek());
        String currentDayType = getDayType(now.getDayOfWeek());

        // 2. Filter all eligible lines according to context
        List<GreetingMessage> eligibleLines = new ArrayList<>();
        for (GreetingMessage msg : messages) {
            if (isEligible(msg, context, currentTb, currentGapClass, currentDay, currentDayType, activeBirthdays, activeFestivals, clockSet)) {
                eligibleLines.add(msg);
            }
        }

        // 3. Determine Pool: Priority 1 (Birthday) > Priority 2 (Festival) > Priority 3 (Normal)
        String poolName = null;
        List<GreetingMessage> poolCandidates = new ArrayList<>();
        String activeFestivalKey = null;

        if (clockSet && !activeBirthdays.isEmpty()) {
            for (GreetingMessage msg : eligibleLines) {
                if (msg.special != null && activeBirthdays.contains(msg.special)) {
                    poolCandidates.add(msg);
                }
            }
            if (!poolCandidates.isEmpty()) {
                poolName = "birthday";
            }
        }

        if (poolName == null && clockSet && !activeFestivals.isEmpty()) {
            for (GreetingMessage msg : eligibleLines) {
                if (msg.fest != null && activeFestivals.contains(msg.fest)) {
                    poolCandidates.add(msg);
                }
            }
            activeFestivalKey = activeFestivals.iterator().next();
            if (!poolCandidates.isEmpty()) {
                poolName = "festival";
            } else {
                // Generic festival template fallback
                poolName = "festival";
                String festTitle = formatFestivalName(activeFestivalKey);
                String genericText = genericFestivalTemplate.replace("{festival}", festTitle);
                poolCandidates.add(new GreetingMessage("fest_generic_r", genericText, "R", "any", null, null, null, null, null, activeFestivalKey, null, false, null, null, null, false));
                poolCandidates.add(new GreetingMessage("fest_generic_a", genericText, "A", "any", null, null, null, null, null, activeFestivalKey, null, false, null, null, null, false));
                poolCandidates.add(new GreetingMessage("fest_generic_s", genericText, "S", "any", null, null, null, null, null, activeFestivalKey, null, false, null, null, null, false));
            }
        }

        if (poolName == null) {
            poolName = "normal";
            for (GreetingMessage msg : eligibleLines) {
                if (msg.special == null && msg.fest == null) {
                    poolCandidates.add(msg);
                }
            }
        }

        // 4. Repeat Filter (exclude last 5; 90% fresh vs last 30)
        List<GreetingMessage> afterRepeat = applyRepeatFilter(poolCandidates, history);
        if (afterRepeat.isEmpty()) {
            // If repeat filter emptied a special day pool, fall back to normal lines
            if ("birthday".equals(poolName) || "festival".equals(poolName)) {
                List<GreetingMessage> normalLines = new ArrayList<>();
                for (GreetingMessage msg : eligibleLines) {
                    if (msg.special == null && msg.fest == null) normalLines.add(msg);
                }
                poolName = "normal";
                afterRepeat = applyRepeatFilter(normalLines, history);
                if (afterRepeat.isEmpty()) {
                    afterRepeat = normalLines.isEmpty() ? poolCandidates : normalLines;
                }
            } else {
                afterRepeat = poolCandidates; // fallback relax
            }
        }

        if (afterRepeat.isEmpty()) {
            // Ultimate safety fallback
            afterRepeat.add(new GreetingMessage("fallback", "Welcome, {name}.", "R", "any", null, null, null, null, null, null, null, false, null, null, null, false));
            poolName = "normal";
        }

        // 5. Name Selection Rule
        GreetingMessage chosenMessage;
        String chosenTier;
        boolean named;
        String chosenName = null;

        if (context.forcedTier != null) {
            String ft = context.forcedTier.trim();
            if ("none".equalsIgnoreCase(ft)) {
                named = false;
                chosenTier = "N";
            } else {
                named = true;
                chosenTier = ft.toUpperCase(Locale.ROOT);
                chosenName = names.getOrDefault(chosenTier, names.get("R"));
            }
        } else if (hasAlwaysName(afterRepeat)) {
            // Always named (e.g. birthday lines)
            named = true;
            chosenTier = pickTier(afterRepeat);
            chosenName = names.getOrDefault(chosenTier, "Ashirwad");
        } else {
            // Standard name roll: 50% named
            named = (random.nextDouble() < nameShare);
            if (named) {
                chosenTier = pickTier(afterRepeat);
                if (chosenTier != null) {
                    chosenName = names.get(chosenTier);
                } else {
                    named = false;
                    chosenTier = "N";
                }
            } else {
                chosenTier = "N";
            }
        }

        // Filter and pick specific line from afterRepeat
        if (named && chosenTier != null && !"N".equals(chosenTier)) {
            List<GreetingMessage> tierMatches = new ArrayList<>();
            for (GreetingMessage msg : afterRepeat) {
                if (chosenTier.equals(msg.tone)) {
                    tierMatches.add(msg);
                }
            }
            if (tierMatches.isEmpty()) {
                tierMatches = afterRepeat;
            }

            // In normal pool, check rare roll (4%)
            if ("normal".equals(poolName)) {
                if (random.nextDouble() < chanceRare) {
                    List<GreetingMessage> rareTier = new ArrayList<>();
                    for (GreetingMessage m : tierMatches) {
                        if ("rare".equals(m.kind)) rareTier.add(m);
                    }
                    if (!rareTier.isEmpty()) {
                        tierMatches = rareTier;
                        poolName = "rare";
                    }
                } else {
                    List<GreetingMessage> ordinaryTier = new ArrayList<>();
                    for (GreetingMessage m : tierMatches) {
                        if (!"rare".equals(m.kind) && !"tip".equals(m.kind)) ordinaryTier.add(m);
                    }
                    if (!ordinaryTier.isEmpty()) {
                        tierMatches = ordinaryTier;
                    }
                }
            }

            chosenMessage = tierMatches.get(random.nextInt(tierMatches.size()));
        } else {
            // Unnamed (50%)
            List<GreetingMessage> subPool = afterRepeat;
            if ("normal".equals(poolName)) {
                double r = random.nextDouble();
                if (r < chanceRare) {
                    List<GreetingMessage> rareLines = new ArrayList<>();
                    for (GreetingMessage m : afterRepeat) {
                        if ("rare".equals(m.kind)) rareLines.add(m);
                    }
                    if (!rareLines.isEmpty()) {
                        subPool = rareLines;
                        poolName = "rare";
                    }
                } else if (r < chanceRare + chanceTip) {
                    List<GreetingMessage> tipLines = new ArrayList<>();
                    for (GreetingMessage m : afterRepeat) {
                        if ("tip".equals(m.kind)) tipLines.add(m);
                    }
                    if (!tipLines.isEmpty()) {
                        subPool = tipLines;
                        poolName = "tip";
                    }
                } else {
                    List<GreetingMessage> ordinaryLines = new ArrayList<>();
                    for (GreetingMessage m : afterRepeat) {
                        if (!"rare".equals(m.kind) && !"tip".equals(m.kind)) ordinaryLines.add(m);
                    }
                    if (!ordinaryLines.isEmpty()) {
                        subPool = ordinaryLines;
                    }
                }
            }

            chosenMessage = subPool.get(random.nextInt(subPool.size()));
        }

        // 6. Text formatting & placeholder interpolation
        String finalText = formatText(chosenMessage.text, named, chosenName, context.now, activeFestivalKey);

        return new GreetingResult(chosenMessage.id, finalText, chosenTier, named, poolName);
    }

    private boolean isEligible(GreetingMessage msg, GreetingContext ctx, String currentTb,
                               String currentGapClass, String currentDay, String currentDayType,
                               Set<String> activeBirthdays, Set<String> activeFestivals, boolean clockSet) {
        // needs check
        if (msg.needs != null && msg.needs.contains("birthYear") && birthYear == null) {
            return false;
        }

        // trigger check
        if (!"any".equals(msg.trigger) && !msg.trigger.equals(ctx.trigger)) {
            return false;
        }

        // boot class check
        if (msg.boot != null) {
            if (!"boot".equals(ctx.trigger)) return false;
            if (!msg.boot.equals(ctx.bootClass)) return false;
        }

        // gap class check for wake
        if (msg.gap != null && !msg.gap.isEmpty()) {
            if (!"wake".equals(ctx.trigger)) return false;
            if (currentGapClass == null || !msg.gap.contains(currentGapClass)) return false;
        }

        // screensaver long check
        if (msg.ssLong) {
            if (!"screensaver".equals(ctx.trigger) || ctx.screensaverMinutes < 60) {
                return false;
            }
        }

        // time bucket check
        if (msg.tb != null && !msg.tb.isEmpty()) {
            if (currentTb == null || !msg.tb.contains(currentTb)) return false;
        }

        // day of week check
        if (msg.days != null && !msg.days.isEmpty()) {
            if (currentDay == null || !msg.days.contains(currentDay)) return false;
        }

        // daytype check
        if (msg.daytype != null) {
            if (!msg.daytype.equals(currentDayType)) return false;
        }

        // cond: bridge_on
        if ("bridge_on".equals(msg.cond)) {
            if (!ctx.bridgeOn) return false;
        }

        // date-based lines (special / fest)
        if (!clockSet) {
            if (msg.special != null || msg.fest != null) return false;
        } else {
            if (msg.special != null && !activeBirthdays.contains(msg.special)) {
                return false;
            }
            if (msg.fest != null && !activeFestivals.contains(msg.fest)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasAlwaysName(List<GreetingMessage> list) {
        for (GreetingMessage m : list) {
            if (m.alwaysName) return true;
        }
        return false;
    }

    private static boolean allToneN(List<GreetingMessage> list) {
        for (GreetingMessage m : list) {
            if (!"N".equals(m.tone)) return false;
        }
        return true;
    }

    private String pickTier(List<GreetingMessage> candidates) {
        Set<String> presentTiers = new HashSet<>();
        for (GreetingMessage m : candidates) {
            if ("R".equals(m.tone) || "A".equals(m.tone) || "S".equals(m.tone)) {
                presentTiers.add(m.tone);
            }
        }
        if (presentTiers.isEmpty()) return null;

        double totalWeight = 0.0;
        for (String t : presentTiers) {
            totalWeight += tierWeightsOfNamed.getOrDefault(t, 0.0);
        }
        if (totalWeight <= 0.0) {
            return presentTiers.iterator().next();
        }

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (String t : new String[]{"R", "A", "S"}) {
            if (presentTiers.contains(t)) {
                cumulative += tierWeightsOfNamed.getOrDefault(t, 0.0);
                if (roll <= cumulative) {
                    return t;
                }
            }
        }
        return presentTiers.iterator().next();
    }

    private List<GreetingMessage> applyRepeatFilter(List<GreetingMessage> pool, List<String> history) {
        if (pool == null || pool.isEmpty()) return Collections.emptyList();

        // 1. Identify last 5 IDs (most recent)
        Set<String> last5 = new HashSet<>();
        int count = Math.min(excludeLastN, history.size());
        for (int i = 0; i < count; i++) {
            last5.add(history.get(i));
        }

        List<GreetingMessage> notInLast5 = new ArrayList<>();
        for (GreetingMessage m : pool) {
            if (!last5.contains(m.id)) {
                notInLast5.add(m);
            }
        }

        if (notInLast5.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Fresh share: 90% prefer lines not in history (up to 30)
        Set<String> fullHistory = new HashSet<>();
        int histCount = Math.min(historySize, history.size());
        for (int i = 0; i < histCount; i++) {
            fullHistory.add(history.get(i));
        }

        if (random.nextDouble() < freshShare) {
            List<GreetingMessage> fresh = new ArrayList<>();
            for (GreetingMessage m : notInLast5) {
                if (!fullHistory.contains(m.id)) {
                    fresh.add(m);
                }
            }
            if (!fresh.isEmpty()) {
                return fresh;
            }
        }

        return notInLast5;
    }

    private String formatText(String template, boolean named, String chosenName, LocalDateTime now, String festKey) {
        String text = template;

        if (named && chosenName != null) {
            text = text.replace("{name}", chosenName);
        } else {
            // Clean removal:
            // 1. ", {name}"
            // 2. "{name}, " at start
            // 3. bare "{name}"
            if (text.contains(", {name}")) {
                text = text.replace(", {name}", "");
            } else if (text.startsWith("{name}, ")) {
                text = text.substring("{name}, ".length());
            } else if (text.contains("{name}, ")) {
                text = text.replace("{name}, ", "");
            } else if (text.contains("{name}")) {
                text = text.replace("{name}", "");
            }
        }

        // Interpolate relatives
        text = text.replace("{mother}", relatives.getOrDefault("mother", "Mom"));
        text = text.replace("{father}", relatives.getOrDefault("father", "Papa"));

        // Interpolate year
        text = text.replace("{year}", String.valueOf(now.getYear()));

        // Interpolate age
        if (birthYear != null) {
            int age = now.getYear() - birthYear;
            text = text.replace("{age}", String.valueOf(age));
        }

        // Interpolate festival
        if (festKey != null) {
            text = text.replace("{festival}", formatFestivalName(festKey));
        }

        return cleanSentence(text);
    }

    private static String cleanSentence(String str) {
        if (str == null || str.trim().isEmpty()) return "";
        String s = str.trim();

        // Remove extra spaces
        s = s.replaceAll("\\s+", " ");

        // Clean punctuation artifacts like ",." or ",!" or ",?"
        s = s.replaceAll(",\\s*([.!?])", "$1");

        // Clean leading commas or spaces
        s = s.replaceAll("^[\\s,]+", "");

        if (s.isEmpty()) return "";

        // Capitalize the first letter
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : "");
    }

    private static String formatFestivalName(String key) {
        if (key == null) return "Festival";
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}

