package prlprg;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import prlprg.Subtyper.Fuel;

public class App {

    private static int MAX_SIGS_TO_READ = 100;
    static String dir, types, functions;
    static String[] defaultArgs = { "-c=DARK", // color the output : DARK, LIGHT, NONE
            "-r=../Inputs/", // root directory with input files
            "-f=stdf.jlg", // file with function signatures
            "-t=stdt.jlg", // file with type declarations
            "-m=5000", // max number of sigs to read (0 = all)
    };

    static int FUEL = 1;

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments
        printSeparator();
        print("JuGen starting...");
        print("Log file is /tmp/jl_log.txt");

        if (true || !GenDB.readDB()) {

            printSeparator();
            print("Reading types from " + dir + types);
            new Parser().withFile(dir + types).parseTypes();

            printSeparator();
            print("Reading sigs from " + dir + functions);
            new Parser().withFile(dir + functions).parseSigs(MAX_SIGS_TO_READ);

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
            GenDB.saveDB();
        }

        printSeparator();
        print("Starting orchestrator...");
        Orchestrator gen = new Orchestrator();
        gen.orchestrate();
        // for now the above exit();
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
                    var newm = new Sig(m.nm(), t, m.src());
                    App.output(newm.toString());
                }
            }
        }
    }

    static void parseArgs(String[] args) {
        for (var arg : args) {
            if (arg.startsWith("-r")) { // root directory
                dir = arg.substring(3).strip();
            } else if (arg.startsWith("-t")) {
                types = arg.substring(3).strip();
            } else if (arg.startsWith("-f")) {
                functions = arg.substring(3).strip();
            } else if (arg.startsWith("-m")) { // max number of sigs to read
                var s = arg.substring(3).strip();
                MAX_SIGS_TO_READ = s.equals("0") ? Integer.MAX_VALUE : Integer.parseInt(s);
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
        try {
            logger.flush();
            logger.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.exit(1);
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
            if (duration > 0) return " in " + duration + " secs";
            duration = TimeUnit.NANOSECONDS.toMillis(d);
            if (duration > 0) return " in " + duration + " msecs";
            duration = TimeUnit.NANOSECONDS.toMicros(d);
            return " in " + duration + " usecs";
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
