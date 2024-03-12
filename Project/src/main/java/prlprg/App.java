package prlprg;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import prlprg.Subtyper.Fuel;

public class App {

    public static class Options {
        static final String inputs = "Inputs";
        static final String typeDiscover = "TypeDiscover.jl";
        static final String juliaEnv = "extras";

        static int MAX_SIGS_TO_READ = 100;

        static String root = null;
        static String functions = null;
        static String types = null;
        static String aliases = null;

        static String juliaBin = null;
        static String juliaDepot = null;
        static String juliaProject = null;

        static boolean runTypeDiscovery = false;
        static boolean verbose = false;

        public static Path functionsPath() {
            return Paths.get(Options.root).resolve(Options.inputs).resolve(Options.functions).toAbsolutePath().normalize();
        }

        public static Path typesPath() {
            return Paths.get(Options.root).resolve(Options.inputs).resolve(Options.types).toAbsolutePath().normalize();
        }

        public static Path aliasesPath() {
            return Paths.get(Options.root).resolve(Options.inputs).resolve(Options.aliases).toAbsolutePath().normalize();
        }

        public static Path typeDiscoverPath() {
            return Paths.get(Options.root).resolve(Options.typeDiscover).toAbsolutePath().normalize();
        }

        public static Path depotPath() {
            return juliaDepot == null ? null : Paths.get(juliaDepot).toAbsolutePath().normalize();
        }

        public static Path projectPath() {
            return juliaProject == null ? null : Paths.get(juliaProject).toAbsolutePath().normalize();
        }
    }

    static String[] defaultArgs = { "-c=NONE", // color the output : DARK, LIGHT, NONE
            "-m=0", // max number of sigs to read (0 = all)
            "-r=.", // root directory of the project
            "-f=stdf.jlg", // file with function signatures
            "-t=stdt.jlg", // file with type declarations
            "-a=stda.jlg", // file with alias declarations
            "-julia=julia", // path to the julia binary to use
            "-depot=/tmp/JuliaGenerableDepot", // what depot to run julia with
            // "-project=@", // if set, run all julia processes with the given Project.toml
            "-discovery=FALSE", // set to TRUE to run TypeDiscover.jl to regenerate input files
            "-verbose=FALSE",
    };

    static int FUEL = 1;

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments

        var aliasesPath = Options.aliasesPath();
        var typesPath = Options.typesPath();
        var functionsPath = Options.functionsPath();

        printSeparator();
        {
            print("Checking Julia...");
            Timer t = new Timer();
            t.start();
            JuliaUtils.checkJulia();
            t.stop();
            print("Done in " + t);
        }

        if (Options.runTypeDiscovery) {
            print("Running TypeDiscovery");
            Timer t = new Timer();
            t.start();
            JuliaUtils.runTypeDiscovery();
            t.stop();
            print("Done in " + t);
        }

        printSeparator();
        print("JuGen starting...");
        print("Log file is /tmp/jl_log.txt");

        if (!GenDB.readDB()) { // If we did not find a DB, do god's work...

            printSeparator();
            String[] tds = { //
                    "abstract type Core.Union end", // This is used when Union occurs without arguments
                    "abstract type Core.Vararg end", // Missing in discovery
                    "struct Core.DataType <: Core.Type{T} end", // Missing in discovery
                    "struct Core.Colon <: Core.Function end" };// Missing in discovery

            // Reading nanes only to initialize NameUtils                    
            new Parser().withFile(aliasesPath).lex().parseAliasNames();
            new Parser().withLines(tds).withFile(typesPath).lex().parseTypeNames();

            print("Reading aliases from " + aliasesPath);
            new Parser().withFile(aliasesPath).lex().parseAliases();

            printSeparator();
            print("Reading types from " + typesPath);
            new Parser().withLines(tds).withFile(typesPath).lex().parseTypes();

            printSeparator();
            print("Reading sigs from " + functionsPath);
            new Parser().withFile(functionsPath).lex().parseSigs(Options.MAX_SIGS_TO_READ);

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

        quit(0); // TODO REMOVE

        // What follows will be moved to orchestrator  or GenDB.
        var sub = new Subtyper();
        var cnt = 0;
        for (var i : GenDB.it.types.all()) {
            var tg1 = sub.make(i.decl.thisTy(), new Fuel(1));
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
            var parts = arg.split("=", 2);
            if (parts.length != 2) {
                die("Invalid argument format `" + arg + "'");
            }
            var key = parts[0].strip();
            var val = parts[1].strip();

            if (key.equalsIgnoreCase("-c")) { // Color mode
                CodeColors.mode = switch (val.toLowerCase()) {
                case "dark" -> CodeColors.Mode.DARK;
                case "light" -> CodeColors.Mode.LIGHT;
                default -> CodeColors.Mode.NONE;
                };
            } else if (key.equalsIgnoreCase("-m")) { // max number of sigs to read
                var s = val;
                Options.MAX_SIGS_TO_READ = s.equals("0") ? Integer.MAX_VALUE : Integer.parseInt(s);
            } else if (key.equalsIgnoreCase("-r")) { // root directory
                Options.root = val;
            } else if (key.equalsIgnoreCase("-f")) {
                Options.functions = val;
            } else if (key.equalsIgnoreCase("-t")) {
                Options.types = val;
            } else if (key.equalsIgnoreCase("-a")) {
                Options.aliases = val;
            } else if (key.equalsIgnoreCase("-julia")) {
                Options.juliaBin = val;
            } else if (key.equalsIgnoreCase("-depot")) {
                Options.juliaDepot = val;
            } else if (key.equalsIgnoreCase("-project")) {
                Options.juliaProject = val;
            } else if (key.equalsIgnoreCase("-discovery")) {
                Options.runTypeDiscovery = switch (val.toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> false;
                };
            } else if (key.equalsIgnoreCase("-verbose")) {
                Options.verbose = switch (val.toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> false;
                };
            } else
                die("Unknown argument `" + key + "'");
        }
    }

    static void print(String s) {
        s = s == null ? "null" : s;
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
