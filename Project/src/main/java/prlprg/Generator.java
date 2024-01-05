package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.stream.Collectors;

class Generator {

    GenDB.Types types;
    GenDB.Signatures sigs;

    Generator(GenDB.Types types, GenDB.Signatures sigs) {
        this.types = types;
        this.sigs = sigs;
    }

    public void gen() {
        try {
            HashSet<String> pkgs = new HashSet<>();
            try (var w = new BufferedWriter(new FileWriter("tests.jl"))) {
                for (var s : sigs.allSigs()) {
                    if (s.isGround()) {
                        add_pkgs(s, pkgs);
                        w.write("code_warntype(");
                        w.write(juliaName(s));
                        w.write(", [");
                        w.write(((Tuple) s.ty()).tys().stream().map(Type::toJulia).collect(Collectors.joining(", ")));
                        w.write("])\n");
                    }
                }
            }
            pkgs.remove("Base"); // can't add
            pkgs.remove("Core"); // can't add
            pkgs.remove("Pkg"); // should already be there
            try (var pkgsf = new BufferedWriter(new FileWriter("pkgs.txt"))) {
                for (var p : pkgs) {
                    pkgsf.write(p + "\n");
                }
            }
            try (var importsf = new BufferedWriter(new FileWriter("imports.jl"))) {
                importsf.write("using Pkg\n");
                for (var p : pkgs) {
                    importsf.write("Pkg.add(\"" + p + "\")\nimport " + p + "\n");
                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
        System.exit(0); // TODO: remove
    }

    public void add_pkgs(Sig s, HashSet<String> pkgs) {
        if (s.nm().contains(".")) {
            pkgs.add(s.nm().split("\\.", 2)[0]);
        }
        if (s.ty() instanceof Tuple t) {
            for (var typ : t.tys()) {
                // TODO: recurse into other kinds of type
                if (typ instanceof Inst inst) {
                    if (inst.nm().contains(".")) {
                        pkgs.add(inst.nm().split("\\.", 2)[0]);
                    }
                }
            }
        }
    }

    public String juliaName(Sig s) {
        // FileWatching.|
        // Pkg.Artifacts.var#verify_artifact#1
        // Base.GMP.MPQ.//
        var parts = s.nm().split("\\.");
        var name = parts[parts.length - 1];
        if (name.startsWith("var#")) {
            name = name.substring(0, 3) + "\"" + name.substring(3) + "\"";
        } else if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            name = ":(" + name + ")";
        }
        parts[parts.length - 1] = name;
        return String.join(".", parts);
    }

    public static void main(String[] args) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("julia", "path/to/script.jl");
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            int exitCode = process.waitFor();
            System.out.println("\nExited with error code : " + exitCode);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
