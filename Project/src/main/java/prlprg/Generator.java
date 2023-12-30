package prlprg;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.stream.Collectors;

class Generator {

    GenDB.Types types;
    GenDB.Signatures  sigs;

    Generator(GenDB.Types types, GenDB.Signatures sigs) {
        this.types = types;
        this.sigs  = sigs;
    }
    
    public void gen() {
        try {
            HashSet<String> pkgs = new HashSet<>();
            var w = new BufferedWriter(new FileWriter("tests.jl"));
            for (var s : sigs.allSigs()) {
                    if (s.isGround()) {
                        add_pkgs(s, pkgs);
                        w.write("code_warntype(");
                        w.write(juliaName(s));
                        w.write(", [");
                        w.write(((Tuple) s.ty()).tys().stream().map(Type::toString).collect(Collectors.joining(", ")));
                        w.write("])\n");
                    }
                }

            w.close();

            pkgs.remove("Base");
            pkgs.remove("Core");
            pkgs.remove("f::Base"); // ????
            var pkgsf = new BufferedWriter(new FileWriter("pkgs.txt"));
            var importsf = new BufferedWriter(new FileWriter("imports.jl"));
            for (var p : pkgs) {
                pkgsf.write(p + "\n");
                importsf.write("using Pkg\n Pkg.add(\"" + p + "\")\n");
                importsf.write("import " + p + "\n");
            }
            pkgsf.close();
            importsf.close();
        } catch (IOException e) {
            throw new Error(e);
        }
        System.exit(0);
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

 }