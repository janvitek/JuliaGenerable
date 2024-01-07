package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import prlprg.Parser.MethodInformation;

/**
 * Class for orchestrating the generation of type stability tests.
 *
 * @author jan
 */
class Orchestrator {

    static int num = 0;

    static synchronized int nextNum() {
        return num++;
    }

    GenDB.Types types;
    GenDB.Signatures sigs;

    Orchestrator(GenDB.Types types, GenDB.Signatures sigs) {
        this.types = types;
        this.sigs = sigs;
    }

    /**
     * A context remembers where this generator is writing to. We assume there will
     * be multiple instances working concurrently (at some point).
     */
    class Context {

        Path tmp;
        Path imports;
        Path pkgs;
        Path tests;
        List<String> testFiles = new ArrayList<>();
        int count = 0;

        public Context() {
            try {
                var p = Paths.get("/tmp").resolve("t" + nextNum());
                if (Files.exists(p)) {
                    delete(p);
                }
                tmp = Files.createDirectory(p);
            } catch (IOException e) {
                throw new Error(e);
            }
            imports = tmp.resolve("imports.jl");
            pkgs = tmp.resolve("pkgs.txt");
            tests = tmp.resolve("tests.jl");
        }

        /**
         * Recursively delete the contents of the directory
         *
         * @param path
         *                 to the directory to delete
         */
        void delete(Path p) {
            try {
                // Recursively delete the contents of the directory
                Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes _a) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException _e) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new Error(e);
            }
        }
    }

    void createPkgs(Context ctxt) {
        var pkgs = new HashSet<String>();
        sigs.allSigs().forEach(s -> add_pkgs(s, pkgs));
        types.all().forEach(t -> add_pkgs_for_ty(t.decl.ty(), pkgs));
        pkgs.remove("Base"); // can't add because alraedy there
        pkgs.remove("Core"); // " 
        pkgs.remove("Pkg"); // should already be there      
        try {
            try (var pkgsf = new BufferedWriter(new FileWriter(ctxt.pkgs.toString()))) {
                for (var p : pkgs) {
                    pkgsf.write(p + "\n");
                }
            }
            try (var importsf = new BufferedWriter(new FileWriter(ctxt.imports.toString()))) {
                importsf.write("using Pkg\n");
                for (var p : pkgs) {
                    importsf.write("Pkg.add(\"" + p + "\")\nimport " + p + "\n");
                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    void gen() {
        var ctxt = new Context();
        createPkgs(ctxt);
        createGroundTests(ctxt);
        exec(ctxt);
        readResults(ctxt);
        System.exit(0);
    }

    void createGroundTests(Context ctxt) {
        try {
            try (var w = new BufferedWriter(new FileWriter(ctxt.tests.toString()))) {
                w.write("include(\"imports.jl\")\nusing InteractiveUtils\n");
                int cnt = 0;
                for (var s : sigs.allSigs()) {
                    if (s.isGround()) {
                        var nm = "t" + cnt++ + ".tst";
                        ctxt.testFiles.add(nm);
                        w.write("buffer=IOBuffer()\ntry\n");
                        w.write("code_warntype(buffer, ");
                        w.write(juliaName(s));
                        w.write(", [");
                        w.write(((Tuple) s.ty()).tys().stream().map(Type::toJulia).collect(Collectors.joining(", ")));
                        w.write("])\n");
                        w.write("catch e\ntry\nprintln(buffer, \"Exception occurred: \", e)\ncatch e\nend\nend\n");
                        w.write("open(\"" + nm + "\", \"w\") do file\nwrite(file, String(take!(buffer)))\nend\n");
                    }
                }
                ctxt.count = cnt;
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    void readResults(Context ctxt) {
        File dir = ctxt.tmp.toFile();
        FilenameFilter filter = (file, name) -> name.endsWith(".tst");
        File[] files = dir.listFiles(filter);
        if (files == null) {
            System.err.println("The directory does not exist.");
            return;
        }
        if (files.length != ctxt.count) {
            System.err.println("Expected " + ctxt.count + " files, found " + files.length);
        }
        int count = 0;
        for (File file : files) {
            try {
                var p = new Parser().withFile(file.toString());
                var ms = MethodInformation.parse(p, file.toString());
                if (!ms.isEmpty()) {
                    count++;
                }
                for (var m : ms) {
                    System.err.println(m);
                }
            } catch (Throwable e) {
                System.err.println("Error parsing file " + file.toString() + ": " + e.getMessage());
            }
        }
        if (count != ctxt.count) {
            System.err.println("Expected " + ctxt.count + " methods, found " + count);
        }
    }

    /**
     * Add the package name of a sig to the set of packages. A sig's package is the
     * first part of its name. If the name does not contain a dot then nothing is
     * added. Does the same to the types of the sig.
     *
     * @param s
     *                 the sig
     * @param pkgs
     *                 the set of packages
     */
    void add_pkgs(Sig s, HashSet<String> pkgs) {
        add_nm_to_pkgs(s.nm(), pkgs);
        add_pkgs_for_ty(s.ty(), pkgs);
    }

    /**
     * Add the package name of a function or type name to the set of packages.
     */
    private void add_nm_to_pkgs(String nm, HashSet<String> pkgs) {
        if (nm.contains(".")) {
            pkgs.add(nm.split("\\.", 2)[0]);
        }
    }

    /**
     * Add the package names of the types in a type to the set of packages.
     */
    private void add_pkgs_for_ty(Type ty, HashSet<String> pkgs) {
        switch (ty) {
        case Inst inst:
            add_nm_to_pkgs(inst.nm(), pkgs);
            break;
        case Tuple t:
            t.tys().forEach(typ -> add_pkgs_for_ty(typ, pkgs));
            break;
        case Exist e:
            add_pkgs_for_ty(e.ty(), pkgs);
            add_pkgs_for_ty(e.b().low(), pkgs);
            add_pkgs_for_ty(e.b().up(), pkgs);
            break;
        case Union u:
            u.tys().forEach(typ -> add_pkgs_for_ty(typ, pkgs));
        default:
        }
    }

    /**
     * Convert a Sig's name to a Julia name.
     */
    String juliaName(Sig s) {
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

    /**
     * Execute a Julia script.
     *
     * @param dir
     *                the directory to execute in
     */
    void exec(Context ctxt) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("julia", ctxt.tests.toString());
            processBuilder.directory(ctxt.tmp.toFile());
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new Error(e);
        }
    }
}