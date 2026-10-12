import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Runs against the actual release APK's classes under Android ART, not host OpenJDK. */
public final class LiveprogAndroidRegression {
    private static final String PACKAGE = "me.timschneeberger.rootlessjamesdsp.liveprog.";
    private static Class<?> parserClass;
    private static Class<?> propertyClass;
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        int api = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        check(api == 35, "Expected Android 15/API 35, got " + api);
        System.out.println("Android ART API " + api + " / " + System.getProperty("java.vm.name"));
        boolean rejectsOldPattern = false;
        try {
            Pattern.compile("\\{([^}]*)}");
        } catch (PatternSyntaxException expected) {
            rejectsOldPattern = true;
            System.out.println("PASS: Android ICU rejects the alpha11 pattern");
        }
        check(rejectsOldPattern, "Must exercise Android ICU, which rejects alpha11's unescaped brace");

        parserClass = Class.forName(PACKAGE + "EelParser");
        propertyClass = Class.forName(PACKAGE + "EelBaseProperty");
        File assets = new File(args[0]);
        File[] scripts = assets.listFiles((dir, name) -> name.endsWith(".eel"));
        check(scripts != null && scripts.length == 40, "Expected all 40 bundled EEL scripts");
        Arrays.sort(scripts);
        Set<String> titles = new HashSet<>();
        int parameterCount = 0;
        for (File script : scripts) {
            byte[] before = Files.readAllBytes(script.toPath());
            Object english = parse(script, Locale.ENGLISH);
            Object chinese = parse(script, Locale.SIMPLIFIED_CHINESE);
            String title = (String) call(chinese, "getLocalizedBuiltinTitle");
            check(title != null && titles.add(title), script.getName() + ": unique Chinese title");
            List<?> enProperties = properties(english);
            List<?> zhProperties = properties(chinese);
            check(enProperties.size() == zhProperties.size(), script.getName() + ": parameter count");
            for (int index = 0; index < enProperties.size(); index++) {
                Object en = enProperties.get(index);
                Object zh = zhProperties.get(index);
                check(en.getClass().equals(zh.getClass()), script.getName() + ": parameter type");
                for (String field : Arrays.asList("Key", "Default", "Value", "Minimum", "Maximum", "Step")) {
                    Object left = call(en, "get" + field);
                    Object right = call(zh, "get" + field);
                    check(left == null ? right == null : left.equals(right), script.getName() + ": " + field);
                }
                String label = (String) call(zh, "getDescription");
                check(hasChinese(label), script.getName() + ": Chinese parameter label " + label);
                if (zh.getClass().getSimpleName().equals("EelListProperty")) {
                    List<?> enOptions = (List<?>) call(en, "getOptions");
                    List<?> zhOptions = (List<?>) call(zh, "getOptions");
                    check(enOptions.size() == zhOptions.size(), script.getName() + ": list option count");
                }
            }
            check(Arrays.equals(before, Files.readAllBytes(script.toPath())), script.getName() + ": opening does not rewrite DSP source");
            parameterCount += zhProperties.size();
            System.out.println("PASS: " + script.getName() + " / " + zhProperties.size() + " parameters / " + title);
        }

        for (String name : Arrays.asList("viper_dynamicbass.eel", "viper_dynamicbass_preset.eel")) {
            File script = new File(assets, name);
            Object parser = parse(script, Locale.SIMPLIFIED_CHINESE);
            Object bass = property(parser, "dBassGain");
            check(number(bass, "getValue") == 5.0, name + ": initial bass strength is 5%");
            check(number(bass, "getDefault") == 5.0, name + ": default bass strength is 5%");
            check("低音强度（%）".equals(call(bass, "getDescription")), name + ": translated bass label");
            setAndSave(parser, bass, Float.valueOf(27f));
            if (name.endsWith("_preset.eel")) {
                Object preset = property(parser, "dPreset");
                check(((List<?>) call(preset, "getOptions")).size() == 19, "Bass preset options preserved");
                check("极致头戴式耳机（v2）".equals(((List<?>) call(preset, "getOptions")).get(0)), "Bass options translated");
                setAndSave(parser, preset, Integer.valueOf(3));
            }
            Object reopened = parse(script, Locale.SIMPLIFIED_CHINESE);
            check(number(property(reopened, "dBassGain"), "getValue") == 27.0, name + ": saved bass survives reopening");
            check(call(reopened, "getLocalizedBuiltinTitle") != null, name + ": saved script still recognized");
            if (name.endsWith("_preset.eel")) {
                check(number(property(reopened, "dPreset"), "getValue") == 3.0, "Saved preset survives reopening");
            }
            check(Boolean.TRUE.equals(call(reopened, "restoreDefaults")), name + ": defaults restored");
            check(number(property(parse(script, Locale.SIMPLIFIED_CHINESE), "dBassGain"), "getValue") == 5.0, name + ": reset restores 5%");
            System.out.println("PASS: " + name + " / 5% default, saved 27%, reopened, reset to 5%");
        }
        System.out.println("LIVEPROG_ANDROID_PASS scripts=" + scripts.length + " parameters=" + parameterCount + " checks=" + checks);
    }

    private static Object parse(File file, Locale locale) throws Exception {
        Object parser = parserClass.getConstructor(Locale.class).newInstance(locale);
        Object loaded = parserClass.getMethod("load", String.class, boolean.class, boolean.class, boolean.class)
                .invoke(parser, file.getAbsolutePath(), false, false, false);
        check(Boolean.TRUE.equals(loaded), file.getName() + ": load " + locale);
        return parser;
    }

    private static List<?> properties(Object parser) throws Exception {
        return (List<?>) call(parser, "getProperties");
    }

    private static Object property(Object parser, String key) throws Exception {
        for (Object property : properties(parser)) {
            if (key.equals(call(property, "getKey"))) return property;
        }
        throw new AssertionError("Missing property " + key);
    }

    private static void setAndSave(Object parser, Object property, Number value) throws Exception {
        property.getClass().getMethod("setValue", Number.class).invoke(property, value);
        check(Boolean.TRUE.equals(parserClass.getMethod("manipulateProperty", propertyClass).invoke(parser, property)), "Saving property");
    }

    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    private static double number(Object object, String getter) throws Exception {
        return ((Number) call(object, getter)).doubleValue();
    }

    private static boolean hasChinese(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) >= '\u4e00' && text.charAt(index) <= '\u9fff') return true;
        }
        return false;
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
