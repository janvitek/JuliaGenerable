package prlprg;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import prlprg.Subtyper.Fuel;

public class App {

    public static class Options {
        static int MAX_SIGS_TO_READ = 100;
        static String root;
        static String types, functions, aliases;
        static String juliaBin, juliaDepot, juliaPkgsFile;
        static boolean regen;
        static final String inputs = "Inputs";
        static final String typeDiscover = "TypeDiscover.jl";
        static Path aliasesPath;
        static Path typesPath;
        static Path functionsPath;
    }

    static String[] defaultArgs = {
        "-c=NONE", // color the output : DARK, LIGHT, NONE
        "-r=..", // root directory of the project
        "-f=stdf.jlg", // file with function signatures
        "-t=stdt.jlg", // file with type declarations
        "-a=stda.jlg", // file with alias declarations
        "-m=1000", // max number of sigs to read (0 = all)
        "-julia=julia", // path to the julia binary to use
        "-depot=/tmp/JuliaGenerableDepot", // what depot to run julia with
        "-regen=FALSE", // should regenerate input files even if they exist?
        "-pkgs=pkgs.txt", // file with the list of packages to check, one per line, use `@LOADED` to include all loaded modules in the VM
    };

    static int FUEL = 1;

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments

        Options.aliasesPath = Paths.get(Options.root).resolve(Options.inputs).resolve(Options.aliases);
        Options.typesPath = Paths.get(Options.root).resolve(Options.inputs).resolve(Options.types);
        Options.functionsPath = Paths.get(Options.root).resolve(Options.inputs).resolve(Options.functions);

        printSeparator();
        print("Setting up Julia");
        JuliaUtils.setup();
        if (Options.regen || Files.notExists(Options.aliasesPath) || Files.notExists(Options.typesPath) || Files.notExists(Options.functionsPath)) {
            print("Discovering types and signatures");
            JuliaUtils.runTypeDiscovery();
        }

        printSeparator();
        print("JuGen starting...");
        print("Log file is /tmp/jl_log.txt");

        if (!GenDB.readDB()) { // If we did not find a DB, do god's work...

            printSeparator();
            print("Reading aliases from " + Options.aliasesPath);
            new Parser().withFile(Options.aliasesPath).parseAliases();

            printSeparator();
            print("Reading types from " + Options.typesPath);
            new Parser().withFile(Options.typesPath).parseTypes();

            NameUtils.TypeName.freeze();

            printSeparator();
            print("Reading sigs from " + Options.functionsPath);
            new Parser().withFile(Options.functionsPath).parseSigs(Options.MAX_SIGS_TO_READ);

            printSeparator();
            print("Building DB...");
            Timer t = new Timer();
            t.start();
            GenDB.it.cleanUp();
            var sigs = GenDB.it.sigs.allSigs();
            int sigsC = 0, groundC = 0;
            for (var sig : sigs) {
                sigsC++;
                if (sig.isGround()) groundC++;
            }
            t.stop();
            print("We found " + sigsC + " sigs of which " + groundC + " are ground in " + t);
            GenDB.saveDB(); // save state just in case...
        }

        printSeparator();
        print("Starting orchestrator...");
        Orchestrator gen = new Orchestrator();
        gen.orchestrate();

        // What follows will be moved to orchestrator  or GenDB.
        var sub = new Subtyper();
        var cnt = 0;
        for (var i : GenDB.it.types.all()) {
            var tg1 = sub.make(i.decl.ty(), new Fuel(1));
            var childs = new ArrayList<Type>();
            while (tg1.hasNext()) {
                childs.add(tg1.next());
                cnt++;
            }
            i.level_1_kids = childs;
        }
        print("Generated " + cnt + " types");
        for (var nm : GenDB.it.sigs.allNames()) {
            for (var me : GenDB.it.sigs.get(nm)) {
                var m = me.sig;
                if (m.isGround()) {
                    continue; // Skip trivial cases
                }
                print("Generating subtypes of " + m);
                var tup = m.ty();
                var tg = sub.make(tup, new Fuel(FUEL));
                while (tg.hasNext()) {
                    var t = tg.next();
                    var newm = new Sig(m.nm(), t, List.of(), -1, m.src());
                    App.output(newm.toString());
                }
            }
        }

        quit(0);
    }

    static void parseArgs(String[] args) {
        for (var arg : args) {
            if (arg.startsWith("-julia")) {
                Options.juliaBin = arg.substring(7).strip();
            } else if (arg.startsWith("-depot")) {
                Options.juliaDepot = arg.substring(7).strip();
            } else if (arg.startsWith("-regen")) {
                Options.regen = switch (arg.substring(7).strip()) {
                case "TRUE" -> true;
                case "FALSE" -> false;
                default -> false;
                };
            } else if (arg.startsWith("-pkgs")) {
                Options.juliaPkgsFile = arg.substring(6).strip();
            } else if (arg.startsWith("-r")) { // root directory
                Options.root = arg.substring(3).strip();
            } else if (arg.startsWith("-t")) {
                Options.types = arg.substring(3).strip();
            } else if (arg.startsWith("-a")) {
                Options.aliases = arg.substring(3).strip();
            } else if (arg.startsWith("-f")) {
                Options.functions = arg.substring(3).strip();
            } else if (arg.startsWith("-m")) { // max number of sigs to read
                var s = arg.substring(3).strip();
                Options.MAX_SIGS_TO_READ = s.equals("0") ? Integer.MAX_VALUE : Integer.parseInt(s);
            } else if (arg.startsWith("-c")) { // Color mode
                CodeColors.mode = switch (arg.substring(3).strip()) {
                case "DARK" -> CodeColors.Mode.DARK;
                case "LIGHT" -> CodeColors.Mode.LIGHT;
                default -> CodeColors.Mode.NONE;
                };
            } else
                die("Unknown argument: " + arg);
        }
    }

    static void print(String s) {
        System.err.println(s);
        output(s);
    }

    static void printSeparator() {
        print("-------------------------------------------------------------------");
    }

    static void die(String s) {
        print(s);
        quit(1);
    }

    static void quit(int status) {
        try {
            logger.flush();
            logger.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.exit(status);
    }

    static class Timer {
        long start = -1;
        long end = -1;

        Timer start() {
            if (start != -1) {
                throw new RuntimeException("Timer already started");
            }
            start = System.nanoTime();
            return this;
        }

        Timer stop() {
            if (end != -1) {
                throw new RuntimeException("Timer already stopped");
            }
            end = System.nanoTime();
            return this;
        }

        @Override
        public String toString() {
            if (start == -1 || end == -1) {
                throw new RuntimeException("Timer not started/ended");
            }
            long d = end - start;
            long duration = TimeUnit.NANOSECONDS.toSeconds(d);
            if (duration > 0) return duration + " secs";
            duration = TimeUnit.NANOSECONDS.toMillis(d);
            if (duration > 0) return duration + " msecs";
            duration = TimeUnit.NANOSECONDS.toMicros(d);
            return duration + " usecs";
        }
    }

    private static BufferedWriter logger = null;

    static void output(Object o) {
        var s = o.toString();
        try {
            if (logger == null) {
                logger = new BufferedWriter(new FileWriter("/tmp/jl_log.txt"));
            }
            logger.write(s);
            logger.write("\n");
            logger.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
