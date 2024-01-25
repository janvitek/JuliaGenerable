package prlprg;

import java.util.ArrayList;
import prlprg.Subtyper.Fuel;

public class App {

    private static int MAX_SIGS_TO_READ = 100;
    static String dir, types, functions;
    static String[] defaultArgs = { "-c=DARK", // color the output : DARK, LIGHT, NONE
            "-r=../Inputs/", // root directory with input files
            "-f=stdf.jlg", // file with function signatures
            "-t=stdt.jlg", // file with type declarations
            "-m=50", // max number of sigs to read (0 = all)
    };

    static int FUEL = 1;

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments

        if (true || !GenDB.readDB()) {
            warn("Parsing...");
            var p = new Parser().withFile(dir + types);
            p.parseTypes();
            p = new Parser().withFile(dir + functions);
            p.parseSigs(MAX_SIGS_TO_READ);
            warn("Preparing type and signature database...");
            GenDB.it.cleanUp();
            var sigs = GenDB.it.sigs.allSigs();
            int sigsC = 0, groundC = 0;
            for (var sig : sigs) {
                sigsC++;
                if (sig.isGround()) groundC++;
            }
            warn("Sigs: " + sigsC + ", ground: " + groundC);
            GenDB.saveDB();
        }

        Orchestrator gen = new Orchestrator();
        gen.gen();
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
        warn("Generated " + cnt + " types");
        for (var nm : GenDB.it.sigs.allNames()) {
            for (var me : GenDB.it.sigs.get(nm)) {
                var m = me.sig;
                if (m.isGround()) {
                    continue; // Skip trivial cases
                }
                warn("Generating subtypes of " + m);
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
            } else {
                warn("Unknown argument: " + arg);
                System.exit(1);
            }
        }
    }

    static void info(String s) {
        System.err.println(s);
    }

    static void warn(String s) {
        System.err.println(s);
    }

    static void output(String s) {
        System.out.println(s);
    }
}
