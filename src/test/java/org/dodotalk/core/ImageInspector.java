package org.dodotalk.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Developer-only object-graph inspector for a user-supplied Squeak image.
 * <p>
 * This class lives in the test source set so it can use the VM's
 * package-private context and object-memory accessors without adding a
 * production API. Run it through Gradle:
 *
 * <pre>
 * ./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --summary"
 * </pre>
 *
 * The inspector never writes or saves the image. The optional {@code --send}
 * command executes Smalltalk in the loaded image and should be used with a
 * disposable copy if the image may have side effects.
 */
public final class ImageInspector {

    private final SqueakVM vm;
    private final List<SqueakObject> objects = new ArrayList<>();
    private final Map<SqueakObject, String> classNames = new IdentityHashMap<>();
    private final Map<Object, List<Reference>> references = new IdentityHashMap<>();

    private ImageInspector(File imageFile) throws Exception {
        vm = new SqueakVM(new SqueakImage(imageFile));
        ObjectMemory memory = SqueakVM.objectMemory;
        for (int i = 0; i < memory.getObjectTableLength(); i++) {
            Object object = memory.getObjectAt(i) == null ? null : memory.getObjectAt(i).get();
            if (object instanceof SqueakObject) {
                objects.add((SqueakObject) object);
            }
        }
        for (SqueakObject object : objects) {
            classNames.put(object, className(object));
        }
        for (SqueakObject object : objects) {
            Object[] pointers = object.pointers;
            if (pointers == null) {
                continue;
            }
            for (int i = 0; i < pointers.length; i++) {
                if (pointers[i] instanceof SqueakObject) {
                    references.computeIfAbsent(pointers[i], key -> new ArrayList<>())
                            .add(new Reference(object, i));
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help || options.image == null) {
                usage();
                System.exit(options.help ? 0 : 2);
            }

            ImageInspector inspector = new ImageInspector(new File(options.image));
            if (options.find != null) {
                inspector.find(options.find, options.refs);
            } else if (options.className != null) {
                inspector.dumpClass(options.className, options.limit);
            } else if (options.contexts) {
                inspector.checkContexts();
            } else if (options.send != null) {
                inspector.send(options.receiverClass, options.label, options.send,
                        options.runMillis);
            } else {
                inspector.summary();
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
        // A successfully sent message may leave non-daemon image threads
        // running; this is a command-line tool, so terminate deterministically.
        System.exit(0);
    }

    private void summary() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (SqueakObject object : objects) {
            counts.merge(classNames.get(object), 1, Integer::sum);
        }
        System.out.println("objects=" + objects.size());
        System.out.println("top classes:");
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(40)
                .forEach(entry -> System.out.printf("  %-40s %d%n",
                        entry.getKey(), entry.getValue()));
        checkContexts();
    }

    private void find(String needle, int depth) {
        String lower = needle.toLowerCase(Locale.ROOT);
        int found = 0;
        for (SqueakObject object : objects) {
            String value = stringValue(object);
            if (value == null || !value.toLowerCase(Locale.ROOT).contains(lower)) {
                continue;
            }
            System.out.println("STRING " + quote(value) + " " + id(object));
            printReferences(object, depth, "  ", Collections.newSetFromMap(new IdentityHashMap<>()));
            if (++found >= 50) {
                System.out.println("... output truncated at 50 matches");
                return;
            }
        }
        if (found == 0) {
            System.out.println("no string matched: " + needle);
        }
    }

    private void printReferences(Object target, int depth, String indent, Set<Object> seen) {
        if (depth < 0 || !seen.add(target)) {
            return;
        }
        List<Reference> refs = references.get(target);
        if (refs == null) {
            return;
        }
        int limit = Math.min(50, refs.size());
        for (int i = 0; i < limit; i++) {
            Reference ref = refs.get(i);
            System.out.println(indent + "<- " + classNames.get(ref.owner)
                    + " " + id(ref.owner) + " field[" + ref.index + "] "
                    + fieldLabel(classNames.get(ref.owner), ref.index));
            printStringFields(ref.owner, indent + "   ");
            printReferences(ref.owner, depth - 1, indent + "  ", seen);
        }
        if (refs.size() > limit) {
            System.out.println(indent + "... " + (refs.size() - limit) + " more references");
        }
    }

    private void dumpClass(String name, int limit) {
        int count = 0;
        for (SqueakObject object : objects) {
            if (!name.equals(classNames.get(object))) {
                continue;
            }
            count++;
            if (count <= limit) {
                System.out.println("OBJECT " + classNames.get(object) + " " + id(object)
                        + " pointers=" + object.pointersSize()
                        + " bits=" + object.bitsSize());
                dumpFields(object);
            }
        }
        System.out.println("class " + name + " instances=" + count);
    }

    private void dumpFields(SqueakObject object) {
        Object[] pointers = object.pointers;
        if (pointers == null) {
            return;
        }
        for (int i = 0; i < pointers.length; i++) {
            System.out.println("  [" + i + "] " + fieldLabel(classNames.get(object), i)
                    + " = " + describe(pointers[i]));
        }
    }

    private void printStringFields(SqueakObject object, String indent) {
        Object[] pointers = object.pointers;
        if (pointers == null) {
            return;
        }
        int shown = 0;
        for (int i = 0; i < pointers.length && shown < 6; i++) {
            if (pointers[i] instanceof SqueakObject) {
                String value = stringValue((SqueakObject) pointers[i]);
                if (value != null) {
                    System.out.println(indent + "[" + i + "] string=" + quote(value));
                    shown++;
                }
            }
        }
    }

    private void checkContexts() {
        int blocks = 0;
        int methods = 0;
        int bad = 0;
        for (SqueakObject object : objects) {
            String name = classNames.get(object);
            boolean block = "BlockContext".equals(name);
            boolean method = "MethodContext".equals(name);
            if (!block && !method) {
                continue;
            }
            if (block) {
                blocks++;
            } else {
                methods++;
            }
            Object contextMethod = pointer(object, Squeak.Context_method);
            if (InterpreterHelper.isSTInteger(contextMethod)) {
                Object home = pointer(object, Squeak.BlockContext_home);
                if (!(home instanceof SqueakObject)) {
                    bad++;
                    System.out.println("BAD HOME " + id(object) + " home="
                            + describe(home));
                    continue;
                }
                Object homeMethod = pointer((SqueakObject) home, Squeak.Context_method);
                if (!(homeMethod instanceof SqueakObject)) {
                    bad++;
                    System.out.println("BAD HOME METHOD " + id(object)
                            + " home=" + id((SqueakObject) home)
                            + " method=" + describe(homeMethod));
                }
            } else if (!(contextMethod instanceof SqueakObject)) {
                bad++;
                System.out.println("BAD METHOD " + id(object) + " method="
                        + describe(contextMethod));
            }
        }
        System.out.println("contexts: blocks=" + blocks + " methodContexts=" + methods
                + " bad=" + bad);
    }

    private void send(String receiverClass, String label, String selectorName,
                      long runMillis) throws Exception {
        if (receiverClass == null) {
            throw new IllegalArgumentException("--send requires --receiver-class");
        }
        SqueakObject receiver = findReceiver(receiverClass, label);
        SqueakObject selector = findSelector(selectorName);
        if (receiver == null) {
            throw new IllegalArgumentException("receiver not found: class="
                    + receiverClass + " label=" + label);
        }
        if (selector == null) {
            throw new IllegalArgumentException("selector symbol not found: " + selectorName);
        }

        System.out.println("SEND " + id(receiver) + " >> " + selectorName);
        vm.push(receiver);
        vm.send(selector, 0, false);

        Thread runner = new Thread(() -> {
            try {
                vm.run();
            } catch (Throwable failure) {
                System.out.println("IMAGE THREW: " + failure);
                failure.printStackTrace(System.out);
                printSmalltalkStack(vm.activeContext);
            }
        }, "image-inspector-send");
        runner.setDaemon(true);
        runner.start();
        runner.join(runMillis);
        if (runner.isAlive()) {
            System.out.println("image still running after " + runMillis + " ms");
        }
    }

    private SqueakObject findReceiver(String receiverClass, String label) {
        for (SqueakObject object : objects) {
            if (!receiverClass.equals(classNames.get(object))) {
                continue;
            }
            if (label == null || containsString(object, label, 4,
                    Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return object;
            }
        }
        return null;
    }

    private SqueakObject findSelector(String selectorName) {
        for (SqueakObject object : objects) {
            if ("Symbol".equals(classNames.get(object))
                    && selectorName.equals(stringValue(object))) {
                return object;
            }
        }
        return null;
    }

    private boolean containsString(SqueakObject object, String needle, int depth,
                                   Set<SqueakObject> seen) {
        if (depth < 0 || !seen.add(object)) {
            return false;
        }
        String value = stringValue(object);
        if (value != null && value.contains(needle)) {
            return true;
        }
        Object[] pointers = object.pointers;
        if (pointers != null) {
            for (Object pointer : pointers) {
                if (pointer instanceof SqueakObject
                        && containsString((SqueakObject) pointer, needle, depth - 1, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void printSmalltalkStack(SqueakObject context) {
        System.out.println("SMALLTALK STACK:");
        Set<SqueakObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; depth < 30 && context != null
                && context != SqueakVM.nilObj && seen.add(context); depth++) {
            Object methodField = pointer(context, Squeak.Context_method);
            SqueakObject home = context;
            if (InterpreterHelper.isSTInteger(methodField)) {
                Object homeField = pointer(context, Squeak.BlockContext_home);
                if (!(homeField instanceof SqueakObject)) {
                    System.out.println("  [" + depth + "] BLOCK bad home="
                            + describe(homeField) + " " + id(context));
                    context = (SqueakObject) pointer(context, Squeak.Context_sender);
                    continue;
                }
                home = (SqueakObject) homeField;
            }
            Object method = pointer(home, Squeak.Context_method);
            String selector = method instanceof SqueakObject
                    ? selectorOf((SqueakObject) method) : null;
            Object receiver = pointer(home, Squeak.Context_receiver);
            String receiverName = receiver instanceof SqueakObject
                    ? classNames.get(receiver) : describe(receiver);
            System.out.println("  [" + depth + "] "
                    + (selector == null ? "?" : selector)
                    + "  rcvr=" + receiverName + "  ctx=" + classNames.get(context));
            context = (SqueakObject) pointer(context, Squeak.Context_sender);
        }
    }

    private String selectorOf(SqueakObject method) {
        for (SqueakObject object : objects) {
            String name = classNames.get(object);
            if (name == null || !name.contains("MethodDictionary")) {
                continue;
            }
            Object arrayField = pointer(object, Squeak.MethodDict_array);
            if (!(arrayField instanceof SqueakObject)) {
                continue;
            }
            SqueakObject array = (SqueakObject) arrayField;
            for (int i = Squeak.MethodDict_selectorStart; i < object.pointersSize(); i++) {
                Object candidate = pointer(array, i - Squeak.MethodDict_selectorStart);
                if (candidate == method) {
                    Object selector = pointer(object, i);
                    return selector instanceof SqueakObject
                            ? stringValue((SqueakObject) selector) : null;
                }
            }
        }
        return null;
    }

    private static Object pointer(SqueakObject object, int index) {
        return object.pointers != null && index < object.pointers.length
                ? object.pointers[index] : null;
    }

    private String className(SqueakObject object) {
        Object cls = object.sqClass;
        if (!(cls instanceof SqueakObject)) {
            return String.valueOf(cls);
        }
        return ((SqueakObject) cls).classGetName().asString();
    }

    private static String stringValue(SqueakObject object) {
        if (object.bits instanceof byte[] && object.format >= 8 && object.format < 12) {
            try {
                return object.asString();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String id(SqueakObject object) {
        Object cls = object.sqClass;
        String name = cls instanceof SqueakObject
                ? ((SqueakObject) cls).classGetName().asString() : String.valueOf(cls);
        return name + "@" + Integer.toHexString(System.identityHashCode(object))
                + "#" + object.hash;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof SqueakObject) {
            SqueakObject object = (SqueakObject) value;
            String text = stringValue(object);
            return id(object) + (text == null ? "" : " " + quote(text));
        }
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static String fieldLabel(String className, int index) {
        if ("BitBlt".equals(className)) {
            return switch (index) {
                case 0 -> "destForm";
                case 1 -> "sourceForm";
                case 2 -> "halftoneForm";
                case 3 -> "combinationRule";
                case 4 -> "destX";
                case 5 -> "destY";
                case 6 -> "width";
                case 7 -> "height";
                case 8 -> "sourceX";
                case 9 -> "sourceY";
                case 10 -> "clipX";
                case 11 -> "clipY";
                case 12 -> "clipWidth";
                case 13 -> "clipHeight";
                case 14 -> "colorMap";
                default -> "field";
            };
        }
        if ("Form".equals(className)) {
            return switch (index) {
                case 0 -> "bits";
                case 1 -> "width";
                case 2 -> "height";
                case 3 -> "depth";
                case 4 -> "offset";
                default -> "field";
            };
        }
        if ("BlockContext".equals(className)) {
            return switch (index) {
                case 0 -> "caller";
                case 1 -> "instructionPointer";
                case 2 -> "stackPointer";
                case 3 -> "argumentCount";
                case 4 -> "initialIP";
                case 5 -> "home";
                default -> "temp[" + (index - 6) + "]";
            };
        }
        if ("MethodContext".equals(className)) {
            return switch (index) {
                case 0 -> "sender";
                case 1 -> "instructionPointer";
                case 2 -> "stackPointer";
                case 3 -> "method";
                case 4 -> "receiverMap";
                case 5 -> "receiver";
                default -> "temp[" + (index - 6) + "]";
            };
        }
        if ("ColorSystemView".equals(className) || "ProjectView".equals(className)
                || "StandardSystemView".equals(className)) {
            return switch (index) {
                case 0 -> "model";
                case 1 -> "controller";
                case 2 -> "superView";
                case 3 -> "subViews";
                case 4 -> "transformation";
                case 5 -> "viewport";
                case 6 -> "window";
                case 7 -> "displayTransformation";
                case 8 -> "insetDisplayBox";
                case 9 -> "borderWidth";
                case 10 -> "borderColor";
                case 11 -> "insideColor";
                case 12 -> "boundingBox";
                case 13 -> "labelFrame";
                case 14 -> "labelText";
                case 15 -> "isLabelComplemented";
                case 16 -> "savedSubViews";
                case 17 -> "minimumSize";
                case 18 -> "maximumSize";
                case 19 -> "collapsedViewport";
                case 20 -> "expandedViewport";
                case 21 -> "labelBits";
                case 22 -> "windowBits";
                case 23 -> "bitsValid";
                default -> "field";
            };
        }
        return "field";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value.replace("\n", "\\n").replace("\r", "\\r");
        return escaped.length() > 100 ? escaped.substring(0, 97) + "..." : escaped;
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  ./gradlew inspectImage --args="--image <file> [command]"

                Commands:
                  --summary                         print object/class/context counts
                  --find <text> [--refs N]          find strings and reverse references
                  --class <ClassName> [--limit N]   dump instances and pointer fields
                  --contexts                        check Block/MethodContext validity
                  --receiver-class <ClassName> [--label <text>] \\
                    --send <selector> [--run-millis N]
                                                    send a 0-argument message

                Examples:
                  ./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --summary"
                  ./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --find 'Play With Me - 5'"
                  ./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --class ColorSystemView --limit 3"
                  ./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --receiver-class ColorSystemView --label 'Play With Me - 5' --send expand --run-millis 2000"
                """);
    }

    private record Reference(SqueakObject owner, int index) {
    }

    private static final class Options {
        String image;
        String find;
        String className;
        String receiverClass;
        String label;
        String send;
        boolean contexts;
        boolean summary;
        boolean help;
        int refs = 2;
        int limit = 20;
        long runMillis = 1000;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--image" -> options.image = requireValue(args, ++i, "--image");
                    case "--find" -> options.find = requireValue(args, ++i, "--find");
                    case "--class" -> options.className = requireValue(args, ++i, "--class");
                    case "--receiver-class" ->
                            options.receiverClass = requireValue(args, ++i, "--receiver-class");
                    case "--label" -> options.label = requireValue(args, ++i, "--label");
                    case "--send" -> options.send = requireValue(args, ++i, "--send");
                    case "--refs" -> options.refs = Integer.parseInt(requireValue(args, ++i, "--refs"));
                    case "--limit" -> options.limit = Integer.parseInt(requireValue(args, ++i, "--limit"));
                    case "--run-millis" ->
                            options.runMillis = Long.parseLong(requireValue(args, ++i, "--run-millis"));
                    case "--contexts" -> options.contexts = true;
                    case "--summary" -> options.summary = true;
                    case "--help", "-h" -> options.help = true;
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
