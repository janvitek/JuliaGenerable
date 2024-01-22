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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.Parser.MethodInformation;

/**
 * Class for orchestrating the generation of type stability tests.
 *
 * @author jan
 */
class Orchestrator {

    private static int num = 0; // internal counter for unique names

    /**
     * Generate a unique number in a possibly concurrent context. These numbers are
     * used for temproary director names.
     */
    static synchronized int nextNum() {
        return num++;
    }

    GenDB it; // reference to the GenDB for convenience

    /**
     * Create an orchestrator for the given GenDB.
     */
    Orchestrator() {
        this.it = GenDB.it;
    }

    /**
     * A context remembers where this generator is writing to. We assume there will
     * be multiple instances working concurrently (at some point).
     *
     * If we ever get to multiple concurent tasks running in parallel, then each
     * should have its own context, with the same root but different tmp
     * directories. One will have to figure out what is private to each instance and
     * what can be shared across them.
     */
    class Context {

        Path root; // for all data procduced by this JVM
        Path tmp; // path to the temp directory that holds the Julia results e.g. /tmp/t0
        Path imports; // complete path for the file that has all the inmport statements
        Path pkgs; // complete path for the file that has the list of packages used
        Path tests; // complete path for the file that holds the code to run the tests
        List<String> testFiles = new ArrayList<>(); // the name of the test files created
        int count = 0; // how many fles we actually see in the /tmp/t0 dir after Julia has run

        /**
         * Create a context with a temporary directory.
         */
        public Context() {
            var dt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            try {
                var p = Paths.get("/tmp").resolve("jl_" + dt);
                if (Files.exists(p)) {
                    delete(p);
                }
                root = Files.createDirectory(p);
                p = p.resolve("out." + nextNum());
                tmp = Files.createDirectories(p);
            } catch (IOException e) {
                throw new Error(e); // deletion shouold not fail... without a valid context there is not much we can do
            }
            imports = root.resolve("imports.jl");
            pkgs = root.resolve("pkgs.txt");
            tests = root.resolve("tests.jl");
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
                throw new Error(e); // not sure what else but rethrow if we fail to delete, we could ignore.e..
            }
        }
    }

    /**
     * Create the packages file that list all the packages that were referenced in
     * the DB.
     */
    void createPkgs(Context ctxt) {
        var pkgs = new HashSet<String>();
        for (var p : it.names.packages) {
            if (p.startsWith("Base.") || p.startsWith("Core.") || p.startsWith("Pkg.") || p.equals("Base") || p.equals("Core") || p.equals("Pkg")) {
                continue;
            }
            var prefix = p.contains(".") ? p.substring(0, p.indexOf(".")) : p;
            pkgs.add(prefix);
        }
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

    /**
     * Generate the tests and run them.
     */
    void gen() {
        var ctxt = new Context();
        // populate Input...
        createPkgs(ctxt);
        createGroundTests(ctxt);
        exec(ctxt);
        readResults(ctxt);
        System.exit(0);
    }

    /**
     * Create test for methods that have all concerete arguments. A different
     * approach will be needed for other methods.
     */
    void createGroundTests(Context ctxt) {
        try {
            try (var w = new BufferedWriter(new FileWriter(ctxt.tests.toString()))) {
                w.write("""
                    include("imports.jl")
                    using InteractiveUtils
                    InteractiveUtils.highlighting[:warntype] = false
                    """);
                w.write("\n");
                int cnt = 0;
                for (var s : it.sigs.allSigs()) {
                    if (s.isGround()) {
                        var nm = "t" + cnt++ + ".tst";
                        ctxt.testFiles.add(nm);
                        w.write("""
                            buffer = IOBuffer()
                            try
                              code_warntype(IOContext(buffer, :color => true), %s, [%s])
                            catch e
                              try
                                println(buffer, "Exception occurred: ", e)
                              catch e
                              end
                            end
                            open("%s", "w") do file
                              write(file, String(take!(buffer)))
                            end
                            """.formatted(
                                    juliaName(s),
                                    ((Tuple) s.ty())
                                        .tys()
                                        .stream()
                                        .map(Type::toJulia)
                                        .collect(Collectors.joining(", ")),
                                    nm));
                        w.write("\n");
                    }
                }
                ctxt.count = cnt;
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    /**
     * Read the results of the tests as genereated by Julia.
     */
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
                    var nm = m.sig.nm();

                    var siginfo = it.sigs.get(nm.operationName());

                    if (siginfo == null) {
                        App.warn(nm + " not found !!!!!!");
                    }
                    var nms = it.sigs.allNames();

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
     * Convert a Sig's name to a Julia name.
     */
    String juliaName(Sig s) {
        return s.nm().toJulia();
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